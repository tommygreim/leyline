package leyline.game.bundle

import forge.game.Game
import forge.game.GameEntity
import forge.game.card.Card
import forge.game.staticability.StaticAbility
import forge.game.staticability.StaticAbilityCantAttackBlock
import forge.game.staticability.StaticAbilityMode
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.game.codes.QualificationType
import leyline.game.data.CardData
import leyline.game.mapping.ProjectionCardReferences
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.game.state.PersistentFeedFacts

/** Shell materializer for live combat restrictions observed at one closed snapshot cut. */
internal object CombatQualificationFactsCapture {
    private data class RowKey(
        val affectorId: Int,
        val affectedId: Int,
        val grpId: Int,
        val qualificationType: QualificationType,
    )

    private data class Row(
        val affectorForgeId: ForgeCardId,
        val affectedForgeId: ForgeCardId,
        val sourceParentForgeId: ForgeCardId,
        val abilityGrpId: Int,
        val qualificationType: QualificationType,
        val cantBlockForgeIds: MutableSet<ForgeCardId> = linkedSetOf(),
        val cantBeBlockedByForgeIds: MutableSet<ForgeCardId> = linkedSetOf(),
    )

    fun scan(
        snap: GsmSnapshot,
        bridge: GameBridge,
        references: ProjectionCardReferences,
    ): List<PersistentFeedFacts.CombatQualificationRow> {
        val game = bridge.getGame() ?: return emptyList()
        if (!liveBattlefieldMatchesSnapshot(game, snap)) return emptyList()

        val creatures = battlefieldCreatures(game)
        if (creatures.isEmpty()) return emptyList()

        val rows = linkedMapOf<RowKey, Row>()
        for (source in game.getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES).sortedBy { it.id }) {
            for (staticAbility in source.staticAbilities.orEmpty()) {
                if (staticAbility.keyword != null) continue
                if (staticAbility.checkConditions(StaticAbilityMode.CantAttack)) {
                    scanCantAttack(staticAbility, creatures, game, snap, bridge, references, rows)
                }
                if (staticAbility.checkConditions(StaticAbilityMode.CantBlock)) {
                    scanCantBlock(staticAbility, creatures, snap, bridge, references, rows)
                }
                if (staticAbility.checkConditions(StaticAbilityMode.CantBlockBy)) {
                    scanCantBlockBy(staticAbility, creatures, snap, bridge, references, rows)
                }
            }
        }

