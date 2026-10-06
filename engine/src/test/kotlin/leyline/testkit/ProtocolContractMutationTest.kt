package leyline.testkit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.acceptance.AcceptancePaths
import leyline.acceptance.AcceptanceSuiteLoader
import leyline.acceptance.MatchdoorAcceptanceExecutor
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Regression checks that selected authored contracts reject altered message streams.
 * Each case verifies the unmodified scenario output before applying mutations, so a
 * broken baseline cannot make a rejection pass. Contract discovery belongs to the
 * conformance runner; these examples run in the integration lane.
 */
class ProtocolContractMutationTest :
    FunSpec({
        tags(IntegrationTag)

        fun regression(
            file: String,
            check: (ProtocolContract, List<GREToClientMessage>) -> Unit,
        ) {
            val contract = ProtocolContract.load(AcceptancePaths.resolve("conformance/contracts/$file"))
            test("${contract.name} rejects altered output") {
                val scenario = AcceptanceSuiteLoader.load(contract.suite).scenarios.single { it.id == contract.scenario }
                MatchdoorAcceptanceExecutor().runScenario(scenario) { messages ->
                    withClue(contract.name) {
                        contract.verify(messages)
                        check(contract, messages)
                    }
                } shouldBe scenario.steps.size
            }
        }

        regression("lightning-bolt.yaml", ::checkDamageMutations)
        regression("rabbit-battery-target-selection.yaml", ::checkTargetMutations)
        regression("llanowar-elves.yaml", ::checkManaMutations)
        regression("novice-inspector.yaml") { contract, messages ->
            checkTokenMutations(contract, messages)
            val row = messages.allPersistentAnnotations().single { AnnotationType.TriggeringObject in it.typeList }
            shouldThrow<AssertionError> { contract.verify(messages.withoutRowDeletion(row.id)) }
            checkRowLifetimeMutations(contract, messages, row)
        }
        regression("usher-of-the-fallen.yaml") { contract, messages ->
            checkTokenMutations(contract, messages)
            val stillAvailable =
                messages.mutatingAnnotation(AnnotationType.AbilityExhausted, persistent = true) {
                    it.withIntDetail("UsesRemaining", 1)
                }
            shouldThrow<AssertionError> { contract.verify(stillAvailable) }
        }
        regression("temple-garden.yaml") { contract, messages ->
            val index = messages.indexOfFirst { it.hasOptionalActionMessage() }
            val prompt = messages[index]
            for ((name, mutant) in listOf(
                "wrong optional source" to
                    prompt.toBuilder().setOptionalActionMessage(prompt.optionalActionMessage.toBuilder().setSourceId(0)).build(),
                "wrong optional prompt" to prompt.toBuilder().setPrompt(prompt.prompt.toBuilder().setPromptId(0)).build(),
                "wrong incoming identity" to
                    prompt
                        .toBuilder()
                        .setPrompt(
                            prompt.prompt.toBuilder().setParameters(
                                0,
                                prompt.prompt
                                    .getParameters(0)
                                    .toBuilder()
                                    .setNumberValue(0),
                            ),
                        ).build(),
            )) {
                withClue(name) { shouldThrow<AssertionError> { contract.verify(messages.replacing(index, mutant)) } }
            }
            val wrongPayment = messages.mutatingAnnotation(AnnotationType.ModifiedLife) { it.withIntDetail("life", -1) }
            withClue("wrong life payment") { shouldThrow<AssertionError> { contract.verify(wrongPayment) } }
            val wrongAbility =
                messages.mutatingAnnotation(
                    AnnotationType.ReplacementEffect_803b,
                    persistent = true,
                ) { it.withIntDetail("grpid", 0) }
            withClue("wrong replacement ability") { shouldThrow<AssertionError> { contract.verify(wrongAbility) } }
            val row = messages.allPersistentAnnotations().single { AnnotationType.ReplacementEffect_803b in it.typeList }
            withClue(
                "missing replacement retirement",
            ) { shouldThrow<AssertionError> { contract.verify(messages.withoutRowDeletion(row.id)) } }
            checkRowLifetimeMutations(contract, messages, row)
        }
        regression("lunarch-veteran.yaml") { contract, messages ->
            val wrongZone =
                messages.mutatingAnnotation(AnnotationType.ZoneTransfer_af5a) {
                    if (it.detailString("category") == "CastSpell" &&
                        it.detailInt("zone_src") == 33
                    ) {
                        it.withIntDetail("zone_src", 31)
                    } else {
                        it
                    }
                }
            val wrongIdentity = messages.mutatingAnnotation(AnnotationType.ObjectIdChanged) { it.withIntDetail("new_id", 0) }
            withClue("wrong disturb source zone") { shouldThrow<AssertionError> { contract.verify(wrongZone) } }
            withClue("wrong disturb identity") { shouldThrow<AssertionError> { contract.verify(wrongIdentity) } }
        }
        regression("signaling-roar.yaml") { contract, messages ->
            val wrongZone =
                messages.mutatingAnnotation(AnnotationType.ZoneTransfer_af5a) {
                    if (it.detailString("category") == "Resolve") {
                        it.withIntDetail("zone_dest", 33)
                    } else {
                        it
                    }
                }
            val wrongIdentity = messages.mutatingAnnotation(AnnotationType.ObjectIdChanged) { it.withIntDetail("orig_id", 0) }
            withClue("wrong Omen destination") { shouldThrow<AssertionError> { contract.verify(wrongZone) } }
            withClue("wrong Omen identity") { shouldThrow<AssertionError> { contract.verify(wrongIdentity) } }
        }
    })

