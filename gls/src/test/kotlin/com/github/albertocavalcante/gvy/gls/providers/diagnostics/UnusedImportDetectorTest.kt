// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.nativeapi.AnnotationCoverage
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
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

    private fun compile(code: String, phase: Int = Phases.CANONICALIZATION): ModuleNode = runBlocking {
        compilationService.compile(uri, code, phase)
        compilationService.getAst(uri) as ModuleNode
    }

    @Test
    fun `enclosing imports are used in every nested type position`() {
        val declarations = listOf(
            "class Example implements Map.Entry {}",
            "class Example extends AbstractMap.SimpleEntry { Example() { super(null, null) } }",
            "class Example { void method(Map.Entry value) {} }",
            "class Example { Map.Entry method() { null } }",
            "class Example { Map.Entry value }",
            "class Example { void method() { Map.Entry value = null } }",
            "class Example { void method(Object value) { def result = (Map.Entry) value } }",
            "class Example { List<Map.Entry> values }",
            "class Example { void method() { new AbstractMap.SimpleEntry(null, null) } }",
            "class Example { void method() { Map.Entry.comparingByKey() } }",
        )

        declarations.forEach { declaration ->
            val ast = compile("import java.util.Map\nimport java.util.AbstractMap\n$declaration")
            val expectedUnused = if ("AbstractMap." in declaration) "java.util.Map" else "java.util.AbstractMap"
            assertEquals(
                listOf(expectedUnused),
                UnusedImportDetector.detectUnusedImports(ast).map { it.className },
                declaration,
            )
        }
    }

    @Test
    fun `nested type use credits aliases and preserves direct nested imports`() {
        val aliased = compile(
            """
            import java.util.Map as Container
            import java.util.Set
            class Example { Container.Entry value }
            """.trimIndent(),
        )
        assertEquals(listOf("java.util.Set"), UnusedImportDetector.detectUnusedImports(aliased).map { it.className })

        val direct = compile(
            """
            import java.util.Map
            import java.util.Map.Entry
            class Example { Entry value }
            """.trimIndent(),
        )
        assertEquals(listOf("java.util.Map"), UnusedImportDetector.detectUnusedImports(direct).map { it.className })

        val qualified = compile(
            """
            import java.util.Map
            import java.util.Map.Entry
            class Example { Map.Entry value }
            """.trimIndent(),
        )
        assertEquals(listOf("Entry"), UnusedImportDetector.detectUnusedImports(qualified).map { it.alias })
    }

    @Test
    fun `unresolved qualified nested type spelling credits enclosing alias`() {
        val ast = compile(
            """
            import missing.Outer as Container
            import java.util.Set
            class Example { Container.Inner value }
            """.trimIndent(),
        )
        assertTrue("Container" in TypeUsageCollector.collectUsedTypes(ast))
    }

    @Test
    fun `lowercase aliases work in nested type and static access`() {
        val declarations = listOf(
            "class Example { container.Entry value }",
            "class Example { void method() { container.Entry.comparingByKey() } }",
        )
        for (phase in listOf(Phases.CONVERSION, Phases.CANONICALIZATION)) {
            declarations.forEach { declaration ->
                val ast = compile("import java.util.Map as container\nimport java.util.Set\n$declaration", phase)
                assertEquals(
                    listOf("java.util.Set"),
                    UnusedImportDetector.detectUnusedImports(ast).map { it.className },
                    "phase=$phase: $declaration",
                )
            }
        }
    }

    @Test
    fun `static access credits only the type spelling used`() {
        val sources = mapOf(
            "Map.Entry.comparingByKey()" to "Entry",
            "Entry.comparingByKey()" to "Map",
            "java.util.Map.Entry.comparingByKey()" to null,
        )
        sources.forEach { (expression, expectedUnused) ->
            val ast = compile(
                "import java.util.Map\nimport java.util.Map.Entry\nclass Example { void method() { $expression } }",
            )
            val unused = UnusedImportDetector.detectUnusedImports(ast).map { it.alias }
            val expected = expectedUnused?.let(::listOf) ?: listOf("Map", "Entry")
            assertEquals(expected, unused, expression)
        }
    }

    @Test
    fun `static nested access preserves dollar signs in import aliases`() {
        val outerAlias = "Container${'$'}Alias"
        val nestedAlias = "Entry${'$'}Alias"
        val imports = "import java.util.Map as $outerAlias\nimport java.util.Map.Entry as $nestedAlias"
        val cases = mapOf(
            "$outerAlias.Entry.comparingByKey()" to nestedAlias,
            "$nestedAlias.comparingByKey()" to outerAlias,
        )
        cases.forEach { (expression, expectedUnused) ->
            val ast = compile("$imports\nclass Example { void method() { $expression } }")
            assertEquals(
                listOf(expectedUnused),
                UnusedImportDetector.detectUnusedImports(ast).map { it.alias },
                expression,
            )
        }
    }

    @Test
    fun `local property access does not use a shadowed import`() {
        val ast = compile(
            """
            import java.util.Map
            class Example { void method() { def Map = [x: 1]; println Map.x } }
            """.trimIndent(),
        )
        assertEquals(listOf("java.util.Map"), UnusedImportDetector.detectUnusedImports(ast).map { it.className })
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