        return rows.values
            .sortedWith(compareBy<Row> { it.affectedForgeId.value }.thenBy { it.qualificationType.wireValue }.thenBy { it.abilityGrpId })
            .map { row ->
                PersistentFeedFacts.CombatQualificationRow(
                    affectorForgeId = row.affectorForgeId,
                    affectedForgeId = row.affectedForgeId,
                    sourceParentForgeId = row.sourceParentForgeId,
                    abilityGrpId = row.abilityGrpId,
                    qualificationType = row.qualificationType,
                    cantBlockForgeIds = row.cantBlockForgeIds.sortedBy { it.value },
                    cantBeBlockedByForgeIds = row.cantBeBlockedByForgeIds.sortedBy { it.value },
                )
            }
    }

    private fun scanCantAttack(
        staticAbility: StaticAbility,
        creatures: List<Card>,
        game: Game,
        snap: GsmSnapshot,
        bridge: GameBridge,
        references: ProjectionCardReferences,
        rows: MutableMap<RowKey, Row>,
    ) {
        for (creature in creatures) {
            val targets = attackTargets(game, creature)
            if (targets.isEmpty()) continue
            if (targets.any { StaticAbilityCantAttackBlock.applyCantAttackAbility(staticAbility, creature, it) }) {
                addRow(staticAbility, creature, QualificationType.CantAttack, snap, bridge, references, rows)
            }
        }
    }

    private fun scanCantBlock(
        staticAbility: StaticAbility,
        creatures: List<Card>,
        snap: GsmSnapshot,
        bridge: GameBridge,
        references: ProjectionCardReferences,
        rows: MutableMap<RowKey, Row>,
    ) {
        for (blocker in creatures) {
            if (StaticAbilityCantAttackBlock.applyCantBlockAbility(staticAbility, blocker)) {
                addRow(staticAbility, blocker, QualificationType.CantBlock, snap, bridge, references, rows)
            }
        }
    }

    private fun scanCantBlockBy(
        staticAbility: StaticAbility,
        creatures: List<Card>,
        snap: GsmSnapshot,
        bridge: GameBridge,
        references: ProjectionCardReferences,
        rows: MutableMap<RowKey, Row>,
    ) {
        for (attacker in creatures) {
            if (appliesWithoutSpecificBlocker(staticAbility, attacker)) {
                addRow(staticAbility, attacker, QualificationType.CantBeBlocked, snap, bridge, references, rows)
                continue
            }

            for (blocker in creatures) {
                if (blocker.controller == attacker.controller) continue
                if (!StaticAbilityCantAttackBlock.applyCantBlockByAbility(staticAbility, attacker, blocker)) continue

                if (isBlockerCentric(staticAbility, blocker)) {
                    val row = addRow(staticAbility, blocker, QualificationType.CantBlock, snap, bridge, references, rows) ?: continue
                    row.cantBlockForgeIds.add(ForgeCardId(attacker.id))
                } else {
                    val row = addRow(staticAbility, attacker, QualificationType.CantBeBlocked, snap, bridge, references, rows) ?: continue
                    row.cantBeBlockedByForgeIds.add(ForgeCardId(blocker.id))
                }
            }
        }
    }

    private fun addRow(
        staticAbility: StaticAbility,
        affected: Card,
        qualificationType: QualificationType,
        snap: GsmSnapshot,
        bridge: GameBridge,
        references: ProjectionCardReferences,
        rows: MutableMap<RowKey, Row>,
    ): Row? {
        val sourceParent = sourceParent(staticAbility)
        val sourceParentForgeId = ForgeCardId(sourceParent.id)
        val affectedForgeId = ForgeCardId(affected.id)
        val abilityGrpId = abilityGrpId(staticAbility, sourceParent, snap, bridge, references) ?: return null
        val key = RowKey(sourceParentForgeId.value, affectedForgeId.value, abilityGrpId, qualificationType)
        return rows.getOrPut(key) {
            Row(
                affectorForgeId = sourceParentForgeId,
                affectedForgeId = affectedForgeId,
                sourceParentForgeId = sourceParentForgeId,
                abilityGrpId = abilityGrpId,
                qualificationType = qualificationType,
            )
        }
    }

    private fun abilityGrpId(
        staticAbility: StaticAbility,
        sourceParent: Card,
        snap: GsmSnapshot,
        bridge: GameBridge,
        references: ProjectionCardReferences,
    ): Int? {
        val host = staticAbility.hostCard
        val hostData = cardData(host, snap, references)
        val hostRegistry = bridge.abilityRegistryFor(host, hostData)
        hostRegistry?.forStaticAbility(staticAbility.definitionId)?.let { return it }

        val sourceAbility = host.getEffectSourceAbility()
        if (sourceAbility != null) {
            val sourceData = cardData(sourceParent, snap, references)
            val sourceRegistry = bridge.abilityRegistryFor(sourceParent, sourceData)
            sourceRegistry?.forSpellAbility(sourceAbility.definitionId)?.let { return it }
        }

        return null
    }

    private fun cardData(
        card: Card,
        snap: GsmSnapshot,
        references: ProjectionCardReferences,
    ): CardData? =
        snap.boundCards[ForgeCardId(card.id)]?.data
            ?: references.cardDataByName(card.name)

    private fun sourceParent(staticAbility: StaticAbility): Card = staticAbility.hostCard.getEffectSource() ?: staticAbility.hostCard

    private fun battlefieldCreatures(game: Game): List<Card> = battlefieldCards(game).filter { it.isCreature }.sortedBy { it.id }

    private fun battlefieldCards(game: Game): List<Card> =
        game.players.flatMap { player -> player.getCardsIn(ZoneType.Battlefield) }.sortedBy { it.id }

    private fun liveBattlefieldMatchesSnapshot(
        game: Game,
        snap: GsmSnapshot,
    ): Boolean {
        val liveIds = battlefieldCards(game).map { it.id }.toSet()
        val snapIds =
            snap.zones[ZoneIds.BATTLEFIELD]
                ?.contents
                .orEmpty()
                .map { it.value }
                .toSet()
        return liveIds == snapIds
    }

    private fun attackTargets(
        game: Game,
        attacker: Card,
    ): List<GameEntity> = game.players.filter { it.isOpponentOf(attacker.controller) }

    private fun appliesWithoutSpecificBlocker(
        staticAbility: StaticAbility,
        attacker: Card,
    ): Boolean {
        if (staticAbility.hasParam("ValidBlocker")) return false
        if (staticAbility.hasParam("ValidBlockerRelative")) return false
        if (staticAbility.hasParam("ValidDefender")) return false
        if (staticAbility.hasParam("ValidAttackerRelative")) return false
        return staticAbility.matchesValidParam("ValidAttacker", attacker)
    }

    private fun isBlockerCentric(
        staticAbility: StaticAbility,
        blocker: Card,
    ): Boolean {
        val host = staticAbility.hostCard
        val source = host.getEffectSource()
        return host.id == blocker.id || source?.id == blocker.id
    }
}
