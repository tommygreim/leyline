package leyline.testkit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType
import wotc.mtgo.gre.external.messaging.Messages.OptionalActionMessage
import wotc.mtgo.gre.external.messaging.Messages.OrderReq
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

/** Pure checker regressions over synthetic messages, independent of gameplay execution. */
class ProtocolContractTest :
    FunSpec({
        tags(UnitTag)

        test("malformed specs fail loading rather than weaken assertions") {
            for (invalid in listOf(
                contractText.replace("fields:", "fieldz:"),
                contractText.replace("details.damage", "unknown.damage"),
                contractText.replace("fields: {details.damage: [3]}", "equals: {affectorId: missing.affectorId}"),
                contractText.replace("type: DamageDealt", "type: UnknownEvent"),
                contractText.replace("type: DamageDealt", "type: DamageDealt\n            lane: object"),
                contractText.replace("type: DamageDealt", "type: DamageDealt\n            op: delete"),
                contractText.replace("name: damage", "name: damage\nname: duplicate"),
                contractText.replace("fields: {details.damage: [3]}", "fields: null"),
                contractText.replace("fields:", "where: null\n        fields:"),
                contractText.replace("fields:", "where: {fields: null}\n        fields:"),
                contractText + "\ncounts: null",
            )) {
                shouldThrowAny { ProtocolContract.parse(invalid) }
            }
        }

        test("co-occurrence and order are required within each frame") {
            val contract = ProtocolContract.parse(contractText)
            contract.verify(listOf(frame(start, damage)))
            shouldThrow<AssertionError> { contract.verify(listOf(frame(start), frame(damage))) }
            shouldThrow<AssertionError> { contract.verify(listOf(frame(damage, start))) }
        }

        test("a later correct event cannot hide an earlier contradictory event") {
            val wrong = damage.toBuilder().setDetails(0, damage.getDetails(0).toBuilder().setValueInt32(0, 4)).build()
            shouldThrow<AssertionError> { ProtocolContract.parse(contractText).verify(listOf(frame(start, wrong, damage))) }
        }

        test("explicit selection skips other interactions but keeps the first selected event strict") {
            val contract =
                ProtocolContract.parse(
                    contractText.replace(
                        "fields: {details.damage: [3]}",
                        "where: {equals: {affectorId: start.affectorId}}\n        fields: {details.damage: [3]}",
                    ),
                )
            val unrelated = damage.toBuilder().setAffectorId(1).build()
            contract.verify(listOf(frame(start, unrelated, damage)))
            val wrong = damage.toBuilder().setDetails(0, damage.getDetails(0).toBuilder().setValueInt32(0, 4)).build()
            shouldThrow<AssertionError> { contract.verify(listOf(frame(start, unrelated, wrong, damage))) }
            shouldThrow<IllegalArgumentException> {
                ProtocolContract.parse(
                    contractText.replace("fields:", "where: {equals: {affectorId: missing.affectorId}}\n        fields:"),
                )
            }
        }

        test("the first selected start cannot skip contradictory fields") {
            val contract =
                ProtocolContract.parse(
                    """
                    name: selected start
                    scenario: {suite: warmup, id: land-spell-face}
                    frames:
                      - id: damage
                        events:
                          - id: damage
                            type: DamageDealt
                            where: {fields: {affectorId: 0}}
                            fields: {details.damage: [3]}
                    """.trimIndent(),
                )
            val unrelated = damage.toBuilder().setAffectorId(1).build()
            val wrong = damage.toBuilder().setDetails(0, damage.getDetails(0).toBuilder().setValueInt32(0, 4)).build()
            contract.verify(listOf(frame(unrelated, damage)))
            shouldThrow<AssertionError> { contract.verify(listOf(frame(unrelated, wrong, damage))) }
        }

        test("typed values and exact counts reject superficially similar output") {
            val text = contractText + "\ncounts:\n  - match: {type: DamageDealt}\n    exactly: 1\n"
            val contract = ProtocolContract.parse(text)
            contract.verify(listOf(frame(start, damage)))
            shouldThrow<AssertionError> { contract.verify(listOf(frame(start, damage, damage))) }
            val stringDamage =
                damage
                    .toBuilder()
                    .setDetails(
                        0,
                        KeyValuePairInfo
                            .newBuilder()
                            .setKey("damage")
                            .setType(KeyValuePairValueType.String)
                            .addValueString("3"),
                    ).build()
            shouldThrow<AssertionError> { contract.verify(listOf(frame(start, stringDamage))) }
        }

        test("optional decisions preserve prompt fields and reject incorrect sources") {
            val contract =
                ProtocolContract.parse(
                    """
                    name: optional decision
                    scenario: {suite: modal-warmup, id: shock-land-temple-garden}
                    frames:
                      - id: decision
                        events:
                          - id: decision
                            type: OptionalActionMessage
                            lane: prompt
                            fields: {raw.prompt.promptId: 2233, raw.optionalActionMessage.sourceId: 99}
                    """.trimIndent(),
                )
            val prompt =
                GREToClientMessage
                    .newBuilder()
                    .setPrompt(Prompt.newBuilder().setPromptId(2233))
                    .setOptionalActionMessage(OptionalActionMessage.newBuilder().setSourceId(99))
                    .build()
            contract.verify(listOf(prompt))
            shouldThrow<AssertionError> {
                contract.verify(
                    listOf(prompt.toBuilder().setOptionalActionMessage(prompt.optionalActionMessage.toBuilder().setSourceId(0)).build()),
                )
            }
            shouldThrow<AssertionError> { contract.verify(listOf(prompt.toBuilder().clearOptionalActionMessage().build())) }
        }
        test("persistent rows preserve their protocol name and identity through retirement") {
            val contract =
                ProtocolContract.parse(
                    """
                    name: replacement lifecycle
                    scenario: {suite: modal-warmup, id: shock-land-temple-garden}
                    frames:
                      - id: introduction
                        events:
                          - id: row
                            type: ReplacementEffect
                            lane: persistent
                            op: create
                      - id: retirement
                        events:
                          - id: retired
                            type: ReplacementEffect
                            lane: persistent
                            op: delete
                            sameRow: row
                    counts:
                      - match: {type: ReplacementEffect, lane: persistent, op: create, sameRow: row}
                        exactly: 1
                      - match: {type: ReplacementEffect, lane: persistent, op: delete, sameRow: row}
                        exactly: 1
                    """.trimIndent(),
                )
            val row =
                AnnotationInfo
                    .newBuilder()
                    .addType(AnnotationType.ReplacementEffect_803b)
                    .setId(7)
                    .build()
            val introduced =
                GREToClientMessage
                    .newBuilder()
                    .setGameStateMessage(
                        GameStateMessage.newBuilder().addPersistentAnnotations(row),
                    ).build()
            val retired =
                GREToClientMessage
                    .newBuilder()
                    .setGameStateMessage(
                        GameStateMessage.newBuilder().addDiffDeletedPersistentAnnotationIds(row.id),
                    ).build()
            contract.verify(listOf(introduced, retired))
            contract.verify(listOf(introduced, introduced, retired))
            val wrongRetirement =
                retired
                    .toBuilder()
                    .setGameStateMessage(
                        retired.gameStateMessage.toBuilder().setDiffDeletedPersistentAnnotationIds(0, 8),
                    ).build()
            for (messages in listOf(
                listOf(introduced, retired, introduced),
                listOf(introduced, retired, retired),
                listOf(introduced, wrongRetirement),
            )) {
                shouldThrow<AssertionError> { contract.verify(messages) }
            }
        }
        test("bounded absence checks same-message events and ignores endpoints and unrelated identities") {
            val contract =
                ProtocolContract.parse(
                    windowContractText +
                        """

                        windows:
                          - id: no-damage
                            after: start
                            before: end
                            absent: {type: DamageDealt, equals: {affectorId: start.affectorId}}
                        """.trimIndent(),
                )
            contract.verify(listOf(frame(damage, start), frame(end, damage)))
            contract.verify(listOf(frame(start, damage.toBuilder().setAffectorId(99).build(), end)))
            shouldThrow<AssertionError> { contract.verify(listOf(frame(start, damage, end))) }
            shouldThrow<AssertionError> { contract.verify(listOf(frame(start), frame(damage), frame(end))) }
        }
        test("bounded counts reject missing and extra events without counting outside the anchors") {
            val text =
                windowContractText +
                    """

                    windows:
                      - id: one-damage
                        after: start
                        before: end
                        count: {type: DamageDealt}
                        exactly: 1
                    """.trimIndent()
            val contract = ProtocolContract.parse(text)
            contract.verify(listOf(frame(damage, start, damage, end, damage)))
            shouldThrow<AssertionError> { contract.verify(listOf(frame(start, end))) }
            shouldThrow<AssertionError> { contract.verify(listOf(frame(start, damage, damage, end))) }
            val zero = ProtocolContract.parse(text.replace("exactly: 1", "exactly: 0"))
            zero.verify(listOf(frame(start, end)))
            shouldThrow<AssertionError> { zero.verify(listOf(frame(start, damage, end))) }
        }
        test("a held row may retire at the endpoint but cannot retire and reappear earlier") {
            val contract =
                ProtocolContract.parse(
                    """
                    name: held row
                    scenario: {suite: warmup, id: land-spell-face}
                    frames:
                      - id: row
                        events:
                          - id: row
                            type: ReplacementEffect
                            lane: persistent
                            op: create
                      - id: end
                        events:
                          - id: end
                            type: ReplacementEffect
                            lane: persistent
                            op: delete
                            sameRow: row
                    windows:
                      - id: lifetime
                        holds: row
                        until: end
                    """.trimIndent(),
                )
            val row =
                AnnotationInfo
                    .newBuilder()
                    .addType(AnnotationType.ReplacementEffect_803b)
                    .setId(7)
                    .build()
            val created =
                GREToClientMessage
                    .newBuilder()
                    .setGameStateMessage(
                        GameStateMessage.newBuilder().addPersistentAnnotations(row).addPersistentAnnotations(row.toBuilder().setId(8)),
                    ).build()
            val deleted =
                GREToClientMessage
                    .newBuilder()
                    .setGameStateMessage(
                        GameStateMessage.newBuilder().addDiffDeletedPersistentAnnotationIds(7),
                    ).build()
            val unrelated =
                deleted
                    .toBuilder()
                    .setGameStateMessage(
                        GameStateMessage.newBuilder().addDiffDeletedPersistentAnnotationIds(8),
                    ).build()
            contract.verify(listOf(created, unrelated, deleted))
            val ending =
                ProtocolContract.parse(
                    """
                    name: held until resolution
                    scenario: {suite: warmup, id: land-spell-face}
                    frames:
                      - id: row
                        events:
                          - id: row
                            type: ReplacementEffect
                            lane: persistent
                            op: create
                      - id: resolution
                        events:
                          - id: end
                            type: ResolutionComplete
                    windows:
                      - id: lifetime
                        holds: row
                        until: end
                    """.trimIndent(),
                )
            ending.verify(listOf(created, frame(end), deleted))
            shouldThrow<AssertionError> { ending.verify(listOf(created, deleted, created, frame(end))) }
        }
        test("invalid temporal clauses fail parsing") {
            val valid =
                """
                windows:
                  - id: window
                    after: start
                    before: end
                    count: {type: DamageDealt}
                    exactly: 1
                """.trimIndent()
            for (invalid in listOf(
                valid.replace("after: start", "after: missing"),
                valid.replace("before: end", "before: start"),
                valid.replace("after: start", "after: end").replace("before: end", "before: start"),
                valid.replace("exactly: 1", "exactly: -1"),
                valid.replace("exactly: 1", "exactly: null"),
                valid.replace("count:", "absent:"),
                valid.replace("count:", "unknown:"),
                valid.replace("count: {type: DamageDealt}", "count: {type: DamageDealt, fields: {unknown.value: 1}}"),
                "windows: [{id: lifetime, holds: start, until: end}]",
                valid + "\n  - id: window\n    after: start\n    before: end\n    count: {type: DamageDealt}\n    exactly: 1",
                "windows: null",
            )) {
                shouldThrowAny { ProtocolContract.parse(windowContractText + "\n" + invalid) }
            }
        }
        test("raw enum names omit protobuf collision suffixes without accepting another value") {
            val contract =
                ProtocolContract.parse(
                    """
                    name: cancellation enum
                    scenario: {suite: warmup, id: land-spell-face}
                    frames:
                      - id: prompt
                        events:
                          - id: prompt
                            type: ActionsAvailableReq
                            lane: prompt
                            fields: {raw.allowCancel: "No"}
                    """.trimIndent(),
                )
            val prompt =
                GREToClientMessage
                    .newBuilder()
                    .setActionsAvailableReq(ActionsAvailableReq.getDefaultInstance())
                    .setAllowCancel(AllowCancel.No_a526)
                    .build()
            contract.verify(listOf(prompt))
            shouldThrow<AssertionError> { contract.verify(listOf(prompt.toBuilder().setAllowCancel(AllowCancel.Abort).build())) }
        }
        test("selection ordering and action prompts preserve typed prompt IDs") {
            for ((type, message) in listOf(
                "SelectNReq" to GREToClientMessage.newBuilder().setSelectNReq(SelectNReq.getDefaultInstance()),
                "OrderReq" to GREToClientMessage.newBuilder().setOrderReq(OrderReq.getDefaultInstance()),
                "ActionsAvailableReq" to GREToClientMessage.newBuilder().setActionsAvailableReq(ActionsAvailableReq.getDefaultInstance()),
            )) {
                val contract =
                    ProtocolContract.parse(
                        """
                        name: prompt
                        scenario: {suite: warmup, id: land-spell-face}
                        frames:
                          - id: prompt
                            events:
                              - id: prompt
                                type: $type
                                lane: prompt
                                fields: {raw.prompt.promptId: 23}
                        """.trimIndent(),
                    )
                val prompt = message.setPrompt(Prompt.newBuilder().setPromptId(23)).build()
                contract.verify(listOf(prompt))
                shouldThrow<AssertionError> { contract.verify(listOf(prompt.toBuilder().clearPrompt().build())) }
                shouldThrow<AssertionError> { contract.verify(listOf(frame(start))) }
            }
        }
    })

