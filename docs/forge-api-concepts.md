---
summary: "Key Forge API concepts for engine work: controller callbacks, SpellAbility chains, actions, costs, events, snapshots, and prompts."
read_when:
  - "modifying engine bridge code that calls into Forge"
  - "adding or debugging a Forge PlayerController override"
  - "deciding whether to use a Forge event, snapshot diff, or prompt bridge"
  - "debugging cast, ability, cost, or targeting behaviour"
---
# Forge API Concepts

This is the stable map for how engine uses Forge APIs. It explains the concepts that show up across many classes; per-class rationale stays in KDoc and wire details stay in protocol docs.

## 1. Boundary Shape

`engine` is an adapter around Forge's synchronous rules engine. The client protocol is asynchronous, but Forge expects blocking answers from a `PlayerController`.

There are three main integration surfaces:

- `PlayerController` callbacks for priority, choices, costs, targeting, combat, and ordering.
- Forge `GameEvent`s for facts that happen during engine execution.
- Snapshot reads of `Game`, `Player`, `Card`, zones, stack, and counters when building protocol state.

Do not duplicate game rules in Kotlin. Ask Forge what is legal or what happened, then translate that result.

Construct transient annotations in `AnnotationPipeline` and persistent feeds in
`PersistentFeedBuilder`, not in `StateMapper`. The mapper composes their outputs;
phasing and pending copy-choice markers follow the same ownership boundaries.

## 2. PlayerController Callbacks

Forge dispatches interactive work through virtual methods on the player controller. There is no registration table or composition hook that replaces this surface, so overrides live on `bridge/forge/PlayerController`.

Use this decision rule:

- Priority actions and combat declarations go through `GameActionBridge`.
- Engine-initiated choices go through `InteractivePromptBridge`.
- Mulligan decisions go through `MulliganBridge`.
- Yes/no optional-action style prompts use `OptionalActionGate` when the session must observe a pending prompt outside the normal prompt queue.
- Numeric prompts use `NumericInputGate`.

The engine thread blocks in these calls. Never block on session-owned state from an override; post a pending request and let the session complete the future.

Card-backed choices remain explicit even when only one legal card exists. The
sole mandatory candidate matching an already chosen target is not a new choice
(for example, AttachEffect during Equip resolution). Do not prompt a second
time or suppress unrelated singleton card choices. For
an optional enter-as-copy replacement, the donor picker itself offers Decline;
there is no separate yes/no preflight. Select using Forge's CloneEffect zone,
last-state and validity filters during confirmation, then consume that exact
choice at resolution. Decline must preserve Forge's NotReplaced result.
The native `ReplacementEffectController` suppresses Static ability rows,
including printed enter-as-copy replacements. Use the native
`PendingEffectController` path instead: a player-scoped MiscContinuousEffect
annotation naming the resolving source and printed ability. It accepts Static
rows while the source is off the battlefield/command zone and draws the side
rectangle without a fabricated stack ability. Remove that marker after either
decision without disturbing ETB life-payment replacement markers.
That replacement does not use the stack; copied ETB and reflexive
"when you do" triggers can then require simultaneous ordering. The ordering
projection must retain the same ability identity and original-definition card
context as the admitted stack entry. For enter-as-copy, the actual post-entry
donor-exile reflexive uses the parent printed copy paragraph, matching native
Arena; unrelated reflexive triggers retain their separately mapped child rows.

Library-search callbacks carry a `DelayedReveal` with the actual library owner
and permitted card view. Capture that scope separately from eligible choices:
an empty eligible list still needs the same owner's search and fail-to-find
decision. Do not infer the owner from the chooser or expose a whole library
when Forge supplies only a restricted view. An unrelated hand reveal must not
replace this explicit library-search route.

Search wording is selected from exact Forge `ChangeType` filters and selection
count, not from which cards happen to be eligible. Verified common filters use
their native prompts; unsupported qualifiers/counts keep generic search text.
An instant-or-flash union uses disjoint groups only when both partitions exist
and no candidate overlaps them; otherwise the same eligible set uses a flat
search, including a fail-to-find window when it is empty.

