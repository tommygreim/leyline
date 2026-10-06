package leyline.board.zones

import forge.game.zone.ZoneType
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.BoardTest
import leyline.testkit.annotation
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/** Real-card projection coverage for Forge's stateful Phasing keyword. */
class PhasingProjectionTest :
    BoardTest({
        test("Merfolk Raiders phases out and in through Arena's dedicated zone") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Merfolk Raiders", human, ZoneType.Battlefield)
                }
            val card =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Merfolk Raiders" }
            val iid = board.bridge.instanceId(card.id)

            val phasedOut = board.snapshotDiff { card.phase(false) }
            phasedOut.zonesList.first { it.zoneId == ZoneIds.BATTLEFIELD }.objectInstanceIdsList shouldContain iid
            phasedOut.diffDeletedInstanceIdsList shouldNotContain iid
            phasedOut.zonesList.any { it.zoneId == ZoneIds.PHASED_OUT } shouldBe false
            phasedOut.gameObjectsList.first { it.instanceId == iid }.zoneId shouldBe ZoneIds.PHASED_OUT
            phasedOut.annotation(AnnotationType.PhasedOut_af5a).affectedIdsList shouldBe listOf(iid)

            val phasedIn = board.snapshotDiff { card.phase(false) }
            phasedIn.zonesList.any { it.zoneId == ZoneIds.PHASED_OUT } shouldBe false
            phasedIn.zonesList.first { it.zoneId == ZoneIds.BATTLEFIELD }.objectInstanceIdsList shouldContain iid
            phasedIn.gameObjectsList.first { it.instanceId == iid }.zoneId shouldBe ZoneIds.BATTLEFIELD
            phasedIn.annotation(AnnotationType.PhasedIn).affectedIdsList shouldBe listOf(iid)
            phasedIn.diffDeletedInstanceIdsList shouldNotContain iid
        }
    })