private val contractText =
    """
    name: damage
    scenario: {suite: warmup, id: land-spell-face}
    frames:
      - id: resolution
        events:
          - id: start
            type: ResolutionStart
          - id: damage
            type: DamageDealt
            fields: {details.damage: [3]}
    """.trimIndent()

private val start = AnnotationInfo.newBuilder().addType(AnnotationType.ResolutionStart).build()
private val end = AnnotationInfo.newBuilder().addType(AnnotationType.ResolutionComplete).build()
private val windowContractText =
    """
    name: bounded events
    scenario: {suite: warmup, id: land-spell-face}
    frames:
      - id: start
        events:
          - id: start
            type: ResolutionStart
      - id: end
        events:
          - id: end
            type: ResolutionComplete
    """.trimIndent() + "\n"
private val damage =
    AnnotationInfo
        .newBuilder()
        .addType(AnnotationType.DamageDealt_af5a)
        .addDetails(
            KeyValuePairInfo
                .newBuilder()
                .setKey("damage")
                .setType(KeyValuePairValueType.Int32)
                .addValueInt32(3),
        ).build()

private fun frame(vararg annotations: AnnotationInfo): GREToClientMessage =
    GREToClientMessage.newBuilder().setGameStateMessage(GameStateMessage.newBuilder().addAllAnnotations(annotations.toList())).build()
