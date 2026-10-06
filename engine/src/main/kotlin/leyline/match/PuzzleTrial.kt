package leyline.match

import com.google.protobuf.ByteString
import kotlinx.serialization.Serializable
import leyline.config.PuzzleDefinition
import leyline.config.RuntimeMatchConfig
import leyline.copilot.CopilotProposal
import wotc.mtgo.gre.external.messaging.Messages.AuthenticateRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchDoorConnectRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.ConnectReq
import java.util.HexFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

private const val TRANSIENT_POLL_NANOS = 5_000_000L
private const val STALL_AFTER_NANOS = 1_000_000_000L

@Serializable
data class PuzzleTrialLimits(
    val maxDecisions: Int = 100,
    val maxElapsedMillis: Long = 30_000,
) {
    init {
        require(maxDecisions in 1..10_000) { "maxDecisions must be from 1 to 10000" }
        require(maxElapsedMillis in 1..300_000) { "maxElapsedMillis must be from 1 to 300000" }
    }
}

@Serializable
enum class PuzzleTrialStatus {
    Won,
    Lost,
    Unsupported,
    AdvisorUnavailable,
    Stalled,
    DecisionBudgetExceeded,
    TimeBudgetExceeded,
    Interrupted,
    EngineFailure,
}

@Serializable
data class PuzzleTrialDecision(
    val index: Int,
    val intent: String,
    val promptType: String,
    val promptKey: String? = null,
    val gameStateId: Int? = null,
    val respId: Int? = null,
)

@Serializable
data class PuzzleTrialOutcome(
    val playerSeatId: Int,
    val winningTeam: Int,
    val won: Boolean,
)

@Serializable
data class PuzzleTrialResult(
    val status: PuzzleTrialStatus,
    val definitionId: String,
    val matchId: String? = null,
    /** Seed already configured on the runtime, supplied by the composition root for reporting. */
    val engineSeed: Long? = null,
    val limits: PuzzleTrialLimits,
    val elapsedMillis: Long,
    val decisions: List<PuzzleTrialDecision> = emptyList(),
    val outcome: PuzzleTrialOutcome? = null,
    val reason: String? = null,
)

