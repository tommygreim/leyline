package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.Target
import leyline.bridge.resolveAttackDefender
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationConstants
import leyline.game.bundle.RequestBuilder
import leyline.testkit.BoardTest
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CounterType
import wotc.mtgo.gre.external.messaging.Messages.DamageRecType

class SiegeBattleProjectionTest :
    BoardTest({
        beforeSpec { leyline.testkit.registerUpstreamCatalogCards("Invasion of Zendikar") }

        test("a controlled Siege is offered as an attack target while a protected Siege is excluded") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Centaur Courser", human)
                    addCard("Invasion of Zendikar", human).protectingPlayer = ai
                    addCard("Invasion of Zendikar", ai).protectingPlayer = human
                }
            val ours = board.human.battlefield.card("Invasion of Zendikar")
            val theirs = board.ai.battlefield.card("Invasion of Zendikar")
            val req = RequestBuilder.buildDeclareAttackersReq(SeatId(1), board.bridge)
            val targets =
                req.attackersList
                    .single()
                    .legalDamageRecipientsList
                    .filter { it.type == DamageRecType.PlanesWalker }
                    .map { it.planeswalkerInstanceId }
            assertSoftly {
                targets shouldContain board.bridge.instanceId(ours)
                targets shouldNotContain board.bridge.instanceId(theirs)
                resolveAttackDefender(board.game, board.human, Target.Card(ForgeCardId(ours.id))) shouldBe ours
                resolveAttackDefender(board.game, board.human, Target.Card(ForgeCardId(theirs.id))).shouldBeNull()
            }
        }

        test("protector and defense state survives resync and retires when the Siege leaves play") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:Siege projection
                    Goal:Win
                    Turns:3
                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=Invasion of Zendikar|Counters:DEFENSE=3
                    humanlibrary=Forest
                    ailibrary=Island
                    """.trimIndent(),
                )
            val battle = board.human.battlefield.card("Invasion of Zendikar")
            battle.protectingPlayer = board.ai
            val iid = board.bridge.instanceId(battle)

            fun protectorRows(gsm: wotc.mtgo.gre.external.messaging.Messages.GameStateMessage) =
                gsm.persistentAnnotationsList.filter {
                    AnnotationType.Designation in it.typeList &&
                        it.detailInt("DesignationType") == AnnotationConstants.DESIGNATION_TYPE_BATTLE_PROTECTOR
                }
            val full = handshakeFull(board.game, board.bridge, 21)
            val protector = protectorRows(full).single()
            val counter =
                full.persistentAnnotationsList.single {
                    AnnotationType.Counter_803b in it.typeList && it.affectedIdsList == listOf(iid)
                }
            assertSoftly {
                protector.affectorId shouldBe iid
                protector.affectedIdsList shouldBe listOf(2)
                counter.detailInt("counter_type") shouldBe CounterType.Defense.number
                counter.detailInt("count") shouldBe 3
                protectorRows(handshakeFull(board.game, board.bridge, 22)).single().id shouldBe protector.id
            }
            val diff = board.snapshotDiff { exile(battle, board.game) }
            diff.diffDeletedPersistentAnnotationIdsList shouldContain protector.id
            protectorRows(handshakeFull(board.game, board.bridge, 24)) shouldBe emptyList()
        }
    })
