package leyline.game.annotations

import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId

/** Universal content-side grpIds that annotations reference directly. */
object AnnotationConstants {
    /** Ability grpId used by the `TemporaryPermanent` annotation to mark EOT-sacrifice tokens. */
    val EOT_SACRIFICE_GRP_ID: GrpId = GrpId(192424)

    /** Adventure-cast qualification grpId — fixed ability ID referenced by the `Qualification` annotation. */
    val ADVENTURE_QUALIFICATION_GRP_ID: GrpId = GrpId(196)

    /** Shared Battlefield zone ID, used as `affectorId` on zone-scoped persistent
     *  annotations whose affector is the zone rather than any specific permanent. */
    val BATTLEFIELD_ZONE_AFFECTOR: InstanceId = InstanceId(28)

    /** `DesignationType` enum value for the `Commander` player/card state designation. */
    const val DESIGNATION_TYPE_COMMANDER: Int = 1

    const val DESIGNATION_TYPE_CITYS_BLESSING: Int = 6

    /** Battle source links to its protecting player. */
    const val DESIGNATION_TYPE_BATTLE_PROTECTOR: Int = 13

    /** `DesignationType` enum value for the `Day` game-scope state designation.
     *  Carried on `GainDesignation` / persistent `Designation` / `LoseDesignation`.
     *  The persistent shape additionally carries the `ActivePlayerSpellCount`
     *  detail key — exclusive to types 10/11. */
    const val DESIGNATION_TYPE_DAY: Int = 10

    /** `DesignationType` enum value for the `Night` game-scope state designation.
     *  Pairs with [DESIGNATION_TYPE_DAY] — a game in either state can flip to the
     *  other at second-part-of-untap (CR 731.2) when the previous turn's
     *  active-player spell count meets the rules-side threshold. */
    const val DESIGNATION_TYPE_NIGHT: Int = 11

    /** `DesignationType` enum value for the `Saddled` card-state designation.
     *  Carried on `GainDesignation` / persistent `Designation` / `LoseDesignation`
     *  for mounts saddled this turn. */
    const val DESIGNATION_TYPE_SADDLED: Int = 17

    /** `DesignationType` enum value for the `Suspected` card-state designation. */
    const val DESIGNATION_TYPE_SUSPECTED: Int = 16

    /** `DesignationType` enum value for a solved Case permanent. */
    const val DESIGNATION_TYPE_SOLVED: Int = 15

    /** Ability grpId for the static "can't block" row paired with Suspected. */
    val SUSPECTED_CANT_BLOCK_GRP_ID: GrpId = GrpId(86476)

    /** `DesignationType` enum value for the `Prepared` card-state designation.
     *  Carried on `GainDesignation` / persistent `Designation` / `LoseDesignation`. */
    const val DESIGNATION_TYPE_PREPARED: Int = 24

    /** `DesignationType` enum value for the `Plotted` card-state designation.
     *  Carried on `GainDesignation` / persistent `Designation` / `LoseDesignation`
     *  for cards exiled with the plot keyword. */
    const val DESIGNATION_TYPE_PLOTTED: Int = 18

    /** `DesignationType` enum value for the `LeftUnlocked` Room-door state.
     *  Marks the left half of a split-room enchantment as unlocked. Gained when
     *  a `CastLeftRoom` action resolves; persists while the door stays open. */
    const val DESIGNATION_TYPE_LEFT_UNLOCKED: Int = 19

    /** `DesignationType` enum value for the `RightUnlocked` Room-door state.
     *  Marks the right half of a split-room enchantment as unlocked. Gained when
     *  a `CastRightRoom` action resolves; persists while the door stays open. */
    const val DESIGNATION_TYPE_RIGHT_UNLOCKED: Int = 20

    /** `DesignationType` enum value for an Earthbend-animated mana creature. */
    const val DESIGNATION_TYPE_MANA_CREATURE: Int = 23

    /** `DesignationType` enum value for a player's speed state. */
    const val DESIGNATION_TYPE_PLAYER_SPEED: Int = 21

    /** `REASON` value on the persistent `FaceDown` annotation for Foretell. */
    const val FACEDOWN_REASON_FORETELL: Int = 5

    /** `REASON` value on the persistent `FaceDown` annotation for Disguise. */
    const val FACEDOWN_REASON_DISGUISE: Int = 6

    /** `REASON` value on the persistent `FaceDown` annotation for Cloak. */
    const val FACEDOWN_REASON_CLOAK: Int = 7

    /** `REASON` value on the persistent `FaceDown` annotation for Manifest Dread. */
    const val FACEDOWN_REASON_MANIFEST_DREAD: Int = 8
}
