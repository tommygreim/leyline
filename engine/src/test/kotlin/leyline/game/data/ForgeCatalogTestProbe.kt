package leyline.game.data

import leyline.tooling.headless.MatchFlowHarness

/** Explicit catalog-only fixture: no native metadata or shared registry mutation. */
internal fun forgeCatalogProbe(
    name: String,
    board: String = "",
    puzzleText: String? = null,
    block: MatchFlowHarness.(ForgeCardRepository) -> Unit,
) {
    val repo = ForgeCardRepository.open()
    val harness = MatchFlowHarness(cardRepositoryOverride = repo)
    val defaultPuzzle =
        """
        [metadata]
        Name:Catalog $name
        Goal:Survive
        Turns:5
        Difficulty:Easy
        Description:Verify catalog casting.
        [state]
        ActivePlayer=Human
        ActivePhase=Main1
        HumanLife=20
        AILife=20
        humanlibrary=Forest;Forest;Forest;Forest;Forest
        ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
        """.trimIndent() + "\n" + board
    try {
        harness.connect(puzzleText = puzzleText ?: defaultPuzzle)
        harness.block(repo)
    } finally {
        harness.shutdown()
    }
}
