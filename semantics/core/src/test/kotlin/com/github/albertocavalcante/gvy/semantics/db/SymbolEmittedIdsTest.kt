// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The ids [SemanticDocumentBuilder] really emits, read back by [Symbol] and formatted again from the parts it
 * reads. A shape the indexer emits but [Symbol] cannot rebuild fails here, not in a downstream parser.
 */
class SymbolEmittedIdsTest : SymbolIdTestSupport() {

    private class Expected(val id: String, val owner: String, val member: String, val descriptor: String?)

    private val packaged = build(
        """
        package com.example
        import java.util.List
        import java.io.*
        import static java.lang.Math.max

        class Outer {
            String label
            private int count
            Outer(String label) { this.label = label }
            Outer(String label, int count) { this(label) }
            Outer() { }
            void noArgs() {}
            void withArray(String[] items) { int local = 1 }
            void withVarargs(String... rest) {}
            { def fromInitializer = 1 }
            static class Inner {
                void go(int times) {}
            }
        }
        """,
    )

    private val defaultPackage = build(
        """
        class Plain {
            def run() { def inside = 1 }
        }
        def scriptMethod(int n) { n }
        """,
    )

    private fun SemanticDocument.symbolIds() = symbols.map { it.symbol }

    private fun assertParts(document: SemanticDocument, expected: Expected) {
        assertTrue(expected.id in document.symbolIds(), "${expected.id} is emitted; got ${document.symbolIds()}")
        val parsed = Symbol(expected.id)
        assertEquals(expected.owner, parsed.owner, "${expected.id} owner")
        assertEquals(expected.member, parsed.member, "${expected.id} member")
        assertEquals(expected.descriptor, parsed.descriptor, "${expected.id} descriptor")
    }

    @Test
    fun `classes read as their internal name and have no member`() {
        listOf(
            Expected("com/example/Outer#", "com/example/Outer", "", null),
            Expected("com/example/Outer\$Inner#", "com/example/Outer\$Inner", "", null),
        ).forEach { assertParts(packaged, it) }
        assertParts(defaultPackage, Expected("Plain#", "Plain", "", null))
    }

    @Test
    fun `fields and properties read as owner and name`() {
        listOf(
            Expected("com/example/Outer#label.", "com/example/Outer", "label", null),
            Expected("com/example/Outer#count.", "com/example/Outer", "count", null),
        ).forEach { assertParts(packaged, it) }
    }

    @Test
    fun `methods read their descriptor as the builder writes it`() {
        listOf(
            Expected("com/example/Outer#noArgs().", "com/example/Outer", "noArgs", ""),
            Expected("com/example/Outer#withArray(String[]).", "com/example/Outer", "withArray", "String[]"),
            // Varargs are an array to the compiler, so the id says String[], never String...
            Expected("com/example/Outer#withVarargs(String[]).", "com/example/Outer", "withVarargs", "String[]"),
            Expected("com/example/Outer\$Inner#go(int).", "com/example/Outer\$Inner", "go", "int"),
        ).forEach { assertParts(packaged, it) }
        assertParts(defaultPackage, Expected("Plain#run().", "Plain", "run", ""))
    }

    @Test
    fun `constructors read as the init member with the descriptor the builder writes`() {
        listOf(
            Expected("com/example/Outer#<init>().", "com/example/Outer", "<init>", ""),
            Expected("com/example/Outer#<init>(String).", "com/example/Outer", "<init>", "String"),
            Expected("com/example/Outer#<init>(String,int).", "com/example/Outer", "<init>", "String,int"),
        ).forEach { assertParts(packaged, it) }
        val symbols = packaged.symbols.filter { it.kind == SymbolKind.CONSTRUCTOR }
        assertEquals(3, symbols.size)
        symbols.forEach {
            assertEquals("Outer", it.name)
            assertEquals("com/example/Outer#", it.owner)
        }
    }

