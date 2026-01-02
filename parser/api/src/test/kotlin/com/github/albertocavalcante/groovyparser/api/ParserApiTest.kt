package com.github.albertocavalcante.groovyparser.api

import com.github.albertocavalcante.groovyparser.api.model.Diagnostic
import com.github.albertocavalcante.groovyparser.api.model.NodeInfo
import com.github.albertocavalcante.groovyparser.api.model.NodeKind
import com.github.albertocavalcante.groovyparser.api.model.Position
import com.github.albertocavalcante.groovyparser.api.model.Range
import com.github.albertocavalcante.groovyparser.api.model.Severity
import com.github.albertocavalcante.groovyparser.api.model.SymbolInfo
import com.github.albertocavalcante.groovyparser.api.model.SymbolKind
import com.github.albertocavalcante.groovyparser.api.model.TypeInfo
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the parser API model types.
 */
class ParserApiTest {

    @Test
    fun `Position comparison works correctly`() {
        val pos1 = Position(1, 1)
        val pos2 = Position(1, 5)
        val pos3 = Position(2, 1)

        assertTrue(pos1 < pos2)
        assertTrue(pos2 < pos3)
        assertTrue(pos1 < pos3)
    }

    @Test
    fun `Range contains position correctly`() {
        val range = Range(Position(1, 1), Position(1, 10))

        assertTrue(range.contains(Position(1, 5)))
        assertTrue(range.contains(Position(1, 1)))
        assertTrue(range.contains(Position(1, 10)))
        assertFalse(range.contains(Position(1, 11)))
        assertFalse(range.contains(Position(2, 1)))
    }

    @Test
    fun `ParserCapabilities has sensible defaults`() {
        val basic = ParserCapabilities.BASIC
        assertFalse(basic.supportsErrorRecovery)
        assertFalse(basic.supportsRefactoring)
        assertTrue(basic.supportsPositionTracking)

        val full = ParserCapabilities.FULL
        assertTrue(full.supportsErrorRecovery)
        assertTrue(full.supportsRefactoring)
    }

    @Test
    fun `TypeInfo companion objects are correct`() {
        assertEquals("void", TypeInfo.VOID.name)
        assertEquals("?", TypeInfo.UNKNOWN.name)
        assertFalse(TypeInfo.UNKNOWN.isResolved)
    }

    @Test
    fun `ParseUnit can be implemented`() {
        // Simple stub implementation to verify interface is usable
        val stubUnit = object : ParseUnit {
            override val source = "class Foo {}"
            override val path: Path? = null
            override val isSuccessful = true
            override fun nodeAt(position: Position): NodeInfo? = null
            override fun diagnostics(): List<Diagnostic> = emptyList()
            override fun symbols(): List<SymbolInfo> = listOf(
                SymbolInfo("Foo", SymbolKind.CLASS, Range(Position(1, 1), Position(1, 12))),
            )

            override fun typeAt(position: Position): TypeInfo? = null
        }

        assertEquals("class Foo {}", stubUnit.source)
        assertTrue(stubUnit.isSuccessful)
        assertEquals(1, stubUnit.symbols().size)
        assertEquals("Foo", stubUnit.symbols().first().name)
    }

    @Test
    fun `Diagnostic can be created`() {
        val diag = Diagnostic(
            severity = Severity.ERROR,
            message = "Unexpected token",
            range = Range(Position(1, 1), Position(1, 5)),
        )

        assertEquals(Severity.ERROR, diag.severity)
        assertEquals("groovy-parser", diag.source)
    }

    @Test
    fun `NodeInfo and NodeKind cover major cases`() {
        val node = NodeInfo(
            kind = NodeKind.CLASS,
            name = "Foo",
            range = Range.EMPTY,
        )
        assertEquals(NodeKind.CLASS, node.kind)

        // Verify major categories exist
        assertTrue(NodeKind.entries.contains(NodeKind.METHOD))
        assertTrue(NodeKind.entries.contains(NodeKind.CLOSURE))
        assertTrue(NodeKind.entries.contains(NodeKind.METHOD_CALL))
    }
}
