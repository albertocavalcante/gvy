package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.JenkinsProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the exact Jenkins completion sets for declarative blocks, so a duplicated, missing or
 * leaked completion fails instead of slipping past `contains` checks.
 */
class JenkinsCompletionExactSetTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var strategyRegistry: ProjectStrategyRegistry

    @BeforeEach
    fun setUp() = runBlocking {
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        compilationService = GroovyCompilationService()
        compilationService.workspaceManager.initializeWorkspace(tempDir)

        // Set up strategy registry with Jenkins support
        val jenkinsStrategy = JenkinsProjectStrategy(coroutineScope)
        strategyRegistry = ProjectStrategyRegistry()
        strategyRegistry.register(jenkinsStrategy)
        compilationService.workspaceManager.setStrategyRegistry(strategyRegistry)

        // Initialize Jenkins strategy with file patterns
        val config = ServerConfiguration(
            jenkinsConfig = com.github.albertocavalcante.gvy.jenkins.JenkinsConfiguration(
                filePatterns = listOf("**/Jenkinsfile", "**/Jenkinsfile.*"),
            ),
        )
        // Select strategies to populate activeStrategies (required for findCapability)
        strategyRegistry.selectStrategies(tempDir, config)
        jenkinsStrategy.initialize(tempDir, config)
        jenkinsStrategy.awaitInitialization()
    }

    @AfterEach
    fun tearDown() {
        strategyRegistry.shutdown()
        coroutineScope.cancel()
    }

    private val metadata
        get() = requireNotNull(compilationService.workspaceManager.getJenkinsCapabilities()?.getAllMetadata())

    private suspend fun completeAt(code: String, line: Int, character: Int): List<CompletionItem> {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        Files.writeString(jenkinsfile, code)
        return CompletionProvider.getContextualCompletions(
            uri = jenkinsfile.toUri().toString(),
            line = line,
            character = character,
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = code,
        )
    }

    private fun assertNoDuplicateLabels(items: List<CompletionItem>) {
        val duplicates = items.groupingBy { it.label }.eachCount().filterValues { it > 1 }
        assertTrue(duplicates.isEmpty(), "Duplicate completion labels: $duplicates")
    }

    private fun List<CompletionItem>.labels(kind: CompletionItemKind? = null): Set<String> =
        filter { kind == null || it.kind == kind }.map { it.label }.toSet()

    @Test
    fun `pipeline block offers exactly the top-level sections`(): Unit = runTest {
        val items = completeAt("pipeline {\n    \n}", line = 1, character = 4)

        assertNoDuplicateLabels(items)
        assertEquals(
            setOf("agent", "environment", "libraries", "options", "parameters", "post", "stages", "tools", "triggers"),
            items.labels(),
        )
    }

    @Test
    fun `agent block offers each agent type exactly once`(): Unit = runTest {
        val items = completeAt("pipeline {\n    agent {\n        \n    }\n}", line = 2, character = 8)

        assertNoDuplicateLabels(items)
        assertEquals(setOf("any", "docker", "dockerfile", "kubernetes", "label", "node", "none"), items.labels())
    }

    @Test
    fun `options block offers exactly the declarative options`(): Unit = runTest {
        val items = completeAt("pipeline {\n    options {\n        \n    }\n}", line = 2, character = 8)

        assertNoDuplicateLabels(items)
        assertEquals(metadata.declarativeOptions.keys, items.labels())
        assertEquals(13, items.size, "Bundled declarative option count changed; update this pin deliberately")
    }

    @Test
    fun `post block offers exactly the post conditions`(): Unit = runTest {
        val items = completeAt("pipeline {\n    agent any\n    post {\n        \n    }\n}", line = 3, character = 8)

        assertNoDuplicateLabels(items)
        assertEquals(
            setOf(
                "aborted", "always", "changed", "cleanup", "failure", "fixed",
                "notBuilt", "regression", "success", "unstable", "unsuccessful",
            ),
            items.labels(),
        )
    }

    @Test
    fun `stage block offers exactly the stage directives`(): Unit = runTest {
        val code = "pipeline {\n    stages {\n        stage('Build') {\n            \n        }\n    }\n}"
        val items = completeAt(code, line = 3, character = 12)

        assertNoDuplicateLabels(items)
        // TODO(#1243): drop this filter once stage(...)'s map keys stop leaking into its body.
        //   See: https://github.com/albertocavalcante/gvy/issues/1243
        val leakedMapKeys = items.labels().filter { it.endsWith(":") }.toSet()
        assertEquals(
            setOf("agent", "environment", "matrix", "options", "parallel", "post", "steps", "tools", "when"),
            items.labels() - leakedMapKeys,
        )
    }

    @Test
    fun `when expression body offers global variables but no when conditions`(): Unit = runTest {
        val code = "pipeline {\n    agent any\n    stages {\n        stage('Build') {\n            when {\n" +
            "                expression {\n                    \n                }\n            }\n" +
            "        }\n    }\n}"
        val items = completeAt(code, line = 6, character = 20)

        assertNoDuplicateLabels(items)
        assertTrue(setOf("env", "params", "currentBuild").all { it in items.labels(CompletionItemKind.Variable) })
        assertTrue("fileExists" in items.labels(CompletionItemKind.Function), "expression {} can call steps")
        assertTrue("branch" !in items.labels(), "when conditions are not valid inside expression {}")
    }

    @Test
    fun `when allOf body offers exactly the when conditions`(): Unit = runTest {
        val code = "pipeline {\n    agent any\n    stages {\n        stage('Build') {\n            when {\n" +
            "                allOf {\n                    \n                }\n            }\n        }\n    }\n}"
        val items = completeAt(code, line = 6, character = 20)

        assertNoDuplicateLabels(items)
        assertEquals(WHEN_CONDITIONS, items.labels())
    }

    @Test
    fun `one-line when not body offers exactly the when conditions`(): Unit = runTest {
        val code = "pipeline {\n    agent any\n    stages {\n        stage('Build') {\n            when { not {\n" +
            "                \n            } }\n        }\n    }\n}"
        val items = completeAt(code, line = 5, character = 16)

        assertNoDuplicateLabels(items)
        assertEquals(WHEN_CONDITIONS, items.labels())
    }

    @Test
    fun `nested when expression body offers global variables`(): Unit = runTest {
        val code = "pipeline {\n    agent any\n    stages {\n        stage('Build') {\n            when {\n" +
            "                allOf {\n                    expression {\n                        \n" +
            "                    }\n                }\n            }\n        }\n    }\n}"
        val items = completeAt(code, line = 7, character = 24)

        assertNoDuplicateLabels(items)
        assertTrue(setOf("env", "params", "currentBuild").all { it in items.labels(CompletionItemKind.Variable) })
    }

    @Test
    fun `scripted stage inside a script block offers steps and global variables`(): Unit = runTest {
        val code = "pipeline {\n    agent any\n    stages {\n        stage('Build') {\n            steps {\n" +
            "                script {\n                    stage('Inner') {\n                        \n" +
            "                    }\n                }\n            }\n        }\n    }\n}"
        val items = completeAt(code, line = 7, character = 24)

        assertNoDuplicateLabels(items)
        assertTrue("sh" in items.labels(CompletionItemKind.Function), "steps inside a scripted stage")
        assertTrue(metadata.globalVariables.keys.all { it in items.labels(CompletionItemKind.Variable) })
    }

    @Test
    fun `declarative node agent offers no steps or global variables`(): Unit = runTest {
        val code = "pipeline {\n    agent {\n        node {\n            \n        }\n    }\n}"
        val items = completeAt(code, line = 3, character = 12)

        assertNoDuplicateLabels(items)
        assertTrue(items.labels(CompletionItemKind.Function).isEmpty(), "No steps in agent { node {} }")
        assertTrue(items.labels(CompletionItemKind.Variable).isEmpty(), "No global variables in agent { node {} }")
    }

    @Test
    fun `one-line post condition opening offers steps and global variables`(): Unit = runTest {
        val code = "pipeline {\n    agent any\n    post { always {\n        \n    } }\n}"
        val items = completeAt(code, line = 3, character = 8)

        assertNoDuplicateLabels(items)
        assertEquals(metadata.steps.keys, items.labels(CompletionItemKind.Function))
        assertEquals(metadata.globalVariables.keys, items.labels(CompletionItemKind.Variable))
    }

    @Test
    fun `steps block offers every bundled step and global variable exactly once`(): Unit = runTest {
        val code = "pipeline {\n    stages {\n        stage('Build') {\n            steps {\n" +
            "                \n            }\n        }\n    }\n}"
        val items = completeAt(code, line = 4, character = 16)

        assertNoDuplicateLabels(items)
        assertEquals(metadata.steps.keys, items.labels(CompletionItemKind.Function))
        assertEquals(metadata.globalVariables.keys, items.labels(CompletionItemKind.Variable))
        assertEquals(metadata.steps.size + metadata.globalVariables.size, items.size)
        assertEquals(52, metadata.steps.size, "Bundled step count changed; update this pin deliberately")
    }

    @Test
    fun `environment block keeps global variables for value expressions`(): Unit = runTest {
        val items = completeAt("pipeline {\n    environment {\n        \n    }\n}", line = 2, character = 8)

        assertNoDuplicateLabels(items)
        assertTrue("env" in items.labels(CompletionItemKind.Variable), "environment values reference env")
        assertTrue("params" in items.labels(CompletionItemKind.Variable), "environment values reference params")
    }

    @Test
    fun `scripted node body offers steps and globals without duplicates`(): Unit = runTest {
        val items = completeAt("node {\n    \n}", line = 1, character = 4)

        assertNoDuplicateLabels(items)
        assertTrue(metadata.steps.keys.all { it in items.labels(CompletionItemKind.Function) }, "All steps offered")
        assertTrue(metadata.globalVariables.keys.all { it in items.labels(CompletionItemKind.Variable) })
    }

    private companion object {
        /** `when` conditions from the declarative schema, valid in `when {}` and its combinators. */
        val WHEN_CONDITIONS = setOf(
            "allOf", "anyOf", "beforeAgent", "beforeInput", "beforeOptions", "branch", "buildingTag",
            "changeRequest", "changelog", "changeset", "equals", "expression", "isRestartedRun", "not",
            "tag", "triggeredBy",
        )
    }
}
