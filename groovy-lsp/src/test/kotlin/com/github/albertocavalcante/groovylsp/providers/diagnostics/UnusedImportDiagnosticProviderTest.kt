package com.github.albertocavalcante.groovylsp.providers.diagnostics

import io.mockk.every
import io.mockk.mockk
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnusedImportDiagnosticProviderTest {

    private val provider = UnusedImportDiagnosticProvider()

    @Test
    fun `should return empty list when no imports`() {
        val moduleNode = mockk<ModuleNode>()
        every { moduleNode.imports } returns emptyList()

        val diagnostics = provider.checkUnusedImports(moduleNode, emptySet())
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `should identify unused import`() {
        val moduleNode = mockk<ModuleNode>()
        // Use 2-arg constructor: ImportNode(type, alias) - correctly sets alias property
        val unusedImport = ImportNode(ClassNode("com.unused.Type", 0, null), "Type")
        unusedImport.lineNumber = 10
        unusedImport.columnNumber = 1
        unusedImport.lastLineNumber = 10
        unusedImport.lastColumnNumber = 21 // Groovy AST: exclusive end column

        every { moduleNode.imports } returns listOf(unusedImport)

        // "OtherType" is used, but import alias is "Type" - so this import is unused
        val diagnostics = provider.checkUnusedImports(moduleNode, setOf("OtherType"))

        assertEquals(1, diagnostics.size)
        val diagnostic = diagnostics.first()
        assertEquals("Unused import: com.unused.Type", diagnostic.message)
        assertEquals(9, diagnostic.range.start.line) // LSP is 0-indexed
        assertEquals(0, diagnostic.range.start.character)
        assertEquals(9, diagnostic.range.end.line)
        // NOTE: lastColumnNumber in Groovy AST is 1-based and exclusive, LSP range.end.character is 0-based
        // Groovy AST lastColumnNumber 21 - 1 (0-based conversion) = 20
        assertEquals(20, diagnostic.range.end.character)
        assertEquals(org.eclipse.lsp4j.DiagnosticTag.Unnecessary, diagnostic.tags.first())
    }

    @Test
    fun `should not report used import`() {
        val moduleNode = mockk<ModuleNode>()
        // NOTE: Use 2-arg constructor: ImportNode(type, alias) - correctly sets alias property
        // ImportNode.alias is what we check against usedTypeNames
        val usedImport = ImportNode(ClassNode("com.used.Type", 0, null), "Type")
        usedImport.lineNumber = 5
        usedImport.columnNumber = 1
        usedImport.lastLineNumber = 5
        usedImport.lastColumnNumber = 25
        every { moduleNode.imports } returns listOf(usedImport)

        // "Type" is used in the source - matches the alias
        val diagnostics = provider.checkUnusedImports(moduleNode, setOf("Type"))

        assertTrue(
            diagnostics.isEmpty(),
            "Import with alias 'Type' should not be reported when 'Type' is in usedTypeNames",
        )
    }
}
