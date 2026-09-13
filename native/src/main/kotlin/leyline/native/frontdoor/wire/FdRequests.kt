package leyline.native.frontdoor.wire

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.domain.DeckCard
import leyline.domain.json.productionJson
import org.slf4j.LoggerFactory

/**
 * Typed request parsers for Front Door CmdTypes.
 *
 * Field names match the client's wire casing exactly.
 * Uses lenient JSON parsing (`ignoreUnknownKeys`) so new fields the client
 * adds don't break existing handlers.
 */
object FdRequests {
    private val log = LoggerFactory.getLogger(FdRequests::class.java)

    private val lenientJson =
        productionJson {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /** CmdType 612 — client uses camelCase. */
    data class AiBotMatch(
        val deckId: String,
        val botDeckId: String?,
        val botMatchType: Int?,
    )

    /** CmdType 600 */
    data class EventJoin(
        val eventName: String,
        val entryCurrencyType: String?,
    )

    /** CmdType 601, 606 */
    data class EventName(
        val eventName: String,
    )

    /** CmdType 603 */
    data class EnterPairing(
        val eventName: String,
        val eventCode: String?,
    )

    /** CmdType 608 */
    data class MatchResult(
        val eventName: String,
        val matchId: String?,
    )

    /** CmdType 622/627 — eventName + deck metadata from Summary, deck contents from Deck. */
    data class SetDeck(
        val eventName: String,
        val deckId: String?,
        val deckName: String? = null,
        val tileId: Int? = null,
        val deckArtId: Int? = null,
        val deckFormat: String? = null,
        val preferredSleeve: String? = null,
        val mainDeck: List<DeckCard> = emptyList(),
        val sideboard: List<DeckCard> = emptyList(),
    )

    /** CmdType 400 */
    data class GetDeck(
        val deckId: String,
    )

    /** CmdType 403 */
    data class DeleteDeck(
        val deckId: String,
    )

    // --- Parsers ---

    fun parseAiBotMatch(json: String?): AiBotMatch? =
        parse(json) { obj ->
            AiBotMatch(
                deckId = obj["deckId"]?.jsonPrimitive?.content ?: return@parse null,
                botDeckId = obj["botDeckId"]?.jsonPrimitive?.content,
                botMatchType = obj["botMatchType"]?.jsonPrimitive?.int,
            )
        }

    fun parseEventJoin(json: String?): EventJoin? =
        parse(json) { obj ->
            EventJoin(
                eventName = obj["EventName"]?.jsonPrimitive?.content ?: return@parse null,
                entryCurrencyType = obj["EntryCurrencyType"]?.jsonPrimitive?.content,
            )
        }

    fun parseEventName(json: String?): EventName? =
        parse(json) { obj ->
            val name = obj["EventName"]?.jsonPrimitive?.content ?: return@parse null
            EventName(name)
        }

    fun parseEnterPairing(json: String?): EnterPairing? =
        parse(json) { obj ->
            EnterPairing(
                eventName = obj["EventName"]?.jsonPrimitive?.content ?: return@parse null,
                eventCode = obj["EventCode"]?.jsonPrimitive?.content,
            )
        }

    fun parseMatchResult(json: String?): MatchResult? =
        parse(json) { obj ->
            MatchResult(
                eventName = obj["EventName"]?.jsonPrimitive?.content ?: return@parse null,
                matchId = obj["MatchId"]?.jsonPrimitive?.content,
            )
        }

    fun parseSetDeck(json: String?): SetDeck? =
        parse(json) { obj ->
            val summary = obj["Summary"]?.jsonObject
            // Current clients nest MainDeck/Sideboard under "Deck"; tolerate top-level arrays from older builds.
            val deck = obj["Deck"]?.jsonObject
            val mainElement = obj["MainDeck"] ?: deck?.get("MainDeck")
            val sideElement = obj["Sideboard"] ?: deck?.get("Sideboard")
            val attributes = summary?.get("Attributes")?.jsonArray
            val format =
                attributes
                    ?.mapNotNull { it.jsonObject }
                    ?.firstOrNull { it["name"]?.jsonPrimitive?.content == "Format" }
                    ?.get("value")
                    ?.jsonPrimitive
                    ?.content
            val preferredSleeve =
                summary
                    ?.get("PreferredCosmetics")
                    ?.jsonObject
                    ?.get("Sleeve")
                    ?.jsonPrimitive
                    ?.content
            SetDeck(
                eventName = obj["EventName"]?.jsonPrimitive?.content ?: return@parse null,
                deckId = summary?.get("DeckId")?.jsonPrimitive?.content,
                deckName = summary?.get("Name")?.jsonPrimitive?.content,
                tileId = summary?.get("DeckTileId")?.jsonPrimitive?.int,
                deckArtId = summary?.get("DeckArtId")?.jsonPrimitive?.int,
                deckFormat = format,
                preferredSleeve = preferredSleeve,
                mainDeck = DeckWireBuilder.parseCardList(mainElement, defaultQuantity = 1),
                sideboard = DeckWireBuilder.parseCardList(sideElement, defaultQuantity = 1),
            )
        }

    fun parseGetDeck(json: String?): GetDeck? =
        parse(json) { obj ->
            val id = obj["DeckId"]?.jsonPrimitive?.content ?: return@parse null
            GetDeck(id)
        }

    fun parseDeleteDeck(json: String?): DeleteDeck? =
        parse(json) { obj ->
            val id = obj["DeckId"]?.jsonPrimitive?.content ?: return@parse null
            DeleteDeck(id)
        }

    /** BotDraft_DraftPick — client sends pick info with string card IDs. */
    data class DraftPick(
        val eventName: String,
        val cardId: Int,
    )

    fun parseDraftPick(json: String?): DraftPick? =
        parse(json) { obj ->
            val eventName = obj["EventName"]?.jsonPrimitive?.content ?: return@parse null
            val pickInfo = obj["PickInfo"]?.jsonObject ?: return@parse null
            val cardIds = pickInfo["CardIds"]?.jsonArray ?: return@parse null
            val cardId =
                cardIds
                    .firstOrNull()
                    ?.jsonPrimitive
                    ?.content
                    ?.toIntOrNull()
                    ?: return@parse null
            DraftPick(eventName, cardId)
        }

    private inline fun <T> parse(
        json: String?,
        block: (kotlinx.serialization.json.JsonObject) -> T?,
    ): T? {
        if (json == null) return null
        return try {
            block(lenientJson.parseToJsonElement(json).jsonObject)
        } catch (e: Exception) {
            log.warn("FdRequests: parse failed: {}", e.message)
            null
        }
    }
}
