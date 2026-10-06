package leyline.testkit

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.IntegrationTag
import leyline.acceptance.AcceptanceSuiteLoader
import leyline.acceptance.MatchdoorAcceptanceExecutor
import leyline.testkit.ProtocolContract

/**
 * Executes every authored protocol contract against its deterministic acceptance scenario.
 * Scenario steps own gameplay intent; YAML owns the emitted protocol obligations.
 * Checker regressions run separately, so adding a contract requires no Kotlin dispatch.
 */
class ProtocolConformanceTest :
    FunSpec({
        tags(IntegrationTag, ConformanceTag)
        val paths = ProtocolContract.files()
        require(paths.isNotEmpty()) { "no protocol contracts" }
        for (path in paths) {
            val contract = ProtocolContract.load(path)
            test(contract.name) {
                val scenario = AcceptanceSuiteLoader.load(contract.suite).scenarios.single { it.id == contract.scenario }
                MatchdoorAcceptanceExecutor().runScenario(scenario) { messages ->
                    withClue(contract.name) { contract.verify(messages) }
                } shouldBe scenario.steps.size
            }
        }
    })
