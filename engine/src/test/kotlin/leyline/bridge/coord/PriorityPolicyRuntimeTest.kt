package leyline.bridge.coord

import forge.game.phase.PhaseType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.AutoPassReason
import leyline.testkit.settingsMessage
import leyline.testkit.stop
import wotc.mtgo.gre.external.messaging.Messages.*

class PriorityPolicyRuntimeTest :
    FunSpec({
        tags(UnitTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        fun runtime() = PriorityPolicyRuntime().also { it.installPhaseStops(1, 2) }

        fun PriorityPolicyRuntime.visible(window: PriorityWindowObservation): Boolean =
            (classifyPriorityWindow(window) as? PriorityWindowDecision.Present)?.mode == PriorityWindowMode.Visible

        test("runtime and advertised settings share defaults") {
            assertSoftly {
                val policy = runtime()
                policy.currentSettings() shouldBe PriorityPolicyRuntime.defaultSettings()
                policy.currentSettings().autoPassOption shouldBe AutoPassOption.ResolveMyStackEffects
                policy.enabledPhaseStops(1).containsAll(listOf(PhaseType.MAIN1, PhaseType.MAIN2, PhaseType.COMBAT_BEGIN)).shouldBeTrue()
                policy.hasOpponentStop(PhaseType.COMBAT_BEGIN).shouldBeFalse()
            }
        }

        test("ordinary priority requires an executable action on either turn") {
            assertSoftly {
                val policy = runtime()
                for (own in listOf(true, false)) {
                    for (phase in listOf(PhaseType.MAIN1, PhaseType.MAIN2, PhaseType.COMBAT_DECLARE_BLOCKERS)) {
                        policy.visible(observation(own = own, phase = phase)).shouldBeFalse()
                    }
                }
                // Main phases stop only in your own turn; declare blockers stops in both.
                for (phase in listOf(PhaseType.MAIN1, PhaseType.MAIN2, PhaseType.COMBAT_DECLARE_BLOCKERS)) {
                    policy.visible(observation(phase = phase, meaningful = true)).shouldBeTrue()
                }
                policy.visible(observation(own = false, phase = PhaseType.COMBAT_DECLARE_BLOCKERS, meaningful = true)).shouldBeTrue()
                policy.classifyPriorityWindow(observation()) shouldBe PriorityWindowDecision.Skip(AutoPassReason.SmartPhaseSkip)
            }
        }

        test("upkeep stops apply to the scope for whose turn it is") {
            assertSoftly {
                val policy = runtime()
                // Arena's GameSettings leaves upkeep clear in both turn scopes.
                policy.visible(observation(phase = PhaseType.UPKEEP, meaningful = true)).shouldBeFalse()
                policy.visible(observation(own = false, phase = PhaseType.UPKEEP, meaningful = true)).shouldBeFalse()
                policy.submit(settingsMessage { addStops(stop(StopType.UpkeepStep, SettingScope.Team_ac6e, SettingStatus.Set)) })
                policy.visible(observation(phase = PhaseType.UPKEEP, meaningful = true)).shouldBeTrue()
                policy.visible(observation(own = false, phase = PhaseType.UPKEEP, meaningful = true)).shouldBeFalse()
                policy.visible(observation(phase = PhaseType.UPKEEP)).shouldBeFalse()
                policy.submit(settingsMessage { addStops(stop(StopType.UpkeepStep, SettingScope.Opponents, SettingStatus.Set)) })
                policy.classifyPriorityWindow(observation(own = false, phase = PhaseType.UPKEEP, meaningful = true)) shouldBe
                    PriorityWindowDecision.Present(PriorityWindowMode.Visible, autoResolve = false)
            }
        }

        test("own stack opens a window only when there is something to respond with; an opponent response always reopens") {
            assertSoftly {
                val policy = runtime()
                val ownSpell = PriorityStackObject(1, 1)
                val response = PriorityStackObject(2, 2)
                // Another castable instant lets the player stack a second spell on their own.
                policy.visible(observation(phase = PhaseType.UPKEEP, meaningful = true, stack = listOf(ownSpell))).shouldBeTrue()
                policy.visible(observation(phase = PhaseType.UPKEEP, stack = listOf(ownSpell))).shouldBeFalse()
                policy.visible(observation(phase = PhaseType.UPKEEP, meaningful = true, stack = listOf(response, ownSpell))).shouldBeTrue()
                policy.visible(observation(phase = PhaseType.UPKEEP, stack = listOf(response, ownSpell))).shouldBeFalse()
                policy.classifyPriorityWindow(observation(meaningful = true, stack = listOf(ownSpell))) shouldBe
                    PriorityWindowDecision.Present(PriorityWindowMode.Visible, autoResolve = false)
                policy.classifyPriorityWindow(observation(stack = listOf(ownSpell))) shouldBe
                    PriorityWindowDecision.Present(PriorityWindowMode.SyncOnly, autoResolve = true)
            }
        }

        test("settings full control overrides empty actions and own stack until cleared") {
            assertSoftly {
                val policy = runtime()
                policy.submit(settingsMessage { autoPassOption = AutoPassOption.FullControl })
                policy.isFullControl().shouldBeTrue()
                policy.visible(observation(phase = PhaseType.DRAW)).shouldBeTrue()
                policy.visible(observation(stack = listOf(PriorityStackObject(1, 1)))).shouldBeTrue()
                policy.submit(settingsMessage { autoPassOption = AutoPassOption.Clear_a465 })
                policy.isFullControl().shouldBeFalse()
                policy.currentSettings().autoPassOption shouldBe AutoPassOption.ResolveMyStackEffects
                policy.visible(observation()).shouldBeFalse()
            }
        }

        test("response No holds exactly one ensuing priority after successful action") {
            assertSoftly {
                val policy = runtime()
                policy.submitAutoPassPriority(AutoPassPriority.No_a099)
                policy.actionCompleted(true)
                policy.visible(observation(stack = listOf(PriorityStackObject(1, 1)))).shouldBeTrue()
                policy.visible(observation(stack = listOf(PriorityStackObject(1, 1)))).shouldBeFalse()
                policy.isFullControl().shouldBeFalse()
                policy.submitAutoPassPriority(AutoPassPriority.No_a099)
                policy.actionCompleted(false)
                policy.visible(observation()).shouldBeFalse()
                policy.currentSettings().autoPassOption shouldBe AutoPassOption.ResolveMyStackEffects
            }
        }

        test("manual mana retains its window without creating later empty stops") {
            assertSoftly {
                val policy = runtime()
                policy.visible(observation(phase = PhaseType.UPKEEP).copy(forceVisible = true)).shouldBeTrue()
                policy.visible(observation()).shouldBeFalse()
                policy.visible(observation().copy(promptJustResolved = true)).shouldBeFalse()
                policy.classifyPriorityWindow(observation().copy(forceVisible = true)) shouldBe
                    PriorityWindowDecision.Present(PriorityWindowMode.Visible, autoResolve = false)
            }
        }

        test("transient stops force exact scoped occurrence and expire after leaving it") {
            assertSoftly {
                val policy = runtime()
                policy.submit(
                    settingsMessage {
                        addTransientStops(stop(StopType.UpkeepStep, SettingScope.Team_ac6e, SettingStatus.Set))
                        addTransientStops(stop(StopType.EndStep_ad1f, SettingScope.Opponents, SettingStatus.Set))
                    },
                )
                policy.visible(observation(own = false, phase = PhaseType.UPKEEP)).shouldBeFalse()
                policy.visible(observation(turn = 2, phase = PhaseType.UPKEEP)).shouldBeTrue()
                policy.visible(observation(turn = 2, phase = PhaseType.UPKEEP, stack = listOf(PriorityStackObject(1, 1)))).shouldBeTrue()
                policy.visible(observation(turn = 2, phase = PhaseType.DRAW)).shouldBeFalse()
                policy
                    .takeChangedSettings()!!
                    .transientStopsList
                    .single {
                        it.stopType == StopType.UpkeepStep &&
                            it.appliesTo == SettingScope.Team_ac6e
                    }.status shouldBe
                    SettingStatus.Clear_a3fe
                policy.visible(observation(turn = 3, own = false, phase = PhaseType.END_OF_TURN)).shouldBeTrue()
                policy.visible(observation(turn = 4, phase = PhaseType.UPKEEP)).shouldBeFalse()
                policy.hasOpponentStop(PhaseType.END_OF_TURN).shouldBeFalse()
            }
        }

        test("transient stop clear is independent of baseline and other seat") {
            assertSoftly {
                val policy = runtime()
                policy.submit(
                    settingsMessage { addTransientStops(stop(StopType.PrecombatMainPhase, SettingScope.AnyPlayer, SettingStatus.Set)) },
                )
                policy.submit(
                    settingsMessage {
                        addTransientStops(
                            stop(StopType.PrecombatMainPhase, SettingScope.Team_ac6e, SettingStatus.Clear_a3fe),
                        )
                    },
                )
                policy.visible(observation()).shouldBeFalse()
                policy.visible(observation(own = false)).shouldBeTrue()
                policy.isPhaseStopped(1, PhaseType.MAIN1).shouldBeTrue()
                policy
                    .currentSettings()
                    .transientStopsList
                    .single {
                        it.stopType == StopType.PrecombatMainPhase && it.appliesTo == SettingScope.Team_ac6e
                    }.status shouldBe SettingStatus.Clear_a3fe
            }
        }

        test("turn yield expires at turn boundary and publishes restored settings") {
            assertSoftly {
                val policy = runtime()
                policy.visible(observation(turn = 2, meaningful = true)).shouldBeTrue()
                policy.submit(settingsMessage { autoPassOption = AutoPassOption.UnlessOpponentAction }, turnNumber = 2)
                policy.visible(observation(turn = 2, meaningful = true)).shouldBeFalse()
                policy
                    .visible(
                        observation(turn = 3, own = false, phase = PhaseType.COMBAT_DECLARE_BLOCKERS, meaningful = true),
                    ).shouldBeTrue()
                policy.takeChangedSettings()!!.autoPassOption shouldBe AutoPassOption.ResolveMyStackEffects
                policy.takeChangedSettings() shouldBe null
            }
        }

        test("turn yield resumes for opponent additions or an explicit stop and can be cancelled") {
            assertSoftly {
                val policy = runtime()
                policy.visible(observation(meaningful = true))
                policy.submit(settingsMessage { autoPassOption = AutoPassOption.UnlessOpponentAction }, 1)
                policy.visible(observation(meaningful = true, stack = listOf(PriorityStackObject(1, 2)))).shouldBeTrue()
                policy.currentSettings().autoPassOption shouldBe AutoPassOption.ResolveMyStackEffects
                policy.submit(settingsMessage { autoPassOption = AutoPassOption.UnlessOpponentAction }, 1)
                policy.submit(settingsMessage { addTransientStops(stop(StopType.DrawStep, SettingScope.Team_ac6e, SettingStatus.Set)) })
                policy.visible(observation(phase = PhaseType.DRAW)).shouldBeTrue()
                policy.currentSettings().autoPassOption shouldBe AutoPassOption.ResolveMyStackEffects
                policy.submit(settingsMessage { autoPassOption = AutoPassOption.UnlessOpponentAction }, 1)
                policy.submit(settingsMessage { autoPassOption = AutoPassOption.Clear_a465 })
                policy.visible(observation(meaningful = true)).shouldBeTrue()
            }
        }

        test("resolve all follows identities through pops and expires on empty stack") {
            assertSoftly {
                val policy = runtime()
                val bottom = PriorityStackObject(1, 2)
                val top = PriorityStackObject(2, 2)
                policy.visible(observation(meaningful = true, stack = listOf(top, bottom))).shouldBeTrue()
                policy.submit(settingsMessage { stackAutoPassOption = AutoPassOption.ResolveAll })
                policy.visible(observation(meaningful = true, stack = listOf(top, bottom))).shouldBeFalse()
                policy.visible(observation(meaningful = true, stack = listOf(bottom))).shouldBeFalse()
                policy.visible(observation(meaningful = true)).shouldBeTrue()
                policy.takeChangedSettings()!!.stackAutoPassOption shouldBe AutoPassOption.Clear_a465
            }
        }

        test("resolve all interrupts on a new opponent object but accepts own additions") {
            assertSoftly {
                val policy = runtime()
                val original = PriorityStackObject(1, 2)
                policy.visible(observation(meaningful = true, stack = listOf(original)))
                policy.submit(settingsMessage { stackAutoPassOption = AutoPassOption.ResolveAll })
                policy.visible(observation(meaningful = true, stack = listOf(PriorityStackObject(2, 1), original))).shouldBeFalse()
                policy.visible(observation(meaningful = true, stack = listOf(PriorityStackObject(3, 2), original))).shouldBeTrue()
                policy.currentSettings().stackAutoPassOption shouldBe AutoPassOption.Clear_a465
                policy.submit(settingsMessage { stackAutoPassOption = AutoPassOption.ResolveAll })
                policy.submit(settingsMessage { stackAutoPassOption = AutoPassOption.Clear_a465 })
                policy.visible(observation(meaningful = true, stack = listOf(original))).shouldBeTrue()
            }
        }

        test("settings deltas preserve unrelated preferences and clear-all yields does not clear stops") {
            assertSoftly {
                val policy = runtime()
                policy.submit(
                    settingsMessage {
                        addStops(stop(StopType.UpkeepStep, SettingScope.Team_ac6e, SettingStatus.Set))
                        autoPassOption = AutoPassOption.FullControl
                    },
                )
                policy.submit(settingsMessage { clearAllYields = SettingStatus.Set })
                policy.isFullControl().shouldBeFalse()
                policy.isPhaseStopped(1, PhaseType.UPKEEP).shouldBeTrue()
                policy.submit(settingsMessage { clearAllStops = SettingStatus.Set })
                policy.enabledPhaseStops(1) shouldBe emptySet()
                policy.enabledPhaseStops(2) shouldBe emptySet()
            }
        }

        test("replacement game preserves preferences but clears old game controls") {
            assertSoftly {
                val policy = runtime()
                policy.submit(settingsMessage { addStops(stop(StopType.UpkeepStep, SettingScope.Team_ac6e, SettingStatus.Set)) })
                policy.visible(observation())
                policy.submit(
                    settingsMessage {
                        autoPassOption = AutoPassOption.UnlessOpponentAction
                        addTransientStops(stop(StopType.DrawStep, SettingScope.Team_ac6e, SettingStatus.Set))
                    },
                    1,
                )
                policy.installPhaseStops(10, 20)
                policy.isPhaseStopped(10, PhaseType.UPKEEP).shouldBeTrue()
                policy.currentSettings().autoPassOption shouldBe AutoPassOption.ResolveMyStackEffects
                policy
                    .currentSettings()
                    .transientStopsList
                    .all { it.status == SettingStatus.Clear_a3fe }
                    .shouldBeTrue()
            }
        }
        test("explicit yield commands replace full control and each other atomically") {
            assertSoftly {
                val policy = runtime()
                val stack = listOf(PriorityStackObject(1, 2))
                policy.visible(observation(meaningful = true, stack = stack))
                policy.submit(settingsMessage { autoPassOption = AutoPassOption.FullControl })
                policy.submit(
                    settingsMessage {
                        autoPassOption = AutoPassOption.Clear_a465
                        stackAutoPassOption = AutoPassOption.ResolveAll
                    },
                )
                policy.currentSettings().autoPassOption shouldBe AutoPassOption.ResolveMyStackEffects
                policy.visible(observation(meaningful = true, stack = stack)).shouldBeFalse()
                policy.submit(
                    settingsMessage {
                        autoPassOption = AutoPassOption.UnlessOpponentAction
                        stackAutoPassOption = AutoPassOption.Clear_a465
                    },
                    1,
                )
                policy.currentSettings().stackAutoPassOption shouldBe AutoPassOption.Clear_a465
                policy.visible(observation(meaningful = true)).shouldBeFalse()
            }
        }
    })

private fun observation(
    own: Boolean = true,
    phase: PhaseType = PhaseType.MAIN1,
    meaningful: Boolean = false,
    turn: Int = 1,
    stack: List<PriorityStackObject> = emptyList(),
) = PriorityWindowObservation(
    isOwnTurn = own,
    phase = phase,
    smartPhaseSkip = true,
    promptJustResolved = false,
    stackEmpty = stack.isEmpty(),
    forceVisible = false,
    hasMeaningfulAction = meaningful,
    turn = turn,
    playerId = 1,
    stack = stack,
)
