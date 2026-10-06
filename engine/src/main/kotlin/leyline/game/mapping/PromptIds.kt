package leyline.game.mapping

/** Protocol prompt IDs matching expected protocol values. */
object PromptIds {
    /** PromptParameter labels used by the native dynamic binary-choice workflow. */
    const val ADD_COUNTER = 1203
    const val REMOVE_COUNTER = 1204
    const val CHOOSE_NONCREATURE_NONLAND_CARD = 1243
    const val CHOOSE_NONLAND_CARD = 1032
    const val CHOOSE_OBJECT_TO_COPY = 78

    /** Protocol error envelope; this ID has no player-visible localization. */
    const val ILLEGAL_REQUEST = 3

    /** Cast a revealed card without paying its mana cost. */
    const val FREE_CAST_FROM_REVEAL = 1134

    /** Native resolution-cast browser prompts (client protocol-map). */
    const val RESOLUTION_CAST_ANY_FREE = 15600
    const val RESOLUTION_CAST_COPIES = 13322
    const val RESOLUTION_CAST_PAID = 1148
    const val PASS_PRIORITY = 2
    const val DECLARE_ATTACKERS = 6
    const val ORDER_BLOCKERS = 7
    const val ASSIGN_DAMAGE = 8
    const val SELECT_TARGETS = 10
    const val TARGET_PERMANENT = 1006
    const val TARGET_PLAYER = 1019
    const val TARGET_SPELL = 1022
    const val TARGET_ARTIFACT = 1023
    const val TARGET_OPPONENT = 1038
    const val TARGET_LAND = 1047
    const val TARGET_PLANESWALKER = 1069
    const val TARGET_ENCHANTMENT = 1126
    const val TARGET_SPELL_OR_PERMANENT = 1390
    const val TARGET_CREATURE_YOU_CONTROL = 152
    const val TARGET_CREATURE = 1010
    const val TARGET_CREATURE_YOU_DONT_CONTROL = 1112
    const val TARGET_CREATURE_OR_PLANESWALKER_YOU_DONT_CONTROL = 2401
    const val CHOOSE_ANY_TARGET = 11869
    const val TARGET_PLAYER_DRAWS_X = 15755
    const val DEAL_THREE_DAMAGE_TO_ANY_TARGET = 15845
    const val DEAL_FOUR_DAMAGE_TO_ANY_TARGET = 14412
    const val DEAL_X_DAMAGE_TO_ANY_TARGET = 15850
    const val RETURN_TARGET_SPELL_OR_PERMANENT_TO_HAND = 14411
    const val PAY_COSTS = 11
    const val CASTING_TIME_OPTIONS = 23

    /** Static SelectN choice — type and parity prompts use this loc key. */
    const val CHOOSE_TYPE = 23
    const val MATCH_RESULT_WIN_LOSS = 27

    /** Post-game "reveal hand" option. */
    const val REVEAL_HAND = 29

    /** Post-game "draw card" option. */
    const val DRAW_CARD = 30
    const val MULLIGAN = 34
    const val STARTING_PLAYER = 37

    /** Informational PromptReq after a coin flip resolves. */
    const val COIN_FLIP = 46

    /** Legend rule "choose which to keep". */
    const val SELECT_N_LEGEND_RULE = 72

    const val GROUP_SCRY = 92
    const val GROUP_SURVEIL = 129

    /** Generic library search — "Search for a card." */
    const val SEARCH = 1030
    const val SEARCH_FROM_GROUPS = SEARCH
    const val SEARCH_BASIC_LAND = 1065
    const val SEARCH_CREATURE = 1305
    const val SEARCH_ARTIFACT = 1571
    const val SEARCH_PLANESWALKER = 1684
    const val SEARCH_LAND = 2197
    const val SEARCH_FOREST = 2513
    const val SEARCH_ENCHANTMENT = 2599
    const val SEARCH_INSTANT_OR_SORCERY = 3329
    const val SEARCH_CREATURE_OR_LAND = 1114
    const val SEARCH_ARTIFACT_OR_CREATURE = 2202
    const val SEARCH_ARTIFACT_OR_ENCHANTMENT = 3332
    const val SEARCH_FARSEEK_TYPES = 3589
    const val SEARCH_INSTANT_OR_FLASH = 3713
    const val SEARCH_PLAINS = 3725
    const val SEARCH_SWAMP = 3926
    const val SEARCH_MOUNTAIN = 5595
    const val SEARCH_ISLAND = 11626
    const val SEARCH_BASIC_FOREST = 1346
    const val SEARCH_BASIC_MOUNTAIN = 11261
    const val SEARCH_UP_TO_TWO_LANDS = 1052
    const val SEARCH_UP_TO_TWO_BASIC_LANDS = 1111
    const val SEARCH_UP_TO_TWO_CREATURES = 3402
    const val SELECT_REPLACEMENT = 74

