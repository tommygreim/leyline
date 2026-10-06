package leyline.bridge.coord

import forge.card.ColorSet
import forge.card.MagicColor
import forge.game.card.Card
import forge.game.card.CounterEnumType
import forge.game.player.PlayerController.BinaryChoiceType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptRuntimeBindings
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.StaticChoiceInteractionRuntime
import leyline.bridge.types.StaticChoiceIds
import wotc.mtgo.gre.external.messaging.Messages.StaticList

class StaticChoiceCoordinatorTest :
    FunSpec({
        tags(UnitTag)

        test("chooseColorAllowColorless exposes the exact colored subset plus Colorless") {
            val requests = mutableListOf<PromptRequest>()
            val bridge = InteractivePromptBridge(timeoutMs = null)
            bridge.runtimeBindings =
                PromptRuntimeBindings(
                    staticChoice =
                        object : StaticChoiceInteractionRuntime {
                            override fun awaitSelection(
                                request: PromptRequest,
                                timeoutMs: Long?,
                            ): List<Int> {
                                requests += request
                                return listOf(1)
                            }
                        },
                )

            val selected =
                StaticChoiceCoordinator(bridge).chooseColorAllowColorless(
                    "Choose mana",
                    Card(42, null),
                    ColorSet.fromMask(MagicColor.WHITE.toInt()),
                )

            val request = requests.single()
            assertSoftly {
                selected shouldBe MagicColor.COLORLESS
                request.route shouldBe PromptRouteResolver.resolve(PromptSemantic.StaticCardColorChoice)
                request.staticList shouldBe StaticList.CardColors
                request.options shouldContainExactly listOf("White", "Colorless")
                request.staticOptionIds shouldContainExactly
                    listOf(
                        StaticChoiceIds.cardColorIdForName("White")!!,
                        StaticChoiceIds.cardColorIdForName("Colorless")!!,
                    )
                request.sourceEntityId shouldBe 42
            }
        }

        test("chooseColorAllowColorless has no choice when no colored mana is allowed") {
            val bridge = InteractivePromptBridge(timeoutMs = null)

            StaticChoiceCoordinator(bridge).chooseColorAllowColorless("Choose mana", Card(42, null), ColorSet.fromMask(0)) shouldBe
                MagicColor.COLORLESS
            bridge.history shouldBe emptyList()
        }

        test("chooseBinary uses Arena dynamic two-button SelectN shape") {
            val requests = mutableListOf<PromptRequest>()
            val bridge = InteractivePromptBridge(timeoutMs = null)
            bridge.runtimeBindings =
                PromptRuntimeBindings(
                    staticChoice =
                        object : StaticChoiceInteractionRuntime {
                            override fun awaitSelection(
                                request: PromptRequest,
                                timeoutMs: Long?,
                            ): List<Int> {
                                requests += request
                                return listOf(1)
                            }
                        },
                )

            StaticChoiceCoordinator(bridge).chooseBinary(null, "Tap or untap target creature?", BinaryChoiceType.TapOrUntap, null) shouldBe
                false

            val request = requests.single()
            request.route shouldBe PromptRouteResolver.resolve(PromptSemantic.StaticBinaryChoice)
            request.staticList shouldBe null
            request.staticOptionIds shouldContainExactly listOf(0, 1)
            request.protocolPromptId shouldBe null
        }

        test("chooseSomeType uses Arena's BasicLandTypes workflow") {
            val requests = mutableListOf<PromptRequest>()
            val bridge = InteractivePromptBridge(timeoutMs = null)
            bridge.runtimeBindings =
                PromptRuntimeBindings(
                    staticChoice =
                        object : StaticChoiceInteractionRuntime {
                            override fun awaitSelection(
                                request: PromptRequest,
                                timeoutMs: Long?,
                            ): List<Int> {
                                requests += request
                                return listOf(1)
                            }
                        },
                )

            StaticChoiceCoordinator(bridge).chooseSomeType(
                "Basic Land",
                null,
                listOf("Forest", "Island", "Plains"),
                false,
            ) shouldBe "Island"

            val request = requests.single()
            request.route shouldBe PromptRouteResolver.resolve(PromptSemantic.StaticBasicLandTypeChoice)
            request.staticList shouldBe StaticList.BasicLandTypes
            request.staticOptionIds shouldContainExactly listOf(5, 2, 1)
        }

        test("chooseCounterType exposes all representable counter options") {
            val requests = mutableListOf<PromptRequest>()
            val bridge = InteractivePromptBridge(timeoutMs = null)
            bridge.runtimeBindings =
                PromptRuntimeBindings(
                    staticChoice =
                        object : StaticChoiceInteractionRuntime {
                            override fun awaitSelection(
                                request: PromptRequest,
                                timeoutMs: Long?,
                            ): List<Int> {
                                requests += request
                                return listOf(1)
                            }
                        },
                )

            StaticChoiceCoordinator(bridge).chooseCounterType(
                listOf(CounterEnumType.P1P1, CounterEnumType.POISON),
                null,
                "Choose a counter",
                null,
            ) shouldBe CounterEnumType.POISON

            val request = requests.single()
            request.route shouldBe PromptRouteResolver.resolve(PromptSemantic.StaticCounterTypeChoice)
            request.staticList shouldBe StaticList.CounterTypes
            request.staticOptionIds shouldContainExactly
                listOf(
                    StaticChoiceIds.counterTypeIdFor("P1P1")!!,
                    StaticChoiceIds.counterTypeIdFor("Poison")!!,
                )
        }
    })
