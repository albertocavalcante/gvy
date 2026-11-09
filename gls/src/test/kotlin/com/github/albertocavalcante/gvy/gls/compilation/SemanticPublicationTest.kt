package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SemanticPublicationTest {
    private val uri = URI.create("file:///Example.groovy")
    private val other = URI.create("file:///Other.groovy")
    private val index = WorkspaceSymbolIndex(GroovySemanticDB())
    private val indexer = SemanticIndexer(index)
    private val content = "class Example {}"
    private val ast = checkNotNull(GroovyParserFacade().parse(ParseRequest(uri = uri, content = content)).ast)

    @Test
    fun `deleted document rejects its outstanding publication`() {
        indexer.begin(uri).use { old ->
            indexer.remove(uri)
            indexer.publish(old, content, ast, Phases.CANONICALIZATION)
        }

        assertNull(index.getDocument(uri))
    }

    @Test
    fun `recreated URI accepts new publication and rejects old identity and cleanup`() {
        val old = indexer.begin(uri)
        indexer.remove(uri)
        indexer.begin(uri).use { fresh ->
            indexer.publish(old, "obsolete", ast, Phases.CANONICALIZATION)
            assertNull(index.getDocument(uri), "a still-open token from the deleted lifecycle is invalid")
            old.close()
            indexer.publish(fresh, content, ast, Phases.CANONICALIZATION)
        }

        assertNotNull(index.getDocument(uri))
        assertTrue(indexer.isCurrent(uri, content))
    }

    @Test
    fun `clear invalidates pending publications for every registered URI`() {
        indexer.begin(uri).use { first ->
            indexer.begin(other).use { second ->
                indexer.clear()
                indexer.publish(first, content, ast, Phases.CANONICALIZATION)
                indexer.publish(second, content, ast, Phases.CANONICALIZATION)
            }
        }

        assertNull(index.getDocument(uri))
        assertNull(index.getDocument(other))
    }

    @Test
    fun `unrelated document removal does not invalidate a publication`() {
        indexer.begin(uri).use { publication ->
            indexer.remove(other)
            indexer.publish(publication, content, ast, Phases.CANONICALIZATION)
        }

        assertNotNull(index.getDocument(uri))
    }

    @Test
    fun `failed overlapping compile cleanup preserves the surviving publication`() {
        val failed = indexer.begin(uri)
        indexer.begin(uri).use { surviving ->
            assertSame(failed.identity, surviving.identity)
            failed.close()
            failed.close()
            indexer.publish(surviving, content, ast, Phases.CANONICALIZATION)
        }

        assertNotNull(index.getDocument(uri))
    }

    @Test
    fun `all failed publications release their empty registry state`() {
        val failed = indexer.begin(uri)
        assertFalse(uri in indexer, "pending compilation is not an indexed document")
        failed.close()

        indexer.begin(uri).use { fresh ->
            assertNotSame(failed.identity, fresh.identity)
            indexer.publish(failed, content, ast, Phases.CANONICALIZATION)
            assertNull(index.getDocument(uri), "closed token cannot publish into a fresh lifecycle")
        }
    }

    @Test
    fun `retiring publications keeps the document but rejects work that read the old text`() {
        indexer.update(uri, content, ast, Phases.CANONICALIZATION)
        val stored = index.getDocument(uri)
        indexer.begin(uri).use { beforeClose ->
            indexer.retirePublications(uri)
            indexer.publish(beforeClose, "class Example { def unsaved() {} }", ast, Phases.SEMANTIC_ANALYSIS)
        }

        assertSame(stored, index.getDocument(uri), "a publication begun before the close landed after it")
        assertTrue(indexer.isCurrent(uri, content))
        indexer.begin(uri).use { afterClose ->
            indexer.publish(afterClose, "class Example { def fresh() {} }", ast, Phases.CANONICALIZATION)
        }
        assertTrue(indexer.isCurrent(uri, "class Example { def fresh() {} }"), "later work still publishes")
    }
}
