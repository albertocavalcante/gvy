// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.JenkinsProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.JenkinsParameterHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.JenkinsStepHoverStrategy
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.jenkins.JenkinsConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins how the Jenkins hover strategies join the shared list: first, step then parameter, only while a Jenkins
 * project strategy is active, and deliberately built per request because they capture the workspace's current
 * capabilities and server mode.
 */
class HoverJenkinsStrategyWiringTest {
    private lateinit var scope: CoroutineScope
    private lateinit var workspace: Path
    private lateinit var registry: ProjectStrategyRegistry
    private lateinit var compilationService: GroovyCompilationService
    private lateinit var provider: HoverProvider

    @BeforeEach
    fun setUp() = runBlocking {
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        compilationService = GroovyCompilationService()
        workspace = Files.createTempDirectory("hover-jenkins-wiring")
        compilationService.workspaceManager.initializeWorkspace(workspace)
        provider = HoverProvider(
            compilationService,
            DocumentProvider(),
            HoverContentGenerator(SemanticTypeResolver(compilationService.classpathService.getTypeSolver())),
        )
        registry = ProjectStrategyRegistry()
    }

    @AfterEach
    fun tearDown() {
        registry.shutdown()
        scope.cancel()
        workspace.toFile().deleteRecursively()
    }

    private suspend fun activateJenkins() {
        val jenkins = JenkinsProjectStrategy(scope)
        registry.register(jenkins)
        compilationService.workspaceManager.setStrategyRegistry(registry)
        val config = ServerConfiguration(
            jenkinsConfig = JenkinsConfiguration(filePatterns = listOf("**/Jenkinsfile", "**/Jenkinsfile.*")),
        )
        registry.selectStrategies(workspace, config)
        jenkins.initialize(workspace, config)
        jenkins.awaitInitialization()
    }

    @Test
    fun `without a jenkins project the jenkins strategies are absent`() {
        val strategies = provider.strategies

        assertTrue(strategies.none { it is JenkinsStepHoverStrategy || it is JenkinsParameterHoverStrategy })
    }

    @Test
    fun `an active jenkins project puts step then parameter before the unchanged base order`(): Unit = runBlocking {
        val base = provider.strategies
        activateJenkins()

        val strategies = provider.strategies

        assertEquals(base.size + 2, strategies.size)
        assertTrue(strategies[0] is JenkinsStepHoverStrategy, "First should be the step strategy")
        assertTrue(strategies[1] is JenkinsParameterHoverStrategy, "Second should be the parameter strategy")
        assertEquals(base.map { it::class }, strategies.drop(2).map { it::class })
        base.zip(strategies.drop(2)).forEach { (shared, listed) -> assertSame(shared, listed) }
    }

    @Test
    fun `the jenkins strategies are built per request while the base ones stay shared`(): Unit = runBlocking {
        activateJenkins()

        val first = provider.strategies
        val second = provider.strategies

        assertNotSame(first[0], second[0])
        assertNotSame(first[1], second[1])
        first.drop(2).zip(second.drop(2)).forEach { (a, b) -> assertSame(a, b) }
    }
}
