package leyline.game.bundle

import forge.game.Game
import forge.game.card.Card
import forge.game.combat.CombatUtil
import forge.game.cost.CostExert
import forge.game.player.Player
import forge.game.staticability.StaticAbilityMustAttack
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.data.KeywordAbilityIds
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Builds outbound interactive request protos (targeting, selectN, combat).
 *
 * Pure proto construction from game state — no session state, no sending.
 * [leyline.match.CombatHandler] and [leyline.match.TargetingHandler]
 * handle the inbound responses.
 */
@Suppress("LargeClass") // One object mirrors the interactive request proto surface.
object RequestBuilder {
    private val log = LoggerFactory.getLogger(RequestBuilder::class.java)

    /** Build the [SearchReq] fields for a library search.
     *
     *  [sourceInstanceId] — `searchReq.sourceId`.
     *
     */
    @Suppress("LongParameterList")
    fun buildSearchRequest(
        sourceInstanceId: Int,
        libraryZoneId: Int,
        allLibraryIds: List<Int>,
        validTargetIds: List<Int>,
        maxFind: Int = 1,
        allowFailToFind: Boolean = true,
    ): SearchReq {
        val searchReq =
            SearchReq
                .newBuilder()
                .setMaxFind(maxFind)
                .addZonesToSearch(libraryZoneId)
                .addAllItemsToSearch(allLibraryIds)
                .addAllItemsSought(validTargetIds)
                .setSourceId(sourceInstanceId)
        if (allowFailToFind) {
            searchReq.setAllowFailToFind(AllowFailToFind.Any)
        }
        return searchReq.build()
    }

    private fun playerDamageRecipient(seatId: SeatId): DamageRecipient =
        DamageRecipient
            .newBuilder()
            .setType(DamageRecType.Player_a0e5)
            .setPlayerSystemSeatId(seatId.opponent.value)
            .build()

    private fun planeswalkerDamageRecipient(
        card: Card,
        bridge: GameBridge,
    ): DamageRecipient =
        DamageRecipient
            .newBuilder()
            .setType(DamageRecType.PlanesWalker)
            .setPlaneswalkerInstanceId(bridge.instanceId(card))
            .build()

    private fun legalAttackDamageRecipients(
        player: Player,
        card: Card,
        seatId: SeatId,
        bridge: GameBridge,
    ): List<DamageRecipient> =
        buildList {
            for (defender in CombatUtil.getAllPossibleDefenders(player)) {
                if (!CombatUtil.canAttack(card, defender)) continue
                when (defender) {
                    is Player -> add(playerDamageRecipient(seatId))
                    is Card -> if (defender.isPlaneswalker) add(planeswalkerDamageRecipient(defender, bridge))
                }
            }
        }

    private fun selectedAttackDamageRecipient(
        instanceId: Int,
        seatId: SeatId,
        committedDamageRecipients: Map<Int, DamageRecipient>,
    ): DamageRecipient = committedDamageRecipients[instanceId] ?: playerDamageRecipient(seatId)

    private fun buildAttackerOption(
        instanceId: Int,
        legalRecipients: List<DamageRecipient>,
        alternativeGrpId: Int = 0,
        mustAttack: Boolean = false,
    ): Attacker.Builder =
        Attacker
            .newBuilder()
            .setAttackerInstanceId(instanceId)
            .addAllLegalDamageRecipients(legalRecipients)
            .setMustAttack(mustAttack)
            .apply {
                if (alternativeGrpId != 0) setAlternativeGrpId(alternativeGrpId)
            }

