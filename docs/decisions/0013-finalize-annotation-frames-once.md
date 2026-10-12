---
summary: "ADR: collect every transient annotation for a state frame, then order and number the completed frame once."
read_when:
  - "working in AnnotationPipeline, AnnotationOrderEnforcer, StateMapper, BundleBuilder, or frame finalization"
  - "adding an annotation owned by request or bundle delivery"
  - "changing transient annotation ordering, numbering, or frame assembly"
---
# ADR 0013: Finalize Annotation Frames Once

## Status

Accepted and implemented for every `StateMapper`-backed frame. Direct lifecycle
and synthetic builders remain separate single-authority paths as allowed by
this decision.

## Context

Leyline builds one state-diff GSM from several kinds of information:

- Forge events and snapshot differences;
- transfer, combat, mechanic, and effect annotation producers;
- persistent annotation lifecycle changes;
- annotations that exist because of the delivery interaction, such as target
  selection, target submission, or turn narration.

These inputs become known at different layers. The pre-boundary implementation
assembled state and delivery riders in separate imperative objects, allowing a
late append to observe an already ordered list. That historical shape is why
this decision requires one finalization authority.

The current implementation carries an immutable `StateFrameInput` through
`StateMapper.buildDraft` and `StateProjectionCompiler.compileOneViewer`.
`AnnotationPipeline` returns unfinalized transients; the compiler appends
viewer supplements, invokes `AnnotationFrameFinalizer` once, installs the
resulting annotations in the tentative `ProjectionTransition`, and advances
the next ID in that transition.

The historical lifecycle was conceptually:

```text
collect -> order -> number -> plan counter commit -> append rider
                                      or -> append -> reorder -> renumber
```

The list has more than one finalization authority, and the annotation counter
can describe a partial frame rather than the frame that is sent.

## Problem Statement

Annotation correctness has three distinct dimensions:

1. **Content and causal order** — which annotations a producer emits and which
   local sequences must remain together.
2. **Frame membership** — which GSM owns an annotation and which protocol
   interaction the GSM represents.
3. **Mechanical finalization** — cross-producer same-GSM ordering and unique,
   contiguous transient IDs.

The current seam complects all three. Producers return a list that already
looks externally complete, while the bundle layer still owns information that
can change that list. Late repair then asks a raw-proto ordering pass to
recover intent that belonged to the producer or frame owner.

This is unsafe in both directions:

- a delivery rider can bypass the ordering policy or force a second pass;
- a global rule can split a producer-owned causal sequence while satisfying
  its local graph constraints.

Sorting cannot decide that an annotation belongs in another GSM, repair an
incorrect resolution bracket, or determine that two annotations describe the
same effect. Those are semantic decisions and must remain upstream.

## Decision

Every state-diff GSM has one explicit annotation finalization boundary.

The completed lifecycle is:

```text
events + snapshots
       |
       v
emit producer-owned causal sequences
       |
       v
assemble state-frame draft + delivery riders
       |
       v
apply same-GSM ordering once
       |
       v
assign transient IDs once
       |
       v
commit mutations and freeze the GSM
       |
       v
validate and send
```

For the first migration, this boundary applies to state-diff GSMs. Other GSM
families are inventoried and may migrate when the same ownership problem is
present; this ADR does not require a repository-wide wrapper around every
annotation.

### Frame Draft

A frame draft contains every transient annotation currently known to belong
to one state-diff GSM. Its annotations are not externally valid yet: their
transient IDs are uncommitted and may be placeholders.

The draft includes:

- state-derived annotations returned by the annotation pipeline;
- diff-stage annotations derived from previous and current snapshots;
- delivery riders known by bundle assembly before the GSM is committed.

Persistent annotation upserts and deletions remain a separate lifecycle batch.
They may share the same state projection, but they are not merged into the
transient annotation list or renumbered by this finalizer.

### Delivery Riders

A delivery rider is an annotation whose presence is determined by the
interaction used to deliver the state frame rather than by the state snapshot
alone. Existing examples include selecting targets, submitted targets, and
new-turn narration.

Riders are ordinary annotation inputs, not a new protocol abstraction. They
must be materialized while frame-local ID resolution is still available and
before finalization starts. A rider must not call the global annotation ID
allocator itself.

### Finalizer

A pure finalizer accepts:

- the complete unnumbered transient annotation list;
- the first transient annotation ID available to this frame.

It returns:

- the final ordered and numbered annotation list;
- the next transient annotation ID after that list.

The finalizer applies the existing same-GSM ordering policy exactly once and
then assigns IDs positionally. It does not inspect Forge objects, choose frame
membership, emit new annotations, mutate bridge state, or send messages.

Frame assembly may supply an immutable reference-retention predicate. The
finalizer applies it after following same-frame target identity replacements
and before ordering/numbering, so dropping an invalid target rider cannot leave
ID gaps. The predicate belongs to `AnnotationReferenceSanitizer`, not the
ordering kernel. Viewer rendering consumes the retained list and must never
restore the earlier unfiltered annotations.

