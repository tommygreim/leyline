package leyline.testkit

import com.google.protobuf.Descriptors.EnumValueDescriptor
import com.google.protobuf.Message
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.acceptance.AcceptancePaths
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import java.nio.file.Files
import java.nio.file.Path

/**
 * Interprets authored protocol obligations over one scripted interaction's emitted messages.
 * Frames require ordered events in one message; counts cover the complete scenario stream.
 * Windows constrain events strictly between bound witnesses, including within one message.
 * Runtime identities bind between events instead of depending on catalog-specific numbers.
 * Scenario execution is owned by the acceptance executor, not this checker.
 */
class ProtocolContract private constructor(
    val name: String,
    val suite: String,
    val scenario: String,
    private val frames: List<List<Map<String, Any?>>>,
    private val counts: List<Map<String, Any?>>,
    private val windows: List<Map<String, Any?>>,
) {
    /**
     * Selects one matching start, then checks the first eligible event at each step.
     * An explicit `where` skips unrelated interactions without skipping contradictory
     * fields on the selected interaction. Later frames may continue in the same message.
     */
    fun verify(messages: List<GREToClientMessage>) {
        val events = project(messages)
        val bound = mutableMapOf<String, IndexedValue<Event>>()
        var cursor = 0
        // ponytail: one matching start per scripted interaction; add occurrence selection when a scenario repeats indistinguishable starts.
        for (frame in frames) {
            var messageIndex: Int? = null
            for (pattern in frame) {
                val id = pattern["id"] as String
                val index =
                    (cursor until events.size).firstOrNull { index ->
                        (messageIndex == null || events[index].messageIndex == messageIndex) &&
                            kindMatches(events[index], pattern) &&
                            (
                                pattern["where"] == null ||
                                    matches(events[index], pattern["where"].map() + ("type" to pattern["type"]), bound)
                            ) &&
                            (pattern["where"] != null || bound.isNotEmpty() || matches(events[index], pattern, bound)) &&
                            (
                                pattern["sameRow"] == null ||
                                    events[index].values["annotationId"] == bound[pattern["sameRow"]]?.value?.values?.get("annotationId")
                            )
                    }
                withClue("$name: required event $id after ${bound.keys.lastOrNull()}") { index shouldNotBe null }
                val event = events[index!!]
                withClue("$name: $id has contradictory fields or identity; expected=$pattern actual=${event.values}") {
                    matches(event, pattern, bound) shouldBe true
                }
                bound[id] = IndexedValue(index, event)
                messageIndex = event.messageIndex
                cursor = index + 1
            }
        }
        for (count in counts) {
            val pattern = count["match"].map()
            withClue("$name: exact count for $pattern") {
                events.count { matches(it, pattern, bound) } shouldBe count["exactly"]
            }
        }
        for (window in windows) {
            val start = bound.getValue((window["after"] ?: window["holds"]) as String)
            val end = bound.getValue((window["before"] ?: window["until"]) as String)
            val between = events.subList(start.index + 1, end.index)
            withClue("$name: window ${window["id"]} between events ${start.index} and ${end.index}") {
                if (window["holds"] != null) {
                    val rowId = start.value.values["annotationId"]
                    between.none { it.lane == "persistent" && it.op == "delete" && it.values["annotationId"] == rowId } shouldBe true
                } else {
                    val pattern = (window["absent"] ?: window["count"]).map()
                    val found = between.filter { matches(it, pattern, bound) }
                    withClue("matching events: $found") { found.size shouldBe (window["exactly"] ?: 0) }
                }
            }
        }
    }

    companion object {
        fun files(): List<Path> =
            Files.list(AcceptancePaths.resolve("conformance/contracts")).use { paths ->
                paths.filter { it.toString().endsWith(".yaml") }.sorted().toList()
            }

        fun load(path: Path): ProtocolContract = parse(Files.readString(path))

        fun parse(text: String): ProtocolContract {
            val options = LoaderOptions().apply { isAllowDuplicateKeys = false }
            val root = Yaml(SafeConstructor(options)).load<Any?>(text).map()
            root.keysOnly("name", "scenario", "frames", "counts", "windows")
            val scenario = root["scenario"].map()
            scenario.keysOnly("suite", "id")
            val ids = mutableSetOf<String>()
            val frames =
                root["frames"]
                    .list()
                    .map { raw ->
                        val frame = raw.map()
                        frame.keysOnly("id", "events")
                        frame.string("id")
                        frame["events"]
                            .list()
                            .map { rawEvent ->
                                val event = rawEvent.map()
                                event.keysOnly("id", "type", "lane", "op", "keys", "fields", "equals", "sameRow", "where")
                                validatePattern(event, ids)
                                require(ids.add(event.string("id"))) { "duplicate event id" }
                                event
                            }.also { require(it.isNotEmpty()) { "empty contract frame" } }
                    }.also { require(it.isNotEmpty()) { "empty contract" } }
            val counts =
                root["counts"]?.list()?.map { raw ->
                    val count = raw.map()
                    count.keysOnly("match", "exactly")
                    val pattern = count["match"].map()
                    pattern.keysOnly("type", "lane", "op", "keys", "fields", "equals", "sameRow")
                    validatePattern(pattern, ids)
                    require(count["exactly"] is Int && (count["exactly"] as Int) >= 0) { "invalid exact count" }
                    count
                } ?: emptyList()
            val windows = root["windows"]?.list()?.map { it.map() } ?: emptyList()
            validateWindows(windows, frames.flatten())
            return ProtocolContract(root.string("name"), scenario.string("suite"), scenario.string("id"), frames, counts, windows)
        }
    }
}

