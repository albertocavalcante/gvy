// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ModeResolver
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.JenkinsProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverContext
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.JenkinsStepHoverStrategy
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.extracted.StepScope
import com.github.albertocavalcante.nativeapi.ParseRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Completion and hover ask for the metadata of the Jenkinsfile they serve (#1242), so a library step that only
 * another Jenkinsfile loads is never offered. The per-file metadata itself is covered in the jenkins module.
 */
class JenkinsPerFileMetadataCompletionTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var strategyRegistry: ProjectStrategyRegistry
    private lateinit var strategy: JenkinsProjectStrategy

    private val fileA get() = tempDir.resolve("a").resolve("Jenkinsfile")
    private val fileB get() = tempDir.resolve("b").resolve("Jenkinsfile")

    @BeforeEach
    fun setUp(): Unit = runBlocking {
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        compilationService = GroovyCompilationService()
        compilationService.workspaceManager.initializeWorkspace(tempDir)
        strategy = spyk(JenkinsProjectStrategy(coroutineScope))
        strategyRegistry = ProjectStrategyRegistry()
        strategyRegistry.register(strategy)
        compilationService.workspaceManager.setStrategyRegistry(strategyRegistry)
        val config = ServerConfiguration(
            jenkinsConfig = com.github.albertocavalcante.gvy.jenkins.JenkinsConfiguration(
                filePatterns = listOf("**/Jenkinsfile"),
            ),
        )
        strategyRegistry.selectStrategies(tempDir, config)
        strategy.initialize(tempDir, config)
        strategy.awaitInitialization()

        val shared = requireNotNull(strategy.getAllMetadata(fileA.toUri()))
        every { strategy.getAllMetadata(fileA.toUri()) } returns shared.withStep("aLibStep")
        every { strategy.getAllMetadata(fileB.toUri()) } returns shared.withStep("bLibStep")
        Files.createDirectories(fileA.parent)
        Files.createDirectories(fileB.parent)
    }

    @AfterEach
    fun tearDown() {
        strategyRegistry.shutdown()
        coroutineScope.cancel()
    }

    private fun MergedJenkinsMetadata.withStep(name: String) = copy(
        steps = steps + (
            name to MergedStepMetadata(
                name = name,
                scope = StepScope.GLOBAL,
                positionalParams = emptyList(),
                namedParams = emptyMap(),
                extractedDocumentation = "Step from $name's library",
                returnType = null,
                plugin = "library",
                enrichedDescription = null,
                documentationUrl = null,
                category = null,
                examples = emptyList(),
                deprecation = null,
            )
            ),
    )

    private suspend fun labelsAt(file: Path, code: String, line: Int, character: Int): Set<String> {
        Files.writeString(file, code)
        return CompletionProvider.getContextualCompletions(
            uri = file.toUri().toString(),
            line = line,
            character = character,
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = code,
        ).map { it.label }.toSet()
    }

    @Test
    fun `completion in a Jenkinsfile offers its own library steps, not another file's`(): Unit = runTest {
        val code = "node {\n    \n}"

        val inA = labelsAt(fileA, code, line = 1, character = 4)
        val inB = labelsAt(fileB, code, line = 1, character = 4)

        assertTrue("aLibStep" in inA && "bLibStep" !in inA, "completion in A: ${inA.filter { "Lib" in it }}")
        assertTrue("bLibStep" in inB && "aLibStep" !in inB, "completion in B: ${inB.filter { "Lib" in it }}")
    }

    @Test
    fun `hover in a Jenkinsfile documents its own library steps, not another file's`() {
        val modeResolver = mockk<ModeResolver>()
        every { modeResolver.isJenkinsModeEnabled(any()) } returns true
        val hover = JenkinsStepHoverStrategy(strategy, modeResolver)
        fun hoverIn(file: Path, code: String) = hover.generateHover(
            parseCall(file.toUri(), code),
            HoverContext(null, mockk(relaxed = true), file.toUri()),
        )

        assertNotNull(hoverIn(fileA, "aLibStep()"), "A's own step")
        assertNull(hoverIn(fileA, "bLibStep()"), "B's step in A")
        assertNotNull(hoverIn(fileB, "bLibStep()"), "B's own step")
    }

    private fun parseCall(uri: URI, code: String): MethodCallExpression {
        val result = GroovyParserFacade().parse(ParseRequest(uri, code))
        val statement = result.ast!!.statementBlock.statements.first() as ExpressionStatement
        return statement.expression as MethodCallExpression
    }
}