The temporary remembered-card list is not durable clone provenance: subability
cleanup clears it. Read the active `CardCloneStates.origin` to project donor
artwork/frame, with live title exceptions kept separately. Native
`CopiedObjectAnnotationParser` distinguishes the permanent copy layer
(`LayeredEffectType=CopyObject`) from copied tokens and spells. Rebuild that
marker while the layer exists and remove it when the layer ends.

Native AI card-name proposals must satisfy Forge's supplied card-face predicate.
Keep legal strategic proposals; use the full legal catalog for a fallback rather
than restricting name choices to cards already in play.

## 3. SpellAbility Is A Chain

A spell or ability is often an SA chain, not one `SpellAbility`. Wrapper APIs such as `Charm`, `Effect`, `Repeat`, and `RepeatEach` can put meaningful work in sub-abilities that run after choices are made.

Implication: predicates over only the outer SA are suspect:

- `sa.api`
- `sa.hasParam(...)`
- `sa.usesTargeting()`
- direct checks on only `sa.hostCard` or only the first sub-ability

Prefer Forge helpers that walk or resolve the chain:

- `PlaySpellAbility.playAbility(..., mayChooseTargets = true, ...)` for normal spell play.
- `setupTargets()` through Forge's play path for targets that are not already set.
- `AbilityUtils.resolve(effectSA)` for no-stack resolution.
- `getRootAbility()` or recursive target checks where Forge exposes them.

When the client supplied targets before the Forge play path starts, `sa.targets.isEmpty()` is the stable gate: if targets are already present, do not ask Forge to choose them again.

## 4. Castable Abilities

Card spells, alternative costs, and zone-cast options should flow through the shared cast helper, not direct `card.getSpells()` scans.

Use `getAllCastableAbilities(card, player)` when you need Forge's castable SA list. It expands additional and alternative costs with `GameActionUtil`, handles special cast states, sets the activating player, and filters by Forge legality.

Use `chooseCastAbility(card, player)` when you only need the best current cast candidate.

Use `CastRails` when an action needs protocol fields for a named cast rail such as plot, foretell, disturb, escape, warp, or sneak. The rail table is the shared source for action emission and action submission.

Use `getNonManaActivatedAbilities(card, player)` and `getPlayableManaAbilities(card, player)` for ability lookup. Both set the activating player before legality-sensitive checks.

## 5. Legality Versus Affordability

`SpellAbility.canPlay()` answers legality, not mana affordability.

It checks timing, zone restrictions, activator restrictions, activation limits, phase restrictions, and similar rule gates. A legal ability can still be unpayable. Opponent-turn priority stops therefore require payable mana for non-mana activations as well as casts. See the `priority-cycling` acceptance suite for paired available and unavailable response windows.

For mana affordability, call:

```kotlin
ComputerUtilMana.canPayManaCost(sa, player, 0, false)
```

Wrap it defensively. Some exotic costs can throw; an exception should mean "not currently payable" at action-emission time.

Normal action building pattern:

1. Set `activatingPlayer`.
2. Check `canPlay()`.
3. Check `ComputerUtilMana.canPayManaCost(...)`.
4. Emit active or inactive action with the right cost fields.

Shared priority visibility and player controls are described in
[Priority flow](priority-flow.md). Inactive actions remain presentation data;
they do not require a visible priority decision.

## 6. Mana And Costs

Mana-plan previews must preserve the live cast's selected Offering/Emerge
sacrifice and `usedToPay` flag. Forge's dry-run Offering handler clears them;
`ComputerUtilMana.getManaPaymentPlan` restores them after the query so real
payment still uses the player's exact choice. Cover both sacrifice candidates
and the untouched donor in Emerge regression fixtures.

For cast actions, use the effective Forge cost, not printed card data, whenever a live `SpellAbility` exists. Effective cost applies raises and reductions through Forge's `CostAdjustment` pipeline.

For activated abilities, use `SpellAbility.payCosts.totalMana` unless the mechanic has a specific reason to use the cast-cost pipeline.

For mana color translation, use `ManaColorMapping`. Forge's color bitmasks and the client mana ordinals are not the same domain.

