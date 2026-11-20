// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.equivalence

import com.github.albertocavalcante.gvy.build.bsp.BspBuildTool
import com.github.albertocavalcante.gvy.build.equivalence.MavenEquivalenceWorkspace.Companion.REPOSITORY
import com.github.albertocavalcante.gvy.build.equivalence.MavenEquivalenceWorkspace.Companion.WORKSPACE
import com.github.albertocavalcante.gvy.build.maven.AetherSessionFactory
import com.github.albertocavalcante.gvy.build.maven.MavenBuildTool
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.time.measureTimedValue

/**
 * Imports the same Maven workspace in-process ([MavenBuildTool]) and over the Build Server Protocol
 * ([BspBuildTool] launching the standalone `maven-bsp` server as a child process over stdio), then checks
 * each result against a checked-in oracle and the two results against each other.
 *
 * The oracle is the correct import of the fixture, written by hand, so a defect both paths share still fails.
 * A known divergence stays executable: the case pins today's result next to the oracle with a `FIXME(#n)`,
 * so fixing the defect fails the test until the pin is removed.
 *
 * Network: none is needed. Every artifact comes from a generated local repository, and the in-process
 * import's Maven CLI fallback is replaced by a wrapper that refuses to run.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class MavenBspEquivalenceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var processGuard: ChildProcessGuard

    @BeforeEach
    fun setUp() {
        processGuard = ChildProcessGuard()
        mockkObject(AetherSessionFactory)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        val leftovers = processGuard.killLeftovers(LEFTOVER_GRACE)
        assertTrue(leftovers.isEmpty()) { "Server processes outlived the test and were killed: $leftovers" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `in-process and BSP Maven imports agree with the oracle and with each other`(case: EquivalenceCase) {
        val workspace = MavenEquivalenceWorkspace.create(tempDir, case.fixture, case.directories, FIXTURE_ARTIFACTS)
        // Only the local repository's location is redirected; resolution itself is the production resolver.
        every { AetherSessionFactory.resolveLocalRepository() } returns workspace.localRepository

        val (inProcessResult, inProcessTime) = measureTimedValue { MavenBuildTool().resolve(workspace.root) }
        val mavenCliInvoked = workspace.mavenCliInvoked
        val (protocolResult, protocolTime) = measureTimedValue { BspBuildTool().resolve(workspace.root) }
        val (capture, captureTime) = measureTimedValue { workspace.captureProtocol() }
        val inProcess = inProcessResult.toCanonical(workspace.paths)
        val protocol = protocolResult.toCanonical(workspace.paths)

        val expectedDivergence = pairwiseDifferences(case.inProcessToday, case.protocolToday)
        val actualDivergence = pairwiseDifferences(inProcess, protocol)
        val expectedTargets = case.targetsToday.map(TargetSummary::sorted).sortedBy { it.id }
        val connected = capture as? ProtocolCapture.Connected
        val targets = connected?.targets?.map(ProtocolTarget::summary)
        val droppedByClient = connected?.let { artifactsDroppedByClient(it, protocol) }

        val report = report(case, inProcess, mavenCliInvoked, protocol, actualDivergence, capture, droppedByClient) +
            "\n--- wall time: in-process $inProcessTime, BspBuildTool $protocolTime, per-target capture $captureTime"
        println(report)

        // The report is the heading, so a failure in CI carries both projections and their differences once.
        assertAll(
            report,
            {
                assertEquals(case.inProcessToday.sorted(), inProcess.sorted()) {
                    "In-process import differs from the oracle"
                }
            },
            {
                assertEquals(case.inProcessCallsMavenToday, mavenCliInvoked) {
                    "In-process Maven CLI fallback differs from the expectation"
                }
            },
            {
                assertEquals(case.protocolToday.sorted(), protocol.sorted()) {
                    "BSP import differs from the oracle"
                }
            },
            {
                assertEquals(expectedDivergence, actualDivergence) {
                    "The imports differ beyond the allowlist and the pinned FIXMEs"
                }
            },
            {
                assertEquals(expectedTargets, targets) {
                    "Per-target protocol results differ from the oracle"
                }
            },
            {
                assertEquals(emptyList<String>(), droppedByClient) {
                    "BspBuildTool dropped artifacts the server reported"
                }
            },
        )
    }

    /**
     * Every difference the protocol path may have from the in-process path, with the field and the reason.
     * Any other difference fails. Add an entry only for a difference a reviewer can confirm is legitimate;
     * a real divergence gets an issue and a pinned `FIXME(#n)` in its case instead.
     */
    private enum class AllowedProtocolDifference(val field: String, val reason: String) {
        DEPENDENCY_ORDER(
            field = "dependencies (order)",
            reason = "The protocol path concatenates per-target lists in server order and drops duplicates; " +
                "the in-process path returns one resolver walk. The flat list has no order contract; target " +
                "classpath order belongs to targetEvidence, which keeps it.",
        ),
        SOURCE_DIRECTORY_ORDER(
            field = "sourceDirectories (order)",
            reason = "Per-target source items in server order versus a fixed directory list; no order contract.",
        ),
        EVIDENCE_PRODUCER(
            field = "targetEvidence.snapshots[].producer",
            reason = "Each path names its own producer; provenance identifies the observer, not a classpath fact.",
        ),
        ;

        fun reconcile(resolution: CanonicalResolution): CanonicalResolution = when (this) {
            DEPENDENCY_ORDER -> resolution.copy(dependencies = resolution.dependencies.sorted())
            SOURCE_DIRECTORY_ORDER -> resolution.copy(sourceDirectories = resolution.sourceDirectories.sorted())
            EVIDENCE_PRODUCER -> resolution.copy(targetEvidence = withoutProducer(resolution.targetEvidence))
        }

        private fun withoutProducer(evidence: CanonicalEvidence): CanonicalEvidence = when (evidence) {
            CanonicalEvidence.Legacy -> evidence
            is CanonicalEvidence.Scoped -> evidence.copy(
                snapshots = evidence.snapshots.map { it.copy(producer = "<allowed to differ>") },
            )
        }
    }

    /** One fixture with its correct result and, where a FIXME pins it, today's result per path. */
    class EquivalenceCase internal constructor(
        val name: String,
        val fixture: String,
        val directories: List<String>,
        internal val oracle: CanonicalResolution,
        internal val targetsOracle: List<TargetSummary>,
        internal val knownGaps: KnownGaps = KnownGaps(),
    ) {
        internal val inProcessToday: CanonicalResolution get() = knownGaps.inProcess ?: oracle
        internal val inProcessCallsMavenToday: Boolean get() = knownGaps.inProcessCallsMaven
        internal val protocolToday: CanonicalResolution get() = knownGaps.protocol ?: oracle
        internal val targetsToday: List<TargetSummary> get() = knownGaps.targets ?: targetsOracle

        override fun toString(): String = name
    }

    /** Today's results where a tracked defect makes them differ from the oracle; null means "as the oracle". */
    internal data class KnownGaps(
        val inProcess: CanonicalResolution? = null,
        val inProcessCallsMaven: Boolean = false,
        val protocol: CanonicalResolution? = null,
        val targets: List<TargetSummary>? = null,
    )

    companion object {
        private val LEFTOVER_GRACE: Duration = Duration.ofSeconds(10)

        private val GROOVY = FixtureArtifact("org.apache.groovy", "groovy", "4.0.23")
        private val HAMCREST = FixtureArtifact("org.hamcrest", "hamcrest-core", "1.3")
        private val JUNIT = FixtureArtifact("junit", "junit", "4.13.2", dependencies = listOf(HAMCREST))
        private val JENKINS_CORE = FixtureArtifact("org.jenkins-ci.main", "jenkins-core", "2.401.3")
        private val FIXTURE_ARTIFACTS = listOf(GROOVY, HAMCREST, JUNIT, JENKINS_CORE)

        // Oracle values are literal so they do not share code with either import.
        private const val GROOVY_JAR = "$REPOSITORY/org/apache/groovy/groovy/4.0.23/groovy-4.0.23.jar"
        private const val JUNIT_JAR = "$REPOSITORY/junit/junit/4.13.2/junit-4.13.2.jar"
        private const val HAMCREST_JAR = "$REPOSITORY/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"
        private const val JENKINS_CORE_JAR =
            "$REPOSITORY/org/jenkins-ci/main/jenkins-core/2.401.3/jenkins-core-2.401.3.jar"

        private const val GROOVY_MODULE = "org.apache.groovy:groovy:4.0.23"
        private const val JUNIT_MODULE = "junit:junit:4.13.2"
        private const val HAMCREST_MODULE = "org.hamcrest:hamcrest-core:1.3"
        private const val JENKINS_CORE_MODULE = "org.jenkins-ci.main:jenkins-core:2.401.3"

        private val singleModuleOracle = CanonicalResolution(
            status = "Success",
            dependencies = listOf(GROOVY_JAR, JUNIT_JAR, HAMCREST_JAR),
            sourceDirectories = listOf("$WORKSPACE/src/main/groovy", "$WORKSPACE/src/test/groovy"),
            targetEvidence = CanonicalEvidence.Legacy,
        )
        private val singleModuleTargets = listOf(
            TargetSummary(
                id = "maven:com.example:simple-app",
                sources = listOf("$WORKSPACE/src/main/groovy"),
                modules = listOf(GROOVY_MODULE),
            ),
            TargetSummary(
                id = "maven:com.example:simple-app:test",
                sources = listOf("$WORKSPACE/src/test/groovy"),
                modules = listOf(JUNIT_MODULE, GROOVY_MODULE, HAMCREST_MODULE),
            ),
        )
        private val singleModule = EquivalenceCase(
            name = "single-module",
            fixture = "single-module",
            directories = listOf("src/main/groovy", "src/test/groovy"),
            oracle = singleModuleOracle,
            targetsOracle = singleModuleTargets,
            // FIXME(#2289): The BSP server reports no Groovy source directories; these pins record today's result.
            //   See: https://github.com/albertocavalcante/gvy/issues/2289
            knownGaps = KnownGaps(
                protocol = singleModuleOracle.copy(sourceDirectories = emptyList()),
                targets = singleModuleTargets.map { it.copy(sources = emptyList()) },
            ),
        )

        /** `provided` scope and a non-JAR packaging (`hpi`); no `Jenkinsfile`, so no jenkins-core injection. */
        private val jenkinsStyleOracle = CanonicalResolution(
            status = "Success",
            dependencies = listOf(JENKINS_CORE_JAR, GROOVY_JAR),
            sourceDirectories = listOf("$WORKSPACE/src/main/groovy"),
            targetEvidence = CanonicalEvidence.Legacy,
        )
        private val jenkinsStyleTargets = listOf(
            TargetSummary(
                id = "maven:org.jenkins-ci.plugins:example-plugin",
                sources = listOf("$WORKSPACE/src/main/groovy"),
                modules = listOf(GROOVY_MODULE, JENKINS_CORE_MODULE),
            ),
            TargetSummary(
                id = "maven:org.jenkins-ci.plugins:example-plugin:test",
                sources = emptyList(),
                modules = listOf(GROOVY_MODULE, JENKINS_CORE_MODULE),
            ),
        )
        private val jenkinsStyle = EquivalenceCase(
            name = "jenkins-style",
            fixture = "jenkins-style",
            directories = listOf("src/main/groovy"),
            oracle = jenkinsStyleOracle,
            targetsOracle = jenkinsStyleTargets,
            // FIXME(#2289): The BSP server reports no Groovy source directories; these pins record today's result.
            //   See: https://github.com/albertocavalcante/gvy/issues/2289
            knownGaps = KnownGaps(
                protocol = jenkinsStyleOracle.copy(sourceDirectories = emptyList()),
                targets = jenkinsStyleTargets.map { it.copy(sources = emptyList()) },
            ),
        )

        /**
         * module-b depends on its sibling module-a and on junit. The flat model reaches module-a through its
         * source directories, so the oracle lists no class directory for it; `module-a/target/classes` exists
         * so that a path leaking it, or dropping it after the server reported it, is visible.
         */
        private val multiModuleOracle = CanonicalResolution(
            status = "Success",
            dependencies = listOf(GROOVY_JAR, JUNIT_JAR, HAMCREST_JAR),
            sourceDirectories = listOf(
                "$WORKSPACE/module-a/src/main/groovy",
                "$WORKSPACE/module-b/src/main/groovy",
                "$WORKSPACE/module-b/src/test/groovy",
            ),
            targetEvidence = CanonicalEvidence.Legacy,
        )
        private val multiModuleTargets = listOf(
            TargetSummary(
                id = "maven:com.example:module-a",
                sources = listOf("$WORKSPACE/module-a/src/main/groovy"),
                modules = listOf(GROOVY_MODULE),
            ),
            TargetSummary(
                id = "maven:com.example:module-a:test",
                sources = emptyList(),
                modules = listOf(GROOVY_MODULE),
            ),
            // A sibling is a target dependency, not a dependency module; its external modules still count.
            TargetSummary(
                id = "maven:com.example:module-b",
                sources = listOf("$WORKSPACE/module-b/src/main/groovy"),
                modules = listOf(GROOVY_MODULE),
            ),
            TargetSummary(
                id = "maven:com.example:module-b:test",
                sources = listOf("$WORKSPACE/module-b/src/test/groovy"),
                modules = listOf(JUNIT_MODULE, GROOVY_MODULE, HAMCREST_MODULE),
            ),
        )
        private val multiModule = EquivalenceCase(
            name = "multi-module",
            fixture = "multi-module",
            directories = listOf(
                "module-a/src/main/groovy",
                "module-a/target/classes",
                "module-b/src/main/groovy",
                "module-b/src/test/groovy",
            ),
            oracle = multiModuleOracle,
            targetsOracle = multiModuleTargets,
            knownGaps = KnownGaps(
                // FIXME(#2290): The in-process import reads only the root POM, falls back to the Maven CLI and
                //   reports Success with nothing imported; these pins record today's result.
                //   See: https://github.com/albertocavalcante/gvy/issues/2290
                inProcess = multiModuleOracle.copy(dependencies = emptyList(), sourceDirectories = emptyList()),
                inProcessCallsMaven = true,
                // FIXME(#2291): The unresolvable sibling erases module-b's whole classpath (junit, hamcrest-core),
                //   so only module-a's groovy survives the flattening; these pins record today's result.
                //   See: https://github.com/albertocavalcante/gvy/issues/2291
                // FIXME(#2289): The BSP server reports no Groovy source directories.
                //   See: https://github.com/albertocavalcante/gvy/issues/2289
                protocol = multiModuleOracle.copy(dependencies = listOf(GROOVY_JAR), sourceDirectories = emptyList()),
                targets = multiModuleTargets.map { target ->
                    val isModuleB = target.id.startsWith("maven:com.example:module-b")
                    target.copy(sources = emptyList(), modules = if (isModuleB) emptyList() else target.modules)
                },
            ),
        )

        @JvmStatic
        fun cases(): List<EquivalenceCase> = listOf(singleModule, jenkinsStyle, multiModule)

        private fun pairwiseDifferences(inProcess: CanonicalResolution, protocol: CanonicalResolution): List<String> {
            var left = inProcess
            var right = protocol
            for (allowed in AllowedProtocolDifference.entries) {
                left = allowed.reconcile(left)
                right = allowed.reconcile(right)
            }
            return differences(left, right, leftName = "in-process", rightName = "protocol")
        }

        /** Server-reported artifacts (classifier-less, any kind or extension) missing from the flat BSP result. */
        private fun artifactsDroppedByClient(
            capture: ProtocolCapture.Connected,
            protocol: CanonicalResolution,
        ): List<String> {
            val reported = capture.targets
                .flatMap { it.dependencyModules }
                .flatMap { module -> module.artifacts.filter { it.classifier.isNullOrEmpty() } }
                .map { it.path }
                .distinct()
            return reported.filterNot { it in protocol.dependencies }
        }

        @Suppress("LongParameterList") // One report needs every observation of the case.
        private fun report(
            case: EquivalenceCase,
            inProcess: CanonicalResolution,
            mavenCliInvoked: Boolean,
            protocol: CanonicalResolution,
            divergence: List<String>,
            capture: ProtocolCapture,
            droppedByClient: List<String>?,
        ): String = buildString {
            appendLine("===== Maven import equivalence: ${case.name} =====")
            appendLine("--- in-process (MavenBuildTool); Maven CLI fallback invoked: $mavenCliInvoked")
            appendLine(inProcess.render())
            appendLine("--- protocol (BspBuildTool, server in a stdio child process)")
            appendLine(protocol.render())
            appendLine("--- oracle (correct result)")
            appendLine(case.oracle.render())
            val allowed = AllowedProtocolDifference.entries.joinToString { it.field }
            appendLine("--- in-process vs protocol, allowing: $allowed")
            if (divergence.isEmpty()) appendLine("no differences") else divergence.forEach { appendLine(it) }
            appendLine("--- protocol per target (before BspBuildTool flattens it)")
            when (capture) {
                is ProtocolCapture.Connected -> capture.targets.forEach { appendLine(it) }
                is ProtocolCapture.Unavailable -> appendLine("unavailable: ${capture.reason}\n${capture.serverOutput}")
            }
            appendLine("--- server artifacts dropped by BspBuildTool: ${droppedByClient ?: "<no protocol capture>"}")
        }
    }
}

/** A target's sources and dependency-module coordinates, compared sorted: the protocol lists have no order contract. */
internal data class TargetSummary(val id: String, val sources: List<String>, val modules: List<String>) {
    fun sorted(): TargetSummary = copy(sources = sources.sorted(), modules = modules.sorted())
}

internal fun ProtocolTarget.summary(): TargetSummary =
    TargetSummary(id = id, sources = sources, modules = dependencyModules.map { it.coordinates }).sorted()
