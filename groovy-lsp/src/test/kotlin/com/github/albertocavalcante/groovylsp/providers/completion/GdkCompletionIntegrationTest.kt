package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Integration test for GDK method completion.
 * Verifies that GDK methods are suggested when accessing properties on Groovy objects.
 */
class GdkCompletionIntegrationTest {

    private lateinit var compilationService: GroovyCompilationService

    @BeforeEach
    fun setup() {
        compilationService = GroovyCompilationService()
        // Initialize GDK provider
        compilationService.gdkProvider.initialize()
    }

    @Test
    fun `should provide GDK method completions for List`() {
        runBlocking {
            val groovyCode = """
                def myList = [1, 2, 3]
                myList.
            """.trimIndent()

            val uri = URI.create("file:///test/Test.groovy")

            // Calculate cursor position (line 1, character 7 means after the dot on second line)
            val line = 1
            val character = 7

            // Request completions at the original cursor position
            // The provider will handle dummy identifier insertion
            val completions = CompletionProvider.getContextualCompletions(
                uri = uri.toString(),
                line = line,
                character = character,
                compilationService = compilationService,
                content = groovyCode,
            )

            // Should include GDK methods for List
            val completionLabels = completions.map { it.label }
            println("Found ${completions.size} completions: $completionLabels")

            // Check for common GDK methods
            assertThat(completionLabels).contains("each", "find", "collect")
        }
    }

    @Test
    fun `should provide GDK method completions for String`() {
        runBlocking {
            val groovyCode = """
                def myString = "hello"
                myString.
            """.trimIndent()

            val uri = URI.create("file:///test/Test.groovy")
            val line = 1
            val character = 9

            // Request completions
            val completions = CompletionProvider.getContextualCompletions(
                uri = uri.toString(),
                line = line,
                character = character,
                compilationService = compilationService,
                content = groovyCode,
            )

            // Should include GDK methods for String
            val completionLabels = completions.map { it.label }
            println("Found ${completions.size} completions: $completionLabels")

            // Check for common GDK methods (these work on Object, so will work on String too)
            assertThat(completionLabels).contains("each", "find")
        }
    }

    @Test
    fun `should provide JDK method completions for String`() {
        runBlocking {
            val groovyCode = """
                def myString = "hello"
                myString.
            """.trimIndent()

            val uri = URI.create("file:///test/Test.groovy")
            val line = 1
            val character = 9

            // Request completions
            val completions = CompletionProvider.getContextualCompletions(
                uri = uri.toString(),
                line = line,
                character = character,
                compilationService = compilationService,
                content = groovyCode,
            )

            // Should include JDK methods for String (or at least Object methods)
            val completionLabels = completions.map { it.label }
            println("Found ${completions.size} completions: $completionLabels")

            // Check for common JDK methods (these exist on Object, so will be there)
            assertThat(completionLabels).contains("toString", "equals", "hashCode")
        }
    }

    @Test
    fun `should provide type completions for generics`() {
        runBlocking {
            val groovyCode = """
                List<Str
            """.trimIndent()

            val uri = URI.create("file:///test/Test.groovy")
            val line = 0
            val character = 8

            // Request completions
            val completions = CompletionProvider.getContextualCompletions(
                uri = uri.toString(),
                line = line,
                character = character,
                compilationService = compilationService,
                content = groovyCode,
            )

            // We expect to find "String"
            val completionLabels = completions.map { it.label }
            println("Found ${completions.size} completions: $completionLabels")

            assertThat(completionLabels).contains("String")
        }
    }
}