/** Runs one puzzle through bounded engine-owned copilot decisions and reports the observed result. */
class PuzzleTrial(
    private val runtime: MatchRuntime,
    private val engineSeed: Long? = null,
) {
    fun run(
        definition: PuzzleDefinition,
        limits: PuzzleTrialLimits = PuzzleTrialLimits(),
    ): PuzzleTrialResult {
        val startedAt = System.nanoTime()
        val deadline = startedAt + limits.maxElapsedMillis * 1_000_000L
        val progress = AtomicReference(TrialProgress())
        val cleanupDone = CountDownLatch(1)

        fun pendingCleanup() = if (cleanupDone.count > 0) "; runtime cleanup pending" else ""

        fun result(
            status: PuzzleTrialStatus,
            reason: String,
        ): PuzzleTrialResult {
            val snapshot = progress.get()
            return PuzzleTrialResult(
                status = status,
                definitionId = definition.identity,
                matchId = snapshot.matchId,
                engineSeed = engineSeed,
                limits = limits,
                elapsedMillis = elapsedMillis(startedAt),
                decisions = snapshot.decisions,
                reason = reason,
            )
        }

        if (!trialRunning.compareAndSet(false, true)) {
            return result(PuzzleTrialStatus.EngineFailure, "puzzle trial worker is busy")
        }

        val future =
            FutureTask {
                try {
                    runOwned(definition, limits, startedAt, deadline, progress)
                } finally {
                    cleanupDone.countDown()
                    trialRunning.set(false)
                }
            }
        try {
            trialExecutor.execute(future)
        } catch (_: RejectedExecutionException) {
            trialRunning.set(false)
            return result(PuzzleTrialStatus.EngineFailure, "puzzle trial worker is busy")
        }

        return try {
            future.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            result(PuzzleTrialStatus.TimeBudgetExceeded, "puzzle trial exceeded ${limits.maxElapsedMillis} ms${pendingCleanup()}")
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            result(PuzzleTrialStatus.Interrupted, "puzzle trial thread was interrupted${pendingCleanup()}")
        } catch (failure: ExecutionException) {
            val cause = failure.cause ?: failure
            result(PuzzleTrialStatus.EngineFailure, cause.message ?: cause.javaClass.simpleName)
        }
    }

    private fun runOwned(
        definition: PuzzleDefinition,
        limits: PuzzleTrialLimits,
        startedAt: Long,
        deadline: Long,
        progress: AtomicReference<TrialProgress>,
    ): PuzzleTrialResult {
        val decisions = mutableListOf<PuzzleTrialDecision>()
        var matchId: String? = null
        var handle: MatchRuntimeHandle? = null

        fun publishProgress() {
            progress.set(TrialProgress(matchId, decisions.toList()))
        }

        fun result(
            status: PuzzleTrialStatus,
            reason: String? = null,
            outcome: MatchResultObservation? = null,
        ) = PuzzleTrialResult(
            status = status,
            definitionId = definition.identity,
            matchId = matchId,
            engineSeed = engineSeed,
            limits = limits,
            elapsedMillis = elapsedMillis(startedAt),
            decisions = decisions.toList(),
            outcome = outcome?.let { PuzzleTrialOutcome(it.playerSeatId, it.winningTeam, it.won) },
            reason = reason,
        )

        var closeFailure: Exception? = null
        val completion =
            try {
                val launched =
                    runtime.launch(
                        MatchRuntimeLaunch(
                            config =
                                RuntimeMatchConfig(
                                    matchId = "puzzle-trial-${nextTrialId.incrementAndGet()}",
                                    puzzleDefinition = definition,
                                ),
                            onFrame = {},
                        ),
                    )
                handle = launched
                matchId = launched.response.matchId
                publishProgress()
                if (!launched.response.accepted) {
                    TrialCompletion(PuzzleTrialStatus.EngineFailure, "runtime rejected the puzzle trial")
                } else {
                    launched.receive(TrialClientMessages.authenticate("puzzle-trial"))
                    launched.receive(TrialClientMessages.connect(checkNotNull(matchId)))
                    drive(launched, limits, deadline, decisions, ::publishProgress)
                }
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                TrialCompletion(PuzzleTrialStatus.Interrupted, failure.message ?: "puzzle trial thread was interrupted")
            } catch (failure: Exception) {
                TrialCompletion(PuzzleTrialStatus.EngineFailure, failure.message ?: failure.javaClass.simpleName)
            } finally {
                try {
                    handle?.close()
                } catch (failure: Exception) {
                    closeFailure = failure
                }
            }

        val finalCompletion =
            closeFailure?.let { failure ->
                TrialCompletion(PuzzleTrialStatus.EngineFailure, "runtime close failed: ${failure.message ?: failure.javaClass.simpleName}")
            } ?: completion
        return result(finalCompletion.status, finalCompletion.reason, finalCompletion.outcome)
    }

    private fun drive(
        handle: MatchRuntimeHandle,
        limits: PuzzleTrialLimits,
        deadline: Long,
        decisions: MutableList<PuzzleTrialDecision>,
        publishProgress: () -> Unit,
    ): TrialCompletion {
        var lastSubmission: String? = null
        var transientSince: Long? = null
        while (true) {
            stopBeforeDecision(handle, limits, deadline, decisions.size)?.let { return it }

            val proposal = handle.copilotProposal()
            stopBeforeDecision(handle, limits, deadline, decisions.size)?.let { return it }
            if (proposal.intent == "unrealizable") {
                val reason = proposal.reason ?: "copilot returned no usable response"
                if (reason.isTransientPromptState()) {
                    val now = System.nanoTime()
                    val first = transientSince ?: now.also { transientSince = it }
                    if (now - first >= STALL_AFTER_NANOS) {
                        return TrialCompletion(PuzzleTrialStatus.Stalled, reason)
                    }
                    LockSupport.parkNanos(TRANSIENT_POLL_NANOS)
                    continue
                }
                return TrialCompletion(reason.failureStatus(), reason)
            }
            transientSince = null

            val response =
                proposal.responses.firstOrNull()
                    ?: return TrialCompletion(PuzzleTrialStatus.AdvisorUnavailable, "copilot proposal has no response")
            val submission = "${proposal.promptKey}:$response"
            if (submission == lastSubmission) {
                return TrialCompletion(PuzzleTrialStatus.Stalled, "copilot repeated the same response for prompt ${proposal.promptKey}")
            }

            val payload = TrialClientMessages.response(response)
            decisions += proposal.toDecision(decisions.size + 1)
            publishProgress()
            lastSubmission = submission
            handle.receive(payload)
        }
    }

    private fun stopBeforeDecision(
        handle: MatchRuntimeHandle,
        limits: PuzzleTrialLimits,
        deadline: Long,
        decisionCount: Int,
    ): TrialCompletion? {
        if (Thread.currentThread().isInterrupted) {
            return TrialCompletion(PuzzleTrialStatus.Interrupted, "puzzle trial thread was interrupted")
        }
        if (System.nanoTime() >= deadline) {
            return TrialCompletion(PuzzleTrialStatus.TimeBudgetExceeded, "puzzle trial exceeded ${limits.maxElapsedMillis} ms")
        }
        terminalResult(handle)?.let { observed ->
            return TrialCompletion(if (observed.won) PuzzleTrialStatus.Won else PuzzleTrialStatus.Lost, outcome = observed)
        }
        if (decisionCount >= limits.maxDecisions) {
            return TrialCompletion(PuzzleTrialStatus.DecisionBudgetExceeded, "puzzle trial reached ${limits.maxDecisions} decisions")
        }
        return null
    }

    private fun terminalResult(handle: MatchRuntimeHandle): MatchResultObservation? {
        val future = handle.result.toCompletableFuture()
        return if (future.isDone) future.getNow(null) else null
    }

    private fun CopilotProposal.toDecision(index: Int) =
        PuzzleTrialDecision(
            index = index,
            intent = intent,
            promptType = promptType,
            promptKey = promptKey,
            gameStateId = gameStateId,
            respId = respId,
        )

    private fun String.isTransientPromptState() = contains("no pending prompt") || startsWith("stale prompt:")

    private fun String.failureStatus() =
        when {
            contains("no copilot decoder") -> PuzzleTrialStatus.Unsupported
            startsWith("advisor unavailable: UnsupportedPrompt:") -> PuzzleTrialStatus.Unsupported
            contains("runtime handle is closed") -> PuzzleTrialStatus.EngineFailure
            else -> PuzzleTrialStatus.AdvisorUnavailable
        }

    private companion object {
        val nextTrialId = AtomicLong()
        val trialRunning = AtomicBoolean()

        // Ownership rejects concurrent trials; one queue slot bridges a completed
        // callable to the worker becoming ready for its next submission.
        val trialExecutor =
            ThreadPoolExecutor(
                1,
                1,
                1,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(1),
                { task -> Thread(task, "puzzle-trial").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            ).apply { allowCoreThreadTimeOut(true) }
    }
}

private fun elapsedMillis(startedAt: Long) = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0)

