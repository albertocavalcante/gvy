// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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

/** Completion keys in executable calls and Declarative entries. */
class JenkinsArgumentCompletionTest {
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

    private fun List<CompletionItem>.labels(kind: CompletionItemKind? = null): Set<String> =
        filter { kind == null || it.kind == kind }.map { it.label }.toSet()

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
    fun `top-level credentials binding offers its named keys`(): Unit = runTest {
        val code = "withCredentials([string()]) {}"
        val items = completeAt(code, line = 0, character = code.indexOf("string(") + 7)
        val keys = items.labels(CompletionItemKind.Property)

        assertTrue("credentialsId:" in keys && "variable:" in keys, "credentials binding keys: $keys")
        assertTrue("defaultValue:" !in keys, "parameter-definition keys must not leak into bindings")
    }

    @Test
    fun `a call nested inside a directive entry keeps its own step keys`(): Unit = runTest {
        val code = "pipeline {\n    agent any\n    parameters {\n" +
            "        string(name: 'A', defaultValue: string())\n    }\n}"
        val items = completeAt(code, line = 3, character = 47)

        val keys = items.labels(CompletionItemKind.Property)
        assertTrue("credentialsId:" !in keys, "an unrelated nested call is not a credentials binding: $keys")
        assertTrue("trim:" !in keys, "the nested call is not a parameters {} entry: $keys")
    }

    @Test
    fun `a call in an inner closure of a directive entry keeps its own step keys`(): Unit = runTest {
        val line = "        choice(name: 'C', choices: ['a'].collect { string() })"
        val code = "pipeline {\n    agent any\n    parameters {\n$line\n    }\n}"
        val items = completeAt(code, line = 3, character = line.indexOf("string(") + 7)

        val keys = items.labels(CompletionItemKind.Property)
        assertTrue("credentialsId:" !in keys, "an unrelated closure call is not a credentials binding: $keys")
        assertTrue("trim:" !in keys, "an inner closure is not the parameters {} block: $keys")
    }

    @Test
    fun `credential keys require a direct unshadowed implicit binding call`(): Unit = runTest {
        val cases = listOf(
            "service.withCredentials([string()]) {}",
            "withCredentials([service.string()]) {}",
            "withCredentials([wrap(string())]) {}",
            "withCredentials([[string()]]) {}",
            "withCredentials([service?.string()]) {}",
            "withCredentials([services*.string()]) {}",
            "def withCredentials(x, y) {}\nwithCredentials([string()]) {}",
            "def withCredentials = { x, y -> }\nwithCredentials([string()]) {}",
            "def string = { x -> }\nwithCredentials([string()]) {}",
            "def f = { string -> withCredentials([string()]) {} }",
            "@groovy.transform.Field def string = { x -> }\nwithCredentials([string()]) {}",
        )
        for (code in cases) {
            val prefix = code.substringBefore("string(") + "string("
            val line = prefix.count { it == '\n' }
            val column = prefix.substringAfterLast('\n').length
            val keys = completeAt(code, line, column).labels(CompletionItemKind.Property)
            assertTrue("credentialsId:" !in keys, "Unexpected credential keys for $code: $keys")
        }
    }

    @Test
    fun `an unrelated lexical declaration does not shadow a credential binding`(): Unit = runTest {
        val line = "withCredentials([string()]) {}"
        val code = "def unrelated() { def string = 1 }\n$line"
        val keys = completeAt(code, 1, line.indexOf("string(") + 7).labels(CompletionItemKind.Property)
        assertTrue("credentialsId:" in keys && "variable:" in keys, "binding keys after unrelated declaration: $keys")
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
}
