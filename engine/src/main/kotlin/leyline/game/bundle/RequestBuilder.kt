package leyline.game.bundle

import forge.game.Game
import forge.game.card.Card
import forge.game.combat.Combat
import forge.game.combat.CombatUtil
import forge.game.cost.CostExert
import forge.game.player.Player
import forge.game.staticability.StaticAbilityMustAttack
import forge.game.staticability.StaticAbilityMustBlock
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
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

    private fun attackWarning(
        instanceId: Int,
        type: AttackWarningType,
        promptId: Int,
    ): AttackWarning =
        AttackWarning
            .newBuilder()
            .setInstanceId(instanceId)
            .setType(type)
            .setWarningPromptId(promptId)
            .build()

    private fun blockWarning(
        instanceId: Int,
        type: BlockWarningType,
        promptId: Int,
    ): BlockWarning =
        BlockWarning
            .newBuilder()
            .setInstanceId(instanceId)
            .setType(type)
            .setWarningPromptId(promptId)
            .build()

    private fun hasCannotAttackAlone(card: Card): Boolean =
        card.hasKeyword("CARDNAME can't attack or block alone.") || card.hasKeyword("CARDNAME can't attack alone.")

    private fun hasCannotBlockAlone(card: Card): Boolean =
        card.hasKeyword("CARDNAME can't attack or block alone.") || card.hasKeyword("CARDNAME can't block alone.")

    private fun hasMustBeBlocked(card: Card): Boolean =
        card.hasKeyword("CARDNAME must be blocked if able.") ||
            card.hasKeyword("CARDNAME must be blocked by exactly one creature if able.") ||
            card.hasKeyword("CARDNAME must be blocked by two or more creatures if able.") ||
            card.keywords.any { it.original.startsWith("MustBeBlockedBy ") }

    private fun hasMustBeBlockedByAll(card: Card): Boolean =
        card.keywords.any { it.original.startsWith("MustBeBlockedByAll") } ||
            card.hasKeyword("All creatures able to block CARDNAME do so.")

    /** A shared requirement to block an attacker is not a requirement on every candidate blocker. */
    private fun hasIndividualBlockRequirement(
        blocker: Card,
        legalAttackers: List<Card>,
    ): Boolean {
        val costFreeAttackers = legalAttackers.filter { CombatUtil.getBlockCost(blocker.game, blocker, it) == null }
        if (costFreeAttackers.isEmpty()) return false
        return StaticAbilityMustBlock.blocksEachCombatIfAble(blocker) ||
            costFreeAttackers.any { attacker ->
                attacker in blocker.mustBlockCards ||
                    attacker.hasKeyword("All creatures able to block CARDNAME do so.") ||
                    attacker.keywords.any { keyword ->
                        val text = keyword.original
                        text.startsWith("MustBeBlockedByAll:") && blocker.isValid(text.substringAfter(':'), null, null, null)
                    }
            }
    }

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
                    is Card -> add(planeswalkerDamageRecipient(defender, bridge))
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
     * Each attacker includes legal player and permanent damage recipients.
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
        val combat = player.game.phaseHandler.combat ?: Combat(player)
        val globalRestrictions = combat.attackConstraints.globalRestrictions
        val globalMustAttack = !StaticAbilityMustAttack.mustAttackSpecific(player, combat.defenders).isEmpty
        if (globalMustAttack) hasRequirements = true

        // `CannotBeAttackedByMoreThanOne` is keyed by the defender, not by an
        // attacker. The client uses it to remove that defender from the common
        // target set while selecting multiple attackers.
        for ((defender, maxAttackers) in globalRestrictions.defenderMax) {
            if (maxAttackers == 1 && defender is Card) {
                builder.addAttackWarnings(
                    attackWarning(
                        bridge.instanceId(defender),
                        AttackWarningType.CannotBeAttackedByMoreThanOne,
                        0,
                    ),
                )
            }
        }
        val hasAttackRestrictions =
            globalRestrictions.max != null ||
                globalRestrictions.defenderMax.isNotEmpty() ||
                combat.attackConstraints.restrictions.values.any {
                    it.types.isNotEmpty()
                }

        for (card in player.getCardsIn(ForgeZoneType.Battlefield)) {
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

            // These are current-state warnings. A requirement warning remains
            // until the player satisfies it in the echoed request; a warning
            // for an already selected attacker would incorrectly keep Submit
            // disabled. `CannotAttackAlone` is only the exact Arena warning
            // for Forge's NOT_ALONE restriction; ONLY_ALONE and the
            // power/color/two-others restrictions have no faithful wire value.
            if (mustAttack && !isCommitted) {
                builder.addAttackWarnings(
                    attackWarning(instanceId, AttackWarningType.MustAttack, PromptIds.WARNING_MUST_ATTACK),
                )
            }
            if (isCommitted && committedAttackerIds.size == 1 && hasCannotAttackAlone(card)) {
                builder.addAttackWarnings(
                    attackWarning(
                        instanceId,
                        AttackWarningType.CannotAttackAlone,
                        PromptIds.WARNING_ATTACKER_CANNOT_ATTACK_ALONE,
                    ),
                )
            }

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
        if (globalMustAttack && committedAttackerIds.isEmpty()) {
            // No card owns a player-level warning, so Arena uses the neutral
            // instance id while the AI consumes the warning by type.
            builder.addAttackWarnings(
                attackWarning(0, AttackWarningType.MustAttackWithAtLeastOne, PromptIds.WARNING_MUST_ATTACK_WITH_AT_LEAST_ONE),
            )
        }
        builder.setCanSubmitAttackers(true)
        builder.setHasRequirements(hasRequirements)
        builder.setHasRestrictions(hasAttackRestrictions)
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
        val assignedBlockCount = blockerAssignments.size
        val blockersByAttacker = blockerAssignments.values.groupingBy { it }.eachCount()
        var hasBlockRestrictions = false

        for (card in player.getCardsIn(ForgeZoneType.Battlefield)) {
            if (!card.isCreature) continue
            if (!CombatUtil.canBlock(card, combat)) continue

            // Per-attacker legality: only list attackers this creature can legally block
            // (handles flying/reach, menace, protection, etc.)
            val legalAttackers = combat.attackers.filter { CombatUtil.canBlock(it, card) }
            if (legalAttackers.isEmpty()) continue

            val instanceId = bridge.instanceId(card)
            val assignedAttacker = blockerAssignments[instanceId]
            // Forge's aggregate mustBlockAnAttacker also reports any candidate
            // that could satisfy "must be blocked". Arena's per-blocker flag
            // instead means this specific creature is required to block.
            val mustBlock = hasIndividualBlockRequirement(card, legalAttackers)
            if (mustBlock) hasRequirements = true
            if (hasCannotBlockAlone(card) || card.hasKeyword("CARDNAME can't block unless at least two other creatures block.")) {
                hasBlockRestrictions = true
            }
            if (assignedAttacker != null && assignedBlockCount == 1 && hasCannotBlockAlone(card)) {
                builder.addBlockWarnings(
                    blockWarning(
                        instanceId,
                        BlockWarningType.CannotBlockAlone,
                        PromptIds.WARNING_BLOCKER_CANNOT_BLOCK_ALONE,
                    ),
                )
            }
            if (mustBlock && assignedAttacker == null) {
                builder.addBlockWarnings(
                    blockWarning(instanceId, BlockWarningType.MustBlock, PromptIds.WARNING_MUST_BLOCK),
                )
            }
            val blocker =
                Blocker
                    .newBuilder()
                    .setBlockerInstanceId(instanceId)
                    .setMaxAttackers(1)
                    .setMustBlock(mustBlock)

            if (assignedAttacker != null) {
                blocker.addSelectedAttackerInstanceIds(assignedAttacker)
            } else {
                val legalAttackerIds = legalAttackers.map(bridge::instanceId)
                blocker.addAllAttackerInstanceIds(legalAttackerIds)
            }
            builder.addBlockers(blocker)
        }

        // Warnings keyed by attackers describe requirements imposed by the
        // defending player. Only minimum-two (or more) requirements have an
        // exact Arena warning type; the ordinary minimum of one is not a
        // warning because an unblocked attacker is a legal choice.
        for (attacker in combat.attackers) {
            val attackerId = bridge.instanceId(attacker)
            val assigned = blockersByAttacker[attackerId] ?: 0
            val minBlockers = CombatUtil.getMinNumBlockersForAttacker(attacker, player)
            if (minBlockers > 1) {
                hasBlockRestrictions = true
                // Menace restricts a chosen block; it never requires one.
                // Arena disables Submit for every warning, so warning at zero
                // would forbid the legal decision to take combat damage.
                if (assigned in 1 until minBlockers) {
                    builder.addBlockWarnings(
                        blockWarning(attackerId, BlockWarningType.InsufficientBlockers, PromptIds.WARNING_INSUFFICIENT_BLOCKERS),
                    )
                }
            }

            if (assigned == 0 && CombatUtil.canBeBlocked(attacker, combat, player)) {
                if (hasMustBeBlocked(attacker)) {
                    hasRequirements = true
                    builder.addBlockWarnings(
                        blockWarning(attackerId, BlockWarningType.MustBeBlocked, PromptIds.WARNING_ATTACKER_MUST_BE_BLOCKED),
                    )
                }
                if (hasMustBeBlockedByAll(attacker)) {
                    hasRequirements = true
                    builder.addBlockWarnings(
                        blockWarning(
                            attackerId,
                            BlockWarningType.MustBeBlockedByAll,
                            PromptIds.WARNING_ATTACKER_MUST_BE_BLOCKED_BY_ALL,
                        ),
                    )
                }
            }
        }
        builder.setHasRequirements(hasRequirements)
        builder.setHasRestrictions(hasBlockRestrictions)
        // Conformance: client expects empty manaCost
        builder.addManaCost(ManaRequirement.getDefaultInstance())

        log.info("buildDeclareBlockersReq: seat={} blockers={} assigned={}", seatId, builder.blockersCount, blockerAssignments.size)
        return builder.build()
    }
}
