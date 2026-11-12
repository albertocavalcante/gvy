package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.testing.client.BufferIncarnation
import com.github.albertocavalcante.gvy.gls.testing.client.EditorBufferSnapshot
import com.github.albertocavalcante.gvy.gls.testing.client.EditorDocumentChange
import com.github.albertocavalcante.gvy.gls.testing.client.EditorPositionEncoding
import com.github.albertocavalcante.gvy.gls.testing.client.EditorWorkspacePlan
import com.github.albertocavalcante.gvy.gls.testing.client.PlannedBufferEdit
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
    private val firstIncarnation = BufferIncarnation()
    private val secondIncarnation = BufferIncarnation()

    @Test
    fun `plans all documents from snapshots without mutating them`() {
        val buffers = mutableMapOf(
            first to EditorBufferSnapshot(first, 3, "one", firstIncarnation),
            second to EditorBufferSnapshot(second, 7, "two", secondIncarnation),
        )
        val edits = mutableListOf(change(first, 3, "one!"), change(second, null, "two!"))
        val plan = EditorWorkspacePlan.prepare(buffers, edits, EditorPositionEncoding.UTF16)

        assertThat(plan.getValue(first)).isEqualTo(PlannedBufferEdit(buffers.getValue(first), "one!"))
        assertThat(plan.getValue(first).source)
            .isNotEqualTo(buffers.getValue(first).copy(incarnation = BufferIncarnation()))
        assertThat(plan.getValue(second)).isEqualTo(PlannedBufferEdit(buffers.getValue(second), "two!"))
        assertThat(buffers.getValue(first).text).isEqualTo("one")
        assertThat(buffers.getValue(first).version).isEqualTo(3)
        buffers.clear()
        edits.clear()
        assertThat(plan.getValue(first).source.incarnation).isEqualTo(firstIncarnation)
        assertThat(plan.getValue(first).text).isEqualTo("one!")
        assertThat(plan.getValue(second).text).isEqualTo("two!")
        assertThatThrownBy { (plan as MutableMap<URI, PlannedBufferEdit>).clear() }
            .isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `later invalid edit cannot produce a partial plan`() {
        val buffers = mapOf(
            first to EditorBufferSnapshot(first, 1, "one", firstIncarnation),
            second to EditorBufferSnapshot(second, 1, "two", secondIncarnation),
        )
        val bad = EditorDocumentChange(second, 1, listOf(TextEdit(Range(Position(4, 0), Position(4, 0)), "bad")))

        assertThatThrownBy {
            EditorWorkspacePlan.prepare(buffers, listOf(change(first, 1, "changed"), bad), EditorPositionEncoding.UTF16)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(second.toString())
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
        assertThat(buffers.getValue(first).text).isEqualTo("one")
        assertThat(buffers.getValue(second).text).isEqualTo("two")
    }

    @Test
    fun `rejects stale versions unknown documents and repeated document changes`() {
        val buffers = mapOf(first to EditorBufferSnapshot(first, 3, "one", firstIncarnation))
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
        uri,
        version,
        listOf(TextEdit(Range(Position(0, 0), Position(0, 999)), replacement)),
    )
}
