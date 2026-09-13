package leyline.native.frontdoor

import com.google.protobuf.ByteString
import com.google.protobuf.UnknownFieldSet
import kotlinx.serialization.Serializable
import leyline.domain.json.productionJson
import java.nio.file.Path

private val json = productionJson { ignoreUnknownKeys = true }

/** Builds typed Front Door responses from synthetic server data. */
object FdProtoBuilder {
    private val clientCodec by lazy {
        val schema = System.getenv("LEYLINE_CLIENT_SCHEMA")
        require(!schema.isNullOrBlank()) { "Typed StartHook requires LEYLINE_CLIENT_SCHEMA pointing to local client descriptors" }
        ClientProtobufCodec.load(Path.of(schema))
    }

    fun buildStartHookProto(payload: String): ByteArray = clientCodec.pack("Wizards.Arena.Models.Network.StartHookResponseV2", payload)

    private const val FORMATS_TYPE_URL =
        "type.googleapis.com/Wizards.Arena.Models.Network.GetFormatsResponse"
    private const val SETS_TYPE_URL =
        "type.googleapis.com/Wizards.Arena.Models.Network.SetMetadataResponse"

    fun buildFormatsProto(): ByteArray {
        val text = loadText("fd-bootstrap/format-metadata.json")
        val data = json.decodeFromString<FormatData>(text)
        val pools = data.setPools

        val entries =
            data.formats.map { fmt ->
                val b = UnknownFieldSet.newBuilder()
                b.addString(1, fmt.name)
                val sets = fmt.pool?.let { pools[it] } ?: emptyList()
                for (s in sets) {
                    b.addString(3, s)
                    b.addString(4, s)
                }
                for (id in fmt.bannedCards) b.addVarint(5, id.toLong())
                fmt.singleton?.let { b.addVarint(10, it.toLong()) }
                fmt.formatType?.let { b.addVarint(11, it.toLong()) }
                fmt.deckSize?.let { ds ->
                    b.addMessage(
                        12,
                        unknownFields {
                            addVarint(1, ds.min.toLong())
                            addVarint(2, ds.max.toLong())
                        },
                    )
                }
                fmt.sideboardMax?.let {
                    b.addMessage(13, unknownFields { addVarint(2, it.toLong()) })
                }
                // field 14: CommandZoneQuota — Quota{min, max} for commander formats
                fmt.commandZoneSize?.let { cz ->
                    b.addMessage(
                        14,
                        unknownFields {
                            addVarint(1, cz.min.toLong())
                            addVarint(2, cz.max.toLong())
                        },
                    )
                }
                fmt.field16?.let { b.addVarint(16, it.toLong()) }
                for (id in fmt.allowedCommanders) b.addVarint(17, id.toLong())
                for (rr in fmt.rarityRestrictions) {
                    b.addMessage(
                        21,
                        unknownFields {
                            rr.rarity?.let { addVarint(1, it.toLong()) }
                            addMessage(2, unknownFields { addVarint(2, rr.maxCopies.toLong()) })
                        },
                    )
                }
                b.build()
            }

        val inner = UnknownFieldSet.newBuilder()
        for (e in entries) inner.addMessage(1, e)
        // Field 2: format groups — EvergreenFormats, ConstructedSortOrder, BannedFormats.
        // Client's FormatManager.SetupFormats() requires all three; missing EvergreenFormats = hang.
        for (group in data.formatGroups) {
            val gb = UnknownFieldSet.newBuilder()
            gb.addString(1, group.groupName)
            for (name in group.formatNames) gb.addString(2, name)
            inner.addMessage(2, gb.build())
        }
        return wrapInAny(FORMATS_TYPE_URL, inner.build())
    }

