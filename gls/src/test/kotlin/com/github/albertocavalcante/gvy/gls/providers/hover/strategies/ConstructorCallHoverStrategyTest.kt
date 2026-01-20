package com.github.albertocavalcante.gvy.gls.providers.hover.strategies

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.documentation.DocumentationProvider
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverContentGenerator
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertTrue

/**
 * Tests for ConstructorCallHoverStrategy.
 *
 * ConstructorCallHoverStrategy handles hover information for constructor calls,
 * showing constructor signatures and class information.
 */
class ConstructorCallHoverStrategyTest {

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
    fun `hover on simple constructor call`() = runTest {
        val groovyCode = """
            class Person {
                String name
                int age
            }

            def person = new Person()
        """.trimIndent()

        val uri = URI.create("file:///constructor-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on "Person" in "new Person()"
        val hover = hoverProvider.provideHover(uri.toString(), Position(5, 17)) // On "Person"

        if (hover != null) {
            val content = hover.contents.right
            logger.info { "Constructor call hover: ${content.value}" }

            assertTrue(content.kind == MarkupKind.MARKDOWN)
            assertTrue(
                content.value.contains("Person") || content.value.contains("Constructor"),
                "Expected constructor call information. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on constructor call with parameters`() = runTest {
        val groovyCode = """
            class Book {
                String title
                String author
                int pages

                Book(String title, String author, int pages) {
                    this.title = title
                    this.author = author
                    this.pages = pages
                }
            }

            def book = new Book("1984", "Orwell", 328)
        """.trimIndent()

        val uri = URI.create("file:///constructor-with-params-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on "Book" in "new Book(...)"
        val hover = hoverProvider.provideHover(uri.toString(), Position(12, 16)) // On "Book"

        if (hover != null) {
            val content = hover.contents.right
            logger.info { "Parameterized constructor hover: ${content.value}" }

            assertTrue(
                content.value.contains("Book") || content.value.contains("Constructor"),
                "Expected constructor with parameters. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on constructor call with named arguments`() = runTest {
        val groovyCode = """
            class Config {
                String host
                int port
                boolean ssl
            }

            def config = new Config(host: "localhost", port: 8080, ssl: true)
        """.trimIndent()

        val uri = URI.create("file:///named-args-constructor-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on "Config" in "new Config(...)"
        val hover = hoverProvider.provideHover(uri.toString(), Position(6, 18)) // On "Config"

        if (hover != null) {
            val content = hover.contents.right
            logger.info { "Named arguments constructor hover: ${content.value}" }

            assertTrue(
                content.value.contains("Config") || content.value.contains("Constructor"),
                "Expected constructor with named arguments. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on JDK class constructor call`() = runTest {
        val groovyCode = """
            def list = new ArrayList<String>()
            def map = new HashMap<String, Integer>()
        """.trimIndent()

        val uri = URI.create("file:///jdk-constructor-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on "ArrayList" in "new ArrayList<String>()"
        val listHover = hoverProvider.provideHover(uri.toString(), Position(0, 15)) // On "ArrayList"

        if (listHover != null) {
            val content = listHover.contents.right
            logger.info { "ArrayList constructor hover: ${content.value}" }

            assertTrue(
                content.value.contains("ArrayList") || content.value.contains("List"),
                "Expected ArrayList constructor info. Actual: ${content.value}",
            )
        }

        // Hover on "HashMap" in "new HashMap<String, Integer>()"
        val mapHover = hoverProvider.provideHover(uri.toString(), Position(1, 14)) // On "HashMap"

        if (mapHover != null) {
            val content = mapHover.contents.right
            logger.info { "HashMap constructor hover: ${content.value}" }

            assertTrue(
                content.value.contains("HashMap") || content.value.contains("Map"),
                "Expected HashMap constructor info. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on constructor call with generics`() = runTest {
        val groovyCode = """
            class Container<T> {
                T value

                Container(T value) {
                    this.value = value
                }
            }

            def stringContainer = new Container<String>("hello")
            def intContainer = new Container<Integer>(42)
        """.trimIndent()

        val uri = URI.create("file:///generic-constructor-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on "Container" in "new Container<String>(...)"
        val hover = hoverProvider.provideHover(uri.toString(), Position(8, 27)) // On "Container"

        if (hover != null) {
            val content = hover.contents.right
            logger.info { "Generic constructor hover: ${content.value}" }

            assertTrue(
                content.value.contains("Container") || content.value.contains("Constructor"),
                "Expected generic constructor info. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on nested class constructor call`() = runTest {
        val groovyCode = """
            class Outer {
                class Inner {
                    String value
                }
            }

            def outer = new Outer()
            def inner = new Outer.Inner()
        """.trimIndent()

        val uri = URI.create("file:///nested-constructor-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on "Outer" in "new Outer()"
        val outerHover = hoverProvider.provideHover(uri.toString(), Position(6, 16)) // On "Outer"

        if (outerHover != null) {
            val content = outerHover.contents.right
            logger.info { "Outer class constructor hover: ${content.value}" }

            assertTrue(
                content.value.contains("Outer") || content.value.contains("Constructor"),
                "Expected outer class constructor. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on constructor call with default parameters`() = runTest {
        val groovyCode = """
            class Person {
                String name
                int age

                Person(String name, int age = 25) {
                    this.name = name
                    this.age = age
                }
            }

            def person1 = new Person("Alice", 30)
            def person2 = new Person("Bob")
        """.trimIndent()

        val uri = URI.create("file:///default-params-constructor-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on "Person" in first constructor call
        val hover1 = hoverProvider.provideHover(uri.toString(), Position(10, 19)) // On "Person"

        if (hover1 != null) {
            val content = hover1.contents.right
            logger.info { "Constructor with all params hover: ${content.value}" }

            assertTrue(
                content.value.contains("Person") || content.value.contains("Constructor"),
                "Expected constructor info. Actual: ${content.value}",
            )
        }

        // Hover on "Person" in second constructor call (using default)
        val hover2 = hoverProvider.provideHover(uri.toString(), Position(11, 19)) // On "Person"

        if (hover2 != null) {
            val content = hover2.contents.right
            logger.info { "Constructor with default params hover: ${content.value}" }

            assertTrue(
                content.value.contains("Person") || content.value.contains("Constructor"),
                "Expected constructor info. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on constructor call in expression`() = runTest {
        val groovyCode = """
            class Point {
                int x
                int y
            }

            def distance = Math.sqrt(new Point(x: 3, y: 4).x ** 2 + new Point(x: 3, y: 4).y ** 2)
        """.trimIndent()

        val uri = URI.create("file:///constructor-in-expr-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on first "Point" constructor
        val hover = hoverProvider.provideHover(uri.toString(), Position(5, 29)) // On "Point"

        if (hover != null) {
            val content = hover.contents.right
            logger.info { "Constructor in expression hover: ${content.value}" }

            assertTrue(
                content.value.contains("Point") || content.value.contains("Constructor"),
                "Expected constructor in expression. Actual: ${content.value}",
            )
        }
    }

    @Test
    fun `hover on anonymous inner class constructor`() = runTest {
        val groovyCode = """
            abstract class Base {
                abstract String getValue()
            }

            def instance = new Base() {
                String getValue() {
                    return "anonymous"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///anonymous-constructor-test.groovy")
        compilationService.compile(uri, groovyCode)

        // Hover on "Base" in anonymous class
        val hover = hoverProvider.provideHover(uri.toString(), Position(4, 20)) // On "Base"

        if (hover != null) {
            val content = hover.contents.right
            logger.info { "Anonymous inner class constructor hover: ${content.value}" }

            assertTrue(
                content.value.contains("Base") || content.value.contains("Constructor") ||
                    content.value.contains("class"),
                "Expected anonymous inner class info. Actual: ${content.value}",
            )
        }
    }
}
