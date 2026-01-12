package com.github.albertocavalcante.groovylsp.providers.diagnostics

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for TypeUsageCollector - deterministic AST-based type reference collection.
 *
 * The collector identifies all type names used in code to support unused import detection.
 */
class TypeUsageCollectorTest {

    private lateinit var compilationService: GroovyCompilationService
    private val uri = URI.create("file:///Test.groovy")

    @BeforeEach
    fun setup() {
        compilationService = GroovyCompilationService()
    }

    private fun compile(code: String): ModuleNode = runBlocking {
        compilationService.compile(uri, code)
        compilationService.getAst(uri) as ModuleNode
    }

    @Test
    fun `should collect type from variable declaration`() {
        val ast = compile(
            """
            import java.util.ArrayList
            class Test {
                ArrayList list = new ArrayList()
            }
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("ArrayList"), "Should collect ArrayList from variable declaration")
    }

    @Test
    fun `should collect type from method parameter`() {
        val ast = compile(
            """
            import java.util.List
            class Test {
                void process(List items) {}
            }
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("List"), "Should collect List from method parameter")
    }

    @Test
    fun `should collect type from return type`() {
        val ast = compile(
            """
            import java.util.Map
            class Test {
                Map getMap() { [:] }
            }
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("Map"), "Should collect Map from return type")
    }

    @Test
    fun `should collect type from constructor call`() {
        val ast = compile(
            """
            import java.util.HashMap
            def map = new HashMap()
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("HashMap"), "Should collect HashMap from constructor call")
    }

    @Test
    fun `should collect type from extends clause`() {
        val ast = compile(
            """
            import java.util.AbstractList
            class MyList extends AbstractList {
                Object get(int i) { null }
                int size() { 0 }
            }
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("AbstractList"), "Should collect AbstractList from extends")
    }

    @Test
    fun `should collect type from implements clause`() {
        val ast = compile(
            """
            import java.io.Serializable
            class Data implements Serializable {}
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("Serializable"), "Should collect Serializable from implements")
    }

    @Test
    fun `should collect type from static method call`() {
        val ast = compile(
            """
            import java.util.Collections
            def list = Collections.emptyList()
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("Collections"), "Should collect Collections from static call")
    }

    @Test
    fun `should collect type from class expression`() {
        val ast = compile(
            """
            import java.util.Date
            def clazz = Date.class
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("Date"), "Should collect Date from .class expression")
    }

    @Test
    fun `should collect type from annotation`() {
        val ast = compile(
            """
            import groovy.transform.ToString
            @ToString
            class Data {}
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("ToString"), "Should collect ToString from annotation")
    }

    @Test
    fun `should handle aliased imports by collecting alias name`() {
        val ast = compile(
            """
            import java.util.ArrayList as AL
            AL list = new AL()
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        // When using alias, code references "AL" not "ArrayList"
        // But AST resolves to full type - we need to check nameWithoutPackage
        assertTrue(
            usedTypes.contains("ArrayList") || usedTypes.contains("AL"),
            "Should collect type from aliased import usage. Found: $usedTypes",
        )
    }

    @Test
    fun `should collect types from generic type arguments`() {
        val ast = compile(
            """
            import java.util.List
            import java.util.Map
            List<Map> nested = []
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("List"), "Should collect List from generic")
        assertTrue(usedTypes.contains("Map"), "Should collect Map from generic argument")
    }

    @Test
    fun `should collect type from catch clause`() {
        val ast = compile(
            """
            import java.io.IOException
            try {} catch (IOException e) {}
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("IOException"), "Should collect IOException from catch")
    }

    @Test
    fun `should collect type from instanceof expression`() {
        val ast = compile(
            """
            import java.util.List
            def x = null
            if (x instanceof List) {}
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("List"), "Should collect List from instanceof")
    }

    @Test
    fun `should collect type from cast expression`() {
        val ast = compile(
            """
            import java.util.List
            def x = (List) []
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("List"), "Should collect List from cast")
    }

    @Test
    fun `should not include primitive types`() {
        val ast = compile(
            """
            class Test {
                int count = 0
                boolean flag = true
            }
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertFalse(usedTypes.contains("int"), "Should not include primitive int")
        assertFalse(usedTypes.contains("boolean"), "Should not include primitive boolean")
    }

    @Test
    fun `should collect type from closure parameter`() {
        val ast = compile(
            """
            import java.util.Date
            def closure = { Date d -> d.toString() }
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("Date"), "Should collect Date from closure parameter")
    }

    @Test
    fun `should collect type from field declaration`() {
        val ast = compile(
            """
            import java.util.Set
            class Test {
                Set items
            }
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("Set"), "Should collect Set from field declaration")
    }

    @Test
    fun `should collect type from annotation parameter value`() {
        val ast = compile(
            """
            import java.lang.annotation.ElementType
            import java.lang.annotation.Target
            @Target(ElementType.TYPE)
            @interface MyAnnotation {}
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("Target"), "Should collect Target annotation type")
        assertTrue(usedTypes.contains("ElementType"), "Should collect ElementType from annotation parameter")
    }

    @Test
    fun `should collect type from class expression as constructor argument`() {
        val ast = compile(
            """
            import java.util.Date
            def mock = new Object(Date.class)
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(usedTypes.contains("Date"), "Should collect Date from .class in constructor argument")
    }

    @Test
    fun `should collect type from class expression in StubFor pattern`() {
        // Mirrors the real-world pattern from jenkins pipeline-library tests
        val ast = compile(
            """
            import java.text.SimpleDateFormat
            import groovy.mock.interceptor.StubFor

            class Test {
                def simpleDateMock

                void setUp() {
                    simpleDateMock = new StubFor(SimpleDateFormat.class)
                    simpleDateMock.demand.with {
                        format { "2022-02-02" }
                    }
                }
            }
            """.trimIndent(),
        )

        val usedTypes = TypeUsageCollector.collectUsedTypes(ast)

        assertTrue(
            usedTypes.contains("SimpleDateFormat"),
            "Should collect SimpleDateFormat from .class in StubFor constructor",
        )
        assertTrue(usedTypes.contains("StubFor"), "Should collect StubFor from constructor call")
    }
}
