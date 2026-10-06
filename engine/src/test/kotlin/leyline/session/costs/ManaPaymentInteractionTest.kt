package leyline.session.costs

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.clientMessage
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/** Chosen costs share the real PayCosts workflow, independently of the mechanic that chose them. */
class ManaPaymentInteractionTest :
    SessionTest({
        fun MatchFlowHarness.chooseKicker() {
            castSpellByName("Burst Lightning").shouldBeTrue()
            val option =
                lastCastingTimeOptionsReq().castingTimeOptionReqList.single {
                    it.castingTimeOptionType == CastingTimeOptionType.Kicker
                }
            respondToOptionalCost(option.ctoId)
            selectTargets(listOf(OPPONENT_SEAT))
        }

        fun MatchFlowHarness.pay() {
            submitGameplayResponse(
                ClientToGREMessage
                    .newBuilder()
                    .setType(ClientMessageType.PerformAutoTapActionsResp_097b)
                    .setPerformAutoTapActionsResp(PerformAutoTapActionsResp.newBuilder().setIndex(0))
                    .build(),
            ).shouldBeTrue()
        }

        fun MatchFlowHarness.treasures(): Int = human.getZone(ForgeZoneType.Battlefield).cards.count { it.name == "Treasure Token" }

        session(
            "Treasure payment waits and Cancel preserves every source",
            puzzleFile = "data/puzzles/kicker-treasure-mana.pzl",
            fullControl = true,
        ) {
            chooseKicker()
            val msg = allMessages.last { it.hasPayCostsReq() }
            assertSoftly {
                msg.allowCancel shouldBe AllowCancel.Abort
                msg.payCostsReq.manaCostList.sumOf { it.count } shouldBe 5
                msg.payCostsReq.autoTapActionsReq.autoTapSolutionsCount shouldBe 1
                treasures() shouldBe 3
                human.getZone(ForgeZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
                game().stack.isEmpty shouldBe true
            }
            cancelAction()
            assertSoftly {
                treasures() shouldBe 3
                human.getZone(ForgeZoneType.Hand).cards.map { it.name } shouldContain "Burst Lightning"
                human.getZone(ForgeZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
                game().stack.isEmpty shouldBe true
            }
        }

        session(
            "Auto Pay consumes exactly the confirmed Treasures and completes the kicked cast",
            puzzleFile = "data/puzzles/kicker-treasure-mana.pzl",
            fullControl = true,
        ) {
            chooseKicker()
            pay()
            treasures() shouldBe 0
            passUntilResolved()
            ai.life shouldBe 26
        }

        session(
            "life-cost mana waits and Cancel preserves both life and floating mana",
            puzzleFile = "data/puzzles/kicker-life-mana.pzl",
            fullControl = true,
        ) {
            chooseKicker()
            val msg = allMessages.last { it.hasPayCostsReq() }
            assertSoftly {
                msg.payCostsReq.autoTapActionsReq.autoTapSolutionsCount shouldBe 1
                human.life shouldBe 30
                human.manaPool.totalMana() shouldBe 4
                human
                    .getZone(ForgeZoneType.Battlefield)
                    .cards
                    .single()
                    .isTapped shouldBe false
            }
            cancelAction()
            assertSoftly {
                human.life shouldBe 30
                human.manaPool.totalMana() shouldBe 4
                human.getZone(ForgeZoneType.Hand).cards.map { it.name } shouldContain "Burst Lightning"
            }
        }

        session(
            "Auto Pay uses floating mana and the restricted red source after consent",
            puzzleFile = "data/puzzles/kicker-life-mana.pzl",
            fullControl = true,
        ) {
            chooseKicker()
            pay()
            assertSoftly {
                human.life shouldBe 29
                human.manaPool.totalMana() shouldBe 0
                human
                    .getZone(ForgeZoneType.Battlefield)
                    .cards
                    .single()
                    .isTapped shouldBe true
            }
            passUntilResolved()
            ai.life shouldBe 26
        }

        session(
            "Spree's selected mode opens the same full-cost payment and can be cancelled",
            puzzleFile = "data/puzzles/spree-treasure-mana.pzl",
            fullControl = true,
            forgeCatalog = true,
        ) {
            val cto = castSpellUntilCastingTimeOptionsReq("Three Steps Ahead")
            val drawMode =
                cto
                    .getCastingTimeOptionReq(0)
                    .modalReq.modalOptionsList
                    .last()
            val slice = after { respondModalChoice(listOf(drawMode.grpId)) }
            val msg = slice.messages.single { it.hasPayCostsReq() }
            assertSoftly {
                msg.payCostsReq.manaCostList.sumOf { it.count } shouldBe 3
                treasures() shouldBe 2
                human.getZone(ForgeZoneType.Hand).cards.map { it.name } shouldBe listOf("Mountain")
                game().stack.isEmpty shouldBe true
            }
            cancelAction()
            assertSoftly {
                treasures() shouldBe 2
                human.getZone(ForgeZoneType.Hand).cards.map { it.name } shouldContain "Three Steps Ahead"
                human.getZone(ForgeZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
            }
        }

        session(
            "manual mana clicks keep an unaffordable payment open and invalid Auto Pay cannot commit",
            puzzleFile = "data/puzzles/kicker-unaffordable.pzl",
            fullControl = true,
        ) {
            chooseKicker()
            val payment = allMessages.last { it.hasPayCostsReq() }
            val runtime = bridge.cutCoordinator.promptRuntimes(leyline.bridge.types.SeatId(1)).blocking
            val current = runtime.current()!!
            runtime.submitManaPayment(current.interactionId, current.gameStateId, 0) shouldBe false
            runtime.submitManaPayment(current.interactionId, current.gameStateId - 1, null) shouldBe false
            val source =
                payment.payCostsReq.paymentActions.actionsList
                    .first { it.actionType == ActionType.ActivateMana }
            val slice =
                after {
                    submitGameplayResponse(
                        ClientToGREMessage
                            .newBuilder()
                            .setType(ClientMessageType.PerformActionResp_097b)
                            .setPerformActionResp(PerformActionResp.newBuilder().addActions(source))
                            .build(),
                    ).shouldBeTrue()
                }
            assertSoftly {
                slice.messages
                    .single { it.hasPayCostsReq() }
                    .payCostsReq.autoTapActionsReq.autoTapSolutionsCount shouldBe 0
                human.manaPool.totalMana() shouldBe 1
                human.getZone(ForgeZoneType.Battlefield).cards.count { it.isTapped } shouldBe 1
                game().stack.isEmpty shouldBe true
            }
            cancelAction()
            human.getZone(ForgeZoneType.Hand).cards.count { it.name == "Burst Lightning" } shouldBe 2
            human.manaPool.totalMana() shouldBe 0
            human.getZone(ForgeZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
        }

        session(
            "Spree Auto Pay confirms the selected mode cost and resolves its draw",
            puzzleFile = "data/puzzles/spree-treasure-mana.pzl",
            fullControl = true,
            forgeCatalog = true,
        ) {
            val cto = castSpellUntilCastingTimeOptionsReq("Three Steps Ahead")
            val drawMode =
                cto
                    .getCastingTimeOptionReq(0)
                    .modalReq.modalOptionsList
                    .last()
            respondModalChoice(listOf(drawMode.grpId))
            pay()
            treasures() shouldBe 0
            passUntilResolved()
            val discard = allMessages.last { it.hasSelectNReq() }.selectNReq
            discard.context shouldBe SelectionContext.Discard_a163
            respondToSelectN(listOf(discard.idsList.first()))
            passUntilResolved()
            assertSoftly {
                human.getZone(ForgeZoneType.Graveyard).cards.map { it.name } shouldContain "Three Steps Ahead"
                human.getZone(ForgeZoneType.Hand).cards.size shouldBe 2
                game().stack.isEmpty shouldBe true
            }
        }

        session(
            "manual setting opens ordinary spell payment and Undo refunds the last tapped land",
            puzzleFile = "data/puzzles/manual-mana-mind-stone.pzl",
            fullControl = true,
        ) {
            send(
                clientMessage(ClientMessageType.SetSettingsReq_097b) {
                    setSetSettingsReq(
                        SetSettingsReq.newBuilder().setSettings(
                            SettingsMessage.newBuilder().setManaSelectionType(ManaSelectionType.Manual_a88a),
                        ),
                    )
                },
            )
            bridge.priorityPolicy.currentSettings().manaSelectionType shouldBe ManaSelectionType.Manual_a88a
            castSpellByName("Mind Stone").shouldBeTrue()
            val firstPayment = allMessages.last { it.hasPayCostsReq() }
            val firstSource =
                firstPayment.payCostsReq.paymentActions.actionsList
                    .first { it.actionType == ActionType.ActivateMana }
            submitGameplayResponse(
                ClientToGREMessage
                    .newBuilder()
                    .setType(ClientMessageType.PerformActionResp_097b)
                    .setPerformActionResp(PerformActionResp.newBuilder().addActions(firstSource))
                    .build(),
            ).shouldBeTrue()
            assertSoftly {
                human.manaPool.totalMana() shouldBe 1
                human.getZone(ForgeZoneType.Battlefield).cards.count { it.isTapped } shouldBe 1
                allMessages.last { it.hasPayCostsReq() }.allowUndo shouldBe true
            }
            submitGameplayResponse(
                ClientToGREMessage.newBuilder().setType(ClientMessageType.UndoReq).build(),
            ).shouldBeTrue()
            assertSoftly {
                human.manaPool.totalMana() shouldBe 0
                human.getZone(ForgeZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
            }
            cancelAction()
            human.getZone(ForgeZoneType.Hand).cards.map { it.name } shouldContain "Mind Stone"
        }

        session(
            "manual source selection completes an ordinary Mind Stone cast",
            puzzleFile = "data/puzzles/manual-mana-mind-stone.pzl",
            fullControl = true,
        ) {
            send(
                clientMessage(ClientMessageType.SetSettingsReq_097b) {
                    setSetSettingsReq(
                        SetSettingsReq.newBuilder().setSettings(
                            SettingsMessage.newBuilder().setManaSelectionType(ManaSelectionType.Manual_a88a),
                        ),
                    )
                },
            )
            castSpellByName("Mind Stone").shouldBeTrue()
            repeat(2) {
                val payment = allMessages.last { it.hasPayCostsReq() }
                val source =
                    payment.payCostsReq.paymentActions.actionsList
                        .first { it.actionType == ActionType.ActivateMana }
                submitGameplayResponse(
                    ClientToGREMessage
                        .newBuilder()
                        .setType(ClientMessageType.PerformActionResp_097b)
                        .setPerformActionResp(PerformActionResp.newBuilder().addActions(source))
                        .build(),
                ).shouldBeTrue()
            }
            pay()
            passUntilResolved()
            assertSoftly {
                human.getZone(ForgeZoneType.Battlefield).cards.map { it.name } shouldContain "Mind Stone"
                human.getZone(ForgeZoneType.Battlefield).cards.count { it.name == "Island" && it.isTapped } shouldBe 2
                human.manaPool.totalMana() shouldBe 0
            }
        }
    })