private fun checkDamageMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val frameIndex =
        messages.indexOfFirst {
            it.hasGameStateMessage() &&
                it.gameStateMessage.annotationsList.any { a -> AnnotationType.DamageDealt_af5a in a.typeList }
        }
    val frame = messages[frameIndex]
    val damage = frame.gameStateMessage.annotation(AnnotationType.DamageDealt_af5a)
    val wrongDamage =
        damage
            .toBuilder()
            .setDetails(
                damage.detailsList.indexOfFirst { it.key == "damage" },
                damage.detail("damage")!!.toBuilder().setValueInt32(0, 4),
            ).build()
    for ((name, mutant) in listOf(
        "duplicate damage" to
            frame.toBuilder().setGameStateMessage(frame.gameStateMessage.toBuilder().addAnnotations(damage)).build(),
        "wrong amount" to
            frame
                .toBuilder()
                .setGameStateMessage(
                    frame.gameStateMessage.toBuilder().setAnnotations(
                        frame.gameStateMessage.annotationsList.indexOf(damage),
                        wrongDamage,
                    ),
                ).build(),
    )) {
        withClue(name) { shouldThrow<AssertionError> { contract.verify(messages.replacing(frameIndex, mutant)) } }
    }
    val row = messages.allPersistentAnnotations().single { AnnotationType.TargetSpec in it.typeList }
    val birth = messages.indexOfFirst { it.hasGameStateMessage() && row in it.gameStateMessage.persistentAnnotationsList }
    val premature =
        messages.mapIndexed { index, message ->
            if (!message.hasGameStateMessage()) {
                message
            } else {
                val deletions = message.gameStateMessage.diffDeletedPersistentAnnotationIdsList.filter { it != row.id }
                message
                    .toBuilder()
                    .setGameStateMessage(
                        message.gameStateMessage
                            .toBuilder()
                            .clearDiffDeletedPersistentAnnotationIds()
                            .addAllDiffDeletedPersistentAnnotationIds(
                                deletions + if (index == birth) listOf(row.id) else emptyList(),
                            ),
                    ).build()
            }
        }
    withClue("premature target retirement") { shouldThrow<AssertionError> { contract.verify(premature) } }
    checkRowLifetimeMutations(contract, messages, row)
}

private fun checkTargetMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val index = messages.indexOfFirst { it.hasSelectTargetsReq() }
    val prompt = messages[index]
    for ((name, mutant) in listOf(
        "empty target groups" to
            prompt.toBuilder().setSelectTargetsReq(prompt.selectTargetsReq.toBuilder().clearTargets()).build(),
        "wrong source" to prompt.toBuilder().setSelectTargetsReq(prompt.selectTargetsReq.toBuilder().setSourceId(0)).build(),
        "wrong undo flag" to prompt.toBuilder().setAllowUndo(true).build(),
        "wrong prompt parameter" to
            prompt
                .toBuilder()
                .setSelectTargetsReq(
                    prompt.selectTargetsReq.toBuilder().setTargets(
                        0,
                        prompt.selectTargetsReq.getTargets(0).toBuilder().setPrompt(
                            prompt.selectTargetsReq.getTargets(0).prompt.toBuilder().setParameters(
                                0,
                                prompt.selectTargetsReq
                                    .getTargets(0)
                                    .prompt
                                    .getParameters(0)
                                    .toBuilder()
                                    .setNumberValue(0),
                            ),
                        ),
                    ),
                ).build(),
    )) {
        withClue(name) { shouldThrow<AssertionError> { contract.verify(messages.replacing(index, mutant)) } }
    }
    val row = messages.allPersistentAnnotations().single { AnnotationType.TargetSpec in it.typeList }
    withClue("missing target retirement") { shouldThrow<AssertionError> { contract.verify(messages.withoutRowDeletion(row.id)) } }
    checkRowLifetimeMutations(contract, messages, row)
}

