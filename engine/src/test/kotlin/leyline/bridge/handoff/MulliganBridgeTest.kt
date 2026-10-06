package leyline.bridge.handoff

import forge.game.card.CardCollection
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.MulliganPhase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Deadlined poll for an engine-thread mulligan prompt; sleep is poll interval. */
@Suppress("NoThreadSleepInTests")
private fun pollForPrompt(bridge: MulliganBridge): MulliganBridge.PendingPrompt? {
    repeat(50) {
        bridge.pendingPrompt()?.let { return it }
        Thread.sleep(10)
    }
    return null
}

class MulliganBridgeTest :
    FunSpec({

        tags(UnitTag)

        test("default keep decision remains pending until explicitly cancelled") {
            val bridge = MulliganBridge()
            val finished = CountDownLatch(1)
            val thread =
                Thread {
                    try {
                        bridge.awaitKeepDecision(playerId = 1, mulliganCount = 0)
                    } catch (_: java.util.concurrent.CancellationException) {
                        // Disconnect cancels the blocking engine callback.
                    } finally {
                        finished.countDown()
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            try {
                pollForPrompt(bridge).shouldNotBeNull()
                finished.await(50, TimeUnit.MILLISECONDS) shouldBe false
                bridge.cancelPending()
                assertSoftly {
                    finished.await(2, TimeUnit.SECONDS) shouldBe true
                    bridge.pendingPrompt().shouldBeNull()
                    bridge.submitKeep() shouldBe false
                }
            } finally {
                bridge.cancelPending()
                thread.join(2_000)
            }
        }

        test("keep prompt publishes one coherent snapshot and clears after response") {
            val bridge = MulliganBridge(timeoutMs = 5_000)
            val ready = CountDownLatch(1)
            val result = AtomicReference<Boolean>()

            val engineThread =
                Thread {
                    ready.countDown()
                    result.set(bridge.awaitKeepDecision(playerId = 1, mulliganCount = 2))
                }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            val prompt = pollForPrompt(bridge).shouldNotBeNull()
            assertSoftly {
                prompt.phase shouldBe MulliganPhase.WaitingKeep
                prompt.playerId shouldBe 1
                prompt.mulliganCount shouldBe 2
                prompt.cardsToTuck shouldBe 0
                prompt.sequence shouldBe 1
                bridge.pendingPromptAfter(0) shouldBe prompt
                bridge.pendingPromptAfter(prompt.sequence).shouldBeNull()
            }

            bridge.submitMull() shouldBe true
            engineThread.join(2_000)

            assertSoftly {
                result.get() shouldBe false
                bridge.pendingPrompt().shouldBeNull()
                bridge.submitMull() shouldBe false
                bridge.submitKeep() shouldBe false
            }
        }

        test("tuck prompt publishes tuck count without leaking prior keep state") {
            val bridge = MulliganBridge(timeoutMs = 5_000)
            val keepReady = CountDownLatch(1)
            val keepThread =
                Thread {
                    keepReady.countDown()
                    bridge.awaitKeepDecision(playerId = 1, mulliganCount = 3)
                }
            keepThread.isDaemon = true
            keepThread.start()
            keepReady.await(2, TimeUnit.SECONDS)

            pollForPrompt(bridge).shouldNotBeNull()
            bridge.submitMull() shouldBe true
            keepThread.join(2_000)

            val ready = CountDownLatch(1)
            val resultSize = AtomicReference<Int>()

            val engineThread =
                Thread {
                    ready.countDown()
                    resultSize.set(
                        bridge
                            .awaitTuckDecision(playerId = 1, count = 2, hand = CardCollection.EMPTY)
                            .size,
                    )
                }
            engineThread.isDaemon = true
            engineThread.start()
            ready.await(2, TimeUnit.SECONDS)

            val prompt = pollForPrompt(bridge).shouldNotBeNull()
            assertSoftly {
                prompt.phase shouldBe MulliganPhase.WaitingTuck
                prompt.playerId shouldBe 1
                prompt.mulliganCount shouldBe 2
                prompt.cardsToTuck shouldBe 2
                prompt.sequence shouldBe 2
            }

            bridge.submitTuck(emptyList())
            engineThread.join(2_000)

            assertSoftly {
                resultSize.get() shouldBe 0
                bridge.pendingPrompt().shouldBeNull()
            }
        }
    })
