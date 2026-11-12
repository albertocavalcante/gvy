package com.github.albertocavalcante.gvy.gls.testing.client

import org.eclipse.lsp4j.TextEdit
import java.net.URI
import java.util.Collections

/** A caller-owned snapshot of an open editor buffer. Versions are observed, not advanced by planning. */
internal data class EditorBufferSnapshot(val uri: URI, val version: Int, val text: String)

/** One simultaneous edit set against an open buffer; null version means no version constraint was supplied. */
internal data class EditorDocumentChange(val uri: URI, val expectedVersion: Int?, val edits: List<TextEdit>)

/**
 * Computes all new buffer texts without mutating the editor or filesystem. Callers must not mutate input maps,
 * changes or nested LSP objects during this call. Ordered resource operations and repeated document changes are
 * intentionally unsupported; no plan is returned if any document or edit is invalid.
 */
internal object EditorWorkspacePlan {
    fun prepare(
        buffers: Map<URI, EditorBufferSnapshot>,
        changes: List<EditorDocumentChange>,
        encoding: EditorPositionEncoding,
    ): Map<URI, String> {
        require(changes.map { it.uri }.distinct().size == changes.size) {
            "Repeated document changes require ordered workspace operations"
        }
        val result = linkedMapOf<URI, String>()
        for (change in changes) {
            val snapshot = requireNotNull(buffers[change.uri]) { "Document ${change.uri} is not open" }
            require(snapshot.uri == change.uri) { "Document snapshot URI does not match its key" }
            require(change.expectedVersion == null || change.expectedVersion == snapshot.version) {
                "Document ${change.uri} version ${snapshot.version} does not match ${change.expectedVersion}"
            }
            result[change.uri] = EditorTextEdits.apply(snapshot.text, change.edits, encoding)
        }
        return Collections.unmodifiableMap(result)
    }
}
