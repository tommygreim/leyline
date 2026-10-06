---
summary: "Puzzle harness strengths, limits, and when to reach states through setup actions instead of direct .pzl placement."
read_when:
  - "writing or debugging a puzzle fixture"
  - "a puzzle starts from exile, face-down, prepared, plotted, foretold, or another history-sensitive state"
  - "choosing between direct .pzl state and harness setup actions"
---
# Puzzle Harness

Puzzles are the fast acceptance path for focused gameplay seams. A `.pzl` file should usually describe a small board, deterministic libraries, and one behavior under test.

For puzzles that direct Forge AI can solve and the bridge must reproduce, use the scripted loop in [`ai-solved-acceptance.md`](ai-solved-acceptance.md).

## Good Direct State

Direct `.pzl` placement is a good fit when current game state is enough:

- Cards in hand, battlefield, graveyard, library, command, or sideboard.
- Deterministic top-of-library flows: draw, scry, cascade, discover, search.
- Static checks based on current zones, such as graveyard-count cost reduction.
- Counters and attachments that can be rebuilt from current engine state.
- Mana and board setup for a mechanic that will be exercised after the puzzle starts.

## Snapshot Limits

Puzzle setup is not a record of how the game reached the state. It applies a starting state, then leyline builds a Full GSM from the current engine state.

Combat-phase imports can also execute decisions during setup: Forge's loader
advances into declare blockers while temporary, zero-timeout controllers are
installed. That can submit an empty block before the live controller attaches.
For an interactive blocker playtest, start in a normal main phase and reach
combat through gameplay instead. `block1-block-warnings.pzl` casts Kardur to
require both opposing creatures to attack next turn, avoiding dependence on
the AI voluntarily choosing the desired attack.

An opening main phase also needs a legal player action if the client must
visually initialize the board before combat. With no such action, smart phase
skipping can reach Declare Attackers while Arena is still loading DuelScene;
phase updates then arrive before its battlefield holder exists. For example,
`double-block-free-assignment.pzl` leaves a Forest in hand to create a normal
Main1 action window before the player advances to combat.

The same fixture covers native combat selection projection. Iterative block
choices are provisional and must not mutate Forge's combat before submission.
The client nevertheless needs `BlockInfo.attackerIds`, reciprocal
`AttackInfo.orderedBlockers`, and provisional `Declared` block state to draw
assigned-block lines. Refresh deselected candidates as well as selected ones,
and retain the emitted viewer baseline so later real snapshots can clear the
provisional presentation. The `combat-warmup` acceptance scenario
`forced-attack-single-required-block` also verifies that Gaea's Protector's
shared requirement does not force every available defender to block.

`block2-clive-equipment.pzl` starts Clive and Mjölnir on the battlefield so
equipping and Clive's tap-cost transform are immediately legal. It creates the
exile/return, Saga chapter, and new equipment attachment through normal actions,
not by importing a fabricated transformed state. The `block2-transforms`
acceptance suite verifies the forward equip/transform/fight/re-equip sequence;
native paired artwork, return animation, and the later Chapter III return are
separate user checks in the local playtest queue.

`remaining-copy-both-graveyards.pzl` extends the singleton copy test with three
eligible creatures in each graveyard and a noncreature filtering control in
each. The `remaining-ticket-controls` suite selects an own donor, selects an
opponent donor, and declines on separate resets. Only the selected donor is
exiled into its owner's exile by the real post-entry reflexive trigger; other
donors remain. Session tests check the six-candidate picker and the pending
player-scoped side indicator lifecycle. Native picker layout, side rectangle,
donor artwork/frame, and retained copier name remain user checks.

The same fixture includes a second Mjölnir in hand as an identity control:
Equip must publish its native keyword ability row and attach without damage;
the explicit hand-discard activation must retain its separate row and damage
effect. `block2-transforms` covers both gameplay routes. Registry and target
projection tests own wire-identity assertions; the user checks stack text and
animation in the native client.

