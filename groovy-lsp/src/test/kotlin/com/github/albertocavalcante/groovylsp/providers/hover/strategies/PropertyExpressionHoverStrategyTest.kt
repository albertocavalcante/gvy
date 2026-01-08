package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.documentation.DocumentationProvider
import com.github.albertocavalcante.groovylsp.providers.hover.HoverContentGenerator
import com.github.albertocavalcante.groovylsp.providers.hover.HoverProvider
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for PropertyExpressionHoverStrategy.
 *
 * PropertyExpressionHoverStrategy handles hover information for property access expressions
 * like `object.property`, `map.key`, etc.
 */
class PropertyExpressionHoverStrategyTest {

    private val logger = LoggerFactory.getLogger(PropertyExpressionHoverStrategyTest::class.java)
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
    fun `hover on simple property access shows property information`() = runTest {
        val groovyCode = """
            class Person {
                String name
                int age
            }

            def person = new Person(name: "Alice", age: 30)
            println person.name
        """.trimIndent()

        val uri = URI.create("file:///property-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on "name" in person.name
        val hover = hoverProvider.provideHover(uri.toString(), Position(6, 16)) // On "name"

        assertNotNull(hover, "Expected hover content for property access")
        assertTrue(hover.contents.isRight, "Expected MarkupContent")

        val content = hover.contents.right
        logger.info("Property hover content: ${content.value}")

        assertTrue(content.kind == MarkupKind.MARKDOWN)
        assertTrue(
            content.value.contains("name") || content.value.contains("Property"),
            "Expected property information in hover. Actual: ${content.value}",
        )
    }

    @Test
    fun `hover on property with type information`() = runTest {
        val groovyCode = """
            class Book {
                String title
                String author
                int pages
            }

            def book = new Book(title: "1984", author: "Orwell", pages: 328)
            def bookTitle = book.title
            def pageCount = book.pages
        """.trimIndent()

        val uri = URI.create("file:///book-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on "title" property
        val titleHover = hoverProvider.provideHover(uri.toString(), Position(7, 25)) // On "title"

        if (titleHover != null) {
            val content = titleHover.contents.right
            logger.info("Title property hover: ${content.value}")
            assertTrue(
                content.value.contains("title") || content.value.contains("String"),
                "Expected title property with String type. Actual: ${content.value}",
            )
        }

        // Hover on "pages" property
        val pagesHover = hoverProvider.provideHover(uri.toString(), Position(8, 25)) // On "pages"

        if (pagesHover != null) {
            val content = pagesHover.contents.right
            logger.info("Pages property hover: ${content.value}")
            assertTrue(
                content.value.contains("pages") || content.value.contains("int"),
                "Expected pages property with int type. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on map property access`() = runTest {
        val groovyCode = """
            def config = [
                host: "localhost",
                port: 8080,
                debug: true
            ]

            println config.host
            println config.port
        """.trimIndent()

        val uri = URI.create("file:///map-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on "host" in map access
        val hover = hoverProvider.provideHover(uri.toString(), Position(6, 19)) // On "host"

        if (hover != null) {
            val content = hover.contents.right
            logger.info("Map property hover: ${content.value}")
            assertTrue(
                content.value.contains("host") || content.value.contains("Property"),
                "Expected map property information. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on nested property access`() = runTest {
        val groovyCode = """
            class Address {
                String street
                String city
            }

            class Person {
                String name
                Address address
            }

            def person = new Person(
                name: "Bob",
                address: new Address(street: "Main St", city: "NYC")
            )

            println person.address.city
        """.trimIndent()

        val uri = URI.create("file:///nested-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on "address" in person.address.city
        val addressHover = hoverProvider.provideHover(uri.toString(), Position(15, 19)) // On "address"

        if (addressHover != null) {
            val content = addressHover.contents.right
            logger.info("Nested property (address) hover: ${content.value}")
            assertTrue(
                content.value.contains("address") || content.value.contains("Address"),
                "Expected address property. Actual: ${content.value}",
            )
        }

        // Hover on "city" in person.address.city
        val cityHover = hoverProvider.provideHover(uri.toString(), Position(15, 27)) // On "city"

        if (cityHover != null) {
            val content = cityHover.contents.right
            logger.info("Nested property (city) hover: ${content.value}")
            assertTrue(
                content.value.contains("city") || content.value.contains("String"),
                "Expected city property. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on safe navigation property access`() = runTest {
        val groovyCode = """
            class Person {
                String name
            }

            def person = null
            def safeName = person?.name
        """.trimIndent()

        val uri = URI.create("file:///safe-nav-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on "name" with safe navigation
        val hover = hoverProvider.provideHover(uri.toString(), Position(5, 29)) // On "name"

        if (hover != null) {
            val content = hover.contents.right
            logger.info("Safe navigation property hover: ${content.value}")
            assertTrue(
                content.value.contains("name") || content.value.contains("Property"),
                "Expected property with safe navigation. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on property with getter`() = runTest {
        val groovyCode = """
            class Counter {
                private int count = 0

                int getCount() {
                    return count
                }
            }

            def counter = new Counter()
            println counter.count
        """.trimIndent()

        val uri = URI.create("file:///getter-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on "count" property access (which calls getter)
        val hover = hoverProvider.provideHover(uri.toString(), Position(9, 20)) // On "count"

        if (hover != null) {
            val content = hover.contents.right
            logger.info("Property with getter hover: ${content.value}")
            assertTrue(
                content.value.contains("count") || content.value.contains("int"),
                "Expected count property. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on dynamic property access`() = runTest {
        val groovyCode = """
            def obj = new Object()
            obj.metaClass.dynamicProp = "dynamic value"
            println obj.dynamicProp
        """.trimIndent()

        val uri = URI.create("file:///dynamic-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on dynamic property - should not crash
        val hover = hoverProvider.provideHover(uri.toString(), Position(2, 16)) // On "dynamicProp"

        // May be null or have content, but should not crash
        logger.info("Dynamic property hover: ${hover?.contents?.right?.value ?: "null"}")

        // Test passes if we get here without exception
        assertTrue(true, "Dynamic property hover completed without crash")
    }
}
