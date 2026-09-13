package leyline.game.mapping

/** Protocol prompt IDs matching expected protocol values. */
object PromptIds {
    /** Protocol error envelope; this ID has no player-visible localization. */
    const val ILLEGAL_REQUEST = 3

    /** Cast a revealed card without paying its mana cost. */
    const val FREE_CAST_FROM_REVEAL = 1134
    const val PASS_PRIORITY = 2
    const val DECLARE_ATTACKERS = 6
    const val ORDER_BLOCKERS = 7
    const val ASSIGN_DAMAGE = 8
    const val SELECT_TARGETS = 10
    const val TARGET_CREATURE_YOU_CONTROL = 152
    const val TARGET_CREATURE = 1010
    const val TARGET_CREATURE_YOU_DONT_CONTROL = 1112
    const val TARGET_CREATURE_OR_PLANESWALKER_YOU_DONT_CONTROL = 2401
    const val CHOOSE_ANY_TARGET = 11869
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
    const val SELECT_REPLACEMENT = 74

    /** Static SelectN color choice — "Choose a color." */
    const val CHOOSE_COLOR = 118

    /**
     * Typecycling-shaped searches currently use the truthful generic search
     * text. Arena prompt 11626 says "Search for an Island card," so it cannot
     * serve as a fallback for Forestcycling, basic landcycling, or arbitrary
     * creature-type cycling.
     */
    const val SEARCH_TYPECYCLING = SEARCH

    /** Mandatory additional cost (discard). Client expects PayCostsReq promptId=1024. */
    const val DISCARD_COST = 1024

    /** Optional single-card discard — "Discard a card?" */
    const val DISCARD_OPTIONAL = 4482

    /** Optional two-card discard — "Discard up to two cards." */
    const val DISCARD_UP_TO_TWO = 4064

    const val DISCARD_TWO = 1034
    const val DISCARD_THREE = 1814
    const val DISCARD_UP_TO_THREE = 1293

    /** Semantically neutral fallback for a "you may" decision. */
    const val OPTIONAL_ACTION = 23

    /** Optional X-mana payment — "Pay {X}?" */
    const val OPTIONAL_PAY_X = 1159

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

    /** Collect Evidence cost picker — "Exile any number of cards with total mana value N or greater." */
    const val COLLECT_EVIDENCE_COST = 12727

    /** Gather two +1/+1 counters from controlled creatures. */
    const val GATHER_COUNTERS = 2479

    /** Enlist attack cost — "Tap a creature for {CardId} to enlist." */
    const val ENLIST_TAP_COST = 11225

    /** Station activation cost — "Tap a creature to add charge counters equal to its power." */
    const val STATION_TAP_COST = 14726

    /** Frantic Scapegoat trigger — "Suspect one of those creatures?" */
    const val SUSPECT_ONE_OF_THOSE_CREATURES = 12761

    /** Ninjutsu activation cost — "Return an unblocked attacking creature you control to its owner's hand." */
    const val NINJUTSU_RETURN_UNBLOCKED_ATTACKER_COST = 8580

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
