package leyline.bridge.handoff

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.AbilitySub
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.NonInteractiveScope
import leyline.bridge.bootstrap.GameBootstrap

class ModalChoicePromptAdapterTest :
    FunSpec({
        tags(UnitTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        fun request() =
            PromptRequest(
                promptType = "choose_mode",
                message = "Choose a mode",
                options = listOf("one", "two"),
                defaultIndex = 0,
                route = ResolvedPromptRoute.ModalChoice(PromptSemantic.ModalChoice),
            )

        fun handle(): AbilitySub =
            AbilitySub(
                ApiType.Charm,
                Card(7, null).also { it.name = "Host" },
                null,
                emptyMap(),
            )

        fun adapter(
            timeoutMs: Long?,
            isGameLoopThread: Boolean,
            records: MutableList<PromptCallStatus>,
        ) = ModalChoicePromptAdapter(
            timeoutMs = timeoutMs,
            strict = false,
            isGameLoopThread = { isGameLoopThread },
            runtime = { error("fallback must not resolve a runtime") },
            prioritySignal = null,
            record = { _, outcome, _ -> records += outcome },
        )

        test("non-interactive fallback preserves status and selected default") {
            val records = mutableListOf<PromptCallStatus>()
            val choice = handle()

            val result =
                NonInteractiveScope.quiet {
                    adapter(null, isGameLoopThread = true, records).request(request(), listOf(choice), choice.hostCard, choice)
                }

            assertSoftly {
                result shouldBe listOf(choice)
                records shouldBe listOf(PromptCallStatus.NON_INTERACTIVE_SCOPE)
            }
        }

        test("non-game-thread fallback preserves status and selected default") {
            val records = mutableListOf<PromptCallStatus>()
            val choice = handle()

            val result = adapter(null, isGameLoopThread = false, records).request(request(), listOf(choice), choice.hostCard, choice)

            assertSoftly {
                result shouldBe listOf(choice)
                records shouldBe listOf(PromptCallStatus.NON_GAME_THREAD)
            }
        }

        test("optional vote with zero timeout abstains") {
            val records = mutableListOf<PromptCallStatus>()
            val choice = handle()
            val vote = request().copy(min = 0, route = ResolvedPromptRoute.ModalChoice(PromptSemantic.VoteChoice))
            adapter(0L, isGameLoopThread = true, records).request(vote, listOf(choice), choice.hostCard, choice).shouldBeEmpty()
        }

        test("zero timeout returns the default without a history entry or Forge resolution") {
            val records = mutableListOf<PromptCallStatus>()
            val choice = handle()

            val result = adapter(0L, isGameLoopThread = true, records).request(request(), listOf(choice), choice.hostCard, choice)

            assertSoftly {
                result shouldBe listOf(choice)
                records.shouldBeEmpty()
            }
        }
    })
