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
        get() = requireNotNull(
            compilationService.workspaceManager.getJenkinsCapabilities()
                ?.getAllMetadata(tempDir.resolve("Jenkinsfile").toUri()),
        )

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
        assertEquals(
            setOf(
                "agent",
                "environment",
                "input",
                "matrix",
                "options",
                "parallel",
                "post",
                "stages",
                "steps",
                "tools",
                "when",
            ),
            items.labels(),
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

    @Test
    fun `triggers block offers exactly the trigger entries`(): Unit = runTest {
        val items = completeAt("pipeline {\n    agent any\n    triggers {\n        \n    }\n}", line = 3, character = 8)

        assertNoDuplicateLabels(items)
        assertEquals(setOf("cron", "pollSCM", "upstream"), items.labels())
    }

    @Test
    fun `parameters block offers exactly the parameter types`(): Unit = runTest {
        val code = "pipeline {\n    agent any\n    parameters {\n        \n    }\n}"
        val items = completeAt(code, line = 3, character = 8)

        assertNoDuplicateLabels(items)
        assertEquals(setOf("booleanParam", "choice", "password", "string", "text"), items.labels())
    }

    @Test
    fun `stage input parameters block offers exactly the parameter types`(): Unit = runTest {
        val code = "pipeline {\n    agent any\n    stages {\n        stage('Deploy') {\n            input {\n" +
            "                message 'Deploy?'\n                parameters {\n                    \n" +
            "                }\n            }\n            steps { echo 'x' }\n        }\n    }\n}"
        val items = completeAt(code, line = 7, character = 20)

        assertNoDuplicateLabels(items)
        assertEquals(setOf("booleanParam", "choice", "password", "string", "text"), items.labels())
    }

    @Test
    fun `stage input parameter arguments offer the parameter keys`(): Unit = runTest {
        val code = "pipeline {\n    agent any\n    stages {\n        stage('Deploy') {\n            input {\n" +
            "                parameters {\n                    string()\n                }\n            }\n" +
            "            steps { echo 'x' }\n        }\n    }\n}"
        val items = completeAt(code, line = 6, character = 27)

        assertEquals(
            setOf("name:", "defaultValue:", "description:", "trim:"),
            items.labels(CompletionItemKind.Property),
        )
    }

    /** Labels at an empty line inside the innermost of [openers], each opened on its own line in a stage. */
    private suspend fun labelsInStage(vararg openers: String): Set<String> {
        val items = completeInStage(*openers)
        assertNoDuplicateLabels(items)
        return items.labels()
    }

    /** Labels at an empty line inside `agent { [opener] }` at pipeline level. */
    private suspend fun labelsInAgent(opener: String): Set<String> {
        val code = "pipeline {\n    agent {\n        $opener\n            \n        }\n    }\n}"
        val items = completeAt(code, line = 3, character = 12)
        assertNoDuplicateLabels(items)
        return items.labels()
    }

    /** Insert text by label at an empty line inside `agent { [opener] }`. */
    private suspend fun insertTextsInAgent(opener: String): Map<String, String?> {
        val code = "pipeline {\n    agent {\n        $opener\n            \n        }\n    }\n}"
        return completeAt(code, line = 3, character = 12).associate { it.label to it.insertText }
    }

    /** Insert text by label at an empty line inside the innermost of [openers] in a stage. */
    private suspend fun insertTextsInStage(vararg openers: String): Map<String, String?> =
        completeInStage(*openers).associate { it.label to it.insertText }

    /** Completion at an empty line inside the innermost of [openers], each opened on its own line in a stage. */
    private suspend fun completeInStage(vararg openers: String): List<CompletionItem> {
        val opened = openers.mapIndexed { depth, opener -> "    ".repeat(depth + 3) + opener }
        val closed = openers.indices.reversed().map { depth -> "    ".repeat(depth + 3) + "}" }
        val lines = listOf("pipeline {", "    agent any", "    stages {", "        stage('S') {") + opened +
            "    ".repeat(openers.size + 3) + closed + listOf("        }", "    }", "}")
        return completeAt(lines.joinToString("\n"), line = 4 + openers.size, character = 4 * (openers.size + 3))
    }

    @Test
    fun `statement entries insert their name and a space, block entries an opening brace`(): Unit = runTest {
        val docker = insertTextsInAgent("docker {")
        val input = insertTextsInStage("input {")
        val axis = insertTextsInStage("matrix {", "axes {", "axis {")

        assertEquals("image ", docker["image"])
        assertEquals("reuseNode ", docker["reuseNode"])
        assertEquals("message ", input["message"])
        assertEquals("parameters {", input["parameters"], "parameters opens a block")
        assertEquals("values ", axis["values"])
        assertEquals("axis {", insertTextsInStage("matrix {", "axes {")["axis"], "axis opens a block")
    }

    @Test
    fun `parallel block offers exactly stage`(): Unit = runTest {
        assertEquals(setOf("stage"), labelsInStage("parallel {"))
    }

    @Test
    fun `matrix block offers exactly its sections and directives`(): Unit = runTest {
        assertEquals(
            setOf("axes", "excludes", "stages", "agent", "environment", "input", "options", "post", "tools", "when"),
            labelsInStage("matrix {"),
        )
    }

    @Test
    fun `axes, axis, excludes and exclude blocks offer exactly their contents`(): Unit = runTest {
        assertEquals(setOf("axis"), labelsInStage("matrix {", "axes {"))
        assertEquals(setOf("name", "values"), labelsInStage("matrix {", "axes {", "axis {"))
        assertEquals(setOf("exclude"), labelsInStage("matrix {", "excludes {"))
        assertEquals(setOf("axis"), labelsInStage("matrix {", "excludes {", "exclude {"))
    }

    @Test
    fun `axis inside exclude also offers notValues, axis inside axes does not`(): Unit = runTest {
        assertEquals(
            setOf("name", "values", "notValues"),
            labelsInStage("matrix {", "excludes {", "exclude {", "axis {"),
        )
        assertEquals("notValues ", insertTextsInStage("matrix {", "excludes {", "exclude {", "axis {")["notValues"])
        assertTrue("notValues" !in labelsInStage("matrix {", "axes {", "axis {"))
    }

    @Test
    fun `when conditions insert statements without a brace, composites with one`(): Unit = runTest {
        val inserts = insertTextsInStage("when {")

        assertEquals("branch ", inserts["branch"])
        assertEquals("beforeAgent ", inserts["beforeAgent"])
        assertEquals("allOf {", inserts["allOf"])
    }

    @Test
    fun `agent block inserts label without a brace`(): Unit = runTest {
        val code = "pipeline {\n    agent {\n        \n    }\n}"
        val inserts = completeAt(code, line = 2, character = 8).associate { it.label to it.insertText }

        assertEquals("label", inserts["label"], "label takes a value, not a block")
    }

    @Test
    fun `kubernetes agent with a braced multi-line yaml does not hide a later sub-block's entries`(): Unit = runTest {
        val code = listOf(
            "pipeline {",
            "    agent {",
            "        kubernetes {",
            "            yaml '''",
            "apiVersion: v1",
            "kind: Pod",
            "spec: { containers: [ { name: maven } ] }",
            "            '''",
            "        }",
            "    }",
            "    stages {",
            "        stage('S') {",
            "            input {",
            "                ",
            "            }",
            "        }",
            "    }",
            "}",
        ).joinToString("\n")
        val items = completeAt(code, line = 13, character = 16)

        assertEquals(setOf("message", "id", "ok", "submitter", "submitterParameter", "parameters"), items.labels())
    }

    @Test
    fun `agent sub-blocks offer exactly their options`(): Unit = runTest {
        assertEquals(
            setOf(
                "image",
                "args",
                "label",
                "alwaysPull",
                "customWorkspace",
                "registryUrl",
                "registryCredentialsId",
                "reuseNode",
            ),
            labelsInAgent("docker {"),
        )
        assertEquals(
            setOf(
                "filename", "dir", "label", "additionalBuildArgs", "args", "customWorkspace", "registryUrl",
                "registryCredentialsId", "reuseNode",
            ),
            labelsInAgent("dockerfile {"),
        )
        assertEquals(
            setOf(
                "yaml", "yamlFile", "label", "cloud", "defaultContainer", "inheritFrom", "namespace",
                "serviceAccount", "nodeSelector", "workingDir", "customWorkspace", "retries",
            ),
            labelsInAgent("kubernetes {"),
        )
    }

    @Test
    fun `a stage input block offers exactly its parameters`(): Unit = runTest {
        assertEquals(
            setOf("message", "id", "ok", "submitter", "submitterParameter", "parameters"),
            labelsInStage("input {"),
        )
    }

    @Test
    fun `tools block offers exactly the tool types`(): Unit = runTest {
        val items = completeAt("pipeline {\n    agent any\n    tools {\n        \n    }\n}", line = 3, character = 8)

        assertNoDuplicateLabels(items)
        assertEquals(setOf("gradle", "jdk", "maven"), items.labels())
    }

    @Test
    fun `stage-level tools block offers exactly the tool types`(): Unit = runTest {
        val code = "pipeline {\n    agent any\n    stages {\n        stage('Build') {\n            tools {\n" +
            "                \n            }\n        }\n    }\n}"
        val items = completeAt(code, line = 5, character = 16)

        assertNoDuplicateLabels(items)
        assertEquals(setOf("gradle", "jdk", "maven"), items.labels())
    }

    @Test
    fun `directive entries are not offered inside an entry's arguments`(): Unit = runTest {
        val cron =
            completeAt(
                "pipeline {\n    agent any\n    triggers {\n        cron('')\n    }\n}",
                line = 3,
                character = 14,
            )
        val string = completeAt(
            "pipeline {\n    agent any\n    parameters {\n        string()\n    }\n}",
            line = 3,
            character = 15,
        )

        assertEquals(emptySet(), cron.labels() intersect setOf("cron", "pollSCM", "upstream"))
        assertEquals(
            emptySet(),
            string.labels() intersect setOf("booleanParam", "choice", "password", "string", "text"),
        )
    }

    @Test
    fun `trigger entries are not offered in options`(): Unit = runTest {
        val items = completeAt("pipeline {\n    options {\n        \n    }\n}", line = 2, character = 8)

        assertTrue("cron" !in items.labels() && "string" !in items.labels(), "options offers only options")
    }

    @Test
    fun `scripted node body offers no node map keys`(): Unit = runTest {
        val items = completeAt("node {\n    \n}", line = 1, character = 4)

        assertEquals(emptyList(), items.map { it.label }.filter { it.endsWith(":") }, "node's label:/body: leaked")
    }

    @Test
    fun `step closure bodies offer no outer step map keys`(): Unit = runTest {
        val dir = completeAt("node {\n    dir('build') {\n        \n    }\n}", line = 2, character = 8)
        val withCredentials = completeAt(
            "node {\n    withCredentials([string(credentialsId: 'c', variable: 'V')]) {\n        \n    }\n}",
            line = 2,
            character = 8,
        )

        assertEquals(emptyList(), dir.map { it.label }.filter { it.endsWith(":") }, "dir's path: leaked")
        assertEquals(emptyList(), withCredentials.map { it.label }.filter { it.endsWith(":") })
        assertTrue("sh" in dir.labels(CompletionItemKind.Function), "steps are still offered in the body")
    }

    @Test
    fun `argument lists still offer the step's map keys`(): Unit = runTest {
        val node = completeAt("node() {\n}", line = 0, character = 5)
        val sh = completeAt("node {\n    sh()\n}", line = 1, character = 7)

        assertTrue("label:" in node.labels(), "node(|) offers label:")
        assertTrue("returnStdout:" in sh.labels(), "sh(|) offers returnStdout:")
    }

    @Test
    fun `string parameter arguments offer the string parameter keys`(): Unit = runTest {
        val code = "pipeline {\n    agent any\n    parameters {\n        string()\n    }\n}"
        val items = completeAt(code, line = 3, character = 15)

        assertEquals(
            setOf("name:", "defaultValue:", "description:", "trim:"),
            items.labels(CompletionItemKind.Property),
        )
    }

    @Test
    fun `credentials string arguments still offer the credentials binding keys`(): Unit = runTest {
        val code = "node {\n    withCredentials([string()]) {\n    }\n}"
        val items = completeAt(code, line = 1, character = 28)

        val keys = items.labels(CompletionItemKind.Property)
        assertTrue("credentialsId:" in keys && "variable:" in keys, "credentials binding keys: $keys")
        assertTrue("defaultValue:" !in keys, "parameter keys must not leak outside parameters {}")
    }

    @Test
    fun `a call nested inside a directive entry keeps its own step keys`(): Unit = runTest {
        val code = "pipeline {\n    agent any\n    parameters {\n" +
            "        string(name: 'A', defaultValue: string())\n    }\n}"
        val items = completeAt(code, line = 3, character = 47)

        val keys = items.labels(CompletionItemKind.Property)
        assertTrue("credentialsId:" in keys, "the nested string() is the credentials binding step: $keys")
        assertTrue("trim:" !in keys, "the nested call is not a parameters {} entry: $keys")
    }

    @Test
    fun `a call in an inner closure of a directive entry keeps its own step keys`(): Unit = runTest {
        val line = "        choice(name: 'C', choices: ['a'].collect { string() })"
        val code = "pipeline {\n    agent any\n    parameters {\n$line\n    }\n}"
        val items = completeAt(code, line = 3, character = line.indexOf("string(") + 7)

        val keys = items.labels(CompletionItemKind.Property)
        assertTrue("credentialsId:" in keys, "string() in collect {} is the credentials binding step: $keys")
        assertTrue("trim:" !in keys, "an inner closure is not the parameters {} block: $keys")
    }

    @Test
    fun `parenthesised and command-form entries both get directive keys`(): Unit = runTest {
        val paren = "        string(name: 'A', )"
        val command = "        string name: 'A', "
        val wrap = { line: String -> "pipeline {\n    agent any\n    parameters {\n$line\n    }\n}" }
        val parenItems = completeAt(wrap(paren), line = 3, character = paren.indexOf(", )") + 2)
        val commandItems = completeAt(wrap(command), line = 3, character = command.length)

        assertTrue("trim:" in parenItems.labels(), "string(name: 'A', |) offers trim:")
        assertTrue("trim:" in commandItems.labels(), "string name: 'A', | offers trim:")
    }

    @Test
    fun `trigger and tool entries offer their own keys`(): Unit = runTest {
        val pollScm =
            completeAt(
                "pipeline {\n    agent any\n    triggers {\n        pollSCM()\n    }\n}",
                line = 3,
                character = 16,
            )
        val choice = completeAt(
            "pipeline {\n    agent any\n    parameters {\n        choice()\n    }\n}",
            line = 3,
            character = 15,
        )

        assertEquals(setOf("scmpoll_spec:", "ignorePostCommitHooks:"), pollScm.labels(CompletionItemKind.Property))
        assertEquals(setOf("name:", "choices:", "description:"), choice.labels(CompletionItemKind.Property))
    }

    /** Map keys inside an entry's argument list, by directive, as the bundled entries define them (#1246 M1). */
    private suspend fun entryKeys(directive: String, entry: String): Set<String> {
        val line = "        $entry()"
        val code = "pipeline {\n    agent any\n    $directive {\n$line\n    }\n}"
        return completeAt(code, line = 3, character = line.length - 1).labels(CompletionItemKind.Property)
    }

    @Test
    fun `trigger entry arguments offer exactly their keys`(): Unit = runTest {
        assertEquals(setOf("spec:"), entryKeys("triggers", "cron"))
        assertEquals(setOf("upstreamProjects:", "threshold:"), entryKeys("triggers", "upstream"))
    }

    @Test
    fun `parameter entry arguments offer exactly their keys`(): Unit = runTest {
        assertEquals(setOf("name:", "defaultValue:", "description:"), entryKeys("parameters", "booleanParam"))
        assertEquals(setOf("name:", "defaultValue:", "description:", "trim:"), entryKeys("parameters", "text"))
        assertEquals(setOf("name:", "defaultValue:", "description:"), entryKeys("parameters", "password"))
    }

    @Test
    fun `env member access offers env properties but no steps, globals or map keys`(): Unit = runTest {
        val items = completeAt("node {\n    env.\n}", line = 1, character = 8)

        assertNoDuplicateLabels(items)
        val envProperties = requireNotNull(metadata.globalVariables["env"]).properties.keys
        assertTrue(envProperties.isNotEmpty())
        assertTrue(envProperties.all { it in items.labels(CompletionItemKind.Property) }, "All env properties")
        assertTrue("BUILD_ID" in items.labels(), "env.BUILD_ID is offered")
        assertJenkinsStatementItemsAbsent(items)
    }

    @Test
    fun `currentBuild member access offers its properties but no steps, globals or map keys`(): Unit = runTest {
        val items = completeAt("node {\n    currentBuild.\n}", line = 1, character = 17)

        assertNoDuplicateLabels(items)
        val properties = requireNotNull(metadata.globalVariables["currentBuild"]).properties.keys
        assertTrue(properties.all { it in items.labels() }, "All currentBuild properties")
        assertJenkinsStatementItemsAbsent(items)
    }

    @Test
    fun `a partially typed member after env dot offers no steps`(): Unit = runTest {
        val items = completeAt("node {\n    env.BR\n}", line = 1, character = 10)

        assertJenkinsStatementItemsAbsent(items)
    }

    @Test
    fun `steps and this receivers still offer the pipeline steps`(): Unit = runTest {
        val viaSteps = completeAt("def call() {\n    steps.\n}", line = 1, character = 10)
        val viaThis = completeAt("node {\n    this.\n}", line = 1, character = 9)

        assertTrue(
            setOf("sh", "echo").all {
                it in viaSteps.labels(CompletionItemKind.Function)
            },
            "steps.sh, steps.echo",
        )
        assertTrue("sh" in viaThis.labels(CompletionItemKind.Function), "this.sh")
    }

    @Test
    fun `other receivers offer no steps`(): Unit = runTest {
        val params = completeAt("node {\n    params.\n}", line = 1, character = 11)
        val image = completeAt("node {\n    docker.image('maven').\n}", line = 1, character = 25)

        assertJenkinsStatementItemsAbsent(params)
        assertJenkinsStatementItemsAbsent(image)
    }

    /** Steps, global variables and step map keys are statement or argument completions, never members. */
    private fun assertJenkinsStatementItemsAbsent(items: List<CompletionItem>) {
        val labels = items.labels()
        assertEquals(emptySet(), labels intersect metadata.steps.keys, "Steps after '.'")
        assertEquals(emptySet(), labels intersect metadata.globalVariables.keys, "Global variables after '.'")
        assertEquals(emptyList(), items.map { it.label }.filter { it.endsWith(":") }, "Map keys after '.'")
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
