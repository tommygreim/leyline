package leyline.game.snapshot

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

data class StackSnapshot(
    val entries: List<StackEntry>,
)

/**
 * Immutable snapshot of a single stack item for the [addStackAbilitiesFromSnapshot] path.
 *
 * Protocol convention for an `Ability` GameObject in zone Stack:
 * - `grpId` = the **ability row id** (e.g. 86 for Cascade, 169776 for Hidden
 *   Courtyard's activated Discover, 188945 for Reigning Victor's ETB-buff).
 * - `objectSourceGrpId` = the **source card's grpId** (the permanent the ability
 *   lives on).
 * The two are not equal; collapsing them is a long-standing leyline gap.
 * [grpId] and [sourceCardGrpId] mirror that split. A value of 0 means resolution
 * failed; callers fall back to [leyline.game.state.GameBridge.FALLBACK_GRPID].
 */
data class StackEntry(
    /** Forge card ID of the source card (host of the ability). */
    val forgeCardId: ForgeCardId,
    /** Seat that controls / activated the ability. */
    val controller: SeatId,
    /** Owner of the source card (used to set ownerSeatId on the ability object). */
    val owner: SeatId,
    /**
     * Resolved grpId for the **ability** projected onto the stack — the row in the
     * Arena `Abilities` table that describes this trigger / activated SA. Defaults
     * to [sourceCardGrpId] when the resolver doesn't recognize the SA (preserves
     * pre-fix behavior for unknown shapes).
     */
    val grpId: Int,
    /**
     * grpId of the source permanent (the card the ability lives on). Sets
     * `objectSourceGrpId` on the projected ability object. Always populated when
     * the source card has an Arena printing; 0 otherwise.
     */
    val sourceCardGrpId: Int,
    /**
     * `true` when this stack entry is a spell-cast (`SpellPermanent`,
     * `SpellApiBased`). Spells get projected as `Card`-typed objects in the
     * Stack zone via [addSharedZoneCardsFromSnapshot]; the `Ability` projection
     * path skips them. Triggered + activated SAs are `false`.
     */
    val isSpell: Boolean,
    /** True for an activated ability; false for triggers and spells. */
    val isActivatedAbility: Boolean = false,
    /** Card targets chosen for this stack item (may be empty). */
    val targets: List<ForgeCardId>,
    /**
     * Forge `SpellAbility.id` for this stack item. Drives SA-id-keyed
     * surrogate iid allocation via
     * [leyline.game.mapping.FrameIdResolver.triggerStackAbilityIid] so
     * back-to-back triggers from the same source card mint distinct iids.
     * `0` falls back to source-card-keyed surrogate (synthetic test entries,
     * paths where the SA id is not surfaced).
     */
    val forgeAbilityId: Int = 0,
    /** Forge trigger identity when this stack item came from a trigger. */
    val runtimeTriggerId: Int = 0,
    /** Forge identity of the source card behind an engine effect helper. */
    val effectSourceForgeCardId: ForgeCardId? = null,
    /**
     * True for a pre-stack ability shown to the client before Forge puts it on the stack: it
     * appears in the frame's objects, but its lifecycle annotations wait for the events that
     * describe the trigger going on the stack.
     */
    val deferAnnouncement: Boolean = false,
)
