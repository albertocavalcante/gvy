package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The replacement rules: newer content wins, a degraded parse never displaces a resolved one of the same content. */
class SemanticIndexerTest {
    private val uri = URI.create("file:///Guarded.groovy")
    private val index = WorkspaceSymbolIndex(GroovySemanticDB())
    private val parser = GroovyParserFacade()
    private var open = false
    private val indexer = SemanticIndexer(index) { open }

    private fun parse(content: String): ModuleNode =
        checkNotNull(parser.parse(ParseRequest(uri = uri, content = content, compilePhase = Phases.CONVERSION)).ast)

    private fun names() = index.getDocument(uri)?.symbols?.map { it.name }.orEmpty()

    @Test
    fun `a degraded parse of identical content keeps the resolved document`() {
        val content = "class Guarded { def kept() {} }"
        val resolved = parse(content)
        indexer.update(uri, content, resolved, typesUnresolved = false)
        val before = assertNotNull(index.getDocument(uri))

        indexer.update(uri, content, parse(content), typesUnresolved = true)

        assertEquals(before, index.getDocument(uri))
    }

    @Test
    fun `a degraded parse of changed content replaces the document`() {
        indexer.update(uri, "class Guarded { def old() {} }", parse("class Guarded { def old() {} }"), false)

        val changed = "class Guarded { def fresh() {} }"
        indexer.update(uri, changed, parse(changed), typesUnresolved = true)

        assertEquals(true, "fresh" in names() && "old" !in names(), "names: ${names()}")
    }

    @Test
    fun `a resolved parse upgrades a degraded document of the same content`() {
        val content = "class Guarded { def x() {} }"
        indexer.update(uri, content, parse(content), typesUnresolved = true)
        val degraded = index.getDocument(uri)

        indexer.update(uri, content, parse(content), typesUnresolved = false)

        assertNotNull(index.getDocument(uri))
        assertEquals(false, degraded === index.getDocument(uri), "document was rebuilt")
    }

    @Test
    fun `a disk document never replaces an open buffer's`() {
        val buffer = "class Guarded { def buffered() {} }"
        indexer.update(uri, buffer, parse(buffer), typesUnresolved = false)
        open = true

        val disk = "class Guarded { def onDisk() {} }"
        indexer.update(uri, disk, parse(disk), typesUnresolved = false, fromBuffer = false)

        assertEquals(true, "buffered" in names() && "onDisk" !in names(), "names: ${names()}")
    }

    @Test
    fun `remove and clear drop entries`() {
        val content = "class Guarded {}"
        indexer.update(uri, content, parse(content), typesUnresolved = false)
        assertNotNull(index.getDocument(uri))
        indexer.remove(uri)
        assertNull(index.getDocument(uri))

        indexer.update(uri, content, parse(content), typesUnresolved = false)
        assertNotNull(index.getDocument(uri))
        indexer.clear()
        assertNull(index.getDocument(uri))
    }
}
