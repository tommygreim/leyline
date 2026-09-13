package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.BoardTag

/**
 * Lock down the PromptId constants the client resolves to localized strings.
 * A changed value silently swaps the prompt text the player reads, so the
 * numbers are pinned here rather than left to the call sites.
 */
class PromptIdsTest :
    FunSpec({
        tags(BoardTag)

        test("verified constants match reference values") {
            assertSoftly {
                PromptIds.PASS_PRIORITY shouldBe 2
                PromptIds.DECLARE_ATTACKERS shouldBe 6
                PromptIds.ORDER_BLOCKERS shouldBe 7
                PromptIds.ASSIGN_DAMAGE shouldBe 8
                PromptIds.SELECT_TARGETS shouldBe 10
                PromptIds.PAY_COSTS shouldBe 11
                PromptIds.CASTING_TIME_OPTIONS shouldBe 23
                PromptIds.MATCH_RESULT_WIN_LOSS shouldBe 27
                PromptIds.REVEAL_HAND shouldBe 29
                PromptIds.DRAW_CARD shouldBe 30
                PromptIds.MULLIGAN shouldBe 34
                PromptIds.STARTING_PLAYER shouldBe 37
                PromptIds.SELECT_N_LEGEND_RULE shouldBe 72
                PromptIds.GROUP_SCRY shouldBe 92
                PromptIds.GROUP_SURVEIL shouldBe 129
                PromptIds.LEARN_LESSON_OR_DISCARD shouldBe 147
                PromptIds.LEARN_LESSON_ONLY shouldBe 148
                PromptIds.SELECT_N shouldBe 97
                PromptIds.SEARCH shouldBe 1030
                PromptIds.DISCARD_COST shouldBe 1024
                PromptIds.DISCARD_TWO shouldBe 1034
                PromptIds.DISCARD_THREE shouldBe 1814
                PromptIds.DISCARD_OPTIONAL shouldBe 4482
                PromptIds.DISCARD_UP_TO_TWO shouldBe 4064
                PromptIds.DISCARD_UP_TO_THREE shouldBe 1293
                PromptIds.OPTIONAL_ACTION shouldBe 23
                PromptIds.OPTIONAL_PAY_X shouldBe 1159
                PromptIds.SELECT_N_LEGEND_RULE_SOURCE shouldBe 15168
                PromptIds.COLLECT_EVIDENCE_COST shouldBe 12727
                PromptIds.CHOOSE_OR_COST_PAY_BLIGHT shouldBe 15008
            }
        }

        test("DECLARE_ATTACKERS is distinct from SELECT_TARGETS") {
            // Previously both used the same constant name — ensure they're separate
            PromptIds.DECLARE_ATTACKERS shouldNotBe PromptIds.SELECT_TARGETS
        }
    })
