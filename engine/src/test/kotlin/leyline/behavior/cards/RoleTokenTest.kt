package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Role tokens (Wilds of Eldraine, CR 702.166) — end-to-end wire verification.
 *
 * Role tokens are scripted as completely ordinary tokens in Forge
 * (`Type$ Aura|Enchantment|Role`), attached via the same generic
 * `SP$ Token ... AttachedTo$ Targeted` mechanism any other Aura token uses —
 * there's no dedicated Role ability class. "Only one Role, replace the old
 * one" is a real state-based action (`GameAction.stateBasedAction_Role`):
 * every Role-type attachment on a permanent is grouped by controller, sorted
 * by timestamp, and every one but the newest is removed.
 *
 * This test confirms that mechanic's translation to this engine's wire
 * protocol, using two real Arena cards (Monstrous Rage / Royal Treatment,
 * WOE) rather than a synthetic fixture:
 *
 * 1. Casting Monstrous Rage (R) on a creature creates a Monster Role token,
 *    correctly attached — both at the Forge level and on the wire (a
 *    `TokenCreated` annotation, and the token's `parentId` pointing at the
 *    enchanted creature).
 * 2. Casting Royal Treatment (G) on the SAME creature grants a second Role.
 *    The state-based action must remove the first Role, and that removal
 *    must be reported to the client.
 *
 * That removal turns out NOT to be a `ZoneTransfer` to the graveyard: a Role
 * token, like any token, ceases to exist per CR 704.5d the instant it stops
 * being validly attached, rather than actually coming to rest as an object in
 * the graveyard zone. This engine reports that the same way it reports any
 * other Aura TOKEN falling off — `RemoveAttachment` (severs the link) plus
 * `TokenDeleted` (the token ceases to exist), both driven by Forge's fully
 * generic `GameEvent.CardDetached` / `GameEvent.TokenDestroyed` — confirmed
 * by inspecting the actual annotation stream this test produces. No
 * Role-specific code exists or is needed anywhere in this path.
 */
class RoleTokenTest :
    SessionTest({

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Monstrous Rage")
            TestCardRegistry.ensureCardRegistered("Royal Treatment")
            TestCardRegistry.ensureCardRegistered("Grizzly Bears")
        }

        val puzzleText =
            """
            [metadata]
            Name:Role Token Replacement
            Goal:Win
            Turns:3
            Difficulty:Easy
            Description:Cast Monstrous Rage then Royal Treatment on the same creature; confirm Role replacement.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Monstrous Rage;Royal Treatment
            humanbattlefield=Mountain;Forest;Grizzly Bears
            humanlibrary=Mountain;Mountain;Mountain
            aibattlefield=Centaur Courser
            ailibrary=Forest;Forest;Forest
            """.trimIndent()

        session("first Role grant attaches correctly; a second Role replaces it via the SBA", puzzle = puzzleText) {
            val bearsIid = human.battlefield.iid("Grizzly Bears")

            // --- Grant the first Role: Monstrous Rage (R) targeting Grizzly Bears ---
            castSpellByName("Monstrous Rage").shouldBeTrue()
            selectTargets(listOf(bearsIid))
            passUntil {
                human.getZone(ZoneType.Battlefield).cards.any { it.isToken && it.type.hasSubtype("Role") }
            }.shouldBeTrue()

            val monsterRole =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.isToken && it.type.hasSubtype("Role") }
            val monsterRoleIid = human.battlefield.iid(monsterRole)

            // --- Assert: Role token created and correctly attached (Forge-level + wire) ---
            assertSoftly {
                monsterRole.entityAttachedTo shouldBe human.battlefield.card("Grizzly Bears")

                // Zone transfer into play, on the wire (token creation reports via
                // TokenCreated, not a ZoneTransfer — see AnnotationBuilder.tokenCreated).
                val entersPlay =
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.annotationsList }
                        .filter { ann ->
                            AnnotationType.TokenCreated in ann.typeList && monsterRoleIid in ann.affectedIdsList
                        }
                entersPlay.shouldNotBeEmpty()

                // Attachment link, on the wire.
                val monsterObj = accumulator.objects[monsterRoleIid]
                monsterObj.shouldNotBeNull()
                monsterObj.zoneId shouldBe ZoneIds.BATTLEFIELD
                monsterObj.parentId shouldBe bearsIid
            }

            // --- Grant a SECOND Role from a different source: Royal Treatment (G), same creature ---
            castSpellByName("Royal Treatment").shouldBeTrue()
            selectTargets(listOf(bearsIid))
            passUntil {
                human.getZone(ZoneType.Battlefield).cards.count { it.isToken && it.type.hasSubtype("Role") } == 1 &&
                    human.getZone(ZoneType.Battlefield).cards.none { it === monsterRole }
            }.shouldBeTrue()

            // --- Assert: exactly one Role remains, and it's the new (Royal) one ---
            val survivingRole =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.isToken && it.type.hasSubtype("Role") }
            assertSoftly {
                survivingRole.entityAttachedTo shouldBe human.battlefield.card("Grizzly Bears")
                bridge.resolveGrpId(survivingRole) shouldBe 87498

                val allAnns =
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.annotationsList }

                // The SBA-removed Monster Role must show up on the wire: its
                // attachment link to Grizzly Bears is explicitly severed...
                val detached =
                    allAnns.filter { ann ->
                        AnnotationType.RemoveAttachment in ann.typeList &&
                            ann.affectorId == monsterRoleIid &&
                            bearsIid in ann.affectedIdsList
                    }
                detached.shouldNotBeEmpty()

                // ...and the token itself is reported as ceasing to exist —
                // the same way any other Aura TOKEN falling off would.
                val deleted =
                    allAnns.filter { ann ->
                        AnnotationType.TokenDeleted in ann.typeList && monsterRoleIid in ann.affectedIdsList
                    }
                deleted.shouldNotBeEmpty()
            }
        }
    })
