// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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
import kotlin.test.assertTrue

/**
 * A global clear drops the semantic index in O(1), without per-document removals under its locks (#1342); retiring
 * every publication fences in-flight work in O(1) too, but keeps the documents.
 */
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

    @Test
    fun `retiring every publication keeps every document and removes none`() {
        repeat(DOCUMENTS) { index(it) }

        indexer.retireAllPublications()

        verify(exactly = 0) { db.removeDocument(any()) }
        repeat(DOCUMENTS) { assertNotNull(index.getDocument(uri(it)), "document $it was dropped") }
    }

    @Test
    fun `a publication begun before every publication was retired is refused after it`() {
        index(0)
        val replacement = "class Cleared0 { int stale }"
        val indexed = indexer.begin(uri(0))
        val unindexed = indexer.begin(uri(1))

        indexer.retireAllPublications()
        indexed.use { indexer.publish(it, replacement, parse(uri(0), replacement), Phases.CANONICALIZATION) }
        unindexed.use { indexer.publish(it, "class Late {}", parse(uri(1), "class Late {}"), Phases.CANONICALIZATION) }

        assertTrue(indexer.isCurrent(uri(0), "class Cleared0 {}"), "a retired publication replaced a kept document")
        assertNull(index.getDocument(uri(1)), "a retired publication added a document")
    }

    @Test
    fun `documents published after every publication was retired replace kept ones normally`() {
        index(0)
        indexer.retireAllPublications()

        val replacement = "class Cleared0 { int fresh }"
        indexer.update(uri(0), replacement, parse(uri(0), replacement), phase = Phases.CANONICALIZATION)
        index(1)

        assertTrue(indexer.isCurrent(uri(0), replacement))
        assertNotNull(index.getDocument(uri(1)))
    }

    @Test
    fun `the same text at the same phase replaces a document kept from before every publication was retired`() {
        index(0)
        val kept = assertNotNull(index.getDocument(uri(0)))

        indexer.retireAllPublications()
        index(0)

        assertTrue(index.getDocument(uri(0)) !== kept, "a document built from the old inputs was kept")
    }

    @Test
    fun `the same text at the same phase does not replace a document of the current generation`() {
        index(0)
        val kept = assertNotNull(index.getDocument(uri(0)))

        index(0)

        assertTrue(index.getDocument(uri(0)) === kept, "an equal publication replaced the stored document")
    }

    @Test
    fun `a lower phase never replaces a kept document, even from a newer generation`() {
        index(0)
        val kept = assertNotNull(index.getDocument(uri(0)))

        indexer.retireAllPublications()
        val content = "class Cleared0 {}"
        indexer.update(uri(0), content, parse(uri(0), content), phase = Phases.CONVERSION)

        assertTrue(index.getDocument(uri(0)) === kept, "a degraded parse displaced a resolved document")
        assertEquals(Phases.CANONICALIZATION, indexer.phaseOf(uri(0)))
    }

    private companion object {
        const val DOCUMENTS = 50
    }
}
