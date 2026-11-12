// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.JenkinsProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.jenkins.JenkinsConfiguration
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItemKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Ordinary member receivers must not receive the general in-scope list: after `env.` the script's
 * own class, its `main`/`run` methods and `println` snippets are noise. Only `this`/`super`
 * resolve to the enclosing class, and they do so through member access, not the scope list.
 */
class MemberAccessScopeSuppressionTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var scope: CoroutineScope
    private lateinit var registry: ProjectStrategyRegistry

    @BeforeEach
    fun setUp() = runBlocking {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        compilationService = GroovyCompilationService()
        compilationService.workspaceManager.initializeWorkspace(tempDir)
        val jenkins = JenkinsProjectStrategy(scope)
        registry = ProjectStrategyRegistry().apply { register(jenkins) }
        compilationService.workspaceManager.setStrategyRegistry(registry)
        val config = ServerConfiguration(
            jenkinsConfig = JenkinsConfiguration(filePatterns = listOf("**/Jenkinsfile")),
        )
        registry.selectStrategies(tempDir, config)
        jenkins.initialize(tempDir, config)
        jenkins.awaitInitialization()
    }

    @AfterEach
    fun tearDown() {
        registry.shutdown()
        scope.cancel()
    }

    @Test
    fun `env dot in a Jenkinsfile offers no scope classes methods or snippets`(): Unit = runBlocking {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        val code = "def x = env."
        Files.writeString(jenkinsfile, code)

        val items = CompletionProvider.getContextualCompletions(
            uri = jenkinsfile.toUri().toString(),
            line = 0,
            character = code.length,
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = code,
        )

        val labels = items.map { it.label to it.kind }
        assertTrue(items.any { it.label == "BUILD_ID" }, "Expected Jenkins env properties, got $labels")
        assertTrue(items.none { it.label in setOf("println", "main", "run") }, "Scope noise after env.: $labels")
        assertTrue(items.none { it.kind == CompletionItemKind.Class }, "Class items after env.: $labels")
    }
}