The exact API may follow the existing Kotlin vocabulary. The architectural
shape is a value transformation similar to:

```text
AnnotationFrameFinalizer.finalize(draft, firstId)
    -> FinalizedAnnotationFrame(annotations, nextId)
```

No mutable or externally visible GSM may exist between ordering and numbering.

## Ownership

### Annotation Producers

Producers own:

- whether an annotation exists;
- annotation fields and affectees;
- the GSM to which the annotation belongs;
- local causal sequences that cannot be safely reconstructed from raw fields.

Examples of producer-owned sequences include identity-change and zone-transfer
pairs, and resolution start, payload, and resolution-complete groups. If a
cross-producer rule conflicts with one of these sequences, the semantic fix is
made at emission or frame slicing. The finalizer is not expanded into a
semantic scheduler.

### Annotation Pipeline

`AnnotationPipeline` collects state-derived transient annotations and
persistent lifecycle inputs. It returns unfinalized transient annotations. It
does not apply the global same-GSM ordering kernel, allocate transient IDs, or
advance the annotation counter.

Its internal stage order remains meaningful. Finalization does not make stage
ordering disposable.

### State Mapping And Frame Assembly

`StateMapper.buildDraft` builds state projection and an annotation frame draft
from explicit values. It does not commit a partial next-ID result.

`StateProjectionCompiler.compileOneViewer` appends viewer supplements, invokes
the finalizer, installs finalized annotations in the GSM, and places the
returned next ID in the tentative `ProjectionTransition` before installation.

The finalizer belongs under `game.annotations`. The orchestration call belongs
at state-frame assembly, where both mapped state and delivery intent are known.

### Bridge State

The global transient annotation counter advances from the finalized frame
result only. It is committed with the rest of the projection transition after
frame construction succeeds.

Pending one-shot riders must keep their current transactional behavior. A
failed frame build must not consume a pending rider or advance the counter. A
retry sees the same pending input and the same first annotation ID.

### Transport

Match session and transport code receive a frozen GSM. They may validate
annotation IDs and ordering invariants, but they do not append, sort, suppress,
or renumber annotations.

Transport is too late for finalization: bridge mutations may already be
committed, pending riders may already be consumed, and causal ownership is no
longer available there.

## Same-GSM Ordering Policy

`AnnotationOrderEnforcer` remains the mechanical ordering kernel during the
first migration. Its stable topological sort expresses established constraints
such as phase markers, submitted-target leadership, identity references, and
same-card incremental ordering.

This ADR changes when the kernel runs, not what its rules mean.

Rules must remain narrow and mechanically testable. A new rule is not an
acceptable substitute for:

- moving an annotation to the correct GSM;
- preserving a producer-owned atomic sequence;
- pairing repeated resolution brackets;
- suppressing duplicate narration;
- distinguishing combat and noncombat effects.

When a demonstrated ambiguity cannot be expressed from the current annotation
fields, introduce the smallest explicit producer-owned metadata for that case.
Do not pre-emptively wrap every annotation in a lane, role, atom, or causal
group hierarchy.

## Migration

The sequence below records the historical migration plan. The current
`StateMapper`-backed path has completed the finalizer and tentative-transition
steps; the inventory remains as a boundary map for direct builders.

1. Characterize current state-diff output for ordinary diffs, target-selection
   delivery, target-submission delivery, new-turn narration, and build failure
   or retry behavior.
2. Add a pure annotation frame finalizer around the existing ordering kernel
   and positional ID assignment.
3. Change the annotation pipeline to return unfinalized transient annotations.
4. Carry the frame draft through state mapping without committing the final
   annotation counter.
5. Materialize every current state-diff delivery rider before finalization.
6. Finalize the completed frame, set the transition's next annotation ID from
   that result, and install state only after successful GSM construction.
7. Remove append-specific reorder, renumber, and direct counter-allocation
   repairs from the migrated path.
8. Inventory direct, full-state, lifecycle, and synthetic GSM builders. Note
   why each path is already single-authority, should migrate next, or is outside
   this decision.
9. Widen invariant tests so post-finalization mutation is structurally visible.

Migration may temporarily derive compatibility values from the draft or
finalized result. It must not leave two independently writable annotation
lists or counters.

## Implementation Inventory

The first migration covers every `StateMapper`-backed state frame. Normal
bundle assembly adds target-selection, target-submission, and new-turn riders
to the draft. The staged order-zone move also joins the draft because it adds
identity-change and zone-transfer annotations to a mapped frame. The initial
puzzle and headless Full-state builders finalize explicitly with no riders.

The remaining direct builders already know their complete annotation list and
retain one numbering authority:

- deal-hand, mulligan, phase-transition, and game-over lifecycle GSMs in
  `GsmBuilder` and `BundleBuilder`;
