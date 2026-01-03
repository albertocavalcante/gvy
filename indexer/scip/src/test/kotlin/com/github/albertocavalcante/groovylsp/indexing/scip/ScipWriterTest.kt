package com.github.albertocavalcante.groovylsp.indexing.scip

import com.github.albertocavalcante.groovylsp.indexing.Range
import org.junit.jupiter.api.Test
import scip.Index
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScipWriterTest {

    @Test
    fun `emits valid protobuf with metadata`() {
        val baos = ByteArrayOutputStream()
        val writer = ScipWriter(baos, "/project")

        writer.close()

        val index = Index.ADAPTER.decode(baos.toByteArray())

        // Metadata validation
        assertEquals("groovy-lsp", index.metadata?.tool_info?.name)
        assertEquals("0.0.1", index.metadata?.tool_info?.version)
        assertTrue(index.metadata?.project_root?.contains("project") == true)
    }

    @Test
    fun `emits document with occurrences`() {
        val baos = ByteArrayOutputStream()
        val writer = ScipWriter(baos, "/project")

        writer.visitDocumentStart("src/Foo.groovy", "class Foo {}")
        writer.visitDefinition(
            Range(1, 7, 1, 10),
            "scip-groovy maven com.example 0.0.0 Foo#",
            isLocal = false,
            documentation = "A sample class",
        )
        writer.visitDocumentEnd()
        writer.close()

        val index = Index.ADAPTER.decode(baos.toByteArray())

        assertEquals(1, index.documents.size)
        val doc = index.documents[0]
        assertEquals("src/Foo.groovy", doc.relative_path)
        assertEquals("groovy", doc.language)

        // Check occurrence
        assertEquals(1, doc.occurrences.size)
        val occ = doc.occurrences[0]
        assertEquals("scip-groovy maven com.example 0.0.0 Foo#", occ.symbol)

        // Range is 0-based in SCIP
        assertEquals(listOf(0, 6, 0, 9), occ.range)

        // Check symbol info
        assertEquals(1, doc.symbols.size)
        assertEquals("scip-groovy maven com.example 0.0.0 Foo#", doc.symbols[0].symbol)
    }

    @Test
    fun `local symbols do not appear in symbol info`() {
        val baos = ByteArrayOutputStream()
        val writer = ScipWriter(baos, "/project")

        writer.visitDocumentStart("src/Foo.groovy", "def x = 1")
        writer.visitDefinition(
            Range(1, 5, 1, 6),
            "local 0",
            isLocal = true,
            documentation = null,
        )
        writer.visitDocumentEnd()
        writer.close()

        val index = Index.ADAPTER.decode(baos.toByteArray())
        val doc = index.documents[0]

        // Occurrence should exist
        assertEquals(1, doc.occurrences.size)
        // But no symbol info for locals
        assertEquals(0, doc.symbols.size)
    }

    @Test
    fun `references have ReadAccess role`() {
        val baos = ByteArrayOutputStream()
        val writer = ScipWriter(baos, "/project")

        writer.visitDocumentStart("src/Bar.groovy", "Foo foo = new Foo()")
        writer.visitReference(
            Range(1, 1, 1, 4),
            "scip-groovy maven com.example 0.0.0 Foo#",
            isDefinition = false,
        )
        writer.visitDocumentEnd()
        writer.close()

        val index = Index.ADAPTER.decode(baos.toByteArray())
        val occ = index.documents[0].occurrences[0]

        // Should have ReadAccess role
        assertTrue(occ.symbol_roles and scip.SymbolRole.ReadAccess.value != 0)
        // Should NOT have Definition role
        assertTrue(occ.symbol_roles and scip.SymbolRole.Definition.value == 0)
    }

    @Test
    fun `empty documents are not emitted`() {
        val baos = ByteArrayOutputStream()
        val writer = ScipWriter(baos, "/project")

        writer.visitDocumentStart("src/Empty.groovy", "")
        writer.visitDocumentEnd()
        writer.close()

        val index = Index.ADAPTER.decode(baos.toByteArray())

        // Empty document should not be added
        assertEquals(0, index.documents.size)
    }
}
