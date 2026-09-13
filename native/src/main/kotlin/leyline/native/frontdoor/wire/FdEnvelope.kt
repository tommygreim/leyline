package leyline.native.frontdoor.wire

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Wire-level frame constants shared by FD codecs.
 *
 * Duplicated from [ClientFrameDecoder] companion so that FD code can
 * be extracted into a separate module without depending on the Netty
 * codec class.
 */
object FdWireConstants {
    const val HEADER_SIZE = 6
    const val VERSION: Byte = 0x04
    const val TYPE_CTRL_INIT: Byte = 0x12
    const val TYPE_CTRL_ACK: Byte = 0x13
    const val TYPE_DATA_FD: Byte = 0x21
}

/**
 * Front Door protobuf envelope codec.
 *
 * The FD uses a different protobuf schema from the Match Door (not in messages.proto).
 * Field numbers from protocol analysis.
 *
 * Three envelope types:
 * - **Cmd** (C→S command / S→C push): type=1, raw_trans_id=2, {proto=3, json=4}, compressed=5
 * - **Request** (C→S newer path): type=1, raw_trans_id=2, key=3, {proto=4, json=5}, session_info=6, compressed=7
 * - **Response** (S→C reply): raw_trans_id=1, {proto=2, json=3}, error=4, compressed=5
 */
object FdEnvelope {
    private val BATTLEFIELDS = listOf("FDN", "DSK", "BLB", "OTJ", "MKM", "WOE", "FIN")

    // --- Cmd field numbers ---
    private const val CMD_TYPE = 1 // varint (CmdType enum)
    private const val CMD_TRANS_ID = 2 // string
    private const val CMD_JSON_PAYLOAD = 4 // bytes (JSON)

    // --- Response field numbers ---
    private const val RESP_TRANS_ID = 1 // string
    private const val RESP_JSON_PAYLOAD = 3 // bytes (JSON)

    // Protobuf wire types
    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2

    /**
     * Decoded FD message — works for all three envelope types.
     */
    data class FdMessage(
        /** CmdType value. Present in Cmd/Request envelopes, null for Response. */
        val cmdType: Int?,
        /** Transaction GUID (raw_trans_id). */
        val transactionId: String?,
        /** Decoded JSON payload string, if present. */
        val jsonPayload: String?,
        /** Routing key (Request envelope only). */
        val key: String? = null,
        /** Which envelope type this was decoded from. */
        val envelopeType: EnvelopeType = EnvelopeType.UNKNOWN,
        /** Any type URL for typed payloads, without the payload or account data. */
        val protobufTypeUrl: String? = null,
    )

    enum class EnvelopeType { CMD, REQUEST, RESPONSE, UNKNOWN }

    /**
     * Decode raw protobuf bytes into an [FdMessage].
     *
     * Heuristic to distinguish envelope types:
     * - If field 1 is a varint → Cmd or Request (both have type=1 as varint)
     * - If field 1 is length-delimited → Response (raw_trans_id=1 as string)
     *
     * We then look at which json_payload field is present:
     * - Field 4 (bytes) → Cmd
     * - Field 5 (bytes) → Request
     * - Field 3 (bytes) after string field 1 → Response
     */
    fun decode(bytes: ByteArray): FdMessage {
        val fields = parseProtoFields(bytes)

        // Determine envelope type by field 1's wire type
        val field1 = fields.firstOrNull { it.fieldNumber == 1 }

        // Check for compressed flag (field 5 in Cmd/Response, field 7 in Request)
        val isCompressed =
            fields.any {
                it.wireType == WIRE_VARINT && it.fieldNumber in listOf(5, 7) && it.asVarint() != 0
            }

        return when {
            // Response: field 1 is string (raw_trans_id)
            field1 != null &&
                field1.wireType == WIRE_LENGTH_DELIMITED &&
                isLikelyUuid(field1.asString()) -> {
                val transId = field1.asString()
                val rawPayload = fields.firstOrNull { it.fieldNumber == RESP_JSON_PAYLOAD }
                val json = rawPayload?.let { decompress(it.data, isCompressed) }
                FdMessage(
                    cmdType = null,
                    transactionId = transId,
                    jsonPayload = json,
                    envelopeType = EnvelopeType.RESPONSE,
                    protobufTypeUrl = protobufTypeUrl(fields.firstOrNull { it.fieldNumber == RESP_PROTO_PAYLOAD }),
                )
            }
            // Cmd or Request: field 1 is varint (type)
            field1 != null && field1.wireType == WIRE_VARINT -> {
                decodeCmd(fields, field1.asVarint(), isCompressed)
            }
            // CmdType=0 (Authenticate): protobuf omits varint 0 for default values,
            // so field 1 is absent. Detect by: field 2 is UUID, field 4 is payload.
            field1 == null || (field1.wireType == WIRE_LENGTH_DELIMITED && !isLikelyUuid(field1.asString())) -> {
                val field2 = fields.firstOrNull { it.fieldNumber == 2 }
                if (field2 != null && isLikelyUuid(field2.asString())) {
                    // Cmd with CmdType=0 (omitted default)
                    decodeCmd(fields, 0, isCompressed)
                } else {
                    fallback(fields)
                }
            }
            else -> fallback(fields)
        }
    }

