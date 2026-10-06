package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import leyline.testkit.BoardTest

class GroupedSearchClassifierTest :
    BoardTest({
        val shape = GroupedSearchClassifier.Shape("Library", "Hand", "1", "Instant,Card.hasKeywordFlash")
        val candidates =
            listOf(
                GroupedSearchClassifier.Candidate(isInstant = false, hasFlash = true),
                GroupedSearchClassifier.Candidate(isInstant = true, hasFlash = false),
            )

        test("partitions the grounded union in declared quality order") {
            val fixture = groupedSearchFixture()
            GroupedSearchClassifier.classify(fixture.ability, listOf(fixture.flash, fixture.instant)) shouldContainExactly
                listOf(listOf(1), listOf(0))
        }

        test("ordinary and unsupported searches remain flat") {
            val fixture = groupedSearchFixture()
            assertSoftly {
                GroupedSearchClassifier.classify(null, listOf(fixture.instant)).shouldBeNull()
                GroupedSearchClassifier.classify(false, shape, candidates).shouldBeNull()
                GroupedSearchClassifier.classify(true, shape.copy(origin = "Hand"), candidates).shouldBeNull()
                GroupedSearchClassifier.classify(true, shape.copy(destination = "Battlefield"), candidates).shouldBeNull()
                GroupedSearchClassifier.classify(true, shape.copy(changeNum = "2"), candidates).shouldBeNull()
                GroupedSearchClassifier.classify(true, shape.copy(changeType = "Instant"), candidates).shouldBeNull()
            }
        }

        test("overlap and incomplete partitions use the flat search rather than invalid native groups") {
            val fixture = groupedSearchFixture()
            fixture.instant.addIntrinsicKeyword("Flash")
            GroupedSearchClassifier.classify(fixture.ability, listOf(fixture.instant, fixture.flash)).shouldBeNull()
            val incomplete = groupedSearchFixture()
            GroupedSearchClassifier.classify(incomplete.ability, listOf(incomplete.instant, incomplete.other)).shouldBeNull()
            GroupedSearchClassifier.classify(incomplete.ability, listOf(incomplete.instant)).shouldBeNull()
            GroupedSearchClassifier.classify(incomplete.ability, emptyList()).shouldBeNull()
        }
    })

private fun BoardTest.groupedSearchFixture(): GroupedSearchFixture {
    lateinit var teachings: Card
    lateinit var instant: Card
    lateinit var flash: Card
    lateinit var other: Card
    startWithBoard { _, human, _ ->
        teachings = addCard("Mystical Teachings", human, ZoneType.Hand)
        instant = addCard("Brainstorm", human, ZoneType.Library)
        flash = addCard("Nightpack Ambusher", human, ZoneType.Library)
        other = addCard("Grizzly Bears", human, ZoneType.Library)
    }
    return GroupedSearchFixture(
        ability = teachings.spellAbilities.single { it.api == ApiType.ChangeZone },
        instant = instant,
        flash = flash,
        other = other,
    )
}

private data class GroupedSearchFixture(
    val ability: forge.game.spellability.SpellAbility,
    val instant: Card,
    val flash: Card,
    val other: Card,
)
