package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/** Native enter-as-copy lifecycle: optional donor picker, side indicator, then copied permanent and real triggers. */
class SuperiorSpiderManInteractionTest :
    SessionTest({
        session(
            "Mind Swap retains distinct identities for copied ETB and reflexive exile",
            puzzle =
                """
                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Superior Spider-Man
                humangraveyard=Doomsday Excruciator
                humanbattlefield=Island;Island;Swamp;Swamp
                humanlibrary=Island;Island;Island;Island;Island;Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            // The slim fixture provides card/ability identity. Pin the parent →
            // hidden-child relation separately, as supplied by production SQLite.
            (bridge.cardRepository as leyline.game.InMemoryCardRepository).registerAbilityInfo(
                206630,
                leyline.game.data.AbilityInfo(baseId = 0, manaCost = emptyList(), category = 3, hiddenAbilityIds = listOf(206677)),
            )
            castSpellByName("Superior Spider-Man") shouldBe true
            passUntilResolved(maxPasses = 12)
            val donorChoice = allMessages.last { it.hasSelectNReq() }
            donorChoice.prompt.promptId shouldBe PromptIds.CHOOSE_OBJECT_TO_COPY
            donorChoice.selectNReq.idsList shouldBe listOf(human.graveyard.iid("Doomsday Excruciator"))
            respondToSelectN(donorChoice.selectNReq.idsList)
            passUntilResolved(maxPasses = 12)
            val ordering = lastSelectNReq()
            ordering.context shouldBe wotc.mtgo.gre.external.messaging.Messages.SelectionContext.TriggeredAbility_c799
            val initialTiles =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.instanceId in ordering.idsList }
            initialTiles.map { it.grpId }.toSet() shouldBe setOf(174380, 206630)
            initialTiles.single { it.grpId == 174380 }.objectSourceGrpId shouldBe
                bridge.cardRepository.findGrpIdByName("Doomsday Excruciator")
            initialTiles.single { it.grpId == 206630 }.abilityOriginalCardGrpIdsList shouldBe
                listOf(bridge.cardRepository.findGrpIdByName("Superior Spider-Man"))
            respondToSelectN(ordering.idsList, wotc.mtgo.gre.external.messaging.Messages.OrderingType.OrderAsIndicated)
            passUntilResolved(maxPasses = 12)
            val abilityRows =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.type == wotc.mtgo.gre.external.messaging.Messages.GameObjectType.Ability }
                    .map { it.grpId }
                    .toSet()
            assertSoftly {
                (174380 in abilityRows) shouldBe true
                (206630 in abilityRows) shouldBe true
                (97973 in abilityRows) shouldBe false
                (206677 in abilityRows) shouldBe false
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.grpId == 174380 }
                    .any {
                        it.objectSourceGrpId == bridge.cardRepository.findGrpIdByName("Doomsday Excruciator")
                    } shouldBe
                    true
                human.getZone(ZoneType.Exile).cards.any { it.name == "Doomsday Excruciator" } shouldBe true
                human.getZone(ZoneType.Library).size() shouldBe 6
            }
        }

        session(
            "Mind Swap prompts before copying, then exiles the copied card",
            fullControl = true,
            puzzle =
                """
                [metadata]
                Name:Superior Spider-Man Mind Swap
                Goal:Win
                Turns:1
                Difficulty:Easy
                Description:Cast Superior Spider-Man and accept the Mind Swap copy.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Superior Spider-Man
                humangraveyard=Grizzly Bears
                humanbattlefield=Island;Island;Swamp;Swamp
                humanlibrary=Island
                ailibrary=Mountain
                """.trimIndent(),
        ) {
            castSpellByName("Superior Spider-Man") shouldBe true
            passUntilResolved(maxPasses = 8)
            allMessages.none { it.type == GREMessageType.OptionalActionMessage_695e } shouldBe true
            val choiceMessage = allMessages.last { it.hasSelectNReq() }
            choiceMessage.allowCancel shouldBe wotc.mtgo.gre.external.messaging.Messages.AllowCancel.Continue
            val choice = lastSelectNReq()
            choice.minSel shouldBe 1
            choice.idsList shouldBe listOf(human.graveyard.iid("Grizzly Bears"))
            val pending = allMessages.last { it.hasGameStateMessage() }.gameStateMessage
            // ReplacementEffectController suppresses Static ability rows. The
            // pending-effect controller accepts them while their source resolves
            // on the stack, and requires a player (not a card) as affected entity.
            val indicator =
                pending.persistentAnnotationsList.single {
                    wotc.mtgo.gre.external.messaging.Messages.AnnotationType.MiscContinuousEffect in it.typeList
                }
            indicator.affectedIdsList shouldBe listOf(1)
            indicator.detailsList.single { it.key == "grpid" }.valueInt32List shouldBe listOf(206630)
            accumulator.objects.getValue(indicator.affectorId).zoneId shouldBe leyline.game.mapping.ZoneIds.STACK
            accumulator.objects.getValue(indicator.affectorId).grpId shouldBe bridge.cardRepository.findGrpIdByName("Superior Spider-Man")
            pending.gameObjectsList.none { it.type == wotc.mtgo.gre.external.messaging.Messages.GameObjectType.Ability } shouldBe true
            respondToSelectN(choice.idsList)
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                // Copied: Grizzly Bears left the graveyard (exiled by DBImmediateTrig),
                // not still sitting there because the replacement was declined.
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldBe emptyList()
                human.getZone(ZoneType.Exile).cards.map { it.name } shouldBe listOf("Grizzly Bears")
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .count { it.name == "Superior Spider-Man" } shouldBe 1
                // Forge has cleaned its remembered list; presentation must still use the clone layer donor.
                val copied = human.getZone(ZoneType.Battlefield).cards.single { it.name == "Superior Spider-Man" }
                copied.remembered.toList() shouldBe emptyList()
                val emitted =
                    checkNotNull(
                        accumulator.objects.values.singleOrNull {
                            it.zoneId == leyline.game.mapping.ZoneIds.BATTLEFIELD &&
                                it.name == bridge.cardRepository.findTitleIdByName("Superior Spider-Man")
                        },
                    ) { "Copied permanent missing from client state: ${accumulator.objects.values}" }
                emitted.grpId shouldBe bridge.cardRepository.findGrpIdByName("Grizzly Bears")
                emitted.name shouldBe bridge.cardRepository.findTitleIdByName("Superior Spider-Man")
                emitted.power.value shouldBe 4
                emitted.toughness.value shouldBe 4
                accumulator.persistentAnnotations.values.any {
                    wotc.mtgo.gre.external.messaging.Messages.AnnotationType.CopiedObject in it.typeList &&
                        emitted.instanceId in it.affectedIdsList
                } shouldBe true
                accumulator.persistentAnnotations.values.none {
                    wotc.mtgo.gre.external.messaging.Messages.AnnotationType.MiscContinuousEffect in it.typeList
                } shouldBe true
            }
        }

        session(
            "another optional graveyard copy uses the same donor picker without a yes no preflight",
            forgeCatalog = true,
            fullControl = true,
            puzzle =
                """
                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                humanhand=Body Double
                HumanLife=30
                AILife=30
                humanbattlefield=Island;Island;Island;Island;Island
                aigraveyard=Grizzly Bears
                humanlibrary=Island;Island
                ailibrary=Forest;Forest
                """.trimIndent(),
        ) {
            castSpellByName("Body Double") shouldBe true
            passUntilResolved(maxPasses = 8)
            val choice = lastSelectNReq()
            choice.idsList shouldBe listOf(ai.graveyard.iid("Grizzly Bears"))
            allMessages.none { it.type == GREMessageType.OptionalActionMessage_695e } shouldBe true
            respondToSelectN(choice.idsList)
            passUntilResolved(maxPasses = 8)
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .single { it.isCreature }
                .name shouldBe "Grizzly Bears"
            ai
                .getZone(ZoneType.Graveyard)
                .cards
                .single()
                .name shouldBe "Grizzly Bears"
        }

        session(
            "declining Mind Swap leaves the graveyard card alone",
            fullControl = true,
            puzzle =
                """
                [metadata]
                Name:Superior Spider-Man Mind Swap Decline
                Goal:Win
                Turns:1
                Difficulty:Easy
                Description:Cast Superior Spider-Man and decline the Mind Swap copy.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Superior Spider-Man
                humangraveyard=Grizzly Bears
                humanbattlefield=Island;Island;Swamp;Swamp
                humanlibrary=Island
                ailibrary=Mountain
                """.trimIndent(),
        ) {
            castSpellByName("Superior Spider-Man") shouldBe true
            passUntilResolved(maxPasses = 8)
            val choice = lastSelectNReq()
            choice.idsList shouldBe listOf(human.graveyard.iid("Grizzly Bears"))
            cancelAction()
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                allMessages.none { it.type == GREMessageType.OptionalActionMessage_695e } shouldBe true
                accumulator.persistentAnnotations.values.none {
                    wotc.mtgo.gre.external.messaging.Messages.AnnotationType.MiscContinuousEffect in it.typeList
                } shouldBe true
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldBe listOf("Grizzly Bears")
                human.getZone(ZoneType.Exile).cards.map { it.name } shouldBe emptyList()
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .count { it.name == "Superior Spider-Man" } shouldBe 1
            }
        }

        for ((decision, donorName) in listOf("ours" to "Grizzly Bears", "opponent" to "Air Elemental", "decline" to null)) {
            session(
                "copy picker includes both graveyards and handles $decision choice",
                puzzleFile = "data/puzzles/remaining-copy-both-graveyards.pzl",
                fullControl = true,
            ) {
                val ourDonors = listOf("Grizzly Bears", "Walking Corpse", "Llanowar Elves")
                val theirDonors = listOf("Air Elemental", "Colossal Dreadmaw", "Raging Goblin")
                val expectedCandidates = ourDonors.map { human.graveyard.iid(it) } + theirDonors.map { ai.graveyard.iid(it) }
                val selectedZone = if (decision == "opponent") ai.graveyard else human.graveyard
                val donorGrpId = donorName?.let { bridge.cardRepository.findGrpIdByName(it) }
                castSpellByName("Superior Spider-Man") shouldBe true
                passUntilResolved(maxPasses = 8)
                val choice = lastSelectNReq()
                choice.idsList.size shouldBe 6
                choice.idsList.toSet() shouldBe expectedCandidates.toSet()
                choice.minSel shouldBe 1
                choice.maxSel shouldBe 1
                expectedCandidates.forEach { iid ->
                    accumulator.objects.getValue(iid).visibility shouldBe wotc.mtgo.gre.external.messaging.Messages.Visibility.Public
                }
                val pendingIndicators =
                    accumulator.persistentAnnotations.values.filter {
                        wotc.mtgo.gre.external.messaging.Messages.AnnotationType.MiscContinuousEffect in it.typeList
                    }
                pendingIndicators.size shouldBe 1
                pendingIndicators.single().affectedIdsList shouldBe listOf(HUMAN_SEAT)
                allMessages.none { it.type == GREMessageType.OptionalActionMessage_695e } shouldBe true
                if (donorName == null) cancelAction() else respondToSelectN(listOf(selectedZone.iid(donorName)))
                passUntilResolved(maxPasses = 12)

                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .map { it.name }
                    .toSet() shouldBe
                    (ourDonors + "Abrade")
                        .filterNot {
                            decision == "ours" && it == donorName
                        }.toSet()
                ai
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .map { it.name }
                    .toSet() shouldBe
                    (theirDonors + "Duress")
                        .filterNot {
                            decision == "opponent" && it == donorName
                        }.toSet()
                human.getZone(ZoneType.Exile).cards.map { it.name } shouldBe if (decision == "ours") listOf(donorName) else emptyList()
                ai.getZone(ZoneType.Exile).cards.map { it.name } shouldBe if (decision == "opponent") listOf(donorName) else emptyList()
                val spider = human.battlefield.card("Superior Spider-Man")
                spider.netPower shouldBe 4
                spider.netToughness shouldBe 4
                val emitted = accumulator.objects.getValue(human.battlefield.iid("Superior Spider-Man"))
                emitted.grpId shouldBe (donorGrpId ?: bridge.cardRepository.findGrpIdByName("Superior Spider-Man"))
                if (donorName != null) emitted.name shouldBe bridge.cardRepository.findTitleIdByName("Superior Spider-Man")
                accumulator.persistentAnnotations.values.none {
                    wotc.mtgo.gre.external.messaging.Messages.AnnotationType.MiscContinuousEffect in it.typeList
                } shouldBe true
            }
        }
    })