    @Test
    fun `constructor parameters read as the constructor they belong to`() {
        val parameter = Symbol(
            packaged.symbols.single {
                it.kind == SymbolKind.PARAMETER && it.owner == "com/example/Outer#<init>(String)."
            }
                .symbol,
        )
        assertEquals("com/example/Outer", parameter.owner)
        assertEquals("<init>", parameter.member)
        assertEquals("String", parameter.descriptor)
    }

    @Test
    fun `a script method has no class part, so owner and member read the whole id`() {
        val id = "scriptMethod(int)."
        assertParts(defaultPackage, Expected(id, id, id, "int"))
    }

    @Test
    fun `parameters and locals read as the method they belong to`() {
        val parameter = Symbol(packaged.symbols.single { it.kind == SymbolKind.PARAMETER && it.name == "items" }.symbol)
        val local = Symbol(packaged.symbols.single { it.name == "local" }.symbol)
        listOf(parameter, local).forEach {
            assertEquals("com/example/Outer", it.owner)
            assertEquals("withArray", it.member)
            assertEquals("String[]", it.descriptor)
        }
        val inside = Symbol(defaultPackage.symbols.single { it.name == "inside" }.symbol)
        assertEquals("Plain", inside.owner)
        assertEquals("run", inside.member)
        assertEquals("", inside.descriptor)
    }

    @Test
    fun `a local in an initializer block hangs from the initializer scope`() {
        val id = packaged.symbols.single { it.name == "fromInitializer" }.symbol
        val parsed = Symbol(id)
        assertEquals("com/example/Outer", parsed.owner)
        assertEquals("{init}", parsed.member)
        assertEquals(null, parsed.descriptor)
    }

    @Test
    fun `imports read as the import marker and the imported name`() {
        val ids = packaged.symbols.filter { it.kind == SymbolKind.IMPORT }.map { it.symbol }
        assertEquals(
            setOf("import#java/util/List", "import#java/io/", "import#java/lang/Math.max"),
            ids.toSet(),
        )
        ids.forEach { assertEquals("import", Symbol(it).owner) }
    }

    @Test
    fun `every emitted symbol id formats back from the parts read from it`() {
        listOf(packaged, defaultPackage).forEach { document ->
            document.symbols.forEach { info ->
                assertEquals(info.symbol, rebuilt(info).id, "${info.kind} ${info.symbol}")
            }
        }
    }

    private fun rebuilt(info: SymbolInfo): Symbol {
        val parsed = Symbol(info.symbol)
        val owner = Symbol.ofInternalName(parsed.owner)
        return when (info.kind) {
            SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM -> owner
            SymbolKind.FIELD, SymbolKind.PROPERTY -> Symbol.ofField(owner, parsed.member)
            SymbolKind.METHOD, SymbolKind.CONSTRUCTOR -> methodOf(info, parsed)
            SymbolKind.PARAMETER -> Symbol.ofParameter(Symbol(checkNotNull(info.owner)), info.name)
            SymbolKind.VARIABLE -> {
                val scope = Symbol(checkNotNull(info.owner))
                Symbol.ofLocal(scope, info.name, info.range.startLine, info.range.startColumn)
            }
            SymbolKind.IMPORT -> importOf(info)
            SymbolKind.ENUM_MEMBER -> Symbol.ofField(owner, parsed.member)
        }
    }

    /** A method of a class, or of no class (a script method), which the builder gives no `#` part. */
    private fun methodOf(info: SymbolInfo, parsed: Symbol): Symbol {
        val owner = info.owner?.let { Symbol.ofInternalName(Symbol(it).owner) }
        return if (owner == null) {
            Symbol.ofMethod(null, info.name, parsed.descriptor.orEmpty())
        } else {
            Symbol.ofMethod(owner, parsed.member, parsed.descriptor.orEmpty())
        }
    }

    private fun importOf(info: SymbolInfo): Symbol {
        val imported = Symbol(info.symbol).member
        val plain = Symbol.ofImport(imported)
        // A static import's member is the symbol's name; the id's own member is the imported class.
        return if (plain.id == info.symbol) plain else Symbol.ofStaticImport(imported, info.name)
    }
}
