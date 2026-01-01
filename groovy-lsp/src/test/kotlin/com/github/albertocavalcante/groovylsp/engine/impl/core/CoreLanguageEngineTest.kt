package com.github.albertocavalcante.groovylsp.engine.impl.core

import com.github.albertocavalcante.groovyparser.api.ParseRequest
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for [CoreLanguageEngine] and [CoreLanguageSession].
 *
 * Tests parsing, session creation, and feature availability.
 */
class CoreLanguageEngineTest {

    private val engine = CoreLanguageEngine()

    @Test
    fun `engine id is core`() {
        assertEquals("core", engine.id)
    }

    @Test
    fun `createSession returns valid session for simple class`() {
        val request = ParseRequest(
            uri = URI.create("file:///test/Simple.groovy"),
            content = """
                class Simple {
                    String name
                    void greet() {
                        println "Hello"
                    }
                }
            """.trimIndent(),
        )

        val session = engine.createSession(request)

        assertNotNull(session)
        assertTrue(session.result.isSuccess)
        assertTrue(session.result.diagnostics.isEmpty())
    }

    @Test
    fun `createSession returns session with diagnostics for syntax error`() {
        val request = ParseRequest(
            uri = URI.create("file:///test/Invalid.groovy"),
            content = """
                class Invalid {
                    void broken( {
                        // Missing closing paren
                    }
                }
            """.trimIndent(),
        )

        val session = engine.createSession(request)

        assertNotNull(session)
        assertFalse(session.result.isSuccess)
        assertTrue(session.result.diagnostics.isNotEmpty())
    }

    @Test
    fun `createSession provides hoverProvider in features`() {
        val request = ParseRequest(
            uri = URI.create("file:///test/Test.groovy"),
            content = "class Test {}",
        )

        val session = engine.createSession(request)

        assertNotNull(session.features.hoverProvider)
    }

    @Test
    fun `createSession provides documentSymbolProvider in features`() {
        val request = ParseRequest(
            uri = URI.create("file:///test/Test.groovy"),
            content = "class Test {}",
        )

        val session = engine.createSession(request)

        assertNotNull(session.features.documentSymbolProvider)
    }

    @Test
    fun `createSession provides definitionProvider in features`() {
        val request = ParseRequest(
            uri = URI.create("file:///test/Test.groovy"),
            content = "class Test {}",
        )

        val session = engine.createSession(request)

        assertNotNull(session.features.definitionProvider)
    }

    @Test
    fun `createSession provides completionProvider in features`() {
        val request = ParseRequest(
            uri = URI.create("file:///test/Test.groovy"),
            content = "class Test {}",
        )

        val session = engine.createSession(request)

        assertNotNull(session.features.completionProvider)
    }

    @Test
    fun `createSession handles empty content`() {
        val request = ParseRequest(
            uri = URI.create("file:///test/Empty.groovy"),
            content = "",
        )

        val session = engine.createSession(request)

        assertNotNull(session)
        // Empty file is valid Groovy
        assertTrue(session.result.isSuccess)
    }

    @Test
    fun `createSession handles script content without class`() {
        val request = ParseRequest(
            uri = URI.create("file:///test/Script.groovy"),
            content = """
                def message = "Hello World"
                println message
                
                def add(a, b) {
                    return a + b
                }
            """.trimIndent(),
        )

        val session = engine.createSession(request)

        assertNotNull(session)
        assertTrue(session.result.isSuccess)
    }

    @Test
    fun `createSession handles interface definition`() {
        val request = ParseRequest(
            uri = URI.create("file:///test/MyInterface.groovy"),
            content = """
                interface MyInterface {
                    void doSomething()
                    String getName()
                }
            """.trimIndent(),
        )

        val session = engine.createSession(request)

        assertNotNull(session)
        assertTrue(session.result.isSuccess)
    }

    @Test
    fun `createSession handles trait definition`() {
        val request = ParseRequest(
            uri = URI.create("file:///test/MyTrait.groovy"),
            content = """
                trait MyTrait {
                    abstract String getName()
                    
                    void greet() {
                        println "Hello, " + getName()
                    }
                }
            """.trimIndent(),
        )

        val session = engine.createSession(request)

        assertNotNull(session)
        assertTrue(session.result.isSuccess)
    }

    @Test
    fun `createSession handles enum definition`() {
        val request = ParseRequest(
            uri = URI.create("file:///test/Color.groovy"),
            content = """
                enum Color {
                    RED, GREEN, BLUE
                    
                    String display() {
                        return name().toLowerCase()
                    }
                }
            """.trimIndent(),
        )

        val session = engine.createSession(request)

        assertNotNull(session)
        assertTrue(session.result.isSuccess)
    }

    @Test
    fun `createSession handles closure syntax`() {
        val request = ParseRequest(
            uri = URI.create("file:///test/Closures.groovy"),
            content = """
                def list = [1, 2, 3, 4, 5]
                def doubled = list.collect { it * 2 }
                def filtered = list.findAll { it > 2 }
                def sum = list.inject(0) { acc, val -> acc + val }
            """.trimIndent(),
        )

        val session = engine.createSession(request)

        assertNotNull(session)
        assertTrue(session.result.isSuccess)
    }

    @Test
    fun `createSession handles annotation usage`() {
        val request = ParseRequest(
            uri = URI.create("file:///test/Annotated.groovy"),
            content = """
                import groovy.transform.ToString
                import groovy.transform.EqualsAndHashCode
                
                @ToString
                @EqualsAndHashCode
                class Person {
                    String name
                    int age
                }
            """.trimIndent(),
        )

        val session = engine.createSession(request)

        assertNotNull(session)
        assertTrue(session.result.isSuccess)
    }

    @Test
    fun `multiple sessions can be created from same engine`() {
        val request1 = ParseRequest(
            uri = URI.create("file:///test/First.groovy"),
            content = "class First {}",
        )
        val request2 = ParseRequest(
            uri = URI.create("file:///test/Second.groovy"),
            content = "class Second {}",
        )

        val session1 = engine.createSession(request1)
        val session2 = engine.createSession(request2)

        assertNotNull(session1)
        assertNotNull(session2)
        assertTrue(session1.result.isSuccess)
        assertTrue(session2.result.isSuccess)
    }
}