`block2-tablet-trigger-chain.pzl` starts The Vision and Death to Our Enemies
on the battlefield, then casts Tablet normally to create both cast triggers
and its entry mill. Its `block2-trigger-chain` acceptance scenario chooses the
draw mode and checks the resulting hand, graveyard, Treasure, and Tablet in
the original main phase. Native timing and selected-mode text remain user
checks. `block2-death-reflexive.pzl` starts Death with three ordinary plan
counters and casts Bolt to reach the fourth through gameplay; the ensuing
sacrifice and reflexive target picker are not fabricated as imported state.

`block2-paradigm-ugin.pzl` starts Capstone in hand and establishes its exile
and recurring-copy permission by casting it normally. Its deterministic
library stops each Capstone at one Dreadmaw, avoiding unrelated targeting.
Death supplies a cast trigger above the copy. The `block2-ugin` acceptance
scenario now requires the recurring-copy cast and validates each client frame.
A copied spell that ceases to exist on resolution must not remain a member of
public exile without a corresponding object. Native copy visibility beneath
cast triggers and resolving-parent timing remain separate user checks. The
suite also runs `block2-ugin-ability-text.pzl`, a separate Ugin cast/+2 route.

`resolution-cast-capstone.pzl` starts with Sear and Overlord on top of the
library, reaching Capstone's multi-card choice through an ordinary cast. It
tests the resolution-cast browser, remaining-card filtering after targeting,
and declining either all casts or only the remaining cast. The parent spell
must finish resolving before the chosen spells resolve in reverse cast order;
the native foreground animation remains a user check. Use the full Forge
catalog in session tests because the small fixture catalog omits Sear.

`search-empty-library.pzl` exercises a search with no eligible basic land, and
`search-opponent-library.pzl` searches matching copies before a second search
with none. Search callbacks retain Forge's explicit library owner and permitted
view even when their filtered choices are empty. A restricted search must not
expose the rest of the library. The `library-search` suite verifies selection
and fail-to-find completion; native face-up layout remains a user check.
`search-filtered-teachings.pzl` uses two casts: first find the only instant,
then fail to find with only ineligible cards remaining. The native
instant-or-flash wording must remain the same for both searches; absent or
overlapping quality partitions use a flat search instead of invalid groups.

`emerge-wretched-gryff.pzl` offers Walking Corpse and Grizzly Bears as separate
sacrifice choices with four Islands available. The `mechanics-protocol` suite
checks both explicit selection (only Corpse is sacrificed) and cancellation
(Gryff stays in hand, both donors survive, and the stack is empty). Native cost
presentation and untapped mana after Cancel remain user checks. A mana-plan
preview must not erase the selected Emerge sacrifice before real payment.

That means direct `.pzl` placement can be incomplete when the important fact is history-derived:

- A card is in exile because it was plotted, foretold, adventured, prepared, or exiled by a specific source.
- A face-down or special-visibility state must exist in addition to the zone.
- A cast/action rail depends on a Forge flag created by a prior action.
- A client-visible persistent annotation needs a source relationship, designation, or linkage not expressible as plain zone membership.
- A prompt is mid-resolution and depends on source binding from the action that opened it.

In these cases, "card in exile" is underspecified. The fixture needs the richer state: for example, "plotted card in exile" or "card exiled under this source."

## Reaching Rich State

Prefer a setup action path over direct state mutation when the state is history-sensitive:

1. Start with the card in a simple direct state, usually hand or battlefield.
2. Drive the setup action through the normal harness verbs.
3. Wait for a concrete checkpoint, such as zone membership, phase, or available action.
4. Begin the actual assertion from that checkpoint.

Example shape in a session test:

```kotlin
startPuzzleRaw(pzl, validating = true)

castSpellByName("Ratcatcher Trainee")
passUntil(maxPasses = 15) {
    human.exile.cards.any { it.name == "Ratcatcher Trainee" }
}

castFromExile("Ratcatcher Trainee")
```

