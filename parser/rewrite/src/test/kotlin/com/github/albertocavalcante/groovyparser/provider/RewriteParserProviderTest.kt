package com.github.albertocavalcante.groovyparser.provider

import com.github.albertocavalcante.groovyparser.api.model.NodeKind
import com.github.albertocavalcante.groovyparser.api.model.Position
import com.github.albertocavalcante.groovyparser.api.model.Range
import com.github.albertocavalcante.groovyparser.api.model.Severity
import com.github.albertocavalcante.groovyparser.api.model.SymbolKind
import org.junit.jupiter.api.Test
import org.openrewrite.groovy.GroovyParser
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.groovy.tree.G
import org.openrewrite.java.tree.J
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RewriteParserProviderTest {

    private val provider = RewriteParserProvider()

    @Test
    fun `provider has correct name`() {
        assertEquals("rewrite", provider.name)
    }

    @Test
    fun `provider has correct capabilities`() {
        assertFalse(provider.capabilities.supportsErrorRecovery)
        assertTrue(provider.capabilities.supportsCommentPreservation)
        assertFalse(provider.capabilities.supportsSymbolResolution)
        assertTrue(provider.capabilities.supportsRefactoring)
    }

    @Test
    fun `parse valid class returns successful unit`() {
        val source = "class Foo { String name }"
        val unit = provider.parse(source)

        assertTrue(unit.isSuccessful)
        assertEquals(source, unit.source)
        assertTrue(unit.diagnostics().isEmpty())
    }

    @Test
    fun `parse invalid code returns unsuccessful unit`() {
        val source = "class Broken { void method( { }"
        val unit = provider.parse(source)

        assertFalse(unit.isSuccessful)

        val diagnostics = unit.diagnostics()
        assertEquals(1, diagnostics.size)
        assertEquals(Severity.ERROR, diagnostics.single().severity)
        assertEquals("rewrite-parser", diagnostics.single().source)
        assertTrue(diagnostics.single().message.isNotBlank())
        assertTrue(unit.symbols().isEmpty())
    }

    @Test
    fun `symbols extracts class and method`() {
        val source = """
            class Person {
                String name
                void greet() { println name }
            }
        """.trimIndent()
        val unit = provider.parse(source)

        val symbols = unit.symbols()
        assertTrue(symbols.any { it.name == "Person" && it.kind == SymbolKind.CLASS })
        assertTrue(symbols.any { it.name == "greet" && it.kind == SymbolKind.METHOD })
        assertTrue(symbols.any { it.name == "name" && it.kind == SymbolKind.FIELD })
    }

    @Test
    fun `declaration positions track comments nested Groovy and CRLF using UTF-16 columns`() {
        val source = listOf(
            "// 😀 heading",
            "class Outer {",
            "    String name = \"$" + "value\"",
            "    def work() {",
            "        def c = { it?.trim() ?: 'none' }",
            "        c(name)",
            "    }",
            "    class Inner { int nested }",
            "}",
            "",
        ).joinToString("\r\n")
        val unit = provider.parse(source)
        assertTrue(unit.isSuccessful)
        val symbols = unit.symbols()
        fun symbolRange(name: String) = symbols.single { it.name == name }.range
        assertEquals(Range(Position(2, 1), Position(9, 2)), symbolRange("Outer"))
        assertEquals(Range(Position(3, 12), Position(3, 27)), symbolRange("name"))
        assertEquals(Range(Position(4, 5), Position(7, 6)), symbolRange("work"))
        assertEquals(Range(Position(8, 5), Position(8, 31)), symbolRange("Inner"))
        assertEquals(Range(Position(8, 23), Position(8, 29)), symbolRange("nested"))
        val classNode = assertNotNull(unit.nodeAt(Position(2, 1)))
        assertEquals(NodeKind.CLASS, classNode.kind)
        assertEquals(symbolRange("Outer"), classNode.range)
        val methodNode = assertNotNull(unit.nodeAt(Position(4, 5)))
        assertEquals(NodeKind.METHOD, methodNode.kind)
        assertEquals(symbolRange("work"), methodNode.range)
    }

    @Test
    fun `node lookup excludes the end of adjacent declarations`() {
        val unit = provider.parse("class A {}\nclass B {}")
        assertTrue(unit.isSuccessful)
        assertEquals("A", assertNotNull(unit.nodeAt(Position(1, 1))).name)
        assertEquals(null, unit.nodeAt(Position(1, 11)))
        assertEquals("B", assertNotNull(unit.nodeAt(Position(2, 1))).name)
    }

    @Test
    fun `mapping fails closed if the printed tree does not match source`() {
        val tree = GroovyParser.builder().build().parse("class Example {}").findFirst().get() as G.CompilationUnit
        val unit = RewriteParseUnit("different source", null, tree)
        assertTrue(unit.isSuccessful)
        assertEquals(Range.EMPTY, unit.symbols().single().range)
        assertEquals(null, unit.nodeAt(Position(1, 1)))
    }

    @Test
    fun `positions count supplementary characters as UTF-16 columns`() {
        val source = "class C { /* 😀 */ def work() {} }"
        val unit = provider.parse(source)
        assertTrue(unit.isSuccessful)
        val start = source.indexOf("def")
        val end = source.indexOf('}', start) + 1
        assertEquals(
            Range(Position(1, start + 1), Position(1, end + 1)),
            unit.symbols().single { it.name == "work" }.range,
        )
    }

    @Test
    fun `node lookup rejects columns past the requested line`() {
        val unit = provider.parse("class Outer {\n  def work() {}\n}")
        assertTrue(unit.isSuccessful)
        assertEquals(null, unit.nodeAt(Position(2, 1000)))
    }

    @Test
    fun `mapping leaves original markers untouched and supports concurrent readers`() {
        val source = "class Example { String name }"
        val tree = GroovyParser.builder().build().parse(source).findFirst().get() as G.CompilationUnit
        val declarations = mutableListOf<J.ClassDeclaration>()
        object : GroovyVisitor<Unit>() {
            override fun visitClassDeclaration(classDecl: J.ClassDeclaration, p: Unit): J {
                declarations.add(classDecl)
                return super.visitClassDeclaration(classDecl, p)
            }
        }.visit(tree, Unit)
        val originalMarkers = declarations.map { it.markers }
        val unit = RewriteParseUnit(source, null, tree)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..8).map {
                executor.submit<Pair<Range, Range>> {
                    val symbol = unit.symbols().single { it.name == "Example" }
                    symbol.range to assertNotNull(unit.nodeAt(Position(1, 1))).range
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            val expected = Range(Position(1, 1), Position(1, source.length + 1))
            assertTrue(results.all { it.first == expected && it.second == expected })
            assertEquals(originalMarkers, declarations.map { it.markers })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `symbols reports interfaces correctly`() {
        val source = """
            interface Greeter {
                void greet()
            }
        """.trimIndent()
        val unit = provider.parse(source)

        val symbols = unit.symbols()
        assertTrue(
            symbols.any { it.name == "Greeter" && it.kind == SymbolKind.INTERFACE },
            "Should report Greeter as INTERFACE",
        )
    }

    @Test
    fun `nodeAt handles invalid positions gracefully`() {
        val source = "class Foo {}"
        val unit = provider.parse(source)

        val invalidPositions = listOf(
            Position(0, 0),
            Position(-1, 5),
            Position(1, 0),
        )

        invalidPositions.forEach { pos ->
            val node = unit.nodeAt(pos)
            assertEquals(null, node, "Should return null for invalid position $pos")
        }
    }

    @Test
    fun `parse script without class returns successful unit`() {
        val source = """
            def greeting = "Hello"
            println greeting
        """.trimIndent()
        val unit = provider.parse(source)

        assertTrue(unit.isSuccessful)
    }

    @Test
    fun `parse groovy closure returns successful unit`() {
        val source = """
            def list = [1, 2, 3]
            list.each { println it }
        """.trimIndent()
        val unit = provider.parse(source)

        assertTrue(unit.isSuccessful)
    }

    @Test
    fun `symbols extracts multiple classes`() {
        val source = """
            class Foo {}
            class Bar {}
        """.trimIndent()
        val unit = provider.parse(source)

        val symbols = unit.symbols()
        assertTrue(symbols.any { it.name == "Foo" && it.kind == SymbolKind.CLASS })
        assertTrue(symbols.any { it.name == "Bar" && it.kind == SymbolKind.CLASS })
    }
}
