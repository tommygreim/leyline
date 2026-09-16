package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ManaColorMapping
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationConstants
import leyline.game.codes.CounterTypes
import leyline.game.codes.DetailKeys
import leyline.game.codes.QualificationType
import leyline.game.eid
import leyline.game.event.DamageSourceKind
import leyline.game.grp
import leyline.game.iid
import leyline.game.mapping.ZoneIds
import leyline.game.sid
import leyline.game.wid
import leyline.testkit.detail
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.testkit.detailString
import leyline.testkit.hasDetail
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

@Suppress("LargeClass")
class AnnotationBuilderTest :
    FunSpec({

        tags(UnitTag)

        test("reveal-state builders preserve distinct identities and details") {
            val faceUp = AnnotationBuilder.cardRevealed(400.iid, 500.iid, ZoneIds.P2_LIBRARY)
            val known = AnnotationBuilder.instanceRevealedToOpponent(600.iid)

            assertSoftly {
                faceUp.typeList shouldBe listOf(AnnotationType.CardRevealed)
                faceUp.affectorId shouldBe 400
                faceUp.affectedIdsList shouldBe listOf(500)
                faceUp.detailInt(DetailKeys.SOURCE_ZONE) shouldBe ZoneIds.P2_LIBRARY
                known.typeList shouldBe listOf(AnnotationType.InstanceRevealedToOpponent)
                known.affectorId shouldBe 600
                known.affectedIdsList shouldBe listOf(600)
                known.detailsList.shouldBeEmpty()
            }
        }

        test("zoneTransferAnnotation") {
            val ann =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 100.iid,
                    srcZoneId = 31, // Hand
                    destZoneId = 28, // Battlefield
                    category = "PlayLand",
                )
            assertSoftly {
                ann.typeList shouldContain AnnotationType.ZoneTransfer_af5a
                ann.detailInt("zone_src") shouldBe 31
                ann.detailInt("zone_dest") shouldBe 28
                ann.detailString("category") shouldBe "PlayLand"
            }
            ann.affectedIdsList shouldContain 100
        }

        test("castSpellAnnotation") {
            val ann =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 105.iid,
                    srcZoneId = 31, // Hand
                    destZoneId = 27, // Stack
                    category = "CastSpell",
                )
            assertSoftly {
                ann.detailInt("zone_src") shouldBe 31
                ann.detailInt("zone_dest") shouldBe 27
                ann.detailString("category") shouldBe "CastSpell"
            }
        }

        test("zoneTransferWithActingSeat") {
            val ann =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 200.iid,
                    srcZoneId = 27,
                    destZoneId = 28,
                    category = "Resolve",
                    actingSeatId = 1.sid,
                )
            ann.affectorId shouldBe 1
            ann.affectedIdsList shouldContain 200
        }

        test("zoneTransferWithoutActingSeatHasZeroAffector") {
            val ann =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 200.iid,
                    srcZoneId = 31,
                    destZoneId = 28,
                    category = "PlayLand",
                )
            ann.affectorId shouldBe 0
        }

        test("choiceResult carries static choice value domain and chooser") {
            val ann = AnnotationBuilder.choiceResult(414.iid, 1.sid, choiceValue = 1, choiceDomain = 6)

            assertSoftly {
                ann.typeList shouldContain AnnotationType.ChoiceResult
                ann.affectorId shouldBe 414
                ann.affectedIdsList shouldBe listOf(1)
                ann.detailInt(DetailKeys.CHOICE_VALUE) shouldBe 1
                ann.detailInt(DetailKeys.CHOICE_DOMAIN) shouldBe 6
                ann.detailInt(DetailKeys.CHOICE_SENTIMENT) shouldBe 2
            }
        }

        test("choiceResult can omit domain for sacrifice and discard choices") {
            val ann = AnnotationBuilder.choiceResult(414.iid, 1.sid, choiceValue = 222, choiceDomain = null, sentiment = 1)

            assertSoftly {
                ann.typeList shouldContain AnnotationType.ChoiceResult
                ann.affectorId shouldBe 414
                ann.affectedIdsList shouldBe listOf(1)
                ann.detailInt(DetailKeys.CHOICE_VALUE) shouldBe 222
                ann.hasDetail(DetailKeys.CHOICE_DOMAIN) shouldBe false
                ann.detailInt(DetailKeys.CHOICE_SENTIMENT) shouldBe 1
            }
        }

        test("coinFlip carries flipper and int result") {
            val ann = AnnotationBuilder.coinFlip(414.iid, 1.sid, result = 1)

            assertSoftly {
                ann.typeList shouldContain AnnotationType.CoinFlip
                ann.affectorId shouldBe 414
                ann.affectedIdsList shouldBe listOf(1)
                ann.detailInt(DetailKeys.COIN_FLIP_RESULT) shouldBe 1
                ann.detail(DetailKeys.COIN_FLIP_RESULT)?.type shouldBe KeyValuePairValueType.Int32
            }
        }

        test("linkInfoChoice carries choice link metadata") {
            val ann =
                AnnotationBuilder.linkInfoChoice(
                    sourceInstanceId = 435.iid,
                    affectedIds = listOf(6, 176),
                    chooseLinkType = "Type",
                    sourceAbilityGrpId = 176647.grp,
                )

            assertSoftly {
                ann.typeList shouldContain AnnotationType.LinkInfo
                ann.affectorId shouldBe 435
                ann.affectedIdsList shouldBe listOf(6, 176)
                ann.detailInt(DetailKeys.LINK_TYPE) shouldBe 3
                ann.detailString(DetailKeys.CHOOSE_LINK_TYPE) shouldBe "Type"
                ann.detailInt(DetailKeys.SOURCE_ABILITY_GRPID) shouldBe 176647
            }
        }

        // --- ObjectIdChanged ---

        test("objectIdChangedHasOrigAndNewId") {
            val ann = AnnotationBuilder.objectIdChanged(origId = 100.iid, newId = 150.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.ObjectIdChanged
                ann.affectedIdsList shouldContain 100
                ann.detailInt("orig_id") shouldBe 100
                ann.detailInt("new_id") shouldBe 150
            }
        }

        test("objectIdChangedNoAffectorId") {
            val ann = AnnotationBuilder.objectIdChanged(origId = 100.iid, newId = 200.iid)
            ann.affectorId shouldBe 0
        }

        test("objectIdChangedWithAffectorId") {
            val ann = AnnotationBuilder.objectIdChanged(origId = 100.iid, newId = 200.iid, affectorId = 500.iid)
            ann.affectorId shouldBe 500
            ann.affectedIdsList shouldContain 100
        }

        // --- ZoneTransfer affectorId ---

        test("zoneTransferWithAffectorId") {
            val ann =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 200.iid,
                    srcZoneId = 32,
                    destZoneId = 33,
                    category = "Surveil",
                    affectorId = 500.iid,
                )
            ann.affectorId shouldBe 500
            ann.affectedIdsList shouldContain 200
        }

        test("zoneTransferAffectorIdTakesPrecedenceOverActingSeat") {
            val ann =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 200.iid,
                    srcZoneId = 32,
                    destZoneId = 33,
                    category = "Surveil",
                    actingSeatId = 1.sid,
                    affectorId = 500.iid,
                )
            // affectorId (ability instance) takes precedence over actingSeatId (player seat)
            ann.affectorId shouldBe 500
        }

        // --- UserActionTaken ---

        test("userActionTakenFields") {
            val ann =
                AnnotationBuilder.userActionTaken(
                    instanceId = 300.iid,
                    seatId = 1.sid,
                    actionType = ActionType.Play_add3,
                    abilityGrpId = 0.grp,
                )
            assertSoftly {
                ann.typeList shouldContain AnnotationType.UserActionTaken
                ann.affectorId shouldBe 1
                ann.affectedIdsList shouldContain 300
            }

            assertSoftly {
                ann.detailInt("actionType") shouldBe ActionType.Play_add3.number
                ann.detailInt("abilityGrpId") shouldBe 0
            }
        }

        test("userActionTakenCastType") {
            val ann = AnnotationBuilder.userActionTaken(instanceId = 400.iid, seatId = 2.sid, actionType = ActionType.Cast)
            ann.detailInt("actionType") shouldBe ActionType.Cast.number
            ann.affectorId shouldBe 2
        }

        test("userActionTakenOmitsAlternativeGrpIdWhenZero") {
            val ann =
                AnnotationBuilder.userActionTaken(
                    instanceId = 400.iid,
                    seatId = 1.sid,
                    actionType = ActionType.Cast,
                    abilityGrpId = 0.grp,
                )
            // Hardcast / land-play / regular cast: no alternativeGrpId detail emitted
            ann.hasDetail("alternativeGrpId") shouldBe false
        }

        test("userActionTakenIncludesAlternativeGrpIdWhenSet") {
            // Madness cast — alternativeGrpId carries the madness ability grpId
            val ann =
                AnnotationBuilder.userActionTaken(
                    instanceId = 375.iid,
                    seatId = 1.sid,
                    actionType = ActionType.Cast,
                    abilityGrpId = 5658.grp,
                    alternativeGrpId = 5658.grp,
                )
            assertSoftly {
                ann.detailInt("actionType") shouldBe ActionType.Cast.number
                ann.detailInt("abilityGrpId") shouldBe 5658
                ann.detailInt("alternativeGrpId") shouldBe 5658
            }
        }

        // --- CastingTimeOption ---

        test("castingTimeOptionCastThroughAbilityMadnessShape") {
            // Client-visible CastThroughAbility alt-cost shape for a madness cast.
            val ann =
                AnnotationBuilder.castingTimeOption(
                    stackInstanceId = 361.iid,
                    type = CastingTimeOptionType.CastThroughAbility,
                    alternateCostGrpId = 5658.grp,
                )
            assertSoftly {
                ann.typeList shouldContain AnnotationType.CastingTimeOption
                ann.affectorId shouldBe 361
                ann.affectedIdsList shouldContain 361
            }
            assertSoftly {
                ann.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                ann.detailInt("alternateCostGrpId") shouldBe 5658
                // castAbilityGrpId defaults to alternateCostGrpId for CastThroughAbility
                ann.detailInt("castAbilityGrpId") shouldBe 5658
            }
        }

        test("castingTimeOptionAllowsDistinctCastAbilityGrpId") {
            val ann =
                AnnotationBuilder.castingTimeOption(
                    stackInstanceId = 100.iid,
                    type = CastingTimeOptionType.CastThroughAbility,
                    alternateCostGrpId = 5658.grp,
                    castAbilityGrpId = 9999.grp,
                )
            ann.detailInt("alternateCostGrpId") shouldBe 5658
            ann.detailInt("castAbilityGrpId") shouldBe 9999
        }

        // --- ResolutionStart ---

        test("resolutionStartFields") {
            val ann = AnnotationBuilder.resolutionStart(instanceId = 500.iid, grpId = 12345.grp)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.ResolutionStart
                ann.affectorId shouldBe 500
                ann.affectedIdsList shouldContain 500
                ann.detailInt("grpid") shouldBe 12345
                ann.detail("grpid")?.type shouldBe KeyValuePairValueType.Int32
            }
        }

        // --- ResolutionComplete ---

        test("resolutionCompleteFields") {
            val ann = AnnotationBuilder.resolutionComplete(instanceId = 500.iid, grpId = 12345.grp)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.ResolutionComplete
                ann.affectorId shouldBe 500
                ann.affectedIdsList shouldContain 500
                ann.detailInt("grpid") shouldBe 12345
                ann.detail("grpid")?.type shouldBe KeyValuePairValueType.Int32
            }
        }

        // --- PhaseOrStepModified ---

        test("phaseOrStepModifiedHasContent") {
            val ann = AnnotationBuilder.phaseOrStepModified(activeSeat = 2.sid, phase = 1, step = 2)
            assertSoftly {
                ann.typeList shouldBe listOf(AnnotationType.PhaseOrStepModified)
                ann.affectedIdsList shouldBe listOf(2)
                ann.detailsList.map { it.key }.toSet() shouldBe setOf("phase", "step")
            }
        }

        // --- ManaPaid ---

        test("manaPaidFields") {
            val ann = AnnotationBuilder.manaPaid(spellInstanceId = 600.iid, landInstanceId = 42.iid, manaId = 5, color = 4)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.ManaPaid
                ann.affectedIdsList shouldContain 600
                ann.affectorId shouldBe 42
            }

            assertSoftly {
                ann.detailInt("id") shouldBe 5
                ann.detailInt("color") shouldBe 4
            }
        }

        test("manaPaidDefaults") {
            val ann = AnnotationBuilder.manaPaid(spellInstanceId = 600.iid, landInstanceId = 0.iid)
            assertSoftly {
                // Defaults: manaId=0, color=0
                ann.detailInt("id") shouldBe 0
                ann.detailInt("color") shouldBe 0
            }
        }

        test("manaDetailsFields") {
            val ann = AnnotationBuilder.manaDetails(sourceInstanceId = 42.iid, manaId = 5)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.ManaDetails
                ann.affectorId shouldBe 42
                ann.affectedIdsList shouldContain 5
                ann.detailInt("ManaSpecType_DoesNotEmpty") shouldBe 14695
                ann.detail("ManaSpecType_DoesNotEmpty")?.type shouldBe KeyValuePairValueType.Int32
            }
        }

        // --- TappedUntappedPermanent ---

        test("tappedUntappedPermanentFields") {
            val ann = AnnotationBuilder.tappedUntappedPermanent(permanentId = 700.iid, abilityId = 800.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.TappedUntappedPermanent
                ann.affectorId shouldBe 800
                ann.affectedIdsList shouldContain 700
            }

            ann.detailInt("tapped") shouldBe 1
        }

        test("tappedUntappedPermanentUntapVariant") {
            val ann = AnnotationBuilder.tappedUntappedPermanent(permanentId = 700.iid, abilityId = 800.iid, tapped = false)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.TappedUntappedPermanent
                ann.affectorId shouldBe 800
                ann.affectedIdsList shouldContain 700
            }

            ann.detailInt("tapped") shouldBe 0
        }

        // --- AbilityInstanceCreated ---

        test("abilityInstanceCreatedFields") {
            val ann = AnnotationBuilder.abilityInstanceCreated(abilityInstanceId = 900.iid, sourceZoneId = 31)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.AbilityInstanceCreated
                ann.affectedIdsList shouldContain 900
                ann.affectorId shouldBe 0
            }

            ann.detailInt("source_zone") shouldBe 31
        }

        test("abilityInstanceCreatedWithAffectorId") {
            val ann = AnnotationBuilder.abilityInstanceCreated(abilityInstanceId = 900.iid, affectorId = 42.iid, sourceZoneId = 31)
            ann.affectorId shouldBe 42
            ann.affectedIdsList shouldContain 900
        }

        test("abilityInstanceCreatedDefaultZone") {
            val ann = AnnotationBuilder.abilityInstanceCreated(abilityInstanceId = 900.iid)
            ann.detailInt("source_zone") shouldBe 0
        }

        // --- AbilityInstanceDeleted ---

        test("abilityInstanceDeletedFields") {
            val ann = AnnotationBuilder.abilityInstanceDeleted(abilityInstanceId = 900.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.AbilityInstanceDeleted
                ann.affectedIdsList shouldContain 900
                ann.affectorId shouldBe 0
            }
        }

        test("abilityInstanceDeletedWithAffectorId") {
            val ann = AnnotationBuilder.abilityInstanceDeleted(abilityInstanceId = 900.iid, affectorId = 42.iid)
            ann.affectorId shouldBe 42
            ann.affectedIdsList shouldContain 900
        }

        // --- EnteredZoneThisTurn ---

        test("enteredZoneThisTurnFields") {
            val ann =
                AnnotationBuilder.enteredZoneThisTurn(
                    zoneId = ZoneIds.BATTLEFIELD,
                    instanceIds = listOf(100.iid, 200.iid),
                )
            assertSoftly {
                ann.typeList shouldContain AnnotationType.EnteredZoneThisTurn
                ann.affectorId shouldBe 28
                ann.affectedIdsList shouldContain 100
                ann.affectedIdsList shouldContain 200
                ann.affectedIdsCount shouldBe 2
            }
        }

        test("enteredZoneThisTurnSingleId") {
            val ann = AnnotationBuilder.enteredZoneThisTurn(zoneId = ZoneIds.BATTLEFIELD, instanceId = 100.iid)
            ann.affectedIdsCount shouldBe 1
            ann.affectedIdsList shouldContain 100
        }

        // --- DamageDealt ---

        test("damageDealtFields") {
            val ann =
                AnnotationBuilder.damageDealt(
                    sourceInstanceId = 1000.iid,
                    targetId = 2.wid, // player seat
                    amount = 3,
                    sourceKind = DamageSourceKind.Combat,
                )
            assertSoftly {
                ann.typeList shouldContain AnnotationType.DamageDealt_af5a
                ann.affectorId shouldBe 1000
                ann.affectedIdsList shouldBe listOf(2)
            }

            assertSoftly {
                ann.detailInt("damage") shouldBe 3
                ann.detail("damage")?.type shouldBe KeyValuePairValueType.Int32
                ann.detailInt("type") shouldBe 1
                ann.detail("type")?.type shouldBe KeyValuePairValueType.Int32
                ann.detailInt("markDamage") shouldBe 1
                ann.detail("markDamage")?.type shouldBe KeyValuePairValueType.Int32
            }
        }

        test("damageDealtTypeFromSourceKind") {
            val combat =
                AnnotationBuilder.damageDealt(
                    sourceInstanceId = 1000.iid,
                    targetId = 2.wid,
                    amount = 3,
                    sourceKind = DamageSourceKind.Combat,
                )
            val noncombat =
                AnnotationBuilder.damageDealt(
                    sourceInstanceId = 1000.iid,
                    targetId = 2.wid,
                    amount = 3,
                    sourceKind = DamageSourceKind.SpellOrAbility,
                )
            val fight =
                AnnotationBuilder.damageDealt(
                    sourceInstanceId = 1000.iid,
                    targetId = 2.wid,
                    amount = 3,
                    sourceKind = DamageSourceKind.Fight,
                )
            assertSoftly {
                combat.detailInt("type") shouldBe 1
                noncombat.detailInt("type") shouldBe 2
                fight.detailInt("type") shouldBe 3
            }
        }

        // --- ModifiedLife ---

        test("modifiedLifePositiveDelta") {
            val ann = AnnotationBuilder.modifiedLife(playerSeatId = 1.sid, lifeDelta = 3)
            assertSoftly {
                ann.typeList shouldBe listOf(AnnotationType.ModifiedLife)
                ann.affectedIdsList shouldBe listOf(1)
                ann.detailInt("life") shouldBe 3
            }
        }

        test("modifiedLifeNegativeDelta") {
            val ann = AnnotationBuilder.modifiedLife(playerSeatId = 2.sid, lifeDelta = -5)
            ann.detailInt("life") shouldBe -5
        }

        // --- LossOfGame ---

        test("lossOfGameLifeTotalFields") {
            val ann = AnnotationBuilder.lossOfGame(affectedPlayerSeatId = 1.sid, reason = AnnotationLossReason.LifeTotal)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.LossOfGame_af5a
                ann.affectedIdsList shouldBe listOf(1)
                ann.detailInt("reason") shouldBe 0
            }
        }

        test("lossOfGamePoisonFields") {
            val ann = AnnotationBuilder.lossOfGame(affectedPlayerSeatId = 1.sid, reason = AnnotationLossReason.Poison)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.LossOfGame_af5a
                ann.affectedIdsList shouldBe listOf(1)
                ann.detailString("reason") shouldBe "SBA_Poison"
            }
        }

        // --- SyntheticEvent ---

        test("syntheticEventFields") {
            val ann = AnnotationBuilder.syntheticEvent(attackerIid = 290.iid, targetSeatId = 2.sid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.SyntheticEvent
                ann.affectorId shouldBe 290
                ann.affectedIdsList shouldBe listOf(2)
                ann.detailInt("type") shouldBe 1
                ann.detail("type")?.type shouldBe KeyValuePairValueType.Int32
            }
        }

        // --- TokenCreated (Group B) ---

        test("tokenCreatedFields") {
            val ann = AnnotationBuilder.tokenCreated(instanceId = 1100.iid, affectorId = 2100.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.TokenCreated
                ann.affectedIdsList shouldContain 1100
                ann.affectorId shouldBe 2100
                ann.detailsCount shouldBe 0
            }
        }

        // --- TokenDeleted (Group B) ---

        test("tokenDeletedFields") {
            val ann = AnnotationBuilder.tokenDeleted(instanceId = 1150.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.TokenDeleted
                ann.affectorId shouldBe 1150
                ann.affectedIdsList shouldContain 1150
                ann.affectedIdsCount shouldBe 1
                ann.detailsCount shouldBe 0
            }
        }

        // --- TemporaryPermanent (persistent) ---

        test("temporaryPermanentFields") {
            val ann = AnnotationBuilder.temporaryPermanent(affectedInstanceId = 371.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.TemporaryPermanent
                ann.affectorId shouldBe 371
                ann.affectedIdsList shouldContain 371
                ann.detailInt(DetailKeys.ABILITY_GRP_ID_UPPER) shouldBe 192424
            }
        }

        // --- CounterAdded (Group B) ---

        test("counterAddedFields") {
            val ann = AnnotationBuilder.counterAdded(instanceId = 100.iid, counterType = "P1P1", amount = 2)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.CounterAdded
                ann.affectedIdsList shouldContain 100
                ann.detailInt("counter_type") shouldBe 1 // P1P1
                ann.detailInt("transaction_amount") shouldBe 2
            }
        }

        // --- CounterRemoved (Group B) ---

        test("counterRemovedFields") {
            val ann = AnnotationBuilder.counterRemoved(instanceId = 200.iid, counterType = "LOYALTY", amount = 3)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.CounterRemoved
                ann.affectedIdsList shouldContain 200
                ann.detailInt("counter_type") shouldBe 7 // Loyalty
                ann.detailInt("transaction_amount") shouldBe 3
            }
        }

        test("playerCounterAddedFields") {
            val ann = AnnotationBuilder.playerCounterAdded(seatId = 1.sid, counterType = 3, amount = 2, affectorId = 277.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.CounterAdded
                ann.affectorId shouldBe 277
                ann.affectedIdsList shouldBe listOf(1)
                ann.detailInt("counter_type") shouldBe 3
                ann.detailInt("transaction_amount") shouldBe 2
            }
        }

        // --- Shuffle (Group B) ---

        test("shuffleFields") {
            val ann = AnnotationBuilder.shuffle(seatId = 1.sid, oldIds = listOf(101, 102), newIds = listOf(201, 202), affectorId = 99.iid)
            assertSoftly {
                ann.typeList shouldBe listOf(AnnotationType.Shuffle)
                ann.affectorId shouldBe 99
                ann.affectedIdsList shouldBe listOf(1)
                ann.detailIntList("OldIds") shouldBe listOf(101, 102)
                ann.detailIntList("NewIds") shouldBe listOf(201, 202)
            }
        }

        // --- ModifiedPower (Group B) ---

        test("modifiedPowerFields") {
            val ann = AnnotationBuilder.modifiedPower(instanceId = 1200.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.ModifiedPower
                ann.affectedIdsList shouldContain 1200
                ann.affectorId shouldBe 0
                ann.detailsCount shouldBe 0
            }
        }

        // --- ModifiedToughness (Group B) ---

        test("modifiedToughnessFields") {
            val ann = AnnotationBuilder.modifiedToughness(instanceId = 1300.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.ModifiedToughness
                ann.affectedIdsList shouldContain 1300
                ann.detailsCount shouldBe 0
            }
        }

        // --- RemoveAttachment (Group A+) ---

        test("removeAttachmentFields") {
            val ann = AnnotationBuilder.removeAttachment(auraIid = 1400.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.RemoveAttachment
                ann.affectedIdsList shouldContain 1400
                ann.affectedIdsCount shouldBe 1
            }
        }

        test("removeAttachmentWithTargetAndInvalidatingGrpId") {
            val ann = AnnotationBuilder.removeAttachment(auraIid = 1400.iid, targetIid = 1500.iid, invalidatingGrpId = 244.grp)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.RemoveAttachment
                ann.affectorId shouldBe 1400
                ann.affectedIdsList shouldBe listOf(1500)
                ann.detailInt("invalidating_grpid") shouldBe 244
            }
        }

        // --- AttachmentCreated (Group A+) ---

        test("attachmentCreatedFields") {
            val ann = AnnotationBuilder.attachmentCreated(auraIid = 1500.iid, targetIid = 1600.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.AttachmentCreated
                ann.affectorId shouldBe 1500
                ann.affectedIdsList shouldBe listOf(1600)
            }
        }

        // --- Attachment (Group A+ persistent) ---

        test("attachmentFields") {
            val ann = AnnotationBuilder.attachment(auraIid = 1500.iid, targetIid = 1600.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.Attachment
                ann.affectorId shouldBe 1500
                ann.affectedIdsList shouldBe listOf(1600)
            }
        }

        // --- Scry (Group B) ---

        test("scryFields") {
            val ann = AnnotationBuilder.scry(seatId = 1.sid, topIds = listOf(100, 101), bottomIds = listOf(102))
            assertSoftly {
                ann.typeList shouldContain AnnotationType.Scry_af5a
                ann.affectedIdsList shouldBe listOf(100, 101, 102)
                ann.detailIntList("topIds") shouldBe listOf(100, 101)
                ann.detailIntList("bottomIds") shouldBe listOf(102)
            }
        }

        // --- Counter State (Tier 1) ---

        test("counterStateFields") {
            val ann = AnnotationBuilder.counter(instanceId = 100.iid, counterType = 1, count = 1)
            assertSoftly {
                ann.typeList shouldBe
                    listOf(
                        AnnotationType.ModifiedToughness,
                        AnnotationType.ModifiedPower,
                        AnnotationType.Counter_803b,
                    )
                ann.affectedIdsList shouldContain 100
                ann.detailInt("count") shouldBe 1
                ann.detailInt("counter_type") shouldBe 1
            }
        }

        test("minusOneMinusOneCounterStateCarriesPowerToughnessTypes") {
            val ann = AnnotationBuilder.counter(instanceId = 100.iid, counterType = 2, count = 1)
            ann.typeList shouldBe
                listOf(
                    AnnotationType.ModifiedToughness,
                    AnnotationType.ModifiedPower,
                    AnnotationType.Counter_803b,
                )
        }

        test("playerCounterStateFields") {
            val ann = AnnotationBuilder.playerCounter(seatId = 1.sid, counterType = 3, count = 10)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.Counter_803b
                ann.affectedIdsList shouldContain 1
                ann.detailInt("count") shouldBe 10
                ann.detailInt("counter_type") shouldBe 3
            }
        }

        test("counterTypeIdMapsForgeNames") {
            assertSoftly {
                // Exact matches (P1P1, M1M1 already uppercase in both)
                CounterTypes.counterTypeId("P1P1") shouldBe 1
                CounterTypes.counterTypeId("M1M1") shouldBe 2
                // Forge UPPERCASE → proto PascalCase
                CounterTypes.counterTypeId("LOYALTY") shouldBe 7
                CounterTypes.counterTypeId("CHARGE") shouldBe 19
                CounterTypes.counterTypeId("AGE") shouldBe 9
                CounterTypes.counterTypeId("BLOOD") shouldBe 15
                CounterTypes.counterTypeId("STUN") shouldBe 172
                CounterTypes.counterTypeId("POISON") shouldBe 3
                CounterTypes.counterTypeId("LORE") shouldBe 108
                // Unknown falls back to 0
                CounterTypes.counterTypeId("NONEXISTENT") shouldBe 0
            }
        }

        // --- AddAbility (Tier 1) ---

        test("addAbilityFields") {
            val ann =
                AnnotationBuilder.addAbility(
                    instanceId = 100.iid,
                    grpId = 6.grp,
                    effectId = 7005.eid,
                    uniqueAbilityId = 217,
                    originalAbilityObjectZcid = 372,
                )
            assertSoftly {
                ann.typeList shouldContain AnnotationType.AddAbility_af5a
                ann.affectedIdsList shouldContain 100
                ann.detailInt("grpid") shouldBe 6
                ann.detail("grpid")?.type shouldBe KeyValuePairValueType.Int32
                ann.detailInt("effect_id") shouldBe 7005
                ann.detailInt("UniqueAbilityId") shouldBe 217
                ann.detailInt("originalAbilityObjectZcid") shouldBe 372
            }
        }

        test("addAbilityMultiUsesInt32GrpId") {
            val ann =
                AnnotationBuilder.addAbilityMulti(
                    affectedIds = listOf(100.iid, 101.iid),
                    grpId = 6.grp,
                    effectId = 7005.eid,
                    uniqueAbilityIds = listOf(217, 218),
                    originalAbilityObjectZcid = 372,
                    affectorId = 300.iid,
                )

            assertSoftly {
                ann.detailInt("grpid") shouldBe 6
                ann.detail("grpid")?.type shouldBe KeyValuePairValueType.Int32
            }
        }

        test("addAbilityPackedUsesInt32GrpIds") {
            val ann =
                AnnotationBuilder.addAbilityPacked(
                    affectedId = 100.iid,
                    grpIds = listOf(6.grp, 14.grp),
                    effectId = 7005.eid,
                    uniqueAbilityIds = listOf(217, 218),
                    originalAbilityObjectZcids = listOf(372, 373),
                    affectorId = 300.iid,
                )

            val grpIdDetails = ann.detailsList.filter { it.key == "grpid" }
            assertSoftly {
                grpIdDetails.map { it.getValueInt32(0) } shouldBe listOf(6, 14)
                grpIdDetails.map { it.type } shouldBe listOf(KeyValuePairValueType.Int32, KeyValuePairValueType.Int32)
            }
        }

        // --- RemoveAbility (Tier 1) ---

        test("removeAbilityFields") {
            val ann = AnnotationBuilder.removeAbility(instanceId = 200.iid, effectId = 7003.eid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.RemoveAbility
                ann.affectedIdsList shouldContain 200
                ann.detailInt("effect_id") shouldBe 7003
                ann.detailsCount shouldBe 1
            }
        }

        // --- AbilityExhausted (Tier 1) ---

        test("abilityExhaustedFields") {
            val ann =
                AnnotationBuilder.abilityExhausted(
                    instanceId = 294.iid,
                    abilityGrpId = 137955.grp,
                    usesRemaining = 0,
                    uniqueAbilityId = 205,
                )
            assertSoftly {
                ann.typeList shouldContain AnnotationType.AbilityExhausted
                ann.affectorId shouldBe 294
                ann.affectedIdsList shouldContain 294
                ann.detailInt("AbilityGrpId") shouldBe 137955
                ann.detailInt("UsesRemaining") shouldBe 0
                ann.detailInt("UniqueAbilityId") shouldBe 205
            }
        }

        // --- GainDesignation (Tier 1) ---

        test("gainDesignationFields") {
            val ann = AnnotationBuilder.gainDesignation(seatId = 1.sid, designationType = 19)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.GainDesignation
                ann.affectedIdsList shouldContain 1
                ann.detailInt("DesignationType") shouldBe 19
            }
        }

        // --- Designation (Tier 1 stub) ---

        test("designationFields") {
            val ann = AnnotationBuilder.designation(seatId = 1.sid, designationType = 19)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.Designation
                ann.affectedIdsList shouldContain 1
                ann.detailInt("DesignationType") shouldBe 19
            }
        }

        // --- Card-scoped Designation overloads (Prepared, Saddled, Plotted, Day/Night) ---

        test("gainDesignationOnCardFields") {
            val ann = AnnotationBuilder.gainDesignationOnCard(instanceId = 313.iid, designationType = 24)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.GainDesignation
                ann.affectorId shouldBe 313
                ann.affectedIdsList shouldContain 313
                ann.detailInt("DesignationType") shouldBe 24
            }
        }

        test("saddledThisTurnFields") {
            val ann = AnnotationBuilder.saddledThisTurn(mountInstanceId = 313.iid, saddleSourceInstanceIds = listOf(401.iid, 402.iid))
            assertSoftly {
                ann.typeList shouldContain AnnotationType.SaddledThisTurn
                ann.affectorId shouldBe 313
                ann.affectedIdsList shouldBe listOf(401, 402)
                ann.detailsList.shouldBeEmpty()
            }
        }

        test("preparedDesignationFields") {
            val ann = AnnotationBuilder.preparedDesignation(instanceId = 313.iid, preparedCopyInstanceId = 318.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.Designation
                ann.affectorId shouldBe 313
                ann.affectedIdsList shouldContain 313
                ann.detailInt("DesignationType") shouldBe 24
                ann.detailInt("PreparedCopyZcid") shouldBe 318
            }
        }

        test("loseDesignationFields") {
            val ann = AnnotationBuilder.loseDesignation(instanceId = 313.iid, designationType = 24)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.LoseDesignation
                ann.affectorId shouldBe 313
                ann.affectedIdsList shouldContain 313
                ann.detailInt("DesignationType") shouldBe 24
            }
        }

        test("plottedDesignationFields") {
            val ann = AnnotationBuilder.plottedDesignation(instanceId = 411.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.Designation
                ann.affectorId shouldBe 411
                ann.affectedIdsList shouldContain 411
                ann.detailInt("DesignationType") shouldBe 18
                // No PreparedCopyZcid analog — plotted card itself is in exile, no copy
                ann.detailsList.filter { it.key == "PreparedCopyZcid" }.shouldBeEmpty()
            }
        }

        test("suspectedDesignationFields") {
            val ann = AnnotationBuilder.suspectedDesignation(instanceId = 411.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.Designation
                ann.affectorId shouldBe 411
                ann.affectedIdsList shouldContain 411
                ann.detailInt("DesignationType") shouldBe AnnotationConstants.DESIGNATION_TYPE_SUSPECTED
            }
        }

        test("gainDesignationOnGameFields — game-scope lite shape, no affector") {
            val ann = AnnotationBuilder.gainDesignationOnGame(designationType = 10)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.GainDesignation
                ann.affectorId shouldBe 0
                ann.affectedIdsList shouldContain 0
                ann.detailInt("DesignationType") shouldBe 10
                // Lite shape — no APSC on the transient.
                ann.detailsList.filter { it.key == "ActivePlayerSpellCount" }.shouldBeEmpty()
            }
        }

        test("loseDesignationOnGameFields — game-scope lite shape, no affector") {
            val ann = AnnotationBuilder.loseDesignationOnGame(designationType = 11)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.LoseDesignation
                ann.affectorId shouldBe 0
                ann.affectedIdsList shouldContain 0
                ann.detailInt("DesignationType") shouldBe 11
            }
        }

        test("dayNightDesignationFields — persistent rich shape carries APSC") {
            val ann = AnnotationBuilder.dayNightDesignation(designationType = 10, activePlayerSpellCount = 3)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.Designation
                ann.affectorId shouldBe 0
                ann.affectedIdsList shouldContain 0
                ann.detailInt("DesignationType") shouldBe 10
                ann.detailInt("ActivePlayerSpellCount") shouldBe 3
                // Detail-key order matches the rest of the Designation family —
                // auxiliary rich key first, DesignationType last.
                ann.detailsList[0].key shouldBe "ActivePlayerSpellCount"
                ann.detailsList[1].key shouldBe "DesignationType"
            }
        }

        test("dayNightDesignation — rejects non-Day/Night designation types") {
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                AnnotationBuilder.dayNightDesignation(designationType = 18, activePlayerSpellCount = 0)
            }
        }

        test("commander designation rich fields") {
            val playerAnn =
                AnnotationBuilder.commanderPlayerDesignation(
                    seatId = 1.sid,
                    grpId = 93675.grp,
                    colorIdentity = listOf(1, 4),
                    costIncrease = 2,
                )
            val objectAnn =
                AnnotationBuilder.commanderObjectDesignation(
                    instanceId = 411.iid,
                    grpId = 93675.grp,
                    colorIdentity = listOf(1, 4),
                    costIncrease = 2,
                )

            assertSoftly {
                playerAnn.typeList shouldContain AnnotationType.Designation
                playerAnn.affectorId shouldBe 1
                playerAnn.affectedIdsList shouldBe listOf(1)
                playerAnn.detailInt("DesignationType") shouldBe 1
                playerAnn.detailInt("grpid") shouldBe 93675
                playerAnn.detailInt("CostIncrease") shouldBe 2
                playerAnn.detailIntList("ColorIdentity") shouldBe listOf(1, 4)

                objectAnn.affectorId shouldBe 411
                objectAnn.affectedIdsList shouldBe listOf(411)
                objectAnn.detailInt("DesignationType") shouldBe 1
                objectAnn.detailInt("grpid") shouldBe 93675
                objectAnn.detailInt("CostIncrease") shouldBe 2
                objectAnn.detailIntList("ColorIdentity") shouldBe listOf(1, 4)
            }
        }

        test("leftUnlockedDesignationFields") {
            val ann = AnnotationBuilder.leftUnlockedDesignation(instanceId = 117.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.Designation
                ann.affectorId shouldBe 117
                ann.affectedIdsList shouldContain 117
                ann.detailInt("DesignationType") shouldBe 19
            }
        }

        test("rightUnlockedDesignationFields") {
            val ann = AnnotationBuilder.rightUnlockedDesignation(instanceId = 117.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.Designation
                ann.affectorId shouldBe 117
                ann.affectedIdsList shouldContain 117
                ann.detailInt("DesignationType") shouldBe 20
            }
        }

        test("manaCreatureDesignationFields") {
            val ann = AnnotationBuilder.manaCreatureDesignation(instanceId = 199.iid, controllerId = 1.sid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.Designation
                ann.affectorId shouldBe 199
                ann.affectedIdsList shouldBe listOf(199)
                ann.detailInt("DesignationType") shouldBe 23
                ann.detailInt("ControllerId") shouldBe 1
            }
        }

        test("earthbend layered-effect rows carry source ability and layer ids") {
            val type =
                AnnotationBuilder.earthbendModifiedTypeLayeredEffect(
                    instanceId = 199.iid,
                    affectorId = 287.iid,
                    effectId = 7006.eid,
                    sourceAbilityGrpId = 192806.grp,
                )
            val haste =
                AnnotationBuilder.earthbendAddHasteLayeredEffect(
                    instanceId = 199.iid,
                    affectorId = 287.iid,
                    effectId = 7007.eid,
                    sourceAbilityGrpId = 192806.grp,
                    uniqueAbilityId = 203,
                    originalAbilityObjectZcid = 287,
                    hasteGrpId = 9.grp,
                )
            val power =
                AnnotationBuilder.earthbendModifiedPowerLayeredEffect(
                    instanceId = 199.iid,
                    affectorId = 287.iid,
                    effectId = 7008.eid,
                    sourceAbilityGrpId = 192806.grp,
                )
            val toughness =
                AnnotationBuilder.earthbendModifiedToughnessLayeredEffect(
                    instanceId = 199.iid,
                    affectorId = 287.iid,
                    effectId = 7009.eid,
                    sourceAbilityGrpId = 192806.grp,
                )

            assertSoftly {
                type.typeList shouldContain AnnotationType.ModifiedType
                type.typeList shouldContain AnnotationType.LayeredEffect
                type.affectorId shouldBe 287
                type.detailInt("sourceAbilityGRPID") shouldBe 192806
                type.detailInt("effect_id") shouldBe 7006

                haste.typeList shouldContain AnnotationType.AddAbility_af5a
                haste.typeList shouldContain AnnotationType.LayeredEffect
                haste.detailInt("originalAbilityObjectZcid") shouldBe 287
                haste.detailInt("UniqueAbilityId") shouldBe 203
                haste.detailInt("grpid") shouldBe 9
                haste.detailInt("sourceAbilityGRPID") shouldBe 192806
                haste.detailInt("effect_id") shouldBe 7007

                power.typeList shouldContain AnnotationType.ModifiedPower
                power.detailInt("effect_id") shouldBe 7008
                toughness.typeList shouldContain AnnotationType.ModifiedToughness
                toughness.detailInt("effect_id") shouldBe 7009
            }
        }

        test("faceDownFields") {
            val ann = AnnotationBuilder.faceDown(instanceId = 388.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.FaceDown
                ann.affectorId shouldBe 388
                ann.affectedIdsList shouldContain 388
                ann.detailsList.shouldBeEmpty()
            }
        }

        test("suppressedPowerAndToughnessFields") {
            val ann = AnnotationBuilder.suppressedPowerAndToughness(instanceId = 388.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.SuppressedPowerAndToughness
                ann.affectorId shouldBe 388
                ann.affectedIdsList shouldContain 388
                ann.detailsList.shouldBeEmpty()
            }
        }

        // --- LayeredEffect (Tier 1 stub) ---

        test("layeredEffectFields") {
            val ann = AnnotationBuilder.layeredEffect(instanceId = 289.iid, effectId = 7004.eid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.LayeredEffect
                ann.affectedIdsList shouldContain 289
                ann.detailInt("effect_id") shouldBe 7004
            }
        }

        // --- LayeredEffectCreated ---

        test("layeredEffectCreated has correct type and affectedIds") {
            val ann = AnnotationBuilder.layeredEffectCreated(effectId = 7005.eid)
            ann.typeList.first() shouldBe AnnotationType.LayeredEffectCreated
            ann.affectedIdsList shouldBe listOf(7005)
        }

        test("layeredEffectCreated with affectorId includes it") {
            val ann = AnnotationBuilder.layeredEffectCreated(effectId = 7005.eid, affectorId = 335.iid)
            ann.affectorId shouldBe 335
        }

        test("layeredEffectCreated without affectorId defaults to zero") {
            val ann = AnnotationBuilder.layeredEffectCreated(effectId = 7005.eid)
            ann.affectorId shouldBe 0
        }

        test("layeredEffect P/T buff has multi-type array") {
            val ann =
                AnnotationBuilder.layeredEffect(
                    instanceId = 100.iid,
                    effectId = 7005.eid,
                    powerDelta = 1,
                    toughnessDelta = 1,
                    affectorId = 100.iid,
                )
            // Client expects: [ModifiedToughness, ModifiedPower, LayeredEffect]
            assertSoftly {
                ann.typeList shouldContain AnnotationType.ModifiedToughness
                ann.typeList shouldContain AnnotationType.ModifiedPower
                ann.typeList shouldContain AnnotationType.LayeredEffect
                ann.affectedIdsList shouldBe listOf(100)
                ann.affectorId shouldBe 100
                ann.detailInt("effect_id") shouldBe 7005
            }
            // No LayeredEffectType for P/T buffs (client only uses it for CopyObject)
            ann.detailsList.none { it.key == "LayeredEffectType" } shouldBe true
        }

        test("layeredEffect power-only has ModifiedPower co-type") {
            val ann = AnnotationBuilder.layeredEffect(instanceId = 100.iid, effectId = 7005.eid, powerDelta = 3)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.ModifiedPower
                ann.typeList shouldContain AnnotationType.LayeredEffect
                ann.typeList.none { it == AnnotationType.ModifiedToughness } shouldBe true
            }
        }

        test("layeredEffect no deltas has LayeredEffect only") {
            val ann = AnnotationBuilder.layeredEffect(instanceId = 100.iid, effectId = 7005.eid)
            ann.typeList shouldBe listOf(AnnotationType.LayeredEffect)
            ann.affectedIdsList shouldBe listOf(100)
        }

        test("solvedDesignation uses card-scoped DesignationType 15") {
            val ann = AnnotationBuilder.solvedDesignation(23.iid)
            assertSoftly {
                ann.typeList shouldBe listOf(AnnotationType.Designation)
                ann.affectorId shouldBe 23
                ann.affectedIdsList shouldBe listOf(23)
                ann.detailsList.single().getValueInt32(0) shouldBe AnnotationConstants.DESIGNATION_TYPE_SOLVED
            }
        }

        test("layeredEffect sourceAbilityGrpId included when set") {
            val ann =
                AnnotationBuilder.layeredEffect(
                    instanceId = 100.iid,
                    effectId = 7007.eid,
                    sourceAbilityGrpId = 137.grp,
                    powerDelta = 1,
                    toughnessDelta = 1,
                )
            ann.detailInt("sourceAbilityGRPID") shouldBe 137
        }

        test("powerToughnessModCreated has affectorId and detail keys") {
            val ann =
                AnnotationBuilder.powerToughnessModCreated(
                    instanceId = 335.iid,
                    power = 1,
                    toughness = 1,
                    affectorId = 340.iid,
                )
            assertSoftly {
                ann.typeList shouldBe listOf(AnnotationType.PowerToughnessModCreated)
                ann.affectedIdsList shouldBe listOf(335)
                ann.affectorId shouldBe 340
            }
            assertSoftly {
                ann.detailInt("power") shouldBe 1
                ann.detailInt("toughness") shouldBe 1
            }
        }

        // --- Detail-less Tier 2 ---

        test("layeredEffectDestroyedFields") {
            val ann = AnnotationBuilder.layeredEffectDestroyed(effectId = 7007.eid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.LayeredEffectDestroyed
                ann.affectedIdsList shouldContain 7007
                ann.detailsCount shouldBe 0
            }
        }

        test("playerSelectingTargetsFields") {
            val ann = AnnotationBuilder.playerSelectingTargets(instanceId = 303.iid, casterSeatId = 2.sid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.PlayerSelectingTargets
                ann.affectorId shouldBe 2
                ann.affectedIdsList shouldContain 303
                ann.detailsCount shouldBe 0
            }
        }

        test("playerSubmittedTargetsFields") {
            val ann = AnnotationBuilder.playerSubmittedTargets(instanceId = 303.iid, casterSeatId = 2.sid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.PlayerSubmittedTargets
                ann.affectorId shouldBe 2
                ann.affectedIdsList shouldContain 303
                ann.detailsCount shouldBe 0
            }
        }

        test("damagedThisTurnFields") {
            val ann = AnnotationBuilder.damagedThisTurn(affectedIds = listOf(355.iid))
            assertSoftly {
                ann.typeList shouldContain AnnotationType.DamagedThisTurn
                ann.affectedIdsList shouldContain 355
                ann.affectorId shouldBe AnnotationConstants.BATTLEFIELD_ZONE_AFFECTOR.value
                ann.affectorId shouldBe 28
                ann.detailsCount shouldBe 0
            }
        }

        test("damagedThisTurnAccumulatesMultipleVictims") {
            val ann = AnnotationBuilder.damagedThisTurn(affectedIds = listOf(100.iid, 200.iid, 300.iid))
            ann.affectedIdsList shouldBe listOf(100, 200, 300)
            ann.affectorId shouldBe 28
        }

        test("instanceRevealedToOpponentFields") {
            val ann = AnnotationBuilder.instanceRevealedToOpponent(instanceId = 232.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.InstanceRevealedToOpponent
                ann.affectedIdsList shouldContain 232
                ann.detailsCount shouldBe 0
            }
        }

        // --- ColorProduction (Tier 2) ---

        test("colorProductionFields") {
            val ann = AnnotationBuilder.colorProduction(instanceId = 279.iid, colors = listOf(4))
            assertSoftly {
                ann.typeList shouldContain AnnotationType.ColorProduction
                ann.affectorId shouldBe 279
                ann.affectedIdsList shouldContain 279
                ann.detailInt("colors") shouldBe 4
            }
        }

        test("colorProductionMultiColor") {
            val ann = AnnotationBuilder.colorProduction(instanceId = 283.iid, colors = listOf(4, 5))
            ann.affectorId shouldBe 283
            ann.affectedIdsList shouldContain 283
            val colors = ann.detailsList.first { it.key == "colors" }
            assertSoftly {
                colors.valueInt32Count shouldBe 2
                colors.getValueInt32(0) shouldBe 4
                colors.getValueInt32(1) shouldBe 5
            }
        }

        // --- Color ordinal conversion (used by GameEventCollector.computeColorOrdinals) ---

        test("manaColorMappingProducesClientOrdinals") {
            assertSoftly {
                ManaColorMapping.fromProduced("W")?.number shouldBe 1
                ManaColorMapping.fromProduced("U")?.number shouldBe 2
                ManaColorMapping.fromProduced("B")?.number shouldBe 3
                ManaColorMapping.fromProduced("R")?.number shouldBe 4
                ManaColorMapping.fromProduced("G")?.number shouldBe 5
            }
        }

        test("manaColorMappingDualLandOrdinals") {
            val wg = listOf("W", "G").mapNotNull { ManaColorMapping.fromProduced(it)?.number }
            wg shouldBe listOf(1, 5)
            val ub = listOf("U", "B").mapNotNull { ManaColorMapping.fromProduced(it)?.number }
            ub shouldBe listOf(2, 3)
        }

        // --- TriggeringObject (Tier 2) ---

        test("triggeringObjectFields") {
            val ann =
                AnnotationBuilder.triggeringObject(
                    abilityInstanceId = 294.iid,
                    sourceCardInstanceId = 195.iid,
                    sourceZone = 27,
                )
            assertSoftly {
                ann.typeList shouldContain AnnotationType.TriggeringObject
                ann.affectorId shouldBe 294
                ann.affectedIdsList shouldContain 195
                ann.detailInt("source_zone") shouldBe 27
            }
        }

        // --- TargetSpec (Tier 2) ---

        test("targetSpecFields") {
            val ann =
                AnnotationBuilder.targetSpec(
                    instanceId = 293.iid,
                    affectorId = 303.iid,
                    abilityGrpId = 176387.grp,
                    index = 1,
                    promptId = 1330,
                    promptParameters = 303,
                )
            assertSoftly {
                ann.typeList shouldContain AnnotationType.TargetSpec
                ann.affectedIdsList shouldContain 293
                ann.detailInt("abilityGrpId") shouldBe 176387
                ann.detailInt("index") shouldBe 1
                ann.detailInt("promptId") shouldBe 1330
                ann.detailInt("promptParameters") shouldBe 303
            }
        }

        test("targetSpec groups affectees and aligned distributions") {
            val ann =
                AnnotationBuilder.targetSpec(
                    instanceIds = listOf(293.iid, 294.iid),
                    affectorId = 303.iid,
                    abilityGrpId = 90088.grp,
                    index = 1,
                    promptId = 11869,
                    promptParameters = 303,
                    distributions = listOf(1, 1),
                )
            assertSoftly {
                ann.affectedIdsList shouldBe listOf(293, 294)
                ann.detailIntList("distributions") shouldBe listOf(1, 1)
            }
        }

        // --- PowerToughnessModCreated (Tier 2) ---

        test("powerToughnessModCreatedFields") {
            val ann = AnnotationBuilder.powerToughnessModCreated(instanceId = 335.iid, power = 1, toughness = 1)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.PowerToughnessModCreated
                ann.affectedIdsList shouldContain 335
                ann.detailInt("power") shouldBe 1
                ann.detailInt("toughness") shouldBe 1
            }
        }

        // --- DisplayCardUnderCard (Tier 2) ---

        test("displayCardUnderCardFields") {
            val ann = AnnotationBuilder.displayCardUnderCard(affectorId = 200.iid, instanceId = 304.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.DisplayCardUnderCard
                ann.affectorId shouldBe 200
                ann.affectedIdsList shouldContain 304
            }
            assertSoftly {
                ann.detailInt("Disable") shouldBe 0
                ann.detailInt("TemporaryZoneTransfer") shouldBe 1
            }
        }

        // --- PredictedDirectDamage (Tier 2) ---

        test("predictedDirectDamageFields") {
            val ann = AnnotationBuilder.predictedDirectDamage(instanceId = 336.iid, value = 2)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.PredictedDirectDamage
                ann.affectedIdsList shouldContain 336
                ann.detailInt("value") shouldBe 2
            }
        }

        // --- Qualification (Tier 1 persistent) ---

        test("qualificationAdventure") {
            val ann = AnnotationBuilder.qualification(instanceId = 348.iid)
            assertSoftly {
                ann.typeList shouldContain AnnotationType.Qualification
                ann.affectedIdsList shouldBe listOf(348)
                ann.detailInt("QualificationType") shouldBe 47
                ann.detailInt("QualificationSubtype") shouldBe 0
                ann.detailInt("grpid") shouldBe 196
                ann.detailInt("SourceParent") shouldBe 0
                ann.detail("QualificationType")?.type shouldBe KeyValuePairValueType.Int32
                ann.detail("QualificationSubtype")?.type shouldBe KeyValuePairValueType.Int32
                ann.detail("grpid")?.type shouldBe KeyValuePairValueType.Int32
                ann.detail("SourceParent")?.type shouldBe KeyValuePairValueType.Int32
            }
            ann.affectorId shouldBe 0
        }

        // --- AbilityWordActive (Tier 1 persistent) ---

        test("abilityWordActiveQuantitative") {
            val ann =
                AnnotationBuilder.abilityWordActive(
                    instanceId = 295.iid,
                    abilityWordName = "Threshold",
                    value = 5,
                    threshold = 7,
                    abilityGrpId = 175886.grp,
                )
            assertSoftly {
                ann.typeList shouldContain AnnotationType.AbilityWordActive
                ann.affectorId shouldBe 295
                ann.affectedIdsList shouldBe listOf(295)
                ann.detailString("AbilityWordName") shouldBe "Threshold"
                ann.detailInt("value") shouldBe 5
                ann.detailInt("threshold") shouldBe 7
                ann.detailInt("AbilityGrpId") shouldBe 175886
            }
        }

        test("AbilityWordActive emits exact paid colors as a required int32 list") {
            val ann =
                AnnotationBuilder.abilityWordActive(
                    instanceId = 700.iid,
                    abilityWordName = "ColorsSpentToCast",
                    colors = listOf(1, 4),
                )

            assertSoftly {
                ann.affectorId shouldBe 700
                ann.affectedIdsList shouldBe listOf(700)
                ann.detailIntList(DetailKeys.COLORS) shouldBe listOf(1, 4)
                ann.detailString(DetailKeys.ABILITY_WORD_NAME) shouldBe "ColorsSpentToCast"
            }
        }

        test("abilityWordActiveKeywordOnly") {
            val ann =
                AnnotationBuilder.abilityWordActive(
                    instanceId = 303.iid,
                    abilityWordName = "Descended",
                    affectorId = 1.iid,
                )
            assertSoftly {
                ann.typeList shouldContain AnnotationType.AbilityWordActive
                ann.affectorId shouldBe 1
                ann.affectedIdsList shouldBe listOf(303)
                ann.detailString("AbilityWordName") shouldBe "Descended"
                ann.detail("value") shouldBe null
                ann.detail("threshold") shouldBe null
            }
        }

        test("qualification annotation shape") {
            val ann =
                AnnotationBuilder.qualification(
                    affectorId = 287.iid,
                    instanceId = 287.iid,
                    grpId = 142.grp,
                    qualificationType = QualificationType.CombatKeyword,
                    qualificationSubtype = 0,
                    sourceParent = 287.iid,
                )
            assertSoftly {
                ann.typeList shouldContain AnnotationType.Qualification
                ann.affectorId shouldBe 287
                ann.affectedIdsList shouldContain 287
            }
            assertSoftly {
                ann.detailInt("grpid") shouldBe 142
                ann.detailInt("QualificationType") shouldBe QualificationType.CombatKeyword.wireValue
                ann.detailInt("QualificationSubtype") shouldBe 0
                ann.detailInt("SourceParent") shouldBe 287
                ann.detail("grpid")?.type shouldBe KeyValuePairValueType.Int32
                ann.detail("QualificationType")?.type shouldBe KeyValuePairValueType.Int32
                ann.detail("QualificationSubtype")?.type shouldBe KeyValuePairValueType.Int32
                ann.detail("SourceParent")?.type shouldBe KeyValuePairValueType.Int32
            }
        }
    })
