package leyline.native.frontdoor

import leyline.native.frontdoor.service.LobbyStubs
import leyline.native.frontdoor.wire.CmdType
import leyline.native.frontdoor.wire.FdResponse

/** Static lobby responses shared by authenticated Front Door sessions. */
internal fun frontDoorStubs(bootstrapData: FrontDoorBootstrapData): Map<Int, () -> FdResponse> =
    mapOf(
        // Static bootstrap data (proto)
        CmdType.GET_FORMATS.value to { FdResponse.RawProto(bootstrapData.getFormatsProto) },
        CmdType.GET_SETS.value to { FdResponse.RawProto(bootstrapData.getSetsProto) },
        // Static bootstrap data (JSON)
        CmdType.DECK_GET_PRECONS_V3.value to { FdResponse.Json(bootstrapData.preconDecksJson) },
        CmdType.CAROUSEL_GET_ITEMS.value to { FdResponse.Json("[]") },
        CmdType.GRAPH_GET_DEFINITIONS.value to { FdResponse.Json(bootstrapData.graphDefinitionsJson) },
        CmdType.GET_DESIGNER_METADATA.value to { FdResponse.Json(bootstrapData.designerMetadataJson) },
        // Lobby stubs
        CmdType.EVENT_GET_ACTIVE_MATCHES.value to { FdResponse.Json(LobbyStubs.activeMatches()) },
        CmdType.CURRENCY_GET_CURRENCIES.value to { FdResponse.Json(LobbyStubs.currencies()) },
        CmdType.BOOSTER_GET_OWNED.value to { FdResponse.Json(LobbyStubs.boosters()) },
        CmdType.QUEST_GET_QUESTS.value to { FdResponse.Json(LobbyStubs.quests()) },
        CmdType.RANK_GET_COMBINED.value to { FdResponse.Json(LobbyStubs.rankInfo()) },
        CmdType.RANK_GET_SEASON_DETAILS.value to { FdResponse.Json(LobbyStubs.rankSeasonDetails()) },
        CmdType.RANK_EVALUATE_PAYOUTS_V2.value to { FdResponse.Json(LobbyStubs.rankSeasonDetails()) },
        CmdType.PERIODIC_REWARDS_GET_STATUS.value to { FdResponse.Json(LobbyStubs.periodicRewards()) },
        CmdType.RENEWAL_GET_CURRENT.value to { FdResponse.Json(LobbyStubs.periodicRewards()) },
        CmdType.COSMETICS_GET_OWNED.value to { FdResponse.Json(LobbyStubs.cosmetics()) },
        CmdType.GET_NET_DECK_FOLDERS.value to { FdResponse.Json(LobbyStubs.netDeckFolders()) },
        CmdType.STATIC_CONTENT.value to { FdResponse.Json(LobbyStubs.staticContent()) },
        CmdType.GET_ALL_PREFERRED_PRINTINGS.value to { FdResponse.Json(LobbyStubs.preferredPrintings()) },
        CmdType.GET_ALL_PRIZE_WALLS.value to { FdResponse.Json(LobbyStubs.prizeWalls()) },
        CmdType.MERC_GET_STORE_STATUS_V2.value to { FdResponse.Json(LobbyStubs.storeStatus()) },
        CmdType.STORE_GET_ENTITLEMENTS_V2.value to { FdResponse.Json(LobbyStubs.entitlements()) },
        CmdType.MERC_GET_SKUS_AND_LISTINGS.value to { FdResponse.Json(LobbyStubs.skusAndListings()) },
        CmdType.LOG_BUSINESS_EVENTS.value to { FdResponse.Json(LobbyStubs.telemetryAck()) },
        CmdType.LOG_BUSINESS_EVENTS_V2.value to { FdResponse.Json(LobbyStubs.telemetryAck()) },
        // Typed proto stubs
        CmdType.GET_VOUCHER_DEFINITIONS.value to {
            FdResponse.TypedProto(
                "Wizards.Arena.Models.Network.GetVoucherDefinitionsResponse",
            )
        },
        CmdType.CHALLENGE_RECONNECT_ALL.value to { FdResponse.TypedProto("Wizards.Arena.Models.Network.ChallengeReconnectAllResp") },
    )