For land color production:

- Check `manaPart.isComboMana` first.
- Combo sources use `manaPart.getComboColors(sa)`.
- Single-color sources use `manaPart.origProduced`.
- Split produced tokens on spaces, not characters.

Manual mana activation remains a player decision when its source has non-mana
costs. Pay those costs through the active controller's
`getCostDecisionMaker(...)` and `CostPayment.payCost(...)` so sacrifice and
other choices reach the frontend. Reserve `AiCostDecision` and
`payComputerCosts(...)` for automatic AI payment paths.

## 7. Cost Payment Decisions

Forge cost payment uses visitor-style cost parts. `CostDecision` is the bridge point for interactive non-mana cost decisions.

Use the existing cost-decision path when the engine is paying a cost and asks for cards or permanents. Do not invent a parallel resolver from protocol input to game mutation. The bridge should collect a choice, return Forge objects to the cost visitor, and let Forge perform the payment.

Optional additional costs should be selected before `PlaySpellAbility.playAbility(...)` runs, then fed back into Forge through `GameActionUtil.addOptionalCosts(...)`.

## 8. Events Versus Snapshots

Forge events are best for "what caused this?" facts:

- cast, resolve, fizzle, mana payment
- card changed zones and why
- damage source
- attachment lifecycle
- token creation
- controller change
- shuffle, scry, surveil

Snapshots are best for "what is true now?" facts:

- zones and object visibility
- live power/toughness and counters
- continuous effects
- keyword grants
- designations
- persistent annotation baselines

Do not infer a cause from snapshots when a Forge event can carry it. Do not store a parallel mutable truth when a snapshot can read the current Forge state.

When an upstream Forge event lacks the payload needed for protocol translation, prefer a small fork-local event enrichment over correlating unrelated events after the fact. The event should carry the Forge object IDs needed by the bridge, not protocol instance IDs.

### Atomic imported places

Puzzle-based state import has one protected pre-settle hook immediately before
its final `checkStateEffects`. Specialized importers may complete current-state
restoration there while the stack is resolving and triggers are suppressed.
Keep the default hook empty so ordinary puzzles retain their existing behavior.

Restore relationships only after object identities and visible types exist.
Restore survival abilities and current characteristics before marked damage,
then combat and phase, and allow one final settled-state check. Do not call
state-based actions or start an advisor from inside a partially imported place.
Verification must be object-aware: creature power/toughness fields do not define
planeswalker or other noncreature characteristics.

## 9. Prompt Semantics

`PromptSemantic` is the planner-facing contract between an engine callback and
a protocol prompt shape. A producer resolves it through `PromptRouteResolver`
when constructing `PromptRequest`; the request stores one immutable
`ResolvedPromptRoute` for the full pending interaction. Its `semantic`
accessor is diagnostic data derived from that route.

Use an explicit semantic when the Forge callback does not uniquely imply the wire type. Avoid relying on fallback classification by message text or by "candidate refs exist" unless the prompt is truly generic targeting.

Adding a semantic means updating the enum and the resolver's exhaustive route
catalog, then the mapping docs that describe the Forge callback to protocol
prompt relation. Classifiers, builders, re-prompts, and response handlers
consume the bound route rather than maintaining semantic lookup tables.

## 10. Identity

Forge card IDs are engine identity. Client instance IDs are protocol identity. Keep them separate.

Event-layer types should carry Forge IDs. Resolve Forge IDs to instance IDs at mapping time, when the current frame has the right allocation and zone-transfer context.

When a prompt records target or source identity while a spell is still on the stack, freeze the instance ID if later resolution would move the source and change the normal lookup result.

## 11. Tests

Use the smallest test surface that exercises the concept:

- Pure mapper or annotation tests for deterministic translation logic.
- Match harness tests when the Forge callback and session bridge both matter.
- Integration tests when phase progression, priority, or multi-step engine state matters.

For board setup, pick the Forge API that matches the test intent:

- `player.playLand(land, true, null)` for testing land play itself.
- `game.action.moveToPlay(...)` for a raw move that should not fire land-play events.
- Harness setup helpers for pre-existing board state where no event should fire.