    /** Static SelectN color choice — "Choose a color." */
    const val CHOOSE_COLOR = 118

    /**
     * Legacy generic fallback for typecycling-shaped searches. Exact supported
     * filters use SearchPromptResolver; Island wording must not be a fallback
     * for Forestcycling, basic landcycling, or arbitrary creature types.
     */
    const val SEARCH_TYPECYCLING = SEARCH

    /** Mandatory additional cost (discard). Client expects PayCostsReq promptId=1024. */
    const val DISCARD_COST = 1024

    /** Optional single-card discard — "Discard a card?" */
    const val DISCARD_OPTIONAL = 4482

    /** Optional whole-hand discard — "Discard your hand?" */
    const val DISCARD_HAND_OPTIONAL = 8606

    /** Winternight Stories-style alternate — "Discard a creature card?" */
    const val DISCARD_CREATURE_OPTIONAL = 8771

    /** Optional two-card discard — "Discard up to two cards." */
    const val DISCARD_UP_TO_TWO = 4064

    const val DISCARD_TWO = 1034
    const val DISCARD_THREE = 1814
    const val DISCARD_UP_TO_THREE = 1293

    /** Semantically neutral fallback for a "you may" decision. */
    const val OPTIONAL_ACTION = 23

    /** Optional basic-land search — "Search your library for a basic land card?" */
    const val SEARCH_BASIC_LAND_OPTIONAL = 1250

    /** Optional X-mana payment — "Pay {X}?" */
    const val OPTIONAL_PAY_X = 1159

    /** Fixed energy-payment questions in the native prompt catalog. */
    val OPTIONAL_PAY_ENERGY = mapOf(1 to 1151, 2 to 1150, 3 to 12672, 4 to 13635)
    const val DREDGE_THIS_CARD = 89

    /** Commander zone replacement decision: "Move your commander to the command zone?" */
    const val COMMANDER_RETURN_TO_COMMAND = 144

    /** Learn mixed prompt: choose a Lesson from sideboard or discard a hand card. */
    const val LEARN_LESSON_OR_DISCARD = 147

    /** Learn Lesson-only prompt: choose a Lesson card owned outside the game. */
    const val LEARN_LESSON_ONLY = 148

    /** Shock land ETB "pay life or enter tapped" (OptionalActionMessage). */
    const val SHOCK_LAND_ETB = 2233

    /** Endure trigger resolution "put +1/+1 counters or create a Spirit token" (OptionalActionMessage).
     *  Loc text: "Put N +1/+1 counters on this creature?" — Yes = counters, No = Spirit token. */
    const val ENDURE_PUT_COUNTERS = 13976

    /** Clash resolution "put that card on the bottom of your library?" (OptionalActionMessage).
     *  Verified against Arena's card database. Yes = bottom, No = stays on top —
     *  the inverse sense of `willPutCardOnTop`'s return value. */
    const val CLASH_PUT_ON_BOTTOM = 3183

    /** Semantically neutral card/entity selection — "Choose items." */
    const val SELECT_N = 97

    /** Mutate target group — "Target a non-Human creature you own." */
    const val MUTATE_TARGET = 141

    /** Mentor target group — "target attacking creature with lesser power." */
    const val MENTOR_TARGET = 2247

    /**
     * Stock Up's outer-prompt loc key — "Put two of them into your hand."
     *
     * This value is Stock-Up-specific; other Dig-shape effects (Sleight of Hand,
     * Impulse, etc.) almost certainly need different loc keys. A card-specific
     * dispatcher keyed on `(sa.api == Dig, sa.hostCard.grpId, ChangeNum)` is
     * the right long-term home — today this constant is the only value we
     * have a confirmed-rendering integration for.
     */
    const val SELECT_N_STOCK_UP = 2490

    /** Manifest Dread: choose one of the top two cards to manifest. */
    const val MANIFEST_DREAD = 13125

    /** Inner prompt parameter used by Manifest Dread's resolution picker. */
    const val MANIFEST_DREAD_INNER_PARAMETER = 1

    /** Brainstorm-style putback prompt: choose cards to put into your library before ordering. */
    const val SELECT_N_LIBRARY_PUTBACK = 2035

