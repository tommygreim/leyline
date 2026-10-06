package leyline.session.stack

import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest
import leyline.testkit.clientMessage
import leyline.testkit.settingsMessage
import leyline.testkit.stop
import wotc.mtgo.gre.external.messaging.Messages.*

class PrioritySynchronizationFlowTest :
    SessionTest({
        val puzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Forest;Forest
            humanhand=Runeclaw Bear
            humanlibrary=Forest;Forest;Forest
            aibattlefield=Mountain
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        val ownStackResponsePuzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Forest;Forest;Mountain
            humanhand=Runeclaw Bear;Shock
            humanlibrary=Forest;Forest;Forest
            aibattlefield=Mountain
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        session("normal casting advances to the next executable decision without a pass", puzzle = puzzle, turns = 3) {
            assertSoftly {
                val before = messageSnapshot()
                val initialTurn = turn()

                castSpellByName("Runeclaw Bear").shouldBeTrue()

                val pending = checkNotNull(bridge.actionBridge(SeatId(1)).getPending())
                assertSoftly {
                    pending.state.kind shouldBe PendingActionKind.PRIORITY
                    turn() shouldBeGreaterThan initialTurn
                    game().stack.isEmpty.shouldBeTrue()
                    messagesSince(before).count { it.hasActionsAvailableReq() } shouldBe 1
                    bridge.cutCoordinator
                        .hasCommittedBatches(SeatId(1))
                        .shouldBeFalse()
                }
                bridge.throwIfGameLoopFailed()
            }
        }

        session("explicit auto-resolve advances through a second SyncOnly stop without an action request", puzzle = puzzle, turns = 3) {
            assertSoftly {
                updateSettings(
                    SettingsMessage
                        .newBuilder()
                        .setAutoPassOption(AutoPassOption.ResolveMyStackEffects)
                        .build(),
                )
                val before = messageSnapshot()

                castSpellByName("Runeclaw Bear").shouldBeTrue()

                val emitted = messagesSince(before)
                val significant = emitted.filter { it.hasGameStateMessage() || it.hasActionsAvailableReq() }.map { it.type }
                val firstActionRequest = significant.indexOfFirst { it == GREMessageType.ActionsAvailableReq_695e }
                assertSoftly {
                    firstActionRequest shouldBeGreaterThanOrEqual 2
                    significant
                        .take(firstActionRequest)
                        .all {
                            it == GREMessageType.GameStateMessage_695e
                        }.shouldBeTrue()
                    bridge
                        .actionBridge(SeatId(1))
                        .getPending()
                        ?.state
                        ?.kind shouldBe PendingActionKind.PRIORITY
                    bridge.cutCoordinator
                        .hasCommittedBatches(SeatId(1))
                        .shouldBeFalse()
                    game().stack.isEmpty.shouldBeTrue()
                    human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain "Runeclaw Bear"
                }
                bridge.throwIfGameLoopFailed()
            }
        }

        session(
            "normal priority resolves an own spell despite another legal response",
            puzzle = ownStackResponsePuzzle,
            turns = 3,
        ) {
            assertSoftly {
                castSpellByName("Runeclaw Bear").shouldBeTrue()

                // Shock is still castable, but Arena's normal ResolveMyStackEffects
                // mode must not make the player manually pass their own Bear.
                game().stack.isEmpty.shouldBeTrue()
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain "Runeclaw Bear"
                human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain "Shock"
                bridge.priorityPolicy.isFullControl().shouldBeFalse()
                bridge.throwIfGameLoopFailed()
            }
        }

        session("settings full control holds own spell and clearing resumes automatic resolution", puzzle = puzzle, turns = 3) {
            assertSoftly {
                updateSettings(settingsMessage { autoPassOption = AutoPassOption.FullControl })
                castSpellByName("Runeclaw Bear").shouldBeTrue()
                game().stack.isEmpty.shouldBeFalse()
                bridge.priorityPolicy.isFullControl().shouldBeTrue()
                updateSettings(settingsMessage { autoPassOption = AutoPassOption.Clear_a465 })
                passPriority()
                game().stack.isEmpty.shouldBeTrue()
                bridge.priorityPolicy.currentSettings().autoPassOption shouldBe AutoPassOption.ResolveMyStackEffects
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain "Runeclaw Bear"
            }
        }

        session("successful cast consumes a bounded hold without making later windows full control", puzzle = puzzle, turns = 3) {
            assertSoftly {
                bridge.priorityPolicy.submitAutoPassPriority(AutoPassPriority.No_a099)
                castSpellByName("Runeclaw Bear").shouldBeTrue()
                game().stack.isEmpty.shouldBeFalse()
                bridge.priorityPolicy.isFullControl().shouldBeFalse()
                passPriority()
                game().stack.isEmpty.shouldBeTrue()
                game().phaseHandler.phase shouldBe PhaseType.MAIN1
                human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain "Forest"
            }
        }

        session("explicit upkeep stop publishes expiry before next actionable decision", puzzle = puzzle, turns = 4) {
            assertSoftly {
                updateSettings(settingsMessage { addTransientStops(stop(StopType.UpkeepStep, SettingScope.Team_ac6e, SettingStatus.Set)) })
                castSpellByName("Runeclaw Bear").shouldBeTrue()
                game().phaseHandler.phase shouldBe PhaseType.UPKEEP
                game().stack.isEmpty.shouldBeTrue()
                val before = messageSnapshot()
                passPriority()
                game().phaseHandler.phase shouldBe PhaseType.MAIN1
                val emitted = messagesSince(before)
                val expiry = emitted.first { it.hasSetSettingsResp() }
                expiry.setSettingsResp.settings.transientStopsList
                    .single {
                        it.stopType == StopType.UpkeepStep && it.appliesTo == SettingScope.Team_ac6e
                    }.status shouldBe SettingStatus.Clear_a3fe
                emitted.indexOf(expiry) shouldBeLessThan emitted.indexOfFirst { it.hasActionsAvailableReq() }
                human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain "Forest"
            }
        }

        session("turn yield restores normal settings through the GRE delivery path", puzzle = puzzle, turns = 4) {
            assertSoftly {
                send(
                    clientMessage(ClientMessageType.SetSettingsReq_097b) {
                        setSetSettingsReq(
                            SetSettingsReq.newBuilder().setTurnNumber(turn()).setSettings(
                                settingsMessage { autoPassOption = AutoPassOption.UnlessOpponentAction },
                            ),
                        )
                    },
                )
                drainSink()
                val initialTurn = turn()
                val before = messageSnapshot()
                passPriority()
                turn() shouldBeGreaterThan initialTurn
                messagesSince(before)
                    .first { it.hasSetSettingsResp() }
                    .setSettingsResp.settings.autoPassOption shouldBe
                    AutoPassOption.ResolveMyStackEffects
                human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain "Runeclaw Bear"
            }
        }
    })
