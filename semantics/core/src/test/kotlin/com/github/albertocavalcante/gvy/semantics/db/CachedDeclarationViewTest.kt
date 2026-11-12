package com.github.albertocavalcante.gvy.semantics.db

import com.github.albertocavalcante.gvy.semantics.TypeConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CachedDeclarationViewTest {
    private val uri = URI.create("file:///C.groovy")

    private fun document(line: Int, names: List<String> = listOf("a", "b")) = SemanticDocument(
        uri,
        names.map { name ->
            SymbolInfo("C#$name.", SymbolKind.FIELD, Range(line, 0, line, 1), name, "C#", TypeConstants.INT)
        },
        emptyList(),
    )

    @Test
    fun `unchanged publication view is reused and every update retires it`() {
        val db = GroovySemanticDB()
        db.updateDocument(uri, document(1), "first")
        val before = db.snapshotDeclarations()
        assertSame(before, db.snapshotDeclarations())
        val version = db.version
        db.updateDocument(uri, document(3, listOf("b", "a")), "changed body")
        val after = db.snapshotDeclarations()
        assertNotSame(before, after)
        assertEquals(version, after.version)
        assertSame(after, db.snapshotDeclarations())
        assertEquals(listOf("a", "b"), before.findMembers("C#").map { it.second.name })
        assertEquals(listOf("b", "a"), after.findMembers("C#").map { it.second.name })
        assertEquals(1, before.findMembers("C#").first().second.range.startLine)
        assertEquals(3, after.findMembers("C#").first().second.range.startLine)
    }

    @Test
    fun `concurrent captures publish one coherent cached view`() {
        val db = GroovySemanticDB()
        db.updateDocument(uri, document(1))
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val readers = (1..8).map {
                pool.submit<DeclarationIndexSnapshot> {
                    check(start.await(10, TimeUnit.SECONDS))
                    db.snapshotDeclarations()
                }
            }
            start.countDown()
            val views = readers.map { it.get(10, TimeUnit.SECONDS) }
            views.forEach { view ->
                assertSame(views.first(), view)
                assertEquals(listOf("a", "b"), view.findMembers("C#").map { it.second.name })
            }
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `remove and clear replace the published view`() {
        val db = GroovySemanticDB()
        db.updateDocument(uri, document(1))
        val present = db.snapshotDeclarations()
        db.removeDocument(uri)
        val removed = db.snapshotDeclarations()
        assertNotSame(present, removed)
        assertEquals(2, present.findMembers("C#").size)
        assertEquals(0, removed.findMembers("C#").size)
        db.clear()
        assertNotSame(removed, db.snapshotDeclarations())
    }
}
