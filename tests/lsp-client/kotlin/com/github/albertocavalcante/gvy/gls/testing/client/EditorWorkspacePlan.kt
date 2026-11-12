package com.github.albertocavalcante.gvy.gls.testing.client

import org.eclipse.lsp4j.TextEdit
import java.net.URI
import java.util.Collections
import java.util.UUID

/** A caller-owned snapshot of an open editor buffer. Versions are observed, not advanced by planning. */
internal data class EditorBufferSnapshot(val uri: URI, val version: Int, val text: String, val incarnation: UUID)

/** One simultaneous edit set against an open buffer; null version means no version constraint was supplied. */
internal data class EditorDocumentChange(val uri: URI, val expectedVersion: Int?, val edits: List<TextEdit>)

/** The source snapshot must still match the live buffer, including its per-open incarnation, before commit. */
internal data class PlannedBufferEdit(val source: EditorBufferSnapshot, val text: String)

/**
 * Computes all new buffer texts without mutating the editor or filesystem. Callers must not mutate input maps,
 * changes or nested LSP objects during this call. Ordered resource operations and repeated document changes are
 * intentionally unsupported; no plan is returned if any document or edit is invalid. A live adapter must
 * revalidate each exact source snapshot and its per-open incarnation under its own transaction lock before commit.
 */
internal object EditorWorkspacePlan {
    fun prepare(
        buffers: Map<URI, EditorBufferSnapshot>,
        changes: List<EditorDocumentChange>,
        encoding: EditorPositionEncoding,
    ): Map<URI, PlannedBufferEdit> {
        require(changes.map { it.uri }.distinct().size == changes.size) {
            "Repeated document changes require ordered workspace operations"
        }
        val result = linkedMapOf<URI, PlannedBufferEdit>()
        for (change in changes) {
            val snapshot = requireNotNull(buffers[change.uri]) { "Document ${change.uri} is not open" }
            require(snapshot.uri == change.uri) { "Document snapshot URI does not match its key" }
            require(change.expectedVersion == null || change.expectedVersion == snapshot.version) {
                "Document ${change.uri} version ${snapshot.version} does not match ${change.expectedVersion}"
            }
            val text = try {
                EditorTextEdits.apply(snapshot.text, change.edits, encoding)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid edits for ${change.uri}", failure)
            }
            result[change.uri] = PlannedBufferEdit(snapshot, text)
        }
        return Collections.unmodifiableMap(result)
    }
}
