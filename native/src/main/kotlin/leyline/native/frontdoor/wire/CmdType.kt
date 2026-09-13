package leyline.native.frontdoor.wire

/**
 * Front Door command type — wraps the CmdType varint from FD envelopes.
 *
 * Named companions for handled types; [name] for human-readable logging.
 * Not an enum because the client has 600+ types and adds more with patches —
 * unknown values must be representable.
 */
@JvmInline
value class CmdType(
    val value: Int,
) {
    fun name(): String = NAMES[value] ?: "Unknown($value)"

    override fun toString(): String = name()

    companion object {
        // --- Auth & startup ---
        val AUTHENTICATE = CmdType(0)
        val START_HOOK = CmdType(1)
        val ATTACH = CmdType(5)
        val GET_FORMATS = CmdType(6)

        // --- Decks ---
        val DECK_GET = CmdType(400)
        val DECK_DELETE = CmdType(403)
        val DECK_UPSERT_V2 = CmdType(406)
        val DECK_GET_SUMMARIES_V2 = CmdType(407)
        val DECK_GET_PRECONS_V3 = CmdType(410)
        val DECK_GET_SUMMARIES_V3 = CmdType(411)
        val DECK_UPSERT_V3 = CmdType(412)

        // --- Cards ---
        val CARD_GET_ALL = CmdType(551)

        // --- Events ---
        val EVENT_JOIN = CmdType(600)
        val EVENT_DROP = CmdType(601)
        val EVENT_ENTER_PAIRING = CmdType(603)
        val EVENT_LEAVE_PAIRING = CmdType(606)
        val EVENT_CLAIM_PRIZE = CmdType(607)
        val EVENT_GET_MATCH_RESULT = CmdType(608)
        val EVENT_AI_BOT_MATCH = CmdType(612)
        val EVENT_RESIGN = CmdType(609)
        val EVENT_GET_ACTIVE_MATCHES = CmdType(613)
        val EVENT_SET_JUMPSTART_PACKET = CmdType(614)
        val EVENT_SET_DECK_V2 = CmdType(622)
        val EVENT_GET_COURSES_V2 = CmdType(623)
        val EVENT_GET_ACTIVE_EVENTS_V2 = CmdType(624)
        val EVENT_SET_COURSE_DECK = CmdType(627)

        // --- Draft ---
        val EVENT_PLAYER_DRAFT_CONFIRM_CARD_POOL_GRANT = CmdType(621)
        val BOT_DRAFT_START = CmdType(1800)
        val BOT_DRAFT_PICK = CmdType(1801)
        val BOT_DRAFT_STATUS = CmdType(1802)
        val DRAFT_COMPLETE_DRAFT = CmdType(1908)

        // --- Store ---
        val CAROUSEL_GET_ITEMS = CmdType(704)
        val MERC_GET_STORE_STATUS_V2 = CmdType(708)
        val STORE_GET_ENTITLEMENTS_V2 = CmdType(712)
        val MERC_GET_SKUS_AND_LISTINGS = CmdType(715)
        val CURRENCY_GET_CURRENCIES = CmdType(800)
        val BOOSTER_GET_OWNED = CmdType(901)

        // --- Progress ---
        val QUEST_GET_QUESTS = CmdType(1000)
        val RANK_GET_COMBINED = CmdType(1100)
        val RANK_GET_SEASON_DETAILS = CmdType(1102)
        val RANK_EVALUATE_PAYOUTS_V2 = CmdType(1105)
        val PERIODIC_REWARDS_GET_STATUS = CmdType(1200)
        val RENEWAL_GET_CURRENT = CmdType(1201)

        // --- Misc ---
        val GET_VOUCHER_DEFINITIONS = CmdType(1520)
        val GET_SETS = CmdType(1521)
        val GRAPH_GET_DEFINITIONS = CmdType(1700)
        val GRAPH_GET_STATE = CmdType(1701)
        val GRAPH_ADVANCE_NODE = CmdType(1703)
        val COSMETICS_GET_OWNED = CmdType(1900)
        val GET_PLAY_BLADE_QUEUE_CONFIG = CmdType(1910)
        val GET_PLAYER_PREFERENCES = CmdType(1911)
        val SET_PLAYER_PREFERENCES = CmdType(1912)
        val LOG_BUSINESS_EVENTS = CmdType(1913)
        val LOG_BUSINESS_EVENTS_V2 = CmdType(1914)
        val GET_NET_DECK_FOLDERS = CmdType(2200)
        val GET_PLAYER_INBOX = CmdType(2300)
        val GET_DESIGNER_METADATA = CmdType(2400)
        val STATIC_CONTENT = CmdType(2500)
        val GET_ALL_PREFERRED_PRINTINGS = CmdType(2600)
        val GET_ALL_PRIZE_WALLS = CmdType(2700)
        val CHALLENGE_RECONNECT_ALL = CmdType(3006)

        /** Human-readable name for any CmdType value, including unknown ones. */
        fun nameOf(code: Int): String = NAMES[code] ?: "Unknown($code)"

        /** CmdType enum values → names (from protocol analysis). */
        private val NAMES =
            mapOf(
                0 to "Authenticate",
                1 to "StartHook",
                2 to "Scaling_Passthrough",
                5 to "Attach",
                6 to "GetFormats",
                7 to "ForceDetach",
                400 to "Deck_GetDeck",
                401 to "Deck_GetDeckSummaries",
                403 to "Deck_DeleteDeck",
                406 to "Deck_UpsertDeckV2",
                407 to "Deck_GetDeckSummariesV2",
                410 to "Deck_GetAllPreconDecksV3",
                411 to "Deck_GetDeckSummariesV3",
                412 to "Deck_UpsertDeckV3",
                550 to "Card_GetCardSet",
                551 to "Card_GetAllCards",
                552 to "Card_RedeemWildCards",
                600 to "Event_Join",
                601 to "Event_Drop",
                602 to "Event_SetDeck",
                603 to "Event_EnterPairing",
                604 to "Event_GetActiveEvents",
                605 to "Event_GetCourses",
                606 to "Event_LeavePairing",
                607 to "Event_ClaimPrize",
                608 to "Event_GetMatchResultReport",
                609 to "Event_Resign",
                610 to "Event_GetChoices",
                611 to "Event_SetChoice",
                612 to "Event_AiBotMatch",
                613 to "Event_GetActiveMatches",
                614 to "Event_SetJumpStartPacket",
                615 to "Event_JoinDraftQueue",
                616 to "Event_LeaveDraftQueue",
                617 to "Event_PlayerDraftReadyPlayer",
                618 to "Event_PlayerDraftGetTablePacks",
                619 to "Event_JoinDraft",
                620 to "Event_PlayerDraftMakePick",
                621 to "Event_PlayerDraftConfirmCardPoolGrant",
                622 to "Event_SetDeckV2",
                623 to "Event_GetCoursesV2",
                624 to "Event_GetActiveEventsV2",
                625 to "Event_PlayerDraftReserveCard",
                626 to "Event_PlayerDraftClearReservedCard",
                627 to "Event_SetCourseDeck",
                1800 to "BotDraft_StartDraft",
                1801 to "BotDraft_DraftPick",
                1802 to "BotDraft_DraftStatus",
                1908 to "Draft_CompleteDraft",
                703 to "Store_GetEntitlements",
                704 to "Carousel_GetCarouselItems",
                708 to "Merc_GetStoreStatusV2",
                712 to "Store_GetEntitlementsV2",
                715 to "Merc_GetSkusAndListings",
                800 to "Currency_GetCurrencies",
                901 to "Booster_GetOwnedBoosters",
                1000 to "Quest_GetQuests",
                1100 to "Rank_GetCombinedRankInfo",
                1102 to "Rank_GetSeasonAndRankDetails",
                1105 to "Rank_EvaluatePayoutsV2",
                1200 to "PeriodicRewards_GetStatus",
                1201 to "Renewal_GetCurrentRenewal",
                1520 to "GetVoucherDefinitions",
                1521 to "GetSets",
                1700 to "Graph_GetGraphDefinitions",
                1701 to "Graph_GetGraphState",
                1702 to "Graph_Process",
                1703 to "Graph_AdvanceNode",
                1900 to "Cosmetics_GetPlayerOwnedCosmetics",
                1910 to "GetPlayBladeQueueConfig",
                1911 to "GetPlayerPreferences",
                1912 to "SetPlayerPreferences",
                1913 to "LogBusinessEvents",
                1914 to "LogBusinessEventsV2",
                2200 to "GetNetDeckFolders",
                2300 to "GetPlayerInbox",
                2400 to "GetDesignerMetadata",
                2500 to "StaticContent",
                2600 to "GetAllPreferredPrintings",
                2700 to "GetAllPrizeWalls",
                3000 to "ChallengeJoin",
                3001 to "ChallengeCreate",
                3002 to "ChallengeSendMessage",
                3003 to "ChallengeExit",
                3004 to "ChallengeInvite",
                3005 to "ChallengeClose",
                3006 to "ChallengeReconnectAll",
                3007 to "ChallengeKick",
                3008 to "ChallengeReady",
                3009 to "ChallengeUnready",
                3010 to "ChallengeSetSettings",
                3011 to "ChallengeIssue",
                3012 to "ChallengeStartLaunchCountdown",
            )
    }
}
