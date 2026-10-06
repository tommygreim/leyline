---
summary: "Automatic priority behavior, player overrides, scoped settings and runtime ownership."
read_when:
  - "changing priority visibility, phase stops, full control or yielding"
  - "debugging empty Pass or Resolve windows"
---
# Priority Flow

Forge owns rules priority, stack resolution, phases and required choices.
`PriorityPolicyRuntime` decides whether a rules-priority boundary needs a
visible player decision. Native and in-process heads submit the same settings
and action responses. State-only synchronization still publishes gameplay
changes before the engine advances.

## Normal play

Priority is automatic when there is no executable non-mana action. A legal
but unaffordable action remains an inactive affordance. Casts and activations
use the same cost, target and mode checks for priority and active offers.
Alternative-zone casts, zero-mana spells and payable non-mana costs count.
Plain mana production does not force a stop, but manually activating mana
retains a visible window to spend it.

A meaningful response to an opponent-controlled stack object stops on either
turn, regardless of ordinary phase preferences. With smart stops enabled, an
own-stack object targeting one's own battlefield permanent also offers legal
responses: for example, Slip Out the Back in response to one's own Aether Gust.
Ordinary own-stack objects otherwise auto-resolve. Full control, a bounded
action hold or an explicit stop can request additional own-stack windows.
AutoPassPriority.Yes alone is not evidence of the official server's smart-stop
heuristics; this is the bridge's supported policy, not a reconstruction of all
Arena automatic-priority cases.

On an empty stack, own-turn baseline preferences enable main phases,
beginning combat, declarations and first-strike damage. Opponent-turn
meaningful responses remain eligible, including upkeep cycling. Baseline
preferences never force an empty decision. A disabled own upkeep or draw
window can be requested with an explicit stop or full control.

Combat declarations, targets, modes, payment, ordering and other required
choices keep their dedicated interaction owners. Priority automation never
answers them. It does not create priority during untap or suppress cleanup
choices supplied by Forge.

## Player controls

- `autoPassOption=FullControl` exposes every rules-priority boundary. `Clear`
  restores the normal `ResolveMyStackEffects` setting.
- Response `autoPassPriority=No` requests one ensuing priority window after
  the accepted action completes successfully. Failed or cancelled actions
  discard the request. It does not enable persistent full control.
- `transientStops` force an exact step for the requested own/opponent scope.
  A future stop survives until reached, remains active within that occurrence,
  and clears when the engine leaves it. Persistent `stops` remain ordinary
  phase preferences.
- `autoPassOption=UnlessOpponentAction` yields for the requested `turnNumber`.
  The turn boundary, a new opponent stack object or an explicit stop ends it.
  `Clear` cancels it. Required choices remain interactive while yielding.
- `stackAutoPassOption=ResolveAll` yields through the current stack batch.
  Removing an existing stack object does not interrupt it. A new opponent
  stack object, an explicit stop or the stack becoming empty ends it.
  Clearing the stack option cancels it.

These are the supported local control lifetimes. They are not a claim that
every native client mode has identical lifetime semantics.

## Ownership and diagnostics

The engine supplies turn, phase, player identity and the ordered stack's
instance/controller identities. Ability controllers come from the stack
instance, not from the source permanent. Completion callbacks confirm action
holds only after the Forge play path succeeds.

The policy owns accumulated settings. Connect and reconnect return its current
snapshot. Expiration produces a settings update through `MatchCutCoordinator`
before the next priority horizon. Sessions only deliver the committed output;
the engine never writes session state. See [Bridge threading](bridge-threading.md).

`event=match.priority_decision` reports the decision reason, visibility,
turn/phase and top stack identity/controller. `PriorityPolicyRuntimeTest`
covers the decision table and lifetimes. `PrioritySynchronizationFlowTest`
proves the shared session path. The `priority-flow` and `priority-cycling`
puzzle suites cover exhausted turns and payable opponent response windows.
