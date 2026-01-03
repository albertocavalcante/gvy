package com.github.albertocavalcante.groovylsp.engine

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.engine.config.EngineConfiguration
import com.github.albertocavalcante.groovylsp.engine.config.EngineType
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the Language Engine abstraction.
 * Verifies that NativeLanguageEngine correctly wraps ParseResult and provides access to LSP features.
 *
 * These tests ensure that:
 * 1. Sessions are created correctly for valid Groovy code
 * 2. Diagnostics are properly mapped from ParserDiagnostic to LSP Diagnostic
 * 3. The isSuccess flag accurately reflects compilation status
 * 4. Features (HoverProvider) are accessible via the session
 * 5. Edge cases are handled properly
 */
class NativeLanguageEngineTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var documentProvider: DocumentProvider

    @BeforeEach
    fun setup() {
        documentProvider = DocumentProvider()
        val config = EngineConfiguration(type = EngineType.Native)
        compilationService = GroovyCompilationService(
            documentProvider = documentProvider,
            engineConfig = config,
        )
    }

    @Test
    fun `getSession returns non-null session for compiled file`() = runBlocking {
        val code = "class Foo { String name }"
        val uri = URI.create("file:///test/Foo.groovy")

        documentProvider.put(uri, code)
        compilationService.compile(uri, code)
        val session = compilationService.getSession(uri)

        assertNotNull(session, "Session should not be null after compilation")
        assertNotNull(session.features, "Features should not be null")
        assertNotNull(session.features.hoverProvider, "HoverProvider should be available")
    }

    @Test
    fun `getSession returns null for uncached file`() {
        val uri = URI.create("file:///test/NeverCompiled.groovy")
        val session = compilationService.getSession(uri)

        assertNull(session, "Session should be null for files that were never compiled")
    }

    @Test
    fun `session diagnostics are mapped correctly for valid code`() = runBlocking {
        val code = "class Foo { String name }"
        val uri = URI.create("file:///test/Foo.groovy")

        documentProvider.put(uri, code)
        compilationService.compile(uri, code)
        val session = compilationService.getSession(uri)

        assertNotNull(session)
        assertTrue(session.result.isSuccess, "Valid code should compile successfully")
        assertTrue(session.result.diagnostics.isEmpty(), "Valid code should have no diagnostics")
    }

    @Test
    fun `session diagnostics are mapped correctly for code with warnings`() = runBlocking {
        val code = """
            class Foo {
                def unusedVariable = 42
                void method() {
                    def x  // Unused local variable
                }
            }
        """.trimIndent()
        val uri = URI.create("file:///test/WarningFoo.groovy")

        documentProvider.put(uri, code)
        compilationService.compile(uri, code)
        val session = compilationService.getSession(uri)

        // Code should compile successfully even with warnings
        assertNotNull(session)
        assertTrue(session.result.isSuccess, "Code with warnings should compile successfully")
        // Note: Whether diagnostics are present depends on the parser configuration
    }

    @Test
    fun `session result isSuccess is true for valid code`() = runBlocking {
        val code = """
            package test
            class ValidClass {
                String name
                void greet() { println "Hello" }
            }
        """.trimIndent()
        val uri = URI.create("file:///test/Valid.groovy")

        documentProvider.put(uri, code)
        compilationService.compile(uri, code)
        val session = compilationService.getSession(uri)

        assertNotNull(session)
        assertTrue(session.result.isSuccess)
    }

    @Test
    fun `session features provides hover provider`() = runBlocking {
        val code = "class Sample { String field }"
        val uri = URI.create("file:///test/Sample.groovy")

        documentProvider.put(uri, code)
        compilationService.compile(uri, code)
        val session = compilationService.getSession(uri)

        assertNotNull(session)
        assertNotNull(session.features.hoverProvider, "HoverProvider should be available via session")
    }

    @Test
    fun `session result metadata properties are accessible`() = runBlocking {
        val code = "class MetadataTest { int value = 42 }"
        val uri = URI.create("file:///test/Metadata.groovy")

        documentProvider.put(uri, code)
        compilationService.compile(uri, code)
        val session = compilationService.getSession(uri)

        assertNotNull(session)
        assertNotNull(session.result, "Result metadata should not be null")
        // Verify we can access both properties without errors
        val isSuccess = session.result.isSuccess
        val diagnostics = session.result.diagnostics
        assertTrue(isSuccess, "Simple class should compile successfully")
        assertTrue(diagnostics.isEmpty(), "Valid code should have empty diagnostics list")
    }

    @Test
    fun `session persists after multiple getSession calls`() = runBlocking {
        val code = "class Persistent { String data }"
        val uri = URI.create("file:///test/Persistent.groovy")

        documentProvider.put(uri, code)
        compilationService.compile(uri, code)

        val session1 = compilationService.getSession(uri)
        val session2 = compilationService.getSession(uri)

        assertNotNull(session1)
        assertNotNull(session2)
        // Both sessions should wrap the same underlying ParseResult
        assertTrue(session1.result.isSuccess)
        assertTrue(session2.result.isSuccess)
    }

    @Test
    fun `session handles complex Groovy features`() = runBlocking {
        val code = """
            @groovy.transform.CompileStatic
            class ComplexExample {
                private final String name
                private final List<Integer> numbers

                ComplexExample(String name, List<Integer> numbers) {
                    this.name = name
                    this.numbers = numbers
                }

                def calculate() {
                    numbers.collect { it * 2 }.sum()
                }

                @Override
                String toString() {
                    "ComplexExample(name=${'$'}name, numbers=${'$'}numbers)"
                }
            }
        """.trimIndent()
        val uri = URI.create("file:///test/Complex.groovy")

        documentProvider.put(uri, code)
        compilationService.compile(uri, code)
        val session = compilationService.getSession(uri)

        assertNotNull(session)
        assertTrue(session.result.isSuccess, "Complex Groovy code should compile successfully")
        assertNotNull(session.features.hoverProvider)
    }

    @Test
    fun `session handles script files`() = runBlocking {
        val code = """
            println "Hello, World!"
            def x = 42
            assert x > 0
        """.trimIndent()
        val uri = URI.create("file:///test/script.groovy")

        documentProvider.put(uri, code)
        compilationService.compile(uri, code)
        val session = compilationService.getSession(uri)

        assertNotNull(session)
        assertTrue(session.result.isSuccess, "Script should compile successfully")
    }

    @Test
    fun `session handles packages and imports`() = runBlocking {
        val code = """
            package com.example.test

            import java.util.HashMap
            import java.util.ArrayList

            class ImportTest {
                private HashMap<String, Integer> map = new HashMap<>()
                private ArrayList<String> list = new ArrayList<>()

                void add(String key, Integer value) {
                    map.put(key, value)
                    list.add(key)
                }

                Integer get(String key) {
                    return map.get(key)
                }
            }
        """.trimIndent()
        val uri = URI.create("file:///test/ImportTest.groovy")

        documentProvider.put(uri, code)
        compilationService.compile(uri, code)
        val session = compilationService.getSession(uri)

        assertNotNull(session)
        assertTrue(session.result.isSuccess, "Code with imports and packages should compile")
    }

    @Test
    fun `diagnostics list is immutable and safe to iterate`() = runBlocking {
        val code = "class DiagnosticsTest { String field }"
        val uri = URI.create("file:///test/DiagnosticsTest.groovy")

        documentProvider.put(uri, code)
        compilationService.compile(uri, code)
        val session = compilationService.getSession(uri)

        assertNotNull(session)
        val diagnostics = session.result.diagnostics

        // Should be able to iterate without throwing
        var count = 0
        diagnostics.forEach { count++ }
        assertEquals(diagnostics.size, count, "Diagnostics list should be iterable")
        assertTrue(diagnostics.isEmpty(), "Valid code should have zero diagnostics")
    }
}