    private fun decodeCmd(
        fields: List<ProtoField>,
        cmdType: Int,
        isCompressed: Boolean,
    ): FdMessage {
        val transId = fields.firstOrNull { it.fieldNumber == 2 }?.asString()
        val rawField3 = fields.firstOrNull { it.fieldNumber == 3 }
        val rawField5 = fields.firstOrNull { it.fieldNumber == 5 && it.wireType == WIRE_LENGTH_DELIMITED }
        val rawField4 = fields.firstOrNull { it.fieldNumber == 4 }
        val requestProtoType = protobufTypeUrl(rawField4)

        return if (rawField5 != null || requestProtoType != null) {
            FdMessage(
                cmdType = cmdType,
                transactionId = transId,
                jsonPayload = rawField5?.let { decompress(it.data, isCompressed) },
                key = rawField3?.asString(),
                envelopeType = EnvelopeType.REQUEST,
                protobufTypeUrl = requestProtoType,
            )
        } else {
            FdMessage(
                cmdType = cmdType,
                transactionId = transId,
                jsonPayload = rawField4?.let { decompress(it.data, isCompressed) },
                envelopeType = EnvelopeType.CMD,
                protobufTypeUrl = protobufTypeUrl(rawField3),
            )
        }
    }

    private fun protobufTypeUrl(field: ProtoField?): String? {
        if (field?.wireType != WIRE_LENGTH_DELIMITED) return null
        return parseProtoFields(field.data)
            .firstOrNull { it.fieldNumber == ANY_TYPE_URL && it.wireType == WIRE_LENGTH_DELIMITED }
            ?.asString()
            ?.takeIf { it.startsWith("type.googleapis.com/") }
    }

    private fun fallback(fields: List<ProtoField>): FdMessage {
        val anyJson =
            fields
                .filter { it.wireType == WIRE_LENGTH_DELIMITED }
                .map { it.asString() }
                .firstOrNull { it.startsWith("{") }
        val anyUuid =
            fields
                .filter { it.wireType == WIRE_LENGTH_DELIMITED }
                .map { it.asString() }
                .firstOrNull { UUID_PATTERN.matches(it) }
        return FdMessage(null, anyUuid, anyJson)
    }