private data class Event(
    val messageIndex: Int,
    val type: String,
    val lane: String,
    val op: String,
    val values: Map<String, Any?>,
)

private fun project(messages: List<GREToClientMessage>): List<Event> {
    val rows = mutableMapOf<Int, AnnotationInfo>()
    val activeRows = mutableSetOf<Int>()
    val objects = mutableSetOf<Int>()
    return buildList {
        messages.forEachIndexed { index, message ->
            if (message.hasGameStateMessage()) {
                val gsm = message.gameStateMessage
                for (obj in gsm.gameObjectsList) {
                    add(
                        Event(
                            index,
                            obj.type.name,
                            "object",
                            if (objects.add(obj.instanceId)) "create" else "update",
                            mapOf("affectorId" to obj.instanceId, "raw" to obj),
                        ),
                    )
                }
                for (annotation in gsm.annotationsList) addAll(annotation.events(index, "transient", "emit"))
                for (annotation in gsm.persistentAnnotationsList) {
                    val op = if (activeRows.add(annotation.id)) "create" else "update"
                    rows[annotation.id] = annotation
                    addAll(annotation.events(index, "persistent", op))
                }
                for (id in gsm.diffDeletedPersistentAnnotationIdsList) {
                    activeRows.remove(id)
                    // Retain the last row to expose repeated deletions to exact-count obligations.
                    rows[id]?.let { addAll(it.events(index, "persistent", "delete")) }
                }
            }
            val prompt =
                when {
                    message.hasSelectTargetsReq() -> "SelectTargetsReq"
                    message.hasOptionalActionMessage() -> "OptionalActionMessage"
                    message.hasSelectNReq() -> "SelectNReq"
                    message.hasOrderReq() -> "OrderReq"
                    message.hasActionsAvailableReq() -> "ActionsAvailableReq"
                    else -> null
                }
            if (prompt != null) {
                add(Event(index, prompt, "prompt", "emit", mapOf("raw" to message)))
            }
        }
    }
}