    /**
     * Build [DeclareAttackersReq] listing all creatures that can legally attack.
     * Each attacker includes legal damage recipients (opponent player and planeswalkers).
     *
     * @param committedAttackerIds instanceIds of attackers already selected (echo-back).
     *   Committed attackers get [selectedDamageRecipient] set to their chosen recipient.
     * @param committedAttackAlternatives selected attack alternative per attacker; 0 means normal attack.
     *   Initial request passes empty set (no pre-selection).
     */
    fun buildDeclareAttackersReq(
        seatId: SeatId,
        bridge: GameBridge,
        committedAttackerIds: Set<Int> = emptySet(),
        committedAttackAlternatives: Map<Int, Int> = emptyMap(),
        committedDamageRecipients: Map<Int, DamageRecipient> = emptyMap(),
    ): DeclareAttackersReq {
        val player = bridge.getPlayer(seatId) ?: return DeclareAttackersReq.getDefaultInstance()
        val builder = DeclareAttackersReq.newBuilder()
        var hasRequirements = false

        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            if (!CombatUtil.canAttack(card)) continue

            val instanceId = bridge.instanceId(card)
            val hasEnlist = card.hasKeyword("Enlist")
            val hasExert = card.staticAbilities.any { it.hasAttackCost(card, CostExert::class.java) }
            val isCommitted = instanceId in committedAttackerIds
            val selectedAlternativeGrpId = committedAttackAlternatives[instanceId] ?: 0
            val legalRecipients = legalAttackDamageRecipients(player, card, seatId, bridge)
            if (legalRecipients.isEmpty()) continue
            // Forge's own InputAttack nags the player the same way: a
            // creature entitiesMustAttack() lists is forced into combat.
            val mustAttack = StaticAbilityMustAttack.entitiesMustAttack(card).isNotEmpty()
            if (mustAttack) hasRequirements = true

            val attacker = buildAttackerOption(instanceId, legalRecipients, mustAttack = mustAttack)
            if (isCommitted && selectedAlternativeGrpId == 0) {
                attacker.setSelectedDamageRecipient(selectedAttackDamageRecipient(instanceId, seatId, committedDamageRecipients))
            }
            builder.addAttackers(attacker)

            if (hasEnlist) {
                val enlistAttacker = buildAttackerOption(instanceId, legalRecipients, KeywordAbilityIds.ENLIST, mustAttack)
                if (isCommitted && selectedAlternativeGrpId == KeywordAbilityIds.ENLIST) {
                    enlistAttacker.setSelectedDamageRecipient(selectedAttackDamageRecipient(instanceId, seatId, committedDamageRecipients))
                }
                builder.addAttackers(enlistAttacker)
            }
            if (hasExert) {
                val exertAttacker = buildAttackerOption(instanceId, legalRecipients, KeywordAbilityIds.EXERT, mustAttack)
                if (isCommitted && selectedAlternativeGrpId == KeywordAbilityIds.EXERT) {
                    exertAttacker.setSelectedDamageRecipient(selectedAttackDamageRecipient(instanceId, seatId, committedDamageRecipients))
                }
                builder.addAttackers(exertAttacker)
            }

            // qualifiedAttackers never has selectedDamageRecipient
            builder.addQualifiedAttackers(buildAttackerOption(instanceId, legalRecipients, mustAttack = mustAttack))
            if (hasEnlist) {
                builder.addQualifiedAttackers(
                    buildAttackerOption(instanceId, legalRecipients, KeywordAbilityIds.ENLIST, mustAttack),
                )
            }
            if (hasExert) {
                builder.addQualifiedAttackers(
                    buildAttackerOption(instanceId, legalRecipients, KeywordAbilityIds.EXERT, mustAttack),
                )
            }
        }
        builder.setCanSubmitAttackers(true)
        builder.setHasRequirements(hasRequirements)
        // Conformance: client expects an empty manaCost entry entry.
        builder.addManaCost(ManaRequirement.getDefaultInstance())

        log.info("buildDeclareAttackersReq: seat={} attackers={} committed={}", seatId, builder.attackersCount, committedAttackerIds.size)
        return builder.build()
    }

    /**
     * Build [DeclareBlockersReq] listing all creatures that can legally block.
     *
     * @param blockerAssignments committed blocker→attacker assignments (instanceIds).
     *   Committed blockers get `selectedAttackerInstanceIds` set and `attackerInstanceIds`
     *   cleared. Uncommitted blockers get `attackerInstanceIds` (available targets).
     */
    fun buildDeclareBlockersReq(
        game: Game,
        seatId: SeatId,
        bridge: GameBridge,
        blockerAssignments: Map<Int, Int> = emptyMap(),
    ): DeclareBlockersReq {
        val player = bridge.getPlayer(seatId) ?: return DeclareBlockersReq.getDefaultInstance()
        val combat = game.phaseHandler.combat ?: return DeclareBlockersReq.getDefaultInstance()
        val builder = DeclareBlockersReq.newBuilder()
        var hasRequirements = false

        for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
            if (!card.isCreature) continue
            if (!CombatUtil.canBlock(card, combat)) continue

            // Per-attacker legality: only list attackers this creature can legally block
            // (handles flying/reach, menace, protection, etc.)
            val legalAttackers = combat.attackers.filter { CombatUtil.canBlock(it, card) }
            if (legalAttackers.isEmpty()) continue

            val instanceId = bridge.instanceId(card)
            // Same check Forge's own InputBlock/validateBlocks uses to nag the
            // player: this creature is forced to block one of its requirement
            // attackers unless something already excuses it (lure satisfied, etc.).
            val mustBlock = CombatUtil.mustBlockAnAttacker(card, combat, null)
            if (mustBlock) hasRequirements = true
            val blocker =
                Blocker
                    .newBuilder()
                    .setBlockerInstanceId(instanceId)
                    .setMaxAttackers(1)
                    .setMustBlock(mustBlock)

            val assignedAttacker = blockerAssignments[instanceId]
            if (assignedAttacker != null) {
                blocker.addSelectedAttackerInstanceIds(assignedAttacker)
            } else {
                val legalAttackerIds = legalAttackers.map(bridge::instanceId)
                blocker.addAllAttackerInstanceIds(legalAttackerIds)
            }
            builder.addBlockers(blocker)
        }
        builder.setHasRequirements(hasRequirements)
        // Conformance: client expects empty manaCost
        builder.addManaCost(ManaRequirement.getDefaultInstance())

        log.info("buildDeclareBlockersReq: seat={} blockers={} assigned={}", seatId, builder.blockersCount, blockerAssignments.size)
        return builder.build()
    }
}
