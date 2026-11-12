package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.testing.client.EditorBufferSnapshot
import com.github.albertocavalcante.gvy.gls.testing.client.EditorDocumentChange
import com.github.albertocavalcante.gvy.gls.testing.client.EditorPositionEncoding
import com.github.albertocavalcante.gvy.gls.testing.client.EditorWorkspacePlan
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.Test
import java.net.URI

class EditorWorkspacePlanTest {
    private val first = URI.create("file:///A.groovy")
    private val second = URI.create("file:///B.groovy")

    @Test
    fun `plans all documents from snapshots without mutating them`() {
        val buffers = mutableMapOf(
            first to EditorBufferSnapshot(first, 3, "one"),
            second to EditorBufferSnapshot(second, 7, "two"),
        )
        val edits = mutableListOf(change(first, 3, "one!"), change(second, null, "two!"))
        val plan = EditorWorkspacePlan.prepare(buffers, edits, EditorPositionEncoding.UTF16)

        assertThat(plan).containsExactlyEntriesOf(mapOf(first to "one!", second to "two!"))
        assertThat(buffers.getValue(first).text).isEqualTo("one")
        assertThat(buffers.getValue(first).version).isEqualTo(3)
        buffers.clear()
        edits.clear()
        assertThat(plan).containsExactlyEntriesOf(mapOf(first to "one!", second to "two!"))
        assertThatThrownBy { (plan as MutableMap<URI, String>)[first] = "changed" }
            .isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `later invalid edit cannot produce a partial plan`() {
        val buffers = mapOf(
            first to EditorBufferSnapshot(first, 1, "one"),
            second to EditorBufferSnapshot(second, 1, "two"),
        )
        val bad = EditorDocumentChange(second, 1, listOf(TextEdit(Range(Position(4, 0), Position(4, 0)), "bad")))

        assertThatThrownBy {
            EditorWorkspacePlan.prepare(buffers, listOf(change(first, 1, "changed"), bad), EditorPositionEncoding.UTF16)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(buffers.getValue(first).text).isEqualTo("one")
        assertThat(buffers.getValue(second).text).isEqualTo("two")
    }

    @Test
    fun `rejects stale versions unknown documents and repeated document changes`() {
        val buffers = mapOf(first to EditorBufferSnapshot(first, 3, "one"))
        val invalid = listOf(
            listOf(change(first, 2, "stale")),
            listOf(change(second, null, "missing")),
            listOf(change(first, 3, "one"), change(first, 3, "again")),
        )
        invalid.forEach { changes ->
            assertThatThrownBy { EditorWorkspacePlan.prepare(buffers, changes, EditorPositionEncoding.UTF16) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun change(uri: URI, version: Int?, replacement: String) = EditorDocumentChange(
        uri, version, listOf(TextEdit(Range(Position(0, 0), Position(0, 999)), replacement)),
    )
}
