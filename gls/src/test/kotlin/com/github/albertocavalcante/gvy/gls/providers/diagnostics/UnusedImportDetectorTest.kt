// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.nativeapi.AnnotationCoverage
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun `collector annotations preserve their imports without crediting generated components`() {
        val ast = compile(
            """
            import groovy.transform.Immutable
            import groovy.transform.CompileStatic
            import groovy.transform.ToString
            @Immutable
            @CompileStatic
            class Money { BigDecimal amount }
            """.trimIndent(),
        )

        assertEquals(
            listOf("groovy.transform.ToString"),
            UnusedImportDetector.detectUnusedImports(ast).map { it.className },
        )
    }

    @Test
    fun `annotated imports are excluded when their source range may include a directive`() {
        val source =
            """
            @java.lang.Deprecated import java.util.List
            class Example {}
            """.trimIndent()
        val result = runBlocking { compilationService.compile(uri, source) }
        assertTrue(result.isSuccess)
        assertEquals(AnnotationCoverage.Complete, result.sourceAnnotations.coverage)
        val ast = assertNotNull(result.ast as? ModuleNode)
        val listImport = ast.imports.single { it.className == "java.util.List" }

        assertTrue(listImport.annotations.isNotEmpty(), "Fixture must attach the marker to the import node")
        assertTrue(UnusedImportDetector.detectUnusedImports(ast, result.sourceAnnotations).none { it === listImport })
    }

    @Test
    fun `collector expansion cannot erase source annotations from unused import safety`() {
        val source =
            """
            import groovy.transform.AnnotationCollector
            @Marker import java.util.List
            @AnnotationCollector(processor='example.NotLoadedDuringAnalysis') @interface Marker {}
            class Example {}
            """.trimIndent()
        val result = runBlocking { compilationService.compile(uri, source) }
        assertTrue(result.isSuccess, result.diagnostics.toString())
        assertEquals(AnnotationCoverage.Complete, result.sourceAnnotations.coverage)
        val ast = assertNotNull(result.ast as? ModuleNode)
        val listImport = ast.imports.single { it.className == "java.util.List" }

        assertTrue(listImport.annotations.isEmpty(), "Collector expansion should remove the mutable AST marker")
        assertTrue(result.sourceAnnotations.isSourceAnnotated(listImport))
        assertTrue(UnusedImportDetector.detectUnusedImports(ast, result.sourceAnnotations).none { it === listImport })
    }

    @Test
    fun `ordinary annotation import wins over a colliding static alias`() {
        val ast = compile(
            """
            import groovy.transform.CompileStatic as Mark
            import static java.lang.Math.PI as Mark
            @Mark class Money {}
            """.trimIndent(),
        )
        val unused = UnusedImportDetector.detectUnusedImports(ast)

        assertEquals(1, unused.size)
        assertEquals("java.lang.Math", unused.single().className)
        assertEquals("PI", unused.single().fieldName)
    }

    @Test
    fun `fully qualified annotations do not credit an explicit import of the same type`() {
        val ast = compile(
            """
            import groovy.transform.CompileStatic
            @groovy.transform.CompileStatic
            class Money {}
            """.trimIndent(),
        )

        assertEquals(
            listOf("groovy.transform.CompileStatic"),
            UnusedImportDetector.detectUnusedImports(ast).map { it.className },
        )
    }

    @Test
    fun `only the written alias of a collector annotation credits its import`() {
        val ast = compile(
            """
            import groovy.transform.Immutable as Value
            import groovy.transform.Immutable as Other
            @Value
            class Money { BigDecimal amount }
            """.trimIndent(),
        )

        assertEquals(listOf("Other"), UnusedImportDetector.detectUnusedImports(ast).map { it.alias })
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

    @Test
    fun `should not report import used as class literal in constructor argument`() {
        val ast = compile(
            """
            import java.util.Date
            import java.util.HashMap
            def mock = new Object(Date.class)
            """.trimIndent(),
        )

        val unusedImports = UnusedImportDetector.detectUnusedImports(ast)

        assertEquals(1, unusedImports.size, "Should detect exactly one unused import")
        assertTrue(
            unusedImports.none { it.className?.contains("Date") == true },
            "Date should NOT be unused (used as .class in constructor argument)",
        )
        assertTrue(
            unusedImports.any { it.className?.contains("HashMap") == true },
            "HashMap should be detected as unused",
        )
    }
}
