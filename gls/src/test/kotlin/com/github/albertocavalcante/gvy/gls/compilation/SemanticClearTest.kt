package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.nativeapi.ParseRequest
import io.mockk.spyk
import io.mockk.verify
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A global clear drops the semantic index in O(1), without per-document removals under its locks (#1342). */
class SemanticClearTest {
    private val db = spyk(GroovySemanticDB())
    private val index = WorkspaceSymbolIndex(db)
    private val parser = GroovyParserFacade()
    private val indexer = SemanticIndexer(index)

    private fun uri(i: Int) = URI.create("file:///Cleared$i.groovy")

    private fun parse(uri: URI, content: String): ModuleNode =
        checkNotNull(parser.parse(ParseRequest(uri = uri, content = content, compilePhase = Phases.CONVERSION)).ast)

    private fun index(i: Int) {
        val content = "class Cleared$i {}"
        indexer.update(uri(i), content, parse(uri(i), content), phase = Phases.CANONICALIZATION)
    }

    @Test
    fun `clearing the semantic index removes no document one by one`() {
        repeat(DOCUMENTS) { index(it) }
        assertNotNull(index.getDocument(uri(0)))

        indexer.clear()

        verify(exactly = 0) { db.removeDocument(any()) }
        repeat(DOCUMENTS) { assertNull(index.getDocument(uri(it)), "document $it survived the clear") }
        assertEquals(0, db.getAllDocuments().size)
    }

    @Test
    fun `a publication begun before the clear is refused after it`() {
        val content = "class Stale {}"
        val publication = indexer.begin(uri(0))

        indexer.clear()
        publication.use { indexer.publish(it, content, parse(uri(0), content), Phases.CANONICALIZATION) }

        assertNull(index.getDocument(uri(0)), "a pre-clear publication must not repopulate the new index")
    }

    @Test
    fun `documents published after the clear are indexed normally`() {
        index(0)
        indexer.clear()

        index(1)

        assertNull(index.getDocument(uri(0)))
        assertNotNull(index.getDocument(uri(1)))
    }

    private companion object {
        const val DOCUMENTS = 50
    }
}