private fun AnnotationInfo.events(
    index: Int,
    lane: String,
    op: String,
): List<Event> {
    val values =
        mapOf(
            "annotationId" to id,
            "affectorId" to affectorId,
            "affectedIds" to affectedIdsList,
            "details" to
                detailsList.associate { detail ->
                    val field = detail.descriptorForType.findFieldByName("value${detail.type.name}")
                    detail.key to field?.let { detail.getField(it) }
                },
            "detailTypes" to detailsList.associate { it.key to it.type.name },
            "keys" to detailsList.map { it.key },
            "raw" to this,
        )
    return typeList.map { Event(index, it.protocolName(), lane, op, values) }
}

private fun matches(
    event: Event,
    pattern: Map<String, Any?>,
    bound: Map<String, IndexedValue<Event>>,
): Boolean {
    if (!kindMatches(event, pattern)) return false
    if (pattern["keys"] != null &&
        (event.values["keys"] as? List<*>)?.sortedBy { it.toString() } != pattern["keys"].list().sortedBy { it.toString() }
    ) {
        return false
    }
    pattern["sameRow"]?.let {
        val row = bound[it]?.value?.values?.get("annotationId") ?: return false
        if (event.values["annotationId"] != row) return false
    }
    for ((selector, expected) in pattern["fields"]?.map().orEmpty()) {
        if (select(event.values, selector) != expected) return false
    }
    for ((selector, reference) in pattern["equals"]?.map().orEmpty()) {
        val value = reference as String
        val other = bound[value.substringBefore('.')]?.value
        val expected = other?.let { select(it.values, value.substringAfter('.')) } ?: return false
        if (select(event.values, selector) != expected) return false
    }
    return true
}

private fun kindMatches(
    event: Event,
    pattern: Map<String, Any?>,
): Boolean =
    event.type == pattern["type"] &&
        (pattern["lane"] == null || event.lane == pattern["lane"]) &&
        (pattern["op"] == null || event.op == pattern["op"])

private val segment = Regex("([A-Za-z][A-Za-z0-9_]*)(?:\\[(\\d+)])?")
private val promptTypes = setOf("SelectTargetsReq", "OptionalActionMessage", "SelectNReq", "OrderReq", "ActionsAvailableReq")
private val enumSuffix = Regex("_[0-9a-f]{4}$")

/** Protobuf name-collision suffixes are build identifiers, not protocol names. */
private fun AnnotationType.protocolName(): String = name.replace(enumSuffix, "")

private fun validateSelector(selector: String) {
    require(
        selector.substringBefore('.').substringBefore('[') in
            setOf("affectorId", "affectedIds", "annotationId", "details", "detailTypes", "raw"),
    ) {
        "unknown selector $selector"
    }
    require(selector.split('.').all { segment.matches(it) }) { "invalid selector $selector" }
    require(
        selector.split('.').all { part ->
            val index = segment.matchEntire(part)!!.groupValues[2]
            index.isEmpty() || index.toIntOrNull() != null
        },
    ) { "invalid selector index $selector" }
}

private fun select(
    values: Map<String, Any?>,
    selector: String,
): Any? {
    var value: Any? = values
    for (part in selector.split('.')) {
        val match = segment.matchEntire(part)!!
        val key = match.groupValues[1]
        value =
            when (val current = value) {
                is Message -> {
                    val field = current.descriptorForType.fields.firstOrNull { it.jsonName == key } ?: return null
                    if (field.hasPresence() && !current.hasField(field)) return null
                    current.getField(field)
                }
                is Map<*, *> -> current[key]
                is List<*> -> if (key == "length") current.size else return null
                else -> return null
            }
        if (match.groupValues[2].isNotEmpty()) value = (value as? List<*>)?.getOrNull(match.groupValues[2].toInt()) ?: return null
    }
    return if (value is EnumValueDescriptor) value.name.replace(enumSuffix, "") else value
}