private fun checkRowLifetimeMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
    row: AnnotationInfo,
) {
    val birth = messages.indexOfFirst { it.hasGameStateMessage() && row in it.gameStateMessage.persistentAnnotationsList }
    val frame = messages[birth]

    fun rowMessage(value: AnnotationInfo): GREToClientMessage =
        frame.toBuilder().setGameStateMessage(GameStateMessage.newBuilder().addPersistentAnnotations(value)).build()
    val update =
        rowMessage(
            row
                .toBuilder()
                .clearAffectedIds()
                .addAffectedIds(0)
                .build(),
        )
    val changed = messages.toMutableList().also { it.add(birth + 1, update) }
    withClue("contradictory row update") { shouldThrow<AssertionError> { contract.verify(changed) } }
    withClue("retired row reintroduced") { shouldThrow<AssertionError> { contract.verify(messages + rowMessage(row)) } }
}

private fun checkManaMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val untapped = messages.mutatingAnnotation(AnnotationType.TappedUntappedPermanent) { it.withIntDetail("tapped", 0) }
    val wrongSource = messages.mutatingAnnotation(AnnotationType.ManaPaid) { it.toBuilder().setAffectorId(0).build() }
    val wrongAbility = messages.mutatingAnnotation(AnnotationType.UserActionTaken) { it.withIntDetail("abilityGrpId", 0) }
    for ((name, mutant) in listOf(
        "untapped source" to untapped,
        "wrong payment source" to wrongSource,
        "wrong mana ability" to wrongAbility,
    )) {
        withClue(name) { shouldThrow<AssertionError> { contract.verify(mutant) } }
    }
}

private fun checkTokenMutations(
    contract: ProtocolContract,
    messages: List<GREToClientMessage>,
) {
    val index =
        messages.indexOfFirst {
            it.hasGameStateMessage() &&
                it.gameStateMessage.gameObjectsList.any { obj -> obj.type == GameObjectType.Token }
        }
    val frame = messages[index]
    val tokenIndex = frame.gameStateMessage.gameObjectsList.indexOfFirst { it.type == GameObjectType.Token }
    val token = frame.gameStateMessage.getGameObjects(tokenIndex)
    for ((name, mutant) in listOf(
        "wrong token parent" to token.toBuilder().setParentId(0).build(),
        "wrong token source" to token.toBuilder().setObjectSourceGrpId(0).build(),
    )) {
        val changed = frame.toBuilder().setGameStateMessage(frame.gameStateMessage.toBuilder().setGameObjects(tokenIndex, mutant)).build()
        withClue(name) { shouldThrow<AssertionError> { contract.verify(messages.replacing(index, changed)) } }
    }
}

private fun List<GREToClientMessage>.mutatingAnnotation(
    type: AnnotationType,
    persistent: Boolean = false,
    mutate: (AnnotationInfo) -> AnnotationInfo,
): List<GREToClientMessage> =
    map { message ->
        if (!message.hasGameStateMessage()) {
            message
        } else {
            val gsm = message.gameStateMessage
            val annotations = if (persistent) gsm.persistentAnnotationsList else gsm.annotationsList
            val changed = annotations.map { if (type in it.typeList) mutate(it) else it }
            val builder = gsm.toBuilder()
            if (persistent) {
                builder.clearPersistentAnnotations().addAllPersistentAnnotations(
                    changed,
                )
            } else {
                builder.clearAnnotations().addAllAnnotations(changed)
            }
            message.toBuilder().setGameStateMessage(builder).build()
        }
    }

private fun AnnotationInfo.withIntDetail(
    key: String,
    value: Int,
): AnnotationInfo =
    toBuilder().setDetails(detailsList.indexOfFirst { it.key == key }, detail(key)!!.toBuilder().setValueInt32(0, value)).build()

private fun List<GREToClientMessage>.withoutRowDeletion(id: Int): List<GREToClientMessage> =
    map { message ->
        if (!message.hasGameStateMessage()) {
            message
        } else {
            message
                .toBuilder()
                .setGameStateMessage(
                    message.gameStateMessage.toBuilder().clearDiffDeletedPersistentAnnotationIds().addAllDiffDeletedPersistentAnnotationIds(
                        message.gameStateMessage.diffDeletedPersistentAnnotationIdsList.filter { it != id },
                    ),
                ).build()
        }
    }

private fun List<GREToClientMessage>.replacing(
    index: Int,
    message: GREToClientMessage,
): List<GREToClientMessage> = toMutableList().also { it[index] = message }