    fun buildSetsProto(): ByteArray {
        val text = loadText("fd-bootstrap/set-metadata.json")
        val data = json.decodeFromString<SetData>(text)

        val entries =
            data.sets.map { s ->
                val b = UnknownFieldSet.newBuilder()
                b.addString(1, s.code)
                for (c in s.collations) {
                    b.addMessage(
                        2,
                        unknownFields {
                            addString(1, c.code)
                            addVarint(2, c.id.toLong())
                            c.rarity?.let { addString(3, it) }
                        },
                    )
                }
                s.digitalOnly?.let { b.addVarint(3, it.toLong()) }
                s.releaseTs?.let { ts ->
                    val tsVal = if (ts == -1L) 0xFFFF_FFFF_0000_0000uL.toLong() else ts
                    b.addMessage(4, unknownFields { addVarint(1, tsVal) })
                }
                s.upcoming?.let { b.addVarint(5, it.toLong()) }
                s.active?.let { b.addVarint(6, it.toLong()) }
                s.currentRelease?.let { b.addVarint(7, it.toLong()) }
                s.parent?.let { b.addString(8, it) }
                b.build()
            }

        val inner = UnknownFieldSet.newBuilder()
        for (e in entries) inner.addMessage(1, e)
        // Field 2: set groups — AllFilters populates collection filter dropdown.
        // Client's SetMetadataProvider.FiltersForFormat() NPEs if _setGroupsAsFilters is empty.
        for (group in data.setGroups) {
            val gb = UnknownFieldSet.newBuilder()
            gb.addString(1, group.groupName)
            for (code in group.setCodes) gb.addString(2, code)
            inner.addMessage(2, gb.build())
        }
        return wrapInAny(SETS_TYPE_URL, inner.build())
    }

    /** Wrap inner message in a google.protobuf.Any envelope (field 1 = type_url, field 2 = value). */
    private fun wrapInAny(
        typeUrl: String,
        inner: UnknownFieldSet,
    ): ByteArray =
        unknownFields {
            addString(1, typeUrl)
            mergeField(
                2,
                UnknownFieldSet.Field
                    .newBuilder()
                    .addLengthDelimited(ByteString.copyFrom(inner.toByteArray()))
                    .build(),
            )
        }.toByteArray()

    private fun loadText(path: String): String =
        FdProtoBuilder::class.java.classLoader
            .getResourceAsStream(path)
            ?.readBytes()
            ?.toString(Charsets.UTF_8)
            ?: error("Missing classpath resource: $path")
}

// --- helpers ---
// mergeField appends to existing field values; addField replaces them.

private fun UnknownFieldSet.Builder.addVarint(
    fieldNum: Int,
    value: Long,
) {
    mergeField(
        fieldNum,
        UnknownFieldSet.Field
            .newBuilder()
            .addVarint(value)
            .build(),
    )
}

private fun UnknownFieldSet.Builder.addString(
    fieldNum: Int,
    value: String,
) {
    mergeField(
        fieldNum,
        UnknownFieldSet.Field
            .newBuilder()
            .addLengthDelimited(ByteString.copyFromUtf8(value))
            .build(),
    )
}

private fun UnknownFieldSet.Builder.addMessage(
    fieldNum: Int,
    value: UnknownFieldSet,
) {
    mergeField(
        fieldNum,
        UnknownFieldSet.Field
            .newBuilder()
            .addLengthDelimited(ByteString.copyFrom(value.toByteArray()))
            .build(),
    )
}

private inline fun unknownFields(block: UnknownFieldSet.Builder.() -> Unit): UnknownFieldSet =
    UnknownFieldSet.newBuilder().apply(block).build()

// --- JSON model ---

@Serializable
private data class FormatData(
    val setPools: Map<String, List<String>>,
    val formats: List<FormatEntry>,
    val formatGroups: List<FormatGroup> = emptyList(),
)

@Serializable
private data class FormatGroup(
    val groupName: String,
    val formatNames: List<String>,
)

@Serializable
private data class DeckSizeRange(
    val min: Int,
    val max: Int,
)

@Serializable
private data class FormatEntry(
    val name: String,
    val pool: String? = null,
    val bannedCards: List<Int> = emptyList(),
    val singleton: Int? = null,
    val formatType: Int? = null,
    val deckSize: DeckSizeRange? = null,
    val sideboardMax: Int? = null,
    val field16: Int? = null,
    val allowedCommanders: List<Int> = emptyList(),
    val rarityRestrictions: List<RarityRestriction> = emptyList(),
    val commandZoneSize: DeckSizeRange? = null,
)

@Serializable
private data class RarityRestriction(
    val rarity: Int? = null,
    val maxCopies: Int = 1,
)

@Serializable
private data class SetData(
    val sets: List<SetEntry>,
    val setGroups: List<SetGroup> = emptyList(),
)

@Serializable
private data class SetGroup(
    val groupName: String,
    val setCodes: List<String>,
)

@Serializable
private data class SetEntry(
    val code: String,
    val collations: List<Collation> = emptyList(),
    val digitalOnly: Int? = null,
    val releaseTs: Long? = null,
    val upcoming: Int? = null,
    val active: Int? = null,
    val currentRelease: Int? = null,
    val parent: String? = null,
)

@Serializable
private data class Collation(
    val code: String,
    val id: Int,
    val rarity: String? = null,
)
