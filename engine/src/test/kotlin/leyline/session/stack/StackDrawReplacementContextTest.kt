package leyline.session.stack

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.SessionTest
import leyline.testkit.hasCard
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

/** A replacement from another permanent must retain its own native text owner. */
class StackDrawReplacementContextTest :
    SessionTest({
        session(
            "Visionary draw trigger explains Teferi's replacement without a card-specific override",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Elvish Visionary
                humanbattlefield=Forest;Forest;Teferi's Ageless Insight
                humanlibrary=Forest;Forest;Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            fullControl = true,
            forgeCatalog = true,
        ) {
            val replacementSource = human.battlefield.card("Teferi's Ageless Insight")
            val printing = checkNotNull(bridge.cardRepository.findGrpIdByName(replacementSource.name))
            val data = checkNotNull(bridge.cardRepository.findByGrpId(printing))
            val registry = checkNotNull(bridge.abilityRegistryFor(replacementSource, data))
            val row = checkNotNull(registry.forReplacementEffect(replacementSource.replacementEffects.single().id))

            check(castSpellByName("Elvish Visionary"))
            check(passUntil(maxPasses = 12) { human.hasCard("Elvish Visionary", ZoneType.Battlefield) })
            val trigger =
                SnapshotCapture
                    .run(game(), bridge, "other-source-replacement", 0)
                    .stack.entries
                    .single { !it.isSpell }
            trigger.contextualAbilities shouldBe listOf(row to printing)
            val contextObject =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .last { it.type == GameObjectType.Ability && it.uniqueAbilitiesList.any { ability -> ability.grpId == row } }
            assertSoftly {
                trigger.contextualAbilities shouldBe listOf(row to printing)
                contextObject.abilityOriginalCardGrpIdsList shouldContain printing
            }
            check(passUntil(maxPasses = 12) { game().stack.isEmpty })
            human.getZone(ZoneType.Hand).cards.size shouldBe 2
        }
    })
