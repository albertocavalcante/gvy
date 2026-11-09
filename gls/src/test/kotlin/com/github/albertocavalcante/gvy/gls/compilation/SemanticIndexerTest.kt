package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The replacement rules: newer content wins, a degraded parse never displaces a resolved one of the same content. */
class SemanticIndexerTest {
    private val uri = URI.create("file:///Guarded.groovy")
    private val index = WorkspaceSymbolIndex(GroovySemanticDB())
    private val parser = GroovyParserFacade()
    private var open = false
    private val indexer = SemanticIndexer(index, isOpen = { open })

    private fun parse(content: String): ModuleNode =
        checkNotNull(parser.parse(ParseRequest(uri = uri, content = content, compilePhase = Phases.CONVERSION)).ast)

    private fun names() = index.getDocument(uri)?.symbols?.map { it.name }.orEmpty()

    @Test
    fun `a document that fails to build for new content drops the stale one`() {
        var fail = false
        val failing = SemanticIndexer(index, build = { ast, u ->
            check(!fail) { "builder bug" }
            com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder(ast, u).build()
        })
        failing.update(uri, "class Old {}", parse("class Old {}"), phase = Phases.CANONICALIZATION)
        assertNotNull(index.getDocument(uri))

        fail = true
        failing.update(uri, "class New {}", parse("class New {}"), phase = Phases.CANONICALIZATION)

        assertNull(index.getDocument(uri))
    }

    @Test
    fun `a degraded parse of identical content keeps the resolved document`() {
        val content = "class Guarded { def kept() {} }"
        val resolved = parse(content)
        indexer.update(uri, content, resolved, phase = Phases.CANONICALIZATION)
        val before = assertNotNull(index.getDocument(uri))

        indexer.update(uri, content, parse(content), phase = Phases.CONVERSION)

        assertEquals(before, index.getDocument(uri))
    }

    @Test
    fun `a degraded parse of changed content replaces the document`() {
        indexer.update(
            uri,
            "class Guarded { def old() {} }",
            parse("class Guarded { def old() {} }"),
            Phases.CANONICALIZATION,
        )

        val changed = "class Guarded { def fresh() {} }"
        indexer.update(uri, changed, parse(changed), phase = Phases.CONVERSION)

        assertEquals(true, "fresh" in names() && "old" !in names(), "names: ${names()}")
    }

    @Test
    fun `a resolved parse upgrades a degraded document of the same content`() {
        val content = "class Guarded { def x() {} }"
        indexer.update(uri, content, parse(content), phase = Phases.CONVERSION)
        val degraded = index.getDocument(uri)

        indexer.update(uri, content, parse(content), phase = Phases.CANONICALIZATION)

        assertNotNull(index.getDocument(uri))
        assertEquals(false, degraded === index.getDocument(uri), "document was rebuilt")
    }

    @Test
    fun `a disk document never replaces an open buffer's`() {
        val buffer = "class Guarded { def buffered() {} }"
        indexer.update(uri, buffer, parse(buffer), phase = Phases.CANONICALIZATION)
        open = true

        val disk = "class Guarded { def onDisk() {} }"
        indexer.update(uri, disk, parse(disk), phase = Phases.CANONICALIZATION, fromBuffer = false)

        assertEquals(true, "buffered" in names() && "onDisk" !in names(), "names: ${names()}")
    }

    @Test
    fun `remove and clear drop entries`() {
        val content = "class Guarded {}"
        indexer.update(uri, content, parse(content), phase = Phases.CANONICALIZATION)
        assertNotNull(index.getDocument(uri))
        indexer.remove(uri)
        assertNull(index.getDocument(uri))

        indexer.update(uri, content, parse(content), phase = Phases.CANONICALIZATION)
        assertNotNull(index.getDocument(uri))
        indexer.clear()
        assertNull(index.getDocument(uri))
    }

    @Test
    fun `a later phase of the same content wins whichever compile lands first`() {
        val content = "class Phased { def m() {} }"
        indexer.update(uri, content, parse(content), phase = Phases.SEMANTIC_ANALYSIS)
        val semantic = assertNotNull(index.getDocument(uri))
        indexer.update(uri, content, parse(content), phase = Phases.CANONICALIZATION)
        val canonical = assertNotNull(index.getDocument(uri))
        assertTrue(canonical !== semantic, "the later phase replaces the earlier one")

        indexer.update(uri, content, parse(content), phase = Phases.SEMANTIC_ANALYSIS)

        assertTrue(index.getDocument(uri) === canonical, "an earlier phase never replaces a later one")
    }

    @Test
    fun `building a document holds no lock, so removing the file meanwhile neither waits nor is undone`() {
        val building = CountDownLatch(1)
        val removed = CountDownLatch(1)
        val slow = SemanticIndexer(index, build = { ast, u ->
            building.countDown()
            removed.await(LATCH_SECONDS, TimeUnit.SECONDS)
            com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder(ast, u).build()
        })
        val content = "class Slow {}"
        val ast = parse(content)
        val update = CompletableFuture.runAsync {
            slow.update(uri, content, ast, phase = Phases.CANONICALIZATION)
        }
        assertTrue(building.await(LATCH_SECONDS, TimeUnit.SECONDS))

        val remove = CompletableFuture.runAsync { slow.remove(uri) }
        remove.get(REMOVE_SECONDS, TimeUnit.SECONDS) // waited on the build's lock before
        removed.countDown()
        update.get(LATCH_SECONDS, TimeUnit.SECONDS)

        assertNull(index.getDocument(uri), "a build that outlived the file's removal must not publish")
    }

    @Test
    fun `a publication taken earlier never overwrites one taken later, whichever finishes first`() {
        val older = indexer.begin(uri)
        val newer = indexer.begin(uri)
        newer.use { indexer.publish(it, "class Newer {}", parse("class Newer {}"), Phases.CANONICALIZATION) }

        older.use { indexer.publish(it, "class Older {}", parse("class Older {}"), Phases.CANONICALIZATION) }

        assertEquals(listOf("Newer"), names().filter { it == "Newer" || it == "Older" })
    }

    private companion object {
        const val LATCH_SECONDS = 10L
        const val REMOVE_SECONDS = 3L
    }
}
