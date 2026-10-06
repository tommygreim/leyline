package leyline

import io.kotest.core.Tag

object UnitTag : Tag()

object BoardTag : Tag()

object IntegrationTag : Tag()

object ForgeCatalogTag : Tag()

/** Authored protocol scenario contracts, isolated from routine integration runs. */
object ConformanceTag : Tag()

/** Puzzle-backed scripted acceptance suites. Run via `just test-acceptance`. */
object AcceptanceTag : Tag()

/**
 * Sim-client E2E tests — synthetic GRE log generation. Slow (drives full
 * games), so they sit outside the regular gate and run via `just test-simclient`.
 * Broad deck/puzzle matrices run through the standalone `:engine:simclient`
 * tool instead of this tag.
 */
object SimClientTag : Tag()
