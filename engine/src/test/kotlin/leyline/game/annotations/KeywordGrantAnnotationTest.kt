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
                            trackedKeyword(7011, 425, 1L, 5L, "Flanking", affector = 435),
                            trackedKeyword(7012, 432, 1L, 5L, "Flanking", affector = 435),
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

            transient.filter { it.typeList.contains(AnnotationType.LayeredEffectCreated) } shouldHaveSize 3
            persistent shouldHaveSize 3
            persistent.forEachIndexed { index, pAnn ->
                assertSoftly {
                    pAnn.affectedIdsList shouldBe listOf(listOf(389, 425, 432)[index])
                    pAnn.detailsList.filter { it.key == "UniqueAbilityId" } shouldHaveSize 1
                    pAnn.detailUint("grpid") shouldBe 26
                    pAnn.detailInt("effect_id") shouldBe 7010 + index
                }
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

        test("same-source keyword recipients retain independent effect lifetimes") {
            val kwDiff =
                EffectTracker.KeywordDiffResult(
                    created =
                        listOf(
                            // Two creatures get Flying from the same static ability (ts=2, staticId=10)
                            trackedKeyword(7020, 100, 2L, 10L, "Flying", affector = 500),
                            trackedKeyword(7021, 200, 2L, 10L, "Flying", affector = 500),
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

            assertSoftly {
                transient.filter { it.typeList.contains(AnnotationType.LayeredEffectCreated) } shouldHaveSize 2
                persistent shouldHaveSize 2
                persistent.map { it.affectedIdsList.single() } shouldBe listOf(100, 200)
                persistent.map { it.detailInt("effect_id") } shouldBe listOf(7020, 7021)
                persistent.map { it.detailUint("grpid") } shouldBe listOf(8, 8)
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
