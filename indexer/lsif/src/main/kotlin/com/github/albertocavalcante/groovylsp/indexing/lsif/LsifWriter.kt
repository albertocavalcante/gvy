package com.github.albertocavalcante.groovylsp.indexing.lsif

import com.github.albertocavalcante.groovylsp.indexing.IndexWriter
import com.github.albertocavalcante.groovylsp.indexing.Range
import com.google.gson.Gson
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger

class LsifWriter(outputStream: OutputStream, private val projectRoot: String) : IndexWriter {
    private val writer = OutputStreamWriter(outputStream)
    private val gson = Gson()
    private val idCounter = AtomicInteger(1)

    // Maps to track IDs
    private val fileIds = mutableMapOf<String, Int>()

    private fun nextId(): Int = idCounter.getAndIncrement()

    private fun emit(element: Map<String, Any>) {
        gson.toJson(element, writer)
        writer.write("\n")
    }

    private fun emitVertex(label: String, extra: Map<String, Any> = emptyMap()): Int {
        val id = nextId()
        val vertex = mapOf(
            "id" to id,
            "type" to "vertex",
            "label" to label,
        ) + extra
        emit(vertex)
        return id
    }

    private fun emitEdge(label: String, outV: Int, inV: Int) {
        val id = nextId()
        val edge = mapOf(
            "id" to id,
            "type" to "edge",
            "label" to label,
            "outV" to outV,
            "inV" to inV,
        )
        emit(edge)
    }

    init {
        // Emit MetaData
        emitVertex(
            "metaData",
            mapOf(
                "version" to "0.4.3",
                "projectRoot" to "file://$projectRoot",
                "positionEncoding" to "utf-16",
                "toolInfo" to mapOf(
                    "name" to "groovy-lsp",
                    "version" to "0.0.1",
                ),
            ),
        )
    }

    private var currentDocumentId: Int? = null

    override fun visitDocumentStart(path: String, content: String) {
        val docId = emitVertex(
            "document",
            mapOf(
                "uri" to "file://$projectRoot/$path",
                "languageId" to "groovy",
                // "contents" could be set to Base64-encoded file content if we wanted to embed the source in the LSIF index
            ),
        )
        fileIds[path] = docId
        currentDocumentId = docId
    }

    override fun visitDocumentEnd() {
        currentDocumentId = null
    }

    override fun visitDefinition(range: Range, symbol: String, isLocal: Boolean, documentation: String?) {
        val docId = currentDocumentId ?: return

        // Range Vertex
        val rangeId = emitVertex(
            "range",
            mapOf(
                "start" to mapOf("line" to range.startLine - 1, "character" to range.startCol - 1),
                "end" to mapOf("line" to range.endLine - 1, "character" to range.endCol - 1),
            ),
        )

        // contains edge from doc to range
        emitEdge("contains", docId, rangeId)

        // Result Set
        val resultSetId = emitVertex("resultSet")
        emitEdge("next", rangeId, resultSetId)

        // Moniker
        val monikerId = emitVertex(
            "moniker",
            mapOf(
                "scheme" to "scip-java", // Using same scheme
                "identifier" to symbol,
                "kind" to (if (isLocal) "local" else "export"),
            ),
        )
        emitEdge("moniker", resultSetId, monikerId)

        // Hover
        if (documentation != null) {
            val hoverId = emitVertex(
                "hoverResult",
                mapOf(
                    "result" to mapOf(
                        "contents" to mapOf(
                            "kind" to "markdown",
                            "value" to documentation,
                        ),
                    ),
                ),
            )
            emitEdge("textDocument/hover", resultSetId, hoverId)
        }
    }

    override fun visitReference(range: Range, symbol: String, isDefinition: Boolean) {
        // Logic similar to definition but linking to existing result set if possible
        // For a simple single-pass writer without global state, we might just emit monikers for everything
        // and let the consumer resolve them.
        val docId = currentDocumentId ?: return

        val rangeId = emitVertex(
            "range",
            mapOf(
                "start" to mapOf("line" to range.startLine - 1, "character" to range.startCol - 1),
                "end" to mapOf("line" to range.endLine - 1, "character" to range.endCol - 1),
            ),
        )
        emitEdge("contains", docId, rangeId)

        val resultSetId = emitVertex("resultSet")
        emitEdge("next", rangeId, resultSetId)

        val monikerId = emitVertex(
            "moniker",
            mapOf(
                "scheme" to "scip-java",
                "identifier" to symbol,
                "kind" to "import",
            ),
        )
        emitEdge("moniker", resultSetId, monikerId)
    }

    override fun close() {
        writer.flush()
        writer.close()
    }
}
