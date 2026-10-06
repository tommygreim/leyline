package leyline.session.costs

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import leyline.tooling.headless.optionalCostResp
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Optional cost interactions — kicker, buyback, multikicker (future).
 *
 * Tests the CastingTimeOptionsReq/Resp protocol: prompt shape, accept/decline,
 * and prompt ordering relative to targeting.
 *
 * Card: Burst Lightning ({R}, kicker {4} — deals 2 damage, or 4 if kicked).
 */
class OptionalCostInteractionTest :
    SessionTest({

        val burstState =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Burst Lightning
            humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain
            humanlibrary=Mountain
            aibattlefield=Centaur Courser
            ailibrary=Mountain
            """.trimIndent()

        /** Accept kicker — send the Kicker option's ctoId. */
        fun MatchFlowHarness.acceptKicker() {
            val kickerOption =
                lastCastingTimeOptionsReq().castingTimeOptionReqList.first {
                    it.castingTimeOptionType == CastingTimeOptionType.Kicker
                }
            respondToOptionalCost(kickerOption.ctoId)
        }

        /** Decline kicker — send the Done option's ctoId (0). */
        fun MatchFlowHarness.declineKicker() {
            val doneOption =
                lastCastingTimeOptionsReq().castingTimeOptionReqList.first {
                    it.castingTimeOptionType == CastingTimeOptionType.Done
                }
            respondToOptionalCost(doneOption.ctoId)
        }

        session("CastingTimeOptionsReq — kicker prompt shape", puzzle = burstState) {
            val cto =
                after { castSpellByName("Burst Lightning").shouldBeTrue() }
                    .expectCastingTimeOptionsReq {
                        option(CastingTimeOptionType.Kicker, ctoId = 1)
                        done(ctoId = 0, required = true)
                    }

            // Locks the option count: block-form asserts Kicker + Done are present
            // and shaped correctly, but a third unexpected option would slip through.
            cto.castingTimeOptionReqList shouldHaveSize 2
        }

        val lowManaState =
            burstState.replace(
                "humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain",
                "humanbattlefield=Mountain;Mountain",
            )

        session("unaffordable chosen cost opens payment and cancel rolls back the cast", puzzle = lowManaState) {
            castSpellByName("Burst Lightning").shouldBeTrue()
            val options = lastCastingTimeOptionsReq().castingTimeOptionReqList
            val kicker = options.single { it.castingTimeOptionType == CastingTimeOptionType.Kicker }
            val normal = options.single { it.castingTimeOptionType == CastingTimeOptionType.Done }
            assertSoftly {
                normal.hasAutoTapSolution() shouldBe true
                kicker.hasAutoTapSolution() shouldBe false
                kicker.manaCostList.sumOf { it.count } shouldBe 5
            }
            // Choosing a cost is not a payment. Native Arena accepts even an
            // unaffordable branch and gives the player a cancellable Pay window.
            val chosen =
                after {
                    submitGameplayResponse(
                        optionalCostResp(kicker.ctoId)
                            .toBuilder()
                            .setCastingTimeOptionsResp(
                                CastingTimeOptionsResp.newBuilder().setCastingTimeOptionResp(
                                    CastingTimeOptionResp
                                        .newBuilder()
                                        .setCtoId(kicker.ctoId)
                                        .setCastingTimeOptionType(CastingTimeOptionType.Kicker),
                                ),
                            ).build(),
                    ).shouldBeTrue()
                }
            chosen.expectOneSelectTargetsReq()
            val payment = after { selectTargets(listOf(OPPONENT_SEAT)) }
            val pay = payment.messages.single { it.hasPayCostsReq() }
            assertSoftly {
                pay.allowCancel shouldBe AllowCancel.Abort
                pay.payCostsReq.manaCostList.sumOf { it.count } shouldBe 5
                pay.payCostsReq.manaCostList.associate { it.colorList.single() to it.count } shouldBe
                    mapOf(ManaColor.Generic to 4, ManaColor.Red_afc9 to 1)
                pay.prompt.promptId shouldBe leyline.game.mapping.PromptIds.PAY_COSTS
                pay.payCostsReq.autoTapActionsReq.autoTapSolutionsCount shouldBe 0
                human.getZone(ForgeZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
                // Forge has not committed a spell while waiting for payment.
                game().stack.isEmpty shouldBe true
            }
            val cancelled =
                after {
                    submitGameplayResponse(
                        ClientToGREMessage.newBuilder().setType(ClientMessageType.CancelActionReq_097b).build(),
                    ).shouldBeTrue()
                }
            assertSoftly {
                human.getZone(ForgeZoneType.Hand).cards.map { it.name } shouldContain "Burst Lightning"
                cancelled.messages.any { it.hasActionsAvailableReq() } shouldBe true
                bridge.cutCoordinator.deferredCast.hasPrompt() shouldBe false
                bridge
                    .projectionStateSnapshot()
                    .viewerCursors
                    .getValue(SeatId(1))
                    .fullState!!
                    .zonesList
                    .single { it.zoneId == ZoneIds.STACK }
                    .objectInstanceIdsList shouldBe emptyList()
            }
            castSpellByName("Burst Lightning").shouldBeTrue()
            after { declineKicker() }.expectOneSelectTargetsReq()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()
            ai.life shouldBe 18
        }

        session("affordable kicker carries its own full-cost payment solution", puzzle = burstState) {
            castSpellByName("Burst Lightning").shouldBeTrue()
            val options = lastCastingTimeOptionsReq().castingTimeOptionReqList
            val kicker = options.single { it.castingTimeOptionType == CastingTimeOptionType.Kicker }
            val normal = options.single { it.castingTimeOptionType == CastingTimeOptionType.Done }
            assertSoftly {
                kicker.hasAutoTapSolution() shouldBe true
                normal.hasAutoTapSolution() shouldBe true
                kicker.autoTapSolution.autoTapActionsCount shouldBe 5
                normal.autoTapSolution.autoTapActionsCount shouldBe 1
                human.getZone(ForgeZoneType.Battlefield).cards.count { it.isTapped } shouldBe 0
            }
            acceptKicker()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()
            ai.life shouldBe 16
        }

        session("kicked Burst Lightning deals 4 damage", puzzle = burstState) {
            castSpellByName("Burst Lightning").shouldBeTrue()
            acceptKicker()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()

            ai.life shouldBe 16
        }

        session("accepted kicker emits CastingTimeOption Kicker details", puzzle = burstState) {
            val burstGrpId = bridge.cardRepository.findGrpIdByName("Burst Lightning")!!
            val kickerAbilityGrpId =
                bridge.cardRepository.findKeywordAbilityGrpId(burstGrpId, KeywordAbilityIds.KICKER)!!

            val snap = messageSnapshot()
            castSpellByName("Burst Lightning").shouldBeTrue()
            acceptKicker()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()

            val messages = messagesSince(snap)
            val ctos =
                messages
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .filter { it.detailInt("type") == CastingTimeOptionType.Kicker.number }
            ctos shouldHaveSize 1
            val cto = ctos.single()

            assertSoftly {
                cto.detailsList.map { it.key }.toSet() shouldBe setOf("type", "kickerAbilityGrpId")
                cto.detailInt("kickerAbilityGrpId") shouldBe kickerAbilityGrpId
            }
        }

        session("unkicked Burst Lightning deals 2 damage", puzzle = burstState) {
            castSpellByName("Burst Lightning").shouldBeTrue()
            declineKicker()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntilResolved()

            ai.life shouldBe 18
        }

        session("kicker GSM has no synthesized ability on stack", puzzle = burstState) {
            val cast = after { castSpellByName("Burst Lightning").shouldBeTrue() }

            // GSM immediately before the CTO should NOT carry a synthesized
            // ability — kicker is a spell-time cost, not an ETB trigger.
            val ctoIdx = cast.messages.indexOfFirst { it.hasCastingTimeOptionsReq() }
            val gsmBeforeCto = cast.messages[ctoIdx - 1].gameStateMessage
            gsmBeforeCto.gameObjectsList.filter { it.type == GameObjectType.Ability } shouldHaveSize 0
        }

        session("optional cost prompt gates targeting — no SelectTargetsReq before response", puzzle = burstState) {
            val cast = after { castSpellByName("Burst Lightning").shouldBeTrue() }
            cast.expectOneCastingTimeOptionsReq().castingTimeOptionReqList shouldHaveSize 2
            cast.expectNoSelectTargetsReq()

            after { declineKicker() }.expectOneSelectTargetsReq()
        }

        session("invalid and stale optional responses leave the exact prompt answerable", puzzle = burstState) {
            after { castSpellByName("Burst Lightning").shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            val promptGameStateId = allMessages.last { it.hasCastingTimeOptionsReq() }.gameStateId
            val stale = optionalCostResp(999).toBuilder().setGameStateId(promptGameStateId - 1).build()
            // These invalid responses intentionally have no owned successor.
            send(stale)
            send(optionalCostResp(999))
            drainSink()

            after { declineKicker() }.expectOneSelectTargetsReq().targetsList shouldHaveSize 1
        }

        session("cancel optional cost prompt returns to priority without orphaning cast", puzzle = burstState) {
            after { castSpellByName("Burst Lightning").shouldBeTrue() }
                .expectOneCastingTimeOptionsReq()

            val cancel = after { cancelAction() }

            cancel.messages.any { it.hasActionsAvailableReq() } shouldBe true
            human
                .getZone(ForgeZoneType.Hand)
                .cards
                .map { it.name } shouldContain "Burst Lightning"
        }
    })