    /**
     * Decompress gzip payload if flagged, otherwise decode as UTF-8.
     *
     * Compressed payloads have a 4-byte uint32 LE prefix (uncompressed size)
     * followed by standard gzip data (magic bytes 1f 8b).
     */
    private fun decompress(
        data: ByteArray,
        compressed: Boolean,
    ): String? {
        if (!compressed) {
            return try {
                String(data, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
        // Find gzip magic (1f 8b) — typically at offset 4 after the size prefix
        val gzipOffset = findGzipMagic(data)
        val stream = if (gzipOffset >= 0) data.copyOfRange(gzipOffset, data.size) else data
        return try {
            GZIPInputStream(ByteArrayInputStream(stream)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            // Not actually gzip — try raw UTF-8
            try {
                String(data, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Find gzip magic bytes (1f 8b) within first 8 bytes. */
    private fun findGzipMagic(data: ByteArray): Int {
        if (data.size < 2) return -1
        val limit = minOf(8, data.size - 2)
        for (i in 0..limit) {
            if (data[i] == 0x1f.toByte() && data[i + 1] == 0x8b.toByte()) return i
        }
        return -1
    }

    private fun isLikelyUuid(s: String): Boolean = UUID_PATTERN.matches(s)

    // --- Response field 2 (protobuf_payload) ---
    private const val RESP_PROTO_PAYLOAD = 2 // bytes (protobuf Any)
    private const val ANY_TYPE_URL = 1 // string field in google.protobuf.Any

    /**
     * Encode an empty Response envelope (S→C ack, no payload).
     * Client's UnpackPayload may NRE on truly empty responses —
     * prefer [encodeProtoResponse] with the correct type URL.
     */
    fun encodeEmptyResponse(transactionId: String): ByteArray = encode { writeString(RESP_TRANS_ID, transactionId) }

    /**
     * Encode a Response with protobuf_payload (field 2) as a google.protobuf.Any.
     * The Any contains the type URL and an empty value (default proto message).
     * Used for CmdTypes where the client expects protobuf, not JSON.
     */
    fun encodeProtoResponse(
        transactionId: String,
        typeUrl: String,
    ): ByteArray {
        // Build inner Any: field 1 = type_url, field 2 = empty bytes
        val anyBytes = encode { writeString(ANY_TYPE_URL, typeUrl) }
        return encode {
            writeString(RESP_TRANS_ID, transactionId)
            writeByteArray(RESP_PROTO_PAYLOAD, anyBytes)
        }
    }

    /**
     * Encode a Response with raw pre-built field 2 bytes (protobuf_payload).
     * The [protoPayload] must already be a serialized google.protobuf.Any
     * (type_url + value).
     */
    fun encodeRawProtoResponse(
        transactionId: String,
        protoPayload: ByteArray,
    ): ByteArray =
        encode {
            writeString(RESP_TRANS_ID, transactionId)
            writeByteArray(RESP_PROTO_PAYLOAD, protoPayload)
        }

    /**
     * Encode a Response envelope (S→C reply to a request) with JSON in field 3.
     */
    fun encodeResponse(
        transactionId: String,
        json: String,
    ): ByteArray =
        encode {
            writeString(RESP_TRANS_ID, transactionId)
            writeString(RESP_JSON_PAYLOAD, json)
        }

    /**
     * Encode a Cmd envelope (S→C push notification, e.g. MatchCreated).
     */
    fun encodeCmd(
        cmdType: Int,
        transactionId: String,
        json: String,
    ): ByteArray =
        encode {
            writeUInt32(CMD_TYPE, cmdType)
            writeString(CMD_TRANS_ID, transactionId)
            writeString(CMD_JSON_PAYLOAD, json)
        }

    // --- Protobuf parsing primitives ---

    private data class ProtoField(
        val fieldNumber: Int,
        val wireType: Int,
        val data: ByteArray = byteArrayOf(),
        val varint: Int = 0,
    ) {
        fun asString(): String = String(data, Charsets.UTF_8)

        fun asVarint(): Int = varint
    }

    private fun parseProtoFields(bytes: ByteArray): List<ProtoField> {
        val fields = mutableListOf<ProtoField>()
        val input = CodedInputStream.newInstance(bytes)
        try {
            while (!input.isAtEnd) {
                val tag = input.readTag()
                if (tag == 0) break
                val fieldNumber = tag ushr 3
                val wireType = tag and 0x07
                when (wireType) {
                    WIRE_VARINT -> fields.add(ProtoField(fieldNumber, wireType, varint = input.readUInt32()))
                    WIRE_LENGTH_DELIMITED ->
                        fields.add(ProtoField(fieldNumber, wireType, data = input.readByteArray()))
                    else -> if (!input.skipField(tag)) return fields
                }
            }
        } catch (_: IOException) {
            // Keep fields decoded before a malformed tail.
        }
        return fields
    }

    private inline fun encode(block: CodedOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        CodedOutputStream.newInstance(bytes).apply {
            block()
            flush()
        }
        return bytes.toByteArray()
    }

    private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /**
     * Build a 6-byte outgoing FD frame header (version + type + LE payload length).
     * Shared by FrontDoorHandler.
     */
    fun buildOutgoingHeader(payloadLength: Int): ByteArray {
        val h = ByteArray(FdWireConstants.HEADER_SIZE)
        h[0] = FdWireConstants.VERSION
        h[1] = FdWireConstants.TYPE_DATA_FD
        h[2] = (payloadLength and 0xFF).toByte()
        h[3] = ((payloadLength shr 8) and 0xFF).toByte()
        h[4] = ((payloadLength shr 16) and 0xFF).toByte()
        h[5] = ((payloadLength shr 24) and 0xFF).toByte()
        return h
    }

    /**
     * Info about a player seat in a match. Used to build the PlayerInfos array
     * in the MatchCreated push notification.
     */
    data class PlayerInfo(
        val seatId: Int,
        val teamId: Int,
        val name: String,
        val avatarId: String = "Avatar_Basic_Adventurer",
        /** Commander grpIds for Brawl/Commander formats. Empty for Standard. */
        val commanderGrpIds: List<Int> = emptyList(),
    )

    /**
     * Build a MatchCreated push notification JSON payload.
     * Shared by FrontDoorHandler.
     *
     * @param playerInfos player seats; null uses defaults (Player + AI Opponent).
     */
    fun buildMatchCreatedJson(
        matchId: String,
        matchDoorHost: String,
        matchDoorPort: Int,
        matchType: String = "Familiar",
        matchTypeInternal: Int = 1,
        yourSeat: Int = 1,
        eventId: String = "AIBotMatch",
        playerInfos: List<PlayerInfo>? = null,
    ): String {
        val players =
            playerInfos ?: listOf(
                PlayerInfo(seatId = 1, teamId = 1, name = "Player", avatarId = "Avatar_Basic_Adventurer"),
                PlayerInfo(seatId = 2, teamId = 2, name = "AI Opponent", avatarId = "Avatar_Basic_Sparky"),
            )
        return buildJsonObject {
            put("Type", "MatchCreated")
            putJsonObject("MatchInfoV4") {
                put("MatchType", matchType)
                put("MatchEndpointHost", matchDoorHost)
                put("MatchEndpointPort", matchDoorPort)
                put("MatchId", matchId)
                put("McFabricId", "wzmc://forge/$matchId")
                put("EventId", eventId)
                put("Battlefield", BATTLEFIELDS.random())
                put("MatchTypeInternal", matchTypeInternal)
                put("YourSeat", yourSeat)
                putJsonArray("ClientMetadata") {}
                putJsonArray("PlayerInfos") {
                    for (p in players) {
                        add(playerInfoJson(p))
                    }
                }
            }
        }.toString()
    }

    private fun playerInfoJson(p: PlayerInfo) =
        buildJsonObject {
            put("ScreenName", p.name)
            put("RankingClass", "")
            put("SeatId", p.seatId)
            put("TeamId", p.teamId)
            if (p.commanderGrpIds.isNotEmpty()) {
                putJsonArray("Commanders") {
                    p.commanderGrpIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                }
            } else {
                putJsonArray("Commanders") {}
            }
            putJsonObject("CosmeticsSelection") {
                putJsonObject("Avatar") {
                    put("Type", "Avatar")
                    put("Id", p.avatarId)
                }
                putJsonArray("Emotes") {}
            }
        }
}