private fun validatePattern(
    pattern: Map<String, Any?>,
    ids: Set<String>,
) {
    val type = pattern.string("type")
    require(
        type in promptTypes ||
            AnnotationType.entries.any { it.protocolName() == type } ||
            GameObjectType.entries.any { it.name == type },
    ) {
        "unsupported event type $type"
    }
    val lanes =
        when {
            type in promptTypes -> setOf("prompt")
            GameObjectType.entries.any { it.name == type } -> setOf("object")
            else -> setOf("transient", "persistent")
        }
    pattern["lane"]?.let { require(it in lanes) { "unsupported lane for $type" } }
    val operations =
        when {
            "object" in lanes -> setOf("create", "update")
            pattern["lane"] == "persistent" -> setOf("create", "update", "delete")
            else -> setOf("emit")
        }
    pattern["op"]?.let { require(it in operations) { "unsupported operation for $type" } }
    pattern["keys"]?.list()?.forEach { require(it is String) { "invalid detail key" } }
    pattern["sameRow"]?.let { require(it in ids) { "unknown row reference $it" } }
    pattern["where"]?.map()?.let { selection ->
        selection.keysOnly("fields", "equals")
        require(selection.isNotEmpty()) { "empty event selection" }
        validatePattern(selection + ("type" to type), ids)
    }
    for ((selector, value) in pattern["fields"]?.map().orEmpty()) {
        validateSelector(selector)
        require(
            value is String ||
                value is Int ||
                value is Boolean ||
                (value is List<*> && value.all { it is String || it is Int || it is Boolean }),
        ) {
            "invalid expected value for $selector"
        }
    }
    for ((selector, reference) in pattern["equals"]?.map().orEmpty()) {
        validateSelector(selector)
        require(reference is String && '.' in reference && reference.substringBefore('.') in ids) { "unknown event reference $reference" }
        validateSelector(reference.substringAfter('.'))
    }
}

/** Windows use strict event boundaries; the endpoint itself may retire a held row. */
private fun validateWindows(
    windows: List<Map<String, Any?>>,
    events: List<Map<String, Any?>>,
) {
    val ids = events.map { it.string("id") }
    val windowIds = mutableSetOf<String>()
    for (window in windows) {
        require(windowIds.add(window.string("id"))) { "duplicate window id" }
        if (window["holds"] != null) {
            window.keysOnly("id", "holds", "until")
            val held = events.singleOrNull { it["id"] == window.string("holds") }
            require(held != null && held["lane"] == "persistent" && held["op"] in setOf("create", "update")) {
                "holds requires an active persistent row"
            }
        } else {
            require((window["absent"] != null) != (window["count"] != null)) { "window requires absent or count" }
            if (window["absent"] != null) {
                window.keysOnly("id", "after", "before", "absent")
            } else {
                window.keysOnly("id", "after", "before", "count", "exactly")
                require(window["exactly"] is Int && (window["exactly"] as Int) >= 0) { "invalid window count" }
            }
            val pattern = (window["absent"] ?: window["count"]).map()
            pattern.keysOnly("type", "lane", "op", "keys", "fields", "equals", "sameRow")
            validatePattern(pattern, ids.toSet())
        }
        val start = window.string(if (window["holds"] != null) "holds" else "after")
        val end = window.string(if (window["holds"] != null) "until" else "before")
        require(start in ids && end in ids && ids.indexOf(start) < ids.indexOf(end)) { "invalid window anchors" }
    }
}

private fun Any?.map(): Map<String, Any?> {
    require(this is Map<*, *> && keys.all { it is String }) { "expected string-keyed mapping" }
    @Suppress("UNCHECKED_CAST")
    return this as Map<String, Any?>
}

private fun Any?.list(): List<Any?> = this as? List<Any?> ?: error("expected list")

private fun Map<String, Any?>.string(key: String): String = this[key] as? String ?: error("$key must be a string")

private fun Map<String, Any?>.keysOnly(vararg allowed: String) {
    require(keys.all { it in allowed }) { "unknown fields: ${keys - allowed.toSet()}" }
    require(values.none { it == null }) { "explicit null fields are not allowed" }
}
