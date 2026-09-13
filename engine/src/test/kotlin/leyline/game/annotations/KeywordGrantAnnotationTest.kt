package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.annotations.MechanicAnnotations
import leyline.game.codes.DetailKeys
import leyline.game.state.EffectTracker
import leyline.testkit.detailInt
import leyline.testkit.detailUint
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Keyword grant annotation pipeline tests — effectAnnotations keyword branch,
 * LayeredEffectCreated/Destroyed, AddAbility pAnn emission, unmapped keyword skip.
 */
class KeywordGrantAnnotationTest :
    FunSpec({

        tags(UnitTag)

        test("effectAnnotations emits LayeredEffectCreated + AddAbility pAnn for keyword grant") {
            val boostDiff = EffectTracker.DiffResult(emptyList(), emptyList())
            val kwDiff =
                EffectTracker.KeywordDiffResult(
                    created =
                        listOf(
                            trackedKeyword(7010, 389, 1L, 5L, "Flanking", affector = 435),
                            trackedKeyword(7010, 425, 1L, 5L, "Flanking", affector = 435),
                            trackedKeyword(7010, 432, 1L, 5L, "Flanking", affector = 435),
                        ),
                    destroyed = emptyList(),
                )
            var uniqueId = 330
            val (transient, persistent) =
                MechanicAnnotations.effectAnnotations(
                    diff = boostDiff,
                    keywordDiff = kwDiff,
                    keywordAffectorInstanceId = ::identityInstanceId,
                    uniqueAbilityIdAllocator = { uniqueId++ },
                )

            // One LayeredEffectCreated for the keyword effect
            transient.filter { it.typeList.contains(AnnotationType.LayeredEffectCreated) } shouldHaveSize 1

            // One AddAbility+LayeredEffect pAnn
            val pAnn = persistent.first { it.typeList.contains(AnnotationType.AddAbility_af5a) }
            assertSoftly {
                pAnn.affectedIdsList shouldHaveSize 3
                pAnn.detailsList.filter { it.key == "UniqueAbilityId" } shouldHaveSize 3
                pAnn.detailUint("grpid") shouldBe 26
            }
        }

        test("effectAnnotations packs extra ability grpIds for selected keyword grants") {
            val kwDiff =
                EffectTracker.KeywordDiffResult(
                    created =
                        listOf(
                            trackedKeyword(7010, 119, 1L, 5L, "Menace", affector = 114),
                        ),
                    destroyed = emptyList(),
                )
            var uniqueId = 330
            val (transient, persistent) =
                MechanicAnnotations.effectAnnotations(
                    diff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    keywordDiff = kwDiff,
                    keywordAffectorInstanceId = ::identityInstanceId,
                    uniqueAbilityIdAllocator = { uniqueId++ },
                    keywordExtraAbilityGrpIds = { instanceId, keyword ->
                        if (instanceId.value == 119 && keyword == "Menace") {
                            listOf(AnnotationConstants.SUSPECTED_CANT_BLOCK_GRP_ID)
                        } else {
                            emptyList()
                        }
                    },
                )

            transient.filter { it.typeList.contains(AnnotationType.LayeredEffectCreated) } shouldHaveSize 1
            val pAnn = persistent.single { it.typeList.contains(AnnotationType.AddAbility_af5a) }
            assertSoftly {
                pAnn.affectedIdsList shouldBe listOf(119)
                pAnn.detailsList.filter { it.key == DetailKeys.GRPID }.flatMap { it.valueInt32List } shouldBe
                    listOf(142, 86476)
                pAnn.detailsList.filter { it.key == DetailKeys.UNIQUE_ABILITY_ID } shouldHaveSize 2
                pAnn.detailsList.filter { it.key == DetailKeys.ORIGINAL_ABILITY_OBJECT_ZCID }.flatMap { it.valueInt32List } shouldBe
                    listOf(114, 114)
            }
        }

        test("effectAnnotations emits LayeredEffectDestroyed for expired keyword") {
            val kwDiff =
                EffectTracker.KeywordDiffResult(
                    created = emptyList(),
                    destroyed =
                        listOf(
                            EffectTracker.TrackedKeywordEffect(7010, EffectTracker.KeywordFingerprint(389, 1L, 5L, "Trample"), "Trample"),
                        ),
                )
            val (transient, _) =
                MechanicAnnotations.effectAnnotations(
                    diff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    keywordDiff = kwDiff,
                )
            transient.filter { it.typeList.contains(AnnotationType.LayeredEffectDestroyed) } shouldHaveSize 1
        }

        test("effectAnnotations skips keywords without a GRE ability id") {
            val kwDiff =
                EffectTracker.KeywordDiffResult(
                    created =
                        listOf(
                            EffectTracker.TrackedKeywordEffect(7010, EffectTracker.KeywordFingerprint(389, 1L, 5L, "Assist"), "Assist"),
                        ),
                    destroyed = emptyList(),
                )
            val (_, persistent) =
                MechanicAnnotations.effectAnnotations(
                    diff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    keywordDiff = kwDiff,
                    uniqueAbilityIdAllocator = { 1 },
                )
            persistent.shouldBeEmpty()
        }

        test("effectAnnotations groups same keyword from same static ability into one pAnn") {
            val kwDiff =
                EffectTracker.KeywordDiffResult(
                    created =
                        listOf(
                            // Two creatures get Flying from the same static ability (ts=2, staticId=10)
                            trackedKeyword(7020, 100, 2L, 10L, "Flying", affector = 500),
                            trackedKeyword(7020, 200, 2L, 10L, "Flying", affector = 500),
                        ),
                    destroyed = emptyList(),
                )
            var uniqueId = 400
            val (transient, persistent) =
                MechanicAnnotations.effectAnnotations(
                    diff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    keywordDiff = kwDiff,
                    keywordAffectorInstanceId = ::identityInstanceId,
                    uniqueAbilityIdAllocator = { uniqueId++ },
                )

            // One transient (LayeredEffectCreated) for the group
            transient.filter { it.typeList.contains(AnnotationType.LayeredEffectCreated) } shouldHaveSize 1

            // One persistent pAnn covering both creatures
            persistent shouldHaveSize 1
            val pAnn = persistent[0]
            assertSoftly {
                pAnn.affectedIdsList shouldHaveSize 2
                pAnn.detailUint("grpid") shouldBe 8 // Flying
                pAnn.detailInt("effect_id") shouldBe 7020
            }
        }

        test("effectAnnotations handles mixed P/T boosts and keyword grants") {
            val boostDiff =
                EffectTracker.DiffResult(
                    created =
                        listOf(
                            EffectTracker.TrackedEffect(
                                syntheticId = 7005,
                                fingerprint = EffectTracker.EffectFingerprint(100, 1L, 0L),
                                powerDelta = 3,
                                toughnessDelta = 3,
                            ),
                        ),
                    destroyed = emptyList(),
                )
            val kwDiff =
                EffectTracker.KeywordDiffResult(
                    created =
                        listOf(
                            trackedKeyword(7010, 100, 1L, 5L, "Trample", affector = 435),
                        ),
                    destroyed = emptyList(),
                )
            var uniqueId = 330
            val (transient, persistent) =
                MechanicAnnotations.effectAnnotations(
                    diff = boostDiff,
                    keywordDiff = kwDiff,
                    keywordAffectorInstanceId = ::identityInstanceId,
                    uniqueAbilityIdAllocator = { uniqueId++ },
                )

            // Transient: LayeredEffectCreated (boost) + PtModCreated + LayeredEffectCreated (keyword)
            transient.filter { it.typeList.contains(AnnotationType.LayeredEffectCreated) } shouldHaveSize 2

            // Persistent: LayeredEffect (boost) + AddAbility+LayeredEffect (keyword) = 2 total
            assertSoftly {
                persistent shouldHaveSize 2
                persistent.filter { it.typeList.contains(AnnotationType.ModifiedPower) } shouldHaveSize 1
                persistent.filter { it.typeList.contains(AnnotationType.AddAbility_af5a) } shouldHaveSize 1
            }
        }
    })

private fun trackedKeyword(
    syntheticId: Int,
    cardInstanceId: Int,
    timestamp: Long,
    staticId: Long,
    keyword: String,
    affector: Int? = null,
): EffectTracker.TrackedKeywordEffect =
    EffectTracker.TrackedKeywordEffect(
        syntheticId,
        EffectTracker.KeywordFingerprint(cardInstanceId, timestamp, staticId, keyword),
        keyword,
        affector?.let(::ForgeCardId),
    )

private fun identityInstanceId(forgeCardId: ForgeCardId): InstanceId = InstanceId(forgeCardId.value)
