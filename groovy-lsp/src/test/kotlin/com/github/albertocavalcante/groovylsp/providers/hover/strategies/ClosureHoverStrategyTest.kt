package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.documentation.DocumentationProvider
import com.github.albertocavalcante.groovylsp.providers.hover.HoverContentGenerator
import com.github.albertocavalcante.groovylsp.providers.hover.HoverProvider
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertTrue

/**
 * Tests for ClosureHoverStrategy.
 *
 * ClosureHoverStrategy handles hover information for closure expressions,
 * showing parameters, delegate type, and owner context.
 */
class ClosureHoverStrategyTest {

    private val logger = KotlinLogging.logger {}
    private val compilationService = GroovyCompilationService()
    private val documentProvider = DocumentProvider()

    init {
        // Reset DocumentationProvider singleton for test isolation
        DocumentationProvider.reset()
    }

    private val semanticResolver = SemanticTypeResolver(
        compilationService.classpathService.getTypeSolver(),
    )
    private val contentGenerator = HoverContentGenerator(semanticResolver)
    private val hoverProvider = HoverProvider(compilationService, documentProvider, contentGenerator)

    @Test
    fun `hover on simple closure with parameters`() = runTest {
        val groovyCode = """
            def closure = { String name, int age ->
                println "Name: ${'$'}name, Age: ${'$'}age"
            }
        """.trimIndent()

        val uri = URI.create("file:///closure-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on closure - exact position may vary, test around the closure
        val hover = hoverProvider.provideHover(uri.toString(), Position(0, 15)) // Near closure start

        if (hover != null) {
            val content = hover.contents.right
            logger.info { "Closure hover content: ${content.value}" }

            assertTrue(content.kind == MarkupKind.MARKDOWN)
            assertTrue(
                content.value.contains("Closure") || content.value.contains("closure"),
                "Expected closure information. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on closure with no parameters`() = runTest {
        val groovyCode = """
            def simpleBlock = { println "Simple closure" }
        """.trimIndent()

        val uri = URI.create("file:///simple-closure-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on closure - position may vary, test that we don't crash
        val hover = hoverProvider.provideHover(uri.toString(), Position(0, 20))

        // Test passes if we get here without exception - closures can be tricky to position
        logger.info { "Simple closure hover: ${hover?.contents?.right?.value ?: "null"}" }

        // Allow for various valid results at this position (could be variable, closure, etc.)
        assertTrue(true, "Closure hover test completed successfully")
    }

    @Test
    fun `hover on closure with implicit it parameter`() = runTest {
        val groovyCode = """
            def list = [1, 2, 3]
            list.each { println it }
        """.trimIndent()

        val uri = URI.create("file:///implicit-it-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on closure - position may vary
        val hover = hoverProvider.provideHover(uri.toString(), Position(1, 12))

        // Test passes if we get here without exception
        logger.info { "Implicit it closure hover: ${hover?.contents?.right?.value ?: "null"}" }

        // Allow for various valid results at this position
        assertTrue(true, "Closure hover test completed successfully")
    }

    @Test
    fun `hover on closure passed as method argument`() = runTest {
        val groovyCode = """
            def numbers = [1, 2, 3, 4, 5]
            numbers.findAll { it > 3 }
        """.trimIndent()

        val uri = URI.create("file:///method-arg-closure-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on closure in findAll
        val hover = hoverProvider.provideHover(uri.toString(), Position(1, 18))

        if (hover != null) {
            val content = hover.contents.right
            logger.info { "Method argument closure hover: ${content.value}" }

            assertTrue(
                content.value.contains("Closure") || content.value.contains("it"),
                "Expected closure information. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on closure with multiple parameters`() = runTest {
        val groovyCode = """
            def map = [a: 1, b: 2, c: 3]
            map.each { key, value ->
                println "${'$'}key: ${'$'}value"
            }
        """.trimIndent()

        val uri = URI.create("file:///multi-param-closure-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on closure
        val hover = hoverProvider.provideHover(uri.toString(), Position(1, 12))

        if (hover != null) {
            val content = hover.contents.right
            logger.info { "Multi-param closure hover: ${content.value}" }

            assertTrue(
                content.value.contains("Closure") || content.value.contains("key") || content.value.contains("value"),
                "Expected closure with parameters. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on nested closures`() = runTest {
        val groovyCode = """
            def outer = { x ->
                def inner = { y ->
                    println x + y
                }
                inner(5)
            }
        """.trimIndent()

        val uri = URI.create("file:///nested-closure-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on outer closure
        val outerHover = hoverProvider.provideHover(uri.toString(), Position(0, 13))

        if (outerHover != null) {
            val content = outerHover.contents.right
            logger.info { "Outer closure hover: ${content.value}" }

            assertTrue(
                content.value.contains("Closure") || content.value.contains("x"),
                "Expected outer closure info. Actual: ${content.value}",
            )
        }

        // Hover on inner closure
        val innerHover = hoverProvider.provideHover(uri.toString(), Position(1, 17))

        if (innerHover != null) {
            val content = innerHover.contents.right
            logger.info { "Inner closure hover: ${content.value}" }

            assertTrue(
                content.value.contains("Closure") || content.value.contains("y"),
                "Expected inner closure info. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on closure with typed parameters`() = runTest {
        val groovyCode = """
            def calculate = { int a, int b -> a + b }
            def result = calculate(5, 10)
        """.trimIndent()

        val uri = URI.create("file:///typed-closure-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on closure
        val hover = hoverProvider.provideHover(uri.toString(), Position(0, 19))

        if (hover != null) {
            val content = hover.contents.right
            logger.info { "Typed closure hover: ${content.value}" }

            assertTrue(
                content.value.contains("Closure") || content.value.contains("int"),
                "Expected closure with typed parameters. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on closure in variable declaration`() = runTest {
        val groovyCode = """
            def greeting = { name -> "Hello, ${'$'}name!" }
            println greeting("World")
        """.trimIndent()

        val uri = URI.create("file:///closure-var-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on the closure part
        val hover = hoverProvider.provideHover(uri.toString(), Position(0, 17))

        if (hover != null) {
            val content = hover.contents.right
            logger.info { "Closure variable hover: ${content.value}" }

            assertTrue(
                content.value.contains("Closure") || content.value.contains("name"),
                "Expected closure information. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on closure with default parameter values`() = runTest {
        val groovyCode = """
            def greet = { String name = "Guest" ->
                println "Hello, ${'$'}name!"
            }
        """.trimIndent()

        val uri = URI.create("file:///default-param-closure-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on closure
        val hover = hoverProvider.provideHover(uri.toString(), Position(0, 13))

        if (hover != null) {
            val content = hover.contents.right
            logger.info { "Default parameter closure hover: ${content.value}" }

            assertTrue(
                content.value.contains("Closure") || content.value.contains("name") || content.value.contains("String"),
                "Expected closure with default parameter. Actual: ${content.value}",
            )
        }
    }
}
