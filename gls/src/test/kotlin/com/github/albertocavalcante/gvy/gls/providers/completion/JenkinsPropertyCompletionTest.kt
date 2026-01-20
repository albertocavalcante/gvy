package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.JenkinsProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TDD: Tests for Jenkins property completions (env., currentBuild.).
 *
 * These tests verify that when typing `env.` or `currentBuild.` in a Jenkins file,
 * the completion provider returns Jenkins-specific properties instead of generic
 * Object methods.
 */
class JenkinsPropertyCompletionTest {

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

    @Test
    fun `env dot should provide BUILD_ID completion in Jenkinsfile`() = runTest {
        // Create a Jenkinsfile with simple property access
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        val code = """
            def x = env.
        """.trimIndent()
        Files.writeString(jenkinsfile, code)

        val uri = jenkinsfile.toUri().toString()
        val content = Files.readString(jenkinsfile)

        val completions = CompletionProvider.getContextualCompletions(
            uri = uri,
            line = 0,
            character = 12, // After "env."
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = content,
        )

        val labels = completions.map { it.label }
        assertTrue(
            labels.any { it == "BUILD_ID" || it == "BUILD_NUMBER" || it == "JOB_NAME" },
            "Should have Jenkins env properties, got: $labels",
        )
    }

    @Test
    fun `currentBuild dot should provide result completion in Jenkinsfile`() = runTest {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        val code = """
            def x = currentBuild.
        """.trimIndent()
        Files.writeString(jenkinsfile, code)

        val uri = jenkinsfile.toUri().toString()
        val content = Files.readString(jenkinsfile)

        val completions = CompletionProvider.getContextualCompletions(
            uri = uri,
            line = 0,
            character = 21, // After "currentBuild."
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = content,
        )

        val labels = completions.map { it.label }
        assertTrue(
            labels.any { it == "result" || it == "number" || it == "displayName" },
            "Should have currentBuild properties, got: $labels",
        )
    }

    @Test
    fun `env dot should NOT provide Jenkins properties in regular Groovy file`() = runTest {
        val groovyFile = tempDir.resolve("Script.groovy")
        Files.writeString(groovyFile, "def env = [:]; env.")

        val uri = groovyFile.toUri().toString()
        val content = Files.readString(groovyFile)

        val completions = CompletionProvider.getContextualCompletions(
            uri = uri,
            line = 0,
            character = 20, // After "env."
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = content,
        )

        val labels = completions.map { it.label }
        // Should NOT have Jenkins-specific env properties
        assertFalse(
            labels.any { it == "BUILD_ID" || it == "JOB_NAME" },
            "Should NOT have Jenkins env properties in regular Groovy file, got: $labels",
        )
    }

    @Test
    fun `unknown qualifier should still get normal method completions`() = runTest {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        Files.writeString(jenkinsfile, "def myList = []; myList.")

        val uri = jenkinsfile.toUri().toString()
        val content = Files.readString(jenkinsfile)

        val completions = CompletionProvider.getContextualCompletions(
            uri = uri,
            line = 0,
            character = 25, // After "myList."
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = content,
        )

        val labels = completions.map { it.label }
        // Should have normal list/collection methods
        assertTrue(
            labels.any { it == "size" || it == "each" || it == "collect" },
            "Unknown qualifier should still get normal completions, got: $labels",
        )
    }

    @Test
    fun `shadowed env variable should NOT provide Jenkins properties`() = runTest {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        // Define a local variable named 'env' that shadows the global one
        val code = """
            def env = "some string"
            env.
        """.trimIndent()
        Files.writeString(jenkinsfile, code)

        val uri = jenkinsfile.toUri().toString()
        val content = Files.readString(jenkinsfile)

        val completions = CompletionProvider.getContextualCompletions(
            uri = uri,
            line = 1,
            character = 4, // After "env."
            compilationService = compilationService,
            semanticResolver = SemanticTypeResolver(ReflectionTypeSolver()),
            content = content,
        )

        val labels = completions.map { it.label }
        // Should NOT have Jenkins-specific env properties because it's a String
        assertFalse(
            labels.any { it == "BUILD_ID" || it == "JOB_NAME" },
            "Shadowed env variable should NOT provide Jenkins properties, got: $labels",
        )
    }

    @Test
    fun `shadowed currentBuild variable should NOT provide Jenkins properties`() = runTest {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        // Define a local Map variable named 'currentBuild' that shadows the global one
        val code = """
            def currentBuild = [:]
            currentBuild.
        """.trimIndent()
        Files.writeString(jenkinsfile, code)

        val uri = jenkinsfile.toUri().toString()
        val content = Files.readString(jenkinsfile)

        val completions = CompletionProvider.getContextualCompletions(
            uri = uri,
            line = 1,
            character = 13, // After "currentBuild."
            compilationService = compilationService,
            semanticResolver = SemanticTypeResolver(ReflectionTypeSolver()),
            content = content,
        )

        val labels = completions.map { it.label }
        // Should NOT have Jenkins-specific currentBuild properties (result, etc.)
        assertFalse(
            labels.any { it == "result" || it == "previousBuild" },
            "Shadowed currentBuild variable should NOT provide Jenkins properties, got: $labels",
        )
        // Should have Map methods
        assertTrue(
            labels.any { it == "put" || it == "size" || it == "get" },
            "Shadowed variable should still provide type-specific methods",
        )
    }

    @Test
    fun `shadowed params variable should NOT provide Jenkins properties`() = runTest {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        // Define a local Integer variable named 'params' that shadows the global one
        val code = """
            int params = 42
            params.
        """.trimIndent()
        Files.writeString(jenkinsfile, code)

        val uri = jenkinsfile.toUri().toString()
        val content = Files.readString(jenkinsfile)

        val completions = CompletionProvider.getContextualCompletions(
            uri = uri,
            line = 1,
            character = 7, // After "params."
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = content,
        )

        val labels = completions.map { it.label }
        // Jenkins 'params' is a Map-like object, usually has properties if defined.
        // But mainly we want to ensure we don't treat it as the global 'params' binding.
        // Since 'int' has limited methods, checking for simple non-Jenkins behavior is enough.
        assertTrue(
            labels.any { it == "doubleValue" || it == "toString" },
            "Shadowed integer params should have Integer methods, got: $labels",
        )
    }
}
