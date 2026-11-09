package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.GroovyMode
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.JenkinsProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeclarativeBlockLevelCompletionTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var documentProvider: DocumentProvider
    private lateinit var compilationService: GroovyCompilationService
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var strategyRegistry: ProjectStrategyRegistry

    @BeforeEach
    fun setUp() = runBlocking {
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        documentProvider = DocumentProvider()
        compilationService = GroovyCompilationService(documentProvider = documentProvider)
        compilationService.workspaceManager.initializeWorkspace(tempDir)

        val jenkinsStrategy = JenkinsProjectStrategy(coroutineScope)
        strategyRegistry = ProjectStrategyRegistry()
        strategyRegistry.register(jenkinsStrategy)
        compilationService.workspaceManager.setStrategyRegistry(strategyRegistry)

        val config = ServerConfiguration(
            jenkinsConfig = com.github.albertocavalcante.gvy.jenkins.JenkinsConfiguration(
                filePatterns = listOf("**/Jenkinsfile", "**/Jenkinsfile.*"),
            ),
        )
        strategyRegistry.selectStrategies(tempDir, config)
        jenkinsStrategy.initialize(tempDir, config)
        jenkinsStrategy.awaitInitialization()
    }

    @AfterEach
    fun tearDown() {
        strategyRegistry.shutdown()
        coroutineScope.cancel()
    }

    @Test
    fun `explicit Groovy mode suppresses Jenkins completions in a detected Jenkinsfile`(): Unit = runTest {
        val uri = tempDir.resolve("Jenkinsfile").toUri()
        val content = "pipeline {\n    options {\n        \n    }\n}"
        val documents = documentProvider.apply { put(uri, content, 1) }
        val endpoint = GroovyTextDocumentService(
            coroutineScope,
            compilationService,
            GroovyTextDocumentServiceOptions(
                documentProvider = documents,
                serverConfiguration = ServerConfiguration(groovyMode = GroovyMode.GROOVY),
            ),
        )

        val result = endpoint.completion(
            CompletionParams(TextDocumentIdentifier(uri.toString()), Position(2, 8)),
        ).await()

        assertFalse(result.left.any { it.label == "buildDiscarder" }, "Explicit Groovy mode must override detection")
    }

    @Test
    fun `Native hover follows live explicit mode changes`(): Unit = runTest {
        val uri = tempDir.resolve("Jenkinsfile").toUri()
        val content = "echo 'hello'"
        val documents = documentProvider.apply { put(uri, content, 1) }
        compilationService.updateEngineConfiguration(
            com.github.albertocavalcante.gvy.gls.engine.config.EngineConfiguration(
                com.github.albertocavalcante.gvy.gls.engine.config.EngineType.Native,
            ),
        )
        compilationService.compile(uri, content)
        val endpoint = GroovyTextDocumentService(
            coroutineScope,
            compilationService,
            GroovyTextDocumentServiceOptions(
                documentProvider = documents,
                serverConfiguration = ServerConfiguration(groovyMode = GroovyMode.GROOVY),
            ),
        )
        val params = HoverParams(TextDocumentIdentifier(uri.toString()), Position(0, 1))
        val cache = compilationService.compilationCacheService
        val models = mutableListOf<GroovyAstModel>()
        // Bare-step cursor lookup is separately WIP. Pin the real call in each current parse,
        // including the fresh parse acquired when hover recompiles a script.
        mockkObject(cache)
        try {
            every { cache.getCachedWithContent(uri, any()) } answers {
                callOriginal()?.also { (_, parse) -> pinStepLookup(parse, uri, models) }
            }
            assertFalse(endpoint.hover(params).await().contents.right.value.contains("Jenkins Step:"))

            endpoint.updateConfiguration(ServerConfiguration(groovyMode = GroovyMode.JENKINS))
            assertContains(endpoint.hover(params).await().contents.right.value, "Jenkins Step: `echo`")

            endpoint.updateConfiguration(ServerConfiguration(groovyMode = GroovyMode.GROOVY))
            assertFalse(endpoint.hover(params).await().contents.right.value.contains("Jenkins Step:"))
        } finally {
            models.forEach { unmockkObject(it) }
            unmockkObject(cache)
        }
    }

    private fun pinStepLookup(parse: ParseResult, uri: URI, models: MutableList<GroovyAstModel>) {
        val model = parse.astModel
        if (models.any { it === model }) return
        val statement = assertNotNull(parse.ast).statementBlock.statements.single() as ExpressionStatement
        val call = statement.expression as MethodCallExpression
        mockkObject(model)
        models.add(model)
        every { model.getNodeAt(uri, any()) } returns call
    }

    @Test
    fun `declarative options should NOT appear inside method call parameters`(): Unit = runTest {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        val code = """
            pipeline {
                options {
                    disableConcurrentBuilds(abortPrevious: true, )
                }
            }
        """.trimIndent()
        Files.writeString(jenkinsfile, code)

        // Position cursor inside disableConcurrentBuilds() args - after the comma
        val completions = CompletionProvider.getContextualCompletions(
            uri = jenkinsfile.toUri().toString(),
            line = 2,
            character = 53, // After "true, "
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = code,
        )

        val labels = completions.map { it.label }
        assertFalse(labels.contains("buildDiscarder"), "buildDiscarder should NOT appear inside method call")
        assertFalse(labels.contains("timestamps"), "timestamps should NOT appear inside method call")
        assertFalse(labels.contains("disableResume"), "disableResume should NOT appear inside method call")
    }

    @Test
    fun `declarative options SHOULD appear at block level in options block`(): Unit = runTest {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        val code = """
            pipeline {
                options {

                }
            }
        """.trimIndent()
        Files.writeString(jenkinsfile, code)

        // Position cursor at empty line inside options block
        val completions = CompletionProvider.getContextualCompletions(
            uri = jenkinsfile.toUri().toString(),
            line = 2,
            character = 8,
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = code,
        )

        val labels = completions.map { it.label }
        assertTrue(labels.contains("disableConcurrentBuilds"), "disableConcurrentBuilds SHOULD appear at block level")
        assertTrue(labels.contains("buildDiscarder"), "buildDiscarder SHOULD appear at block level")
    }
}
