import leyline.build.configureTestDefaults
import org.gradle.api.tasks.JavaExec

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.kotlin.serialization)
    id("leyline.test-conventions")
    id("leyline.kotlin-conventions")
}

repositories {
    mavenCentral()
}

// Test-support code (headless match harness, simclient tooling) that must not
// ship in the engine jar but needs main's classes and dependencies to compile.
// Consumed by engine tests and the simclient JavaExec task below.
val harness =
    sourceSets.create("harness") {
        compileClasspath += sourceSets.main.get().output + configurations["compileClasspath"]
        runtimeClasspath += sourceSets.main.get().output + configurations["runtimeClasspath"]
    }

// Harness needs to see main's `internal` declarations (e.g. ActionMapper helpers),
// same as the built-in test <-> main friend relationship.
kotlin.target.compilations
    .getByName("harness")
    .associateWith(kotlin.target.compilations.getByName("main"))

sourceSets.named("test") {
    compileClasspath += harness.output
    runtimeClasspath += harness.output
}

// Test also needs harness's `internal` declarations (e.g. MatchFlowHarness.drainSink),
// same reason as the harness <-> main associate above.
kotlin.target.compilations
    .getByName("test")
    .associateWith(kotlin.target.compilations.getByName("harness"))

dependencies {
    implementation(platform(libs.netty.bom))
    implementation(project(":domain"))
    implementation(project(":gre-proto"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.serialization.json)
    implementation(libs.protobuf.java.util)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback.classic)
    implementation(libs.snakeyaml)
    api(libs.forge.core)
    api(libs.forge.game)
    api(libs.forge.ai)
    api(libs.forge.gui)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.archunit)
    testImplementation(libs.kotlin.reflect)
    testImplementation(harness.output)
}

tasks.named<Test>("test") {
    systemProperty("kotest.tags", "!SimClientTag & !AcceptanceTag & !ForgeCatalogTag & !ConformanceTag")
    systemProperty("leyline.content.root", rootProject.projectDir.absolutePath)
}

// Default fork count scales with the machine: ~1 fork per 4 cores, capped at 4.
// The CI ARM runners (8 cores) thrash at 4 forks — timing-sensitive specs miss
// their deadlines — so they land on 2; big dev machines get the full 4.
// Override with -PintegrationForks=N.
val integrationForks =
    (project.findProperty("integrationForks") as String?)?.toIntOrNull()
        ?: Runtime
            .getRuntime()
            .availableProcessors()
            .div(4)
            .coerceIn(1, 4)

fun registerEngineTest(
    name: String,
    configure: Test.() -> Unit,
) = tasks.register<Test>(name) {
    configureTestDefaults()
    systemProperty("leyline.content.root", rootProject.projectDir.absolutePath)
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    configure()
}

registerEngineTest("testUnit") {
    systemProperty("kotest.tags", "UnitTag")
    systemProperty("kotest.framework.parallelism", "8")
}

registerEngineTest("testBoard") {
    systemProperty("kotest.tags", "BoardTag")
    systemProperty("kotest.framework.parallelism", "8")
}

val testGate =
    registerEngineTest("testGate") {
        systemProperty("kotest.tags", "(UnitTag | BoardTag) & !SimClientTag")
        // Default 1: measured on the ARM CI runners, parallelism=4 made this step
        // 37-66% slower (283-345s at 1 vs 444/535s at 4) — no in-JVM parallel
        // headroom under job co-tenancy. Override with -PkotestParallelism for
        // local experiments.
        systemProperty("kotest.framework.parallelism", (project.findProperty("kotestParallelism") as String? ?: "1"))
    }

val testIntegration =
    registerEngineTest("testIntegration") {
        systemProperty("kotest.tags", "IntegrationTag & !AcceptanceTag & !ForgeCatalogTag & !ConformanceTag")
        maxParallelForks = integrationForks
    }

val testIntegrationStrict =
    registerEngineTest("testIntegrationStrict") {
        (project.findProperty("jfrFile") as String?)?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
        systemProperty("kotest.tags", "IntegrationTag & !AcceptanceTag & !ForgeCatalogTag & !ConformanceTag")
        maxParallelForks = integrationForks
        outputs.cacheIf { false }
        outputs.upToDateWhen { false }
    }

registerEngineTest("testForgeCatalog") {
    systemProperty("kotest.tags", "ForgeCatalogTag")
    environment("LEYLINE_CARD_DB", rootProject.projectDir.absolutePath)
    filter { includeTestsMatching("leyline.game.data.ForgeCatalog*ProbeTest") }
    classpath = classpath.filter { !it.path.contains("/build/resources/test") && !it.name.contains("sqlite-jdbc") }
    maxParallelForks = 1
    inputs.dir(rootProject.layout.projectDirectory.dir("forge/forge-gui/res/cardsfolder"))
    inputs.dir(rootProject.layout.projectDirectory.dir("forge/forge-gui/res/tokenscripts"))
}

registerEngineTest("testAcceptance") {
    systemProperty("kotest.tags", "AcceptanceTag")
    (project.findProperty("acceptanceSuites") as String?)?.let { systemProperty("acceptance.suites", it) }
    (project.findProperty("acceptanceScenarios") as String?)?.let { systemProperty("acceptance.scenarios", it) }
    (project.findProperty("acceptanceScry") as String?)?.let { systemProperty("acceptance.scry", it) }
    maxParallelForks = 1
    inputs.dir(rootProject.layout.projectDirectory.dir("data/puzzles"))
}

registerEngineTest("testConformance") {
    systemProperty("kotest.tags", "IntegrationTag")
    filter {
        includeTestsMatching("leyline.testkit.ProtocolConformanceTest")
        includeTestsMatching("leyline.testkit.ProtocolContractMutationTest")
    }
    maxParallelForks = 1
    inputs.dir(rootProject.layout.projectDirectory.dir("conformance/contracts"))
    inputs.dir(rootProject.layout.projectDirectory.dir("data/puzzles"))
}

registerEngineTest("testSimClient") {
    systemProperty("kotest.tags", "SimClientTag")
    systemProperty("kotest.framework.parallelism", (project.findProperty("kotestParallelism") as String? ?: "1"))
}

testIntegration.configure { mustRunAfter(testGate) }
testIntegrationStrict.configure { mustRunAfter(testGate) }

val simclient =
    tasks.register<JavaExec>("simclient") {
        group = "simclient"
        (project.findProperty("jfrFile") as String?)?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
        description = "Run standalone simclient deck/puzzle matrices"
        dependsOn(tasks.named("harnessClasses"))
        classpath = sourceSets["harness"].runtimeClasspath
        mainClass.set("leyline.tooling.simclient.SimClientToolKt")
        workingDir = rootProject.projectDir
        args((project.findProperty("simclientArgs") as String?)?.split(" ")?.filter { it.isNotBlank() }.orEmpty())
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