private data class TrialProgress(
    val matchId: String? = null,
    val decisions: List<PuzzleTrialDecision> = emptyList(),
)

private data class TrialCompletion(
    val status: PuzzleTrialStatus,
    val reason: String? = null,
    val outcome: MatchResultObservation? = null,
)

private object TrialClientMessages {
    fun authenticate(clientId: String): ByteArray =
        serviceMessage(
            ClientToMatchServiceMessageType.AuthenticateRequest_f487,
            AuthenticateRequest
                .newBuilder()
                .setClientId(clientId)
                .build()
                .toByteString(),
        )

    fun connect(matchId: String): ByteArray =
        serviceMessage(
            ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487,
            ClientToMatchDoorConnectRequest
                .newBuilder()
                .setMatchId(matchId)
                .setClientToGreMessageBytes(
                    ClientToGREMessage
                        .newBuilder()
                        .setSystemSeatId(1)
                        .setType(ClientMessageType.ConnectReq_097b)
                        .setConnectReq(ConnectReq.newBuilder())
                        .build()
                        .toByteString(),
                ).build()
                .toByteString(),
        )

    fun response(hex: String): ByteArray {
        val message = ClientToGREMessage.parseFrom(HexFormat.of().parseHex(hex))
        return serviceMessage(ClientToMatchServiceMessageType.ClientToGremessage, message.toByteString())
    }

    private fun serviceMessage(
        type: ClientToMatchServiceMessageType,
        payload: ByteString,
    ): ByteArray =
        ClientToMatchServiceMessage
            .newBuilder()
            .setClientToMatchServiceMessageType(type)
            .setPayload(payload)
            .build()
            .toByteArray()
}