    /** Inner SelectNReq.prompt PromptId Parameter value for look-and-pick prompts.
     *  The literal `2` is opaque (no dictionary entry) but is the value the
     *  client expects on this slot for resolution-time pick prompts. */
    const val SELECT_N_INNER_PARAMETER = 2

    /** Inner SelectNReq.prompt PromptId Parameter value for Learn prompts. */
    const val SELECT_N_LEARN_INNER_PARAMETER = 1

    const val CHOOSE_OR_COST = 1103
    const val CHOOSE_OR_COST_PAY_SACRIFICE = 1029
    const val CHOOSE_OR_COST_PAY_BLIGHT = 15008

    /** Pay-cost-via-select for "exile N from graveyard" — Escape's additional cost. */
    const val CHOOSE_OR_COST_PAY_EXILE_FROM_GRAVE = 5500

    /** Pay-cost-via-select for "discard a card" (one card; the only count this
     *  keyword uses as an alternate additional cost, e.g. Bitter Triumph's
     *  `AlternateAdditionalCost:PayLife<3>:Discard<1/Card>`). Same text as
     *  [DISCARD_COST]; verified against Arena's card database (promptId 1024
     *  -> "Discard a card."). */
    const val CHOOSE_OR_COST_DISCARD = DISCARD_COST

    /** Pay-cost-via-select for "pay N life", keyed by N. Verified against Arena's
     *  card database: each promptId's loc text is "Pay {N} life." — a statement,
     *  matching the other CHOOSE_OR_COST_* constants, not the "Pay {N} life?"
     *  question form used elsewhere. Covers every PayLife<N> amount seen in an
     *  AlternateAdditionalCost line across the card pool (3, 4, 5). */
    val CHOOSE_OR_COST_PAY_LIFE =
        mapOf(
            1 to 4215,
            2 to 4271,
            3 to 4275,
            4 to 4249,
            5 to 4214,
            6 to 4327,
            7 to 4326,
            10 to 7022,
            50 to 4244,
        )

    /** Collect Evidence cost picker — "Exile any number of cards with total mana value N or greater." */
    const val COLLECT_EVIDENCE_COST = 12727

    /** Gather two +1/+1 counters from controlled creatures. */
    const val GATHER_COUNTERS = 2479

    /** Enlist attack cost — "Tap a creature for {CardId} to enlist." */
    const val ENLIST_TAP_COST = 11225

    /** Station activation cost — "Tap a creature to add charge counters equal to its power." */
    const val STATION_TAP_COST = 14726

    /** Combat-warning prompt IDs from Arena's PromptMessage enum. */
    const val WARNING_INSUFFICIENT_BLOCKERS = 117
    const val WARNING_BLOCKER_CANNOT_BLOCK_ALONE = 119
    const val WARNING_ATTACKER_CANNOT_ATTACK_ALONE = 120
    const val WARNING_ATTACKER_MUST_BE_BLOCKED = 121
    const val WARNING_MUST_ATTACK_WITH_AT_LEAST_ONE = 122
    const val WARNING_MUST_ATTACK = 124
    const val WARNING_MUST_BLOCK = 125
    const val WARNING_ATTACKER_MUST_BE_BLOCKED_BY_ALL = 130

    /** Frantic Scapegoat trigger — "Suspect one of those creatures?" */
    const val SUSPECT_ONE_OF_THOSE_CREATURES = 12761

    /** Ninjutsu activation cost — "Return an unblocked attacking creature you control to its owner's hand." */
    const val NINJUTSU_RETURN_UNBLOCKED_ATTACKER_COST = 8580

    /** Web-slinging additional cost: return a tapped creature you control. */
    const val WEB_SLINGING_RETURN_TAPPED_CREATURE_COST = 15006

    /** sourceId on SelectNReq for legend rule. */
    const val SELECT_N_LEGEND_RULE_SOURCE = 15168

    /** Numeric input — "Choose X" / pay X stepper. Single observed loc key for X-cost prompts. */
    const val NUMERIC_INPUT = 51

    /** Order cards being placed on the bottom of a library. */
    const val ORDER_LIBRARY_BOTTOM = 42

    /** Order cards being placed on top of a library. */
    const val ORDER_LIBRARY_TOP = 86

    /** Divided damage allocation across already-selected targets. */
    const val DISTRIBUTE_DAMAGE = 2234

    /** Divided counter allocation across already-selected targets. */
    const val DISTRIBUTE_COUNTERS = 4051
}
