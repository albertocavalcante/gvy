package com.github.albertocavalcante.groovylsp.providers.diagnostics

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for UnusedImportDetector - compares imports against used types.
 */
class UnusedImportDetectorTest {

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
    fun `should detect unused regular import`() {
        val ast = compile(
            """
            import java.util.ArrayList
            import java.util.HashMap

            ArrayList list = new ArrayList()
            """.trimIndent(),
        )

        val unusedImports = UnusedImportDetector.detectUnusedImports(ast)

        assertEquals(1, unusedImports.size, "Should detect exactly one unused import")
        assertTrue(
            unusedImports.any { it.className?.contains("HashMap") == true },
            "Should detect HashMap as unused",
        )
    }

    @Test
    fun `should not report used imports`() {
        val ast = compile(
            """
            import java.util.ArrayList
            import java.util.HashMap

            ArrayList list = new ArrayList()
            HashMap map = new HashMap()
            """.trimIndent(),
        )

        val unusedImports = UnusedImportDetector.detectUnusedImports(ast)

        assertTrue(unusedImports.isEmpty(), "Should not report any unused imports")
    }

    @Test
    fun `should detect all unused imports when none are used`() {
        val ast = compile(
            """
            import java.util.ArrayList
            import java.util.HashMap
            import java.util.LinkedList

            def x = 1
            """.trimIndent(),
        )

        val unusedImports = UnusedImportDetector.detectUnusedImports(ast)

        assertEquals(3, unusedImports.size, "Should detect all three imports as unused")
    }

    @Test
    fun `should handle aliased imports correctly`() {
        val ast = compile(
            """
            import java.util.ArrayList as AL
            import java.util.HashMap as HM

            AL list = new AL()
            """.trimIndent(),
        )

        val unusedImports = UnusedImportDetector.detectUnusedImports(ast)

        assertEquals(1, unusedImports.size, "Should detect exactly one unused import")
        assertTrue(
            unusedImports.any { it.className?.contains("HashMap") == true },
            "Should detect HM (HashMap) as unused",
        )
    }

    @Test
    fun `should not report star imports as unused`() {
        val ast = compile(
            """
            import java.util.*

            def x = 1
            """.trimIndent(),
        )

        val unusedImports = UnusedImportDetector.detectUnusedImports(ast)

        assertTrue(
            unusedImports.isEmpty(),
            "Star imports should not be reported as unused (too complex to analyze)",
        )
    }

    @Test
    fun `should handle static imports when field is accessed`() {
        // Note: Static imports of constant fields like Math.PI may be inlined by Groovy
        // at compile time, making them appear unused. This test verifies basic static
        // import detection using a pattern that's more reliably detected.
        val ast = compile(
            """
            import static java.util.Collections.emptyList
            import static java.util.Collections.emptyMap

            def list = emptyList()
            """.trimIndent(),
        )

        val unusedImports = UnusedImportDetector.detectUnusedImports(ast)

        // emptyMap should be detected as unused
        assertEquals(1, unusedImports.size, "Should detect exactly one unused static import")
        assertTrue(
            unusedImports.any { it.fieldName == "emptyMap" },
            "Should detect emptyMap as unused. Found: ${unusedImports.map { it.fieldName }}",
        )
    }

    @Test
    fun `should not report static star imports as unused`() {
        val ast = compile(
            """
            import static java.lang.Math.*

            def x = 1
            """.trimIndent(),
        )

        val unusedImports = UnusedImportDetector.detectUnusedImports(ast)

        assertTrue(
            unusedImports.isEmpty(),
            "Static star imports should not be reported as unused",
        )
    }

    @Test
    fun `should provide correct line information`() {
        val ast = compile(
            """
            import java.util.ArrayList
            import java.util.HashMap

            def x = 1
            """.trimIndent(),
        )

        val unusedImports = UnusedImportDetector.detectUnusedImports(ast)

        assertEquals(2, unusedImports.size)
        // Line numbers are 1-based in Groovy AST
        assertTrue(unusedImports.any { it.lineNumber == 1 }, "Should include line 1")
        assertTrue(unusedImports.any { it.lineNumber == 2 }, "Should include line 2")
    }

    @Test
    fun `should detect import used only in annotation`() {
        val ast = compile(
            """
            import groovy.transform.ToString
            import java.util.HashMap

            @ToString
            class Data {}
            """.trimIndent(),
        )

        val unusedImports = UnusedImportDetector.detectUnusedImports(ast)

        assertEquals(1, unusedImports.size, "Should detect exactly one unused import")
        assertTrue(
            unusedImports.any { it.className?.contains("HashMap") == true },
            "Should detect HashMap as unused (ToString is used)",
        )
    }

    @Test
    fun `should detect import used in extends clause`() {
        val ast = compile(
            """
            import java.util.AbstractList
            import java.util.HashMap

            class MyList extends AbstractList {
                Object get(int i) { null }
                int size() { 0 }
            }
            """.trimIndent(),
        )

        val unusedImports = UnusedImportDetector.detectUnusedImports(ast)

        assertEquals(1, unusedImports.size, "Should detect exactly one unused import")
        assertTrue(
            unusedImports.any { it.className?.contains("HashMap") == true },
            "Should detect HashMap as unused (AbstractList is used)",
        )
    }

    @Test
    fun `should handle empty imports list`() {
        val ast = compile(
            """
            class Test {
                def x = 1
            }
            """.trimIndent(),
        )

        val unusedImports = UnusedImportDetector.detectUnusedImports(ast)

        assertTrue(unusedImports.isEmpty(), "Should handle empty imports gracefully")
    }
}
