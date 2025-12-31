package com.github.albertocavalcante.groovylsp.engine.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNodeKind
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedSymbol
import io.mockk.every
import io.mockk.mockk
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnifiedDocumentSymbolProviderTest {

    private val parseUnit = mockk<ParseUnit>()
    private val provider = UnifiedDocumentSymbolProvider(parseUnit)

    @Test
    fun `getDocumentSymbols maps unified symbols to LSP symbols`() {
        val range = Range(Position(0, 0), Position(10, 0))
        val unifiedSymbol = UnifiedSymbol(
            name = "MyClass",
            kind = UnifiedNodeKind.CLASS,
            range = range,
            selectionRange = range,
            children = listOf(
                UnifiedSymbol(
                    name = "myMethod",
                    kind = UnifiedNodeKind.METHOD,
                    range = range,
                    selectionRange = range,
                ),
            ),
        )

        every { parseUnit.allSymbols() } returns listOf(unifiedSymbol)

        val result = provider.getDocumentSymbols()

        assertEquals(1, result.size)
        val classSymbol = result[0]
        assertEquals("MyClass", classSymbol.name)
        assertEquals(SymbolKind.Class, classSymbol.kind)
        assertEquals(1, classSymbol.children.size)

        val methodSymbol = classSymbol.children[0]
        assertEquals("myMethod", methodSymbol.name)
        assertEquals(SymbolKind.Method, methodSymbol.kind)
    }
}