- combat toggle echoes, reveal/group helpers, and other request-specific
  synthetic GSMs that do not start from a `StateMapper` annotation list;
- commander-choice cleanup GSMs in `BlockingInteractionMaterializer`; the match
  coordinator installs their identity-change, zone-transfer, and limbo retirement
  in the same transition before waking the blocked engine callback.

These paths remain outside the state-frame finalizer. A future migration is
appropriate only if one of them gains a late annotation producer; uniform use
of the finalizer is not an objective.

## Required Invariants

- All state-diff delivery riders are present before finalization.
- One state-diff GSM invokes same-GSM ordering at most once.
- One state-diff GSM assigns transient annotation IDs at most once.
- Final transient IDs are unique, contiguous, and follow final list order.
- The projection transition's next annotation ID equals the next ID after the
  finalized frame, never after a partial producer list.
- A failed frame build advances neither the annotation counter nor pending
  rider state.
- Frame-local instance ID resolution is complete before riders are finalized.
- Persistent annotation IDs and lifecycle remain independent.
- Producers retain responsibility for content, frame membership, and causal
  sequences.
- Match session and transport never rewrite a finalized annotation list.
- The initial migration preserves annotation content, order, IDs, frame
  membership, update type, and request pairing.

## Protocol Change Gate

The initial migration is behavior-preserving. Exact output characterization is
the primary proof.

A separate, explicitly grounded change is required before altering any of the
following:

- which GSM contains an annotation;
- trigger, combat, or resolution bracketing;
- an ordering rule;
- annotation suppression or deduplication;
- combat versus noncombat narration;
- persistent annotation lifecycle.

Aggregate ordering observations are not enough to justify those changes when
interaction branches differ. Verification must cover representative branches
and the exact frame sequence being changed.

## Verification

The implementation must prove both the pure transformation and the lifecycle
boundary:

- finalizer tests cover stable ordering, positional IDs, empty frames, and
  unchanged input values;
- ordering-kernel tests remain unchanged unless a separate semantic change is
  approved;
- state-mapper tests prove it no longer commits a partially finalized counter;
- bundle tests cover ordinary state diffs, selecting-target riders,
  submitted-target riders, and new-turn riders;
- retry tests prove a failed build consumes neither pending rider nor ID;
- frame-local reallocation tests prove rider source IDs remain correct;
- pure diff replay or equivalent fixtures remain byte-equal for migrated
  routes;
- invariant checks reject duplicate IDs, non-contiguous IDs, and ordering
  changes after finalization;
- focused engine tests and the project gate pass.

## Consequences

There is one place to answer: "Is this state frame complete, ordered, and safe
to number?" Delivery-owned annotations no longer need append-specific repair,
and bridge counter state describes exactly what the GSM contains.

The annotation pipeline returns a less externally complete value. That is
intentional: state derivation does not have enough information to finalize a
delivery frame.

The finalizer becomes a load-bearing but deliberately small pure function.
Keeping semantic emission and bracketing outside it prevents a convenient
mechanical seam from growing into a second game engine.

Direct and synthetic GSM paths may remain separate when they already know
their complete annotation set before numbering. Uniformity is not a goal by
itself; single authority is.

## Non-Goals

- Changing protocol-visible annotation behavior.
- Moving damage or resolution payloads between GSMs.
- Fixing duplicate combat life narration.
- Pairing repeated resolution brackets for the same ability.
- Moving every annotation producer into one registry.
- Introducing first-class lane or role types.
- Introducing a universal annotation atom or causal-group wrapper.
- Rewriting state mapping, bundle delivery, match session, or transport.
- Merging persistent and transient annotation lifecycles.

## Alternatives Considered

### Canonicalize In Transport Before Sending

Rejected. This appears to provide the strongest last-moment view, but it is
after bridge mutation planning and too far from pending rider ownership and
frame-local ID resolution. It would make transport responsible for game
semantics and require counter repair after state was committed.

### Require Every Producer To Emit Its Final Position

Rejected. Producers should own local causal order, but no individual producer
knows all annotations in the completed frame. Cross-producer mechanical
constraints would remain duplicated across stage ordering and append sites.

### Keep Late Append And Renumber Repairs

Rejected. Repair makes a partial frame appear complete, permits more append
sites to bypass the policy, and couples global counter mutation to list
rewriting.

### Introduce Grouped Annotation Atoms Everywhere

Deferred. Explicit groups may eventually be justified for a proven repeated
bracket or atomic-sequence ambiguity. Requiring every producer and consumer to
adopt a wrapper now would add a parallel model before its necessary fields and
operations are known.

### Move Bracketing Into The Finalizer

Rejected. Same-GSM canonicalization cannot recover event timing or decide
where one GSM ends and the next begins. Bracketing remains with playback and
frame assembly, where temporal information exists.