If this pattern repeats across several fixtures, promote only that repeated setup to a named helper. Do not add a general scenario language before the repetition exists.

## Full GSM Boundary

A Full GSM should be a resync boundary for stable visible state. If a fact can be safely rebuilt from current engine state, prefer fixing the Full GSM projection over requiring every test to replay history.

Do not try to encode transient history in a Full GSM. Zone-transfer animations, object-id-change transitions, damage events, and cast/resolve brackets are event-stream facts. Full state can show the result; it should not pretend to replay the journey.

## Seeded State Today

Puzzle startup already seeds a few stable facts that plain setup does not emit as events:

- Instance and zone baselines for first-diff zone-transfer detection.
- Persistent attachment annotations for cards that start attached.
- Persistent counter annotations for players and permanents.

Add more seeders only for stable facts that can be read from current engine state without guessing history.

## Decision Rule

- Use direct `.pzl` state when the test is about current-state math or a future action.
- Use harness setup actions when the test is about a state normally created by prior gameplay.
- Add a Full GSM projector when current engine state already contains the needed fact.
- Add a seeder only for stable, snapshot-readable state.
- Avoid arbitrary mutation for impossible or under-specified states.

## Candidate validation

Embedding hosts can call `PuzzleValidation(cardRepository).validate(definition)`
before offering generated puzzle text for play. Validation checks card names
against both the supplied catalog and Forge, then applies the definition to a
disposable game through the same puzzle setup path. `Loaded` means engine setup
succeeded. Catalog, setup, and cleanup failures return `EngineFailure`. A
`Loaded` result does not establish solvability or exercise a client interaction.

The candidate format currently supports two players starting at Human Main1 on
turn one, positive life totals, ordinary card zones, lands played, mana pools,
and the `Tapped` and `SummonSick` card flags. Goals are `Win`, `Survive`, and
`Win before opponent's next turn`, with a limit of 1–99 turns. Text is limited
to 32,768 characters and 100 cards. Unknown cards and malformed values are
invalid. Other state fields, card flags and objectives are unsupported, rather
than silently accepted. Existing authored fixtures retain the full puzzle
loader; extending candidate support requires proof that its state is applied.

Keep validation results with the original definition. After a `Loaded` result,
launch that exact definition and observe its objective and required decisions
through the chosen client. A failed advisor attempt is inconclusive about the
puzzle's solvability.

Scripted acceptance defaults to the small test-card catalog. A scenario may
set `headless: { forge_catalog: true }` to use `ForgeCardRepository.open()` when
its cards require metadata omitted by those fixtures (for example modal or
alternate-cost rows). This is a read-only catalog selection, not a relaxed
stream invariant or a substitute for the native user's visual checks. Existing
scenarios keep their fixture catalog unless they explicitly opt in.

## Bounded engine trial

`PuzzleTrial(matchRuntime, engineSeed).run(definition)` can screen a loaded
definition inside the engine before an interactive trial. It authenticates one
runtime handle, consults advice for each current prompt, submits the first
encoded response, and closes the handle after a terminal or bounded result.
The default limits are 100 submitted decisions and 30 seconds; callers can
supply smaller positive limits.

The elapsed limit is a caller deadline. Trials share one worker and have no
request queue. At the deadline, the caller interrupts the worker and returns
the last immutable progress as `TimeBudgetExceeded`. The worker remains the
sole owner of runtime cleanup. If cleanup is still running, the result says so
and new trials return `EngineFailure` until that worker exits.

The serializable result reports the definition and match identities, the
caller-provided engine seed, elapsed time, semantic prompt-bound decisions, and
any observed winner. `Won` and `Lost` require a terminal engine observation.
Unsupported prompts, unavailable advice, repeated responses, decision and time
limits, interruption, and engine failure have distinct statuses. These
non-terminal statuses are inconclusive about whether another line can solve the
puzzle. The submission loop belongs to this engine-only trial; interactive
hosts should use prompt advice without submitting through `PuzzleTrial`.
