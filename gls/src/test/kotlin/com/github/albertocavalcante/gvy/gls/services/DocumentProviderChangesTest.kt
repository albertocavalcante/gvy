package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.source.DocumentChange
import com.github.albertocavalcante.gvy.source.DocumentChangeRejectionReason
import com.github.albertocavalcante.gvy.source.RawUtf16Position
import com.github.albertocavalcante.gvy.source.RawUtf16Range
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentProviderChangesTest {
    private val uri = URI.create("file:///workspace/Changes.groovy")
    private val documents = DocumentProvider()

    @Test
    fun `a valid batch installs its ordered final text and version in one new snapshot`() {
        val base = open("abc", 3)
        val result = documents.applyChanges(uri, base, 10, listOf(insert(1, "X"), insert(1, "Y")))

        val accepted = assertIs<BufferChangeResult.Applied>(result).snapshot
        assertEquals(DocumentSnapshot("aYXbc", 10), accepted)
        assertSame(accepted, documents.getSnapshot(uri))
        assertNotSame(base, accepted)
        assertEquals(DocumentSnapshot("abc", 3), base)
    }

    @Test
    fun `a later invalid event leaves the original snapshot installed without partial text`() {
        val base = open()
        val changes = listOf(DocumentChange.ReplaceAll("new"), invalidLine())

        assertEquals(
            BufferChangeResult.Invalid(1, DocumentChangeRejectionReason.LINE_OUT_OF_BOUNDS),
            documents.applyChanges(uri, base, 4, changes),
        )
        assertSame(base, documents.getSnapshot(uri))
        assertEquals(DocumentSnapshot("abc", 3), base)
    }

    @Test
    fun `empty newer batches retire snapshot publication while retaining the same incarnation`() {
        val base = open()
        val incarnation = assertNotNull(documents.getIncarnation(uri))
        val result = documents.applyChanges(uri, base, 4, emptyList())
        val accepted = assertIs<BufferChangeResult.Applied>(result).snapshot
        assertEquals(DocumentSnapshot("abc", 4), accepted)
        assertNotSame(base, accepted)
        assertSame(incarnation, documents.getIncarnation(uri))

        var publications = 0
        assertFalse(documents.withCurrentSnapshot(uri, base) { publications++ })
        assertTrue(documents.withCurrentSnapshot(uri, accepted) { publications++ })
        assertEquals(1, publications)
    }

    @Test
    fun `duplicate and older versions are stale on a current base`() {
        val base = open()
        for (version in listOf(Int.MIN_VALUE, 2, 3)) {
            assertEquals(BufferChangeResult.Stale, documents.applyChanges(uri, base, version, listOf(insert(0, "!"))))
            assertSame(base, documents.getSnapshot(uri))
        }
    }

    @Test
    fun `version gaps and signed extremes are compared without overflow`() {
        val base = open("abc", Int.MIN_VALUE)
        val result = documents.applyChanges(uri, base, Int.MAX_VALUE, listOf(insert(3, "!")))
        val accepted = assertIs<BufferChangeResult.Applied>(result).snapshot
        assertEquals(DocumentSnapshot("abc!", Int.MAX_VALUE), accepted)
        assertEquals(
            BufferChangeResult.Stale,
            documents.applyChanges(uri, accepted, Int.MIN_VALUE, emptyList()),
        )
        assertSame(accepted, documents.getSnapshot(uri))
    }

    @Test
    fun `an unversioned buffer can adopt any signed client version`() {
        documents.put(uri, "internal")
        val base = assertNotNull(documents.getSnapshot(uri))
        val result = documents.applyChanges(uri, base, Int.MIN_VALUE, listOf(DocumentChange.ReplaceAll("client")))
        assertEquals(DocumentSnapshot("client", Int.MIN_VALUE), assertIs<BufferChangeResult.Applied>(result).snapshot)
    }

    @Test
    fun `an equal snapshot or another documents snapshot cannot authorize a transition`() {
        val base = open()
        val other = URI.create("file:///workspace/Other.groovy")
        documents.put(other, base.content, assertNotNull(base.version))
        val impostors = listOf(base.copy(), assertNotNull(documents.getSnapshot(other)))
        for (impostor in impostors) {
            assertEquals(BufferChangeResult.RetiredBase, documents.applyChanges(uri, impostor, 4, emptyList()))
            assertSame(base, documents.getSnapshot(uri))
        }
    }

    @Test
    fun `closed and identical reopened buffers reject the retired base`() {
        val base = open()
        val oldIncarnation = assertNotNull(documents.getIncarnation(uri))
        documents.remove(uri)
        assertEquals(BufferChangeResult.RetiredBase, documents.applyChanges(uri, base, 4, emptyList()))
        assertNull(documents.getSnapshot(uri))

        documents.put(uri, base.content, assertNotNull(base.version))
        val reopened = assertNotNull(documents.getSnapshot(uri))
        assertEquals(base, reopened)
        assertEquals(BufferChangeResult.RetiredBase, documents.applyChanges(uri, base, 4, emptyList()))
        assertSame(reopened, documents.getSnapshot(uri))
        assertNotSame(oldIncarnation, documents.getIncarnation(uri))
        var cleared = false
        assertFalse(documents.removeIfCurrent(uri, oldIncarnation) { cleared = true })
        assertFalse(cleared)
        assertSame(reopened, documents.getSnapshot(uri))
    }

    @Test
    fun `the original incarnation can still close a successfully edited buffer`() {
        val base = open()
        val incarnation = assertNotNull(documents.getIncarnation(uri))
        assertIs<BufferChangeResult.Applied>(documents.applyChanges(uri, base, 4, listOf(insert(0, "!"))))
        assertSame(incarnation, documents.getIncarnation(uri))
        var cleared = false
        assertTrue(documents.removeIfCurrent(uri, incarnation) { cleared = true })
        assertTrue(cleared)
        assertNull(documents.getSnapshot(uri))
    }

    @Test
    fun `partially prepared text never becomes an observable buffer state`() {
        val base = open("ab", 3)
        val result = withPausedBatch(base, listOf(insert(1, "X"), insert(1, "Y")), pauseIndex = 1) {
            assertSame(base, documents.getSnapshot(uri))
            assertEquals("ab", documents.get(uri))
            assertEquals(mapOf(uri to "ab"), documents.snapshot())
        }
        assertEquals(DocumentSnapshot("aYXb", 4), assertIs<BufferChangeResult.Applied>(result).snapshot)
    }

    @Test
    fun `a concurrent full replacement finishes during preparation and retires the batch`() {
        val base = open()
        var winner: DocumentSnapshot? = null
        val result = withPausedBatch(base, listOf(insert(0, "discarded"))) {
            winner = assertNotNull(documents.update(uri, "winner", 5))
        }
        assertEquals(BufferChangeResult.RetiredBase, result)
        assertEquals(DocumentSnapshot("winner", 5), winner)
        assertSame(winner, documents.getSnapshot(uri))
    }

    @Test
    fun `a version-only concurrent update also retires prepared edits against the same text`() {
        val base = open()
        val result = withPausedBatch(base, listOf(insert(0, "discarded")), version = 5) {
            assertEquals(DocumentSnapshot("abc", 4), documents.update(uri, null, 4))
        }
        assertEquals(BufferChangeResult.RetiredBase, result)
        assertEquals(DocumentSnapshot("abc", 4), documents.getSnapshot(uri))
    }

    @Test
    fun `closing during preparation cannot recreate a buffer`() {
        val base = open()
        val result = withPausedBatch(base, listOf(insert(0, "discarded"))) { documents.remove(uri) }
        assertEquals(BufferChangeResult.RetiredBase, result)
        assertNull(documents.getSnapshot(uri))
    }

    @Test
    fun `identical close and reopen during preparation preserves the new lifecycle`() {
        val base = open()
        val incarnation = assertNotNull(documents.getIncarnation(uri))
        val result = withPausedBatch(base, listOf(insert(0, "discarded"))) {
            documents.remove(uri)
            documents.put(uri, base.content, assertNotNull(base.version))
        }
        assertEquals(BufferChangeResult.RetiredBase, result)
        assertEquals(base, documents.getSnapshot(uri))
        assertNotSame(base, documents.getSnapshot(uri))
        assertNotSame(incarnation, documents.getIncarnation(uri))
    }

    @Test
    fun `retirement takes precedence over an invalid result prepared for old text`() {
        val base = open()
        val result = withPausedBatch(base, listOf(invalidLine())) {
            assertNotNull(documents.update(uri, "winner", 5))
        }
        assertEquals(BufferChangeResult.RetiredBase, result)
        assertEquals(DocumentSnapshot("winner", 5), documents.getSnapshot(uri))
    }

    @Test
    fun `two batches naming one base have one winner in either forced commit order`() {
        for (winningVersion in listOf(4, 5)) {
            val base = open("ab", 3)
            val losingVersion = if (winningVersion == 4) 5 else 4
            var winner: DocumentSnapshot? = null
            val loser = withPausedBatch(base, listOf(insert(0, "loser")), version = losingVersion) {
                val result = documents.applyChanges(uri, base, winningVersion, listOf(insert(0, "winner")))
                winner = assertIs<BufferChangeResult.Applied>(result).snapshot
            }
            assertEquals(BufferChangeResult.RetiredBase, loser, "winning version=$winningVersion")
            assertEquals(DocumentSnapshot("winnerab", winningVersion), winner)
            assertSame(winner, documents.getSnapshot(uri))
        }
    }

    @Test
    fun `retired base identity takes precedence over duplicate and older versions`() {
        val base = open()
        for (version in listOf(3, 2, Int.MIN_VALUE)) {
            assertEquals(
                BufferChangeResult.RetiredBase,
                documents.applyChanges(uri, base.copy(), version, emptyList()),
            )
        }
        assertNotNull(documents.update(uri, "newer", 5))
        assertEquals(BufferChangeResult.RetiredBase, documents.applyChanges(uri, base, 3, emptyList()))
        assertEquals(DocumentSnapshot("newer", 5), documents.getSnapshot(uri))
    }

    @Test
    fun `retired and stale requests return before inspecting the change list`() {
        val base = open()
        val changes = UnreadChanges()
        assertEquals(BufferChangeResult.RetiredBase, documents.applyChanges(uri, base.copy(), 3, changes))
        assertEquals(BufferChangeResult.Stale, documents.applyChanges(uri, base, 3, changes))
        assertEquals(BufferChangeResult.Stale, documents.applyChanges(uri, base, Int.MIN_VALUE, changes))
        assertSame(base, documents.getSnapshot(uri))

        documents.remove(uri)
        assertEquals(BufferChangeResult.RetiredBase, documents.applyChanges(uri, base, 4, changes))
        assertNull(documents.getSnapshot(uri))
    }

    private fun open(content: String = "abc", version: Int = 3): DocumentSnapshot {
        documents.put(uri, content, version)
        return assertNotNull(documents.getSnapshot(uri))
    }

    private fun insert(character: Int, text: String): DocumentChange.Replace = DocumentChange.Replace(
        RawUtf16Range(RawUtf16Position(0, character), RawUtf16Position(0, character)),
        text,
    )

    private fun invalidLine(): DocumentChange.Replace = DocumentChange.Replace(
        RawUtf16Range(RawUtf16Position(1, 0), RawUtf16Position(1, 0)),
        "invalid",
    )

    private fun withPausedBatch(
        base: DocumentSnapshot,
        changes: List<DocumentChange>,
        pauseIndex: Int = 0,
        version: Int = 4,
        whilePaused: () -> Unit,
    ): BufferChangeResult {
        val paused = PausedChanges(changes, pauseIndex)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val batch = executor.submit(Callable { documents.applyChanges(uri, base, version, paused) })
            assertTrue(paused.entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "batch never reached preparation")
            // Completion before release proves that preparation does not hold the lifecycle lock.
            executor.submit(Callable { whilePaused() }).get(WAIT_SECONDS, TimeUnit.SECONDS)
            paused.release.countDown()
            return batch.get(WAIT_SECONDS, TimeUnit.SECONDS)
        } finally {
            paused.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "owned executor did not stop")
        }
    }

    private class PausedChanges(private val changes: List<DocumentChange>, private val pauseIndex: Int) :
        AbstractList<DocumentChange>() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override val size: Int get() = changes.size

        override fun get(index: Int): DocumentChange {
            if (index == pauseIndex) {
                entered.countDown()
                check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "preparation was never released" }
            }
            return changes[index]
        }
    }

    private class UnreadChanges : AbstractList<DocumentChange>() {
        override val size: Int get() = error("Preflight rejection must not inspect change count")

        override fun get(index: Int): DocumentChange = error("Preflight rejection must not inspect event $index")
    }

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
