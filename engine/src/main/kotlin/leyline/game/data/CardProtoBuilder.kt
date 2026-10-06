package leyline.game.data

import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Builds static [GameObjectInfo] proto projections from [CardRepository] data.
 *
 * Covers the immutable card identity (types, colors, abilities, base P/T).
 * Dynamic game state — counters, damage, tapped, attached, combat — is layered
 * on by [leyline.game.mapping.ObjectMapper]. The split keeps card-DB concerns
 * out of the per-tick diff pipeline.
 */
class CardProtoBuilder(
    private val cards: CardRepository,
) {
    /**
     * Door-state ability grpIds prefixed on every Room enchantment's
     * `uniqueAbilities` list (left door, right door). Constant across all rooms;
     * surfaces the locked/unlocked indicators the client renders. Without these
     * the client renders a Room as a plain enchantment and skips the side-by-side
     * door display.
     */
    private val roomDoorAbilityGrpIds = listOf(347, 348)

    /** SubType ordinal for `Room` (engine proto Messages.SubType.Room = 438). */
    private val roomSubtype = SubType.Room.number

    private fun isRoomCard(subtypes: List<Int>): Boolean = subtypes.contains(roomSubtype)

    /**
     * Universal face-down overlay grpId — the "card back" stencil the
     * client renders in place of the real card art for any face-down
     * permanent.
     */
    private val faceDownOverlayGrpId = 3

    /** Face-down stat — every face-down creature is a 2/2 regardless of printed P/T. */
    private val faceDownPowerAndToughness = 2

    /**
     * Build a [GameObjectInfo] for a supported face-down permanent. The
     * projection drops printed identity (name,
     * subtypes, color, the per-card abilities) and substitutes the
     * universal face-down stencil — `overlayGrpId=3`, single
     * Ward {2} ability `141939`, 2/2 P/T, `Creature` card type.
     *
     * The [grpId] of the underlying card is still set on the proto so the
     * per-seat filter can preserve it for the controller and strip it for
     * the opponent (opponent visibility=Private doesn't reveal identity).
     */
    fun buildFaceDownObjectInfo(grpId: Int): GameObjectInfo.Builder =
        GameObjectInfo
            .newBuilder()
            .setGrpId(grpId)
            .setOverlayGrpId(faceDownOverlayGrpId)
            .setIsFacedown(true)
            .addCardTypes(CardType.Creature)
            .setPower(Int32Value.newBuilder().setValue(faceDownPowerAndToughness))
            .setToughness(Int32Value.newBuilder().setValue(faceDownPowerAndToughness))
            .addUniqueAbilities(
                UniqueAbilityInfo
                    .newBuilder()
                    .setId(50)
                    .setGrpId(KeywordAbilityIds.WARD_TWO),
            )

    /** Build a [GameObjectInfo] from DB data, no template — for the buildFromSnapshot path. */
    fun buildObjectInfo(
        grpId: Int,
        extrinsicKeywordGrpIds: List<Int> = emptyList(),
        abilityGrpIdsOverride: List<Int>? = null,
    ): GameObjectInfo.Builder {
        val builder =
            GameObjectInfo
                .newBuilder()
                .setGrpId(grpId)
                .setOverlayGrpId(grpId)
        val card = cards.findByGrpId(grpId) ?: return builder
        builder.setName(card.titleId)
        card.types.forEach { builder.addCardTypes(CardType.forNumber(it) ?: return@forEach) }
        card.subtypes.forEach { builder.addSubtypes(SubType.forNumber(it) ?: return@forEach) }
        card.supertypes.forEach { builder.addSuperTypes(SuperType.forNumber(it) ?: return@forEach) }
        card.colors.forEach { builder.addColor(CardColor.forNumber(it) ?: return@forEach) }
        if (card.power.isNotEmpty()) builder.setPower(Int32Value.newBuilder().setValue(card.power.toIntOrNull() ?: 0))
        if (card.toughness.isNotEmpty()) builder.setToughness(Int32Value.newBuilder().setValue(card.toughness.toIntOrNull() ?: 0))
        var abilitySeqId = 50
        (abilityGrpIdsOverride ?: staticAbilityGrpIds(grpId)).forEach { abilityGrpId ->
            builder.addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(abilityGrpId))
        }
        for (kwGrpId in extrinsicKeywordGrpIds) {
            builder.addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(kwGrpId))
        }
        return builder
    }

    fun staticAbilityGrpIds(grpId: Int): List<Int> {
        val card = cards.findByGrpId(grpId) ?: return emptyList()
        return buildList {
            if (isRoomCard(card.subtypes)) addAll(roomDoorAbilityGrpIds)
            val abilities =
                card.abilityIds.ifEmpty {
                    BasicLandAbilities.byProtoSubtypeOrdinals(card.subtypes)?.let { listOf(it to 0) } ?: emptyList()
                }
            addAll(
                abilities.map { it.first },
            )
        }
    }

    /** Build a [GameObjectInfo] from DB data, preserving template structure fields. */
    fun buildObjectInfo(
        grpId: Int,
        template: GameObjectInfo,
        extrinsicKeywordGrpIds: List<Int> = emptyList(),
    ): GameObjectInfo {
        val card =
            cards.findByGrpId(grpId) ?: return template
                .toBuilder()
                .setGrpId(grpId)
                .setOverlayGrpId(grpId)
                .build()

        val builder =
            template
                .toBuilder()
                .setGrpId(grpId)
                .setOverlayGrpId(grpId)
                .setName(card.titleId)

        builder.clearCardTypes()
        card.types.forEach { builder.addCardTypes(CardType.forNumber(it) ?: return@forEach) }

        builder.clearSubtypes()
        card.subtypes.forEach { builder.addSubtypes(SubType.forNumber(it) ?: return@forEach) }

        builder.clearSuperTypes()
        card.supertypes.forEach { builder.addSuperTypes(SuperType.forNumber(it) ?: return@forEach) }

        builder.clearColor()
        card.colors.forEach { builder.addColor(CardColor.forNumber(it) ?: return@forEach) }

        if (card.power.isNotEmpty()) {
            builder.setPower(Int32Value.newBuilder().setValue(card.power.toIntOrNull() ?: 0))
        } else {
            builder.clearPower()
        }
        if (card.toughness.isNotEmpty()) {
            builder.setToughness(Int32Value.newBuilder().setValue(card.toughness.toIntOrNull() ?: 0))
        } else {
            builder.clearToughness()
        }

        builder.clearUniqueAbilities()
        var abilitySeqId = template.uniqueAbilitiesList.firstOrNull()?.id ?: 50
        if (isRoomCard(card.subtypes)) {
            for (doorGrpId in roomDoorAbilityGrpIds) {
                builder.addUniqueAbilities(
                    UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(doorGrpId),
                )
            }
        }
        val abilities =
            card.abilityIds.ifEmpty {
                BasicLandAbilities.byProtoSubtypeOrdinals(card.subtypes)?.let { listOf(it to 0) } ?: emptyList()
            }
        abilities.forEach { (abilityGrpId, _) ->
            builder.addUniqueAbilities(
                UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(abilityGrpId),
            )
        }
        for (kwGrpId in extrinsicKeywordGrpIds) {
            builder.addUniqueAbilities(
                UniqueAbilityInfo.newBuilder().setId(abilitySeqId++).setGrpId(kwGrpId),
            )
        }

        return builder.build()
    }
}
