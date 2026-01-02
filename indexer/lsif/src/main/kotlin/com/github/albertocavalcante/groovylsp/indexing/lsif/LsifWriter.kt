package com.github.albertocavalcante.groovylsp.indexing.lsif

import com.github.albertocavalcante.groovylsp.indexing.IndexWriter
import com.github.albertocavalcante.groovylsp.indexing.Range
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger

class LsifWriter(outputStream: OutputStream, private val projectRoot: String) : IndexWriter {
    private val writer = OutputStreamWriter(outputStream)
    private val json = Json { encodeDefaults = true }
    private val idCounter = AtomicInteger(1)

    private fun nextId(): Int = idCounter.getAndIncrement()

    private fun emit(element: kotlinx.serialization.json.JsonObject) {
        writer.write(element.toString())
        writer.write("\n")
    }

    private fun emitVertex(label: String, builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): Int {
        val id = nextId()
        val vertex = buildJsonObject {
            put("id", id)
            put("type", "vertex")
            put("label", label)
            builder()
        }
        emit(vertex)
        return id
    }

    private fun emitEdge(label: String, outV: Int, inV: Int) {
        val id = nextId()
        val edge = buildJsonObject {
            put("id", id)
            put("type", "edge")
            put("label", label)
            put("outV", outV)
            put("inV", inV)
        }
        emit(edge)
    }

    init {
        // Emit MetaData
        val rootUri = java.io.File(projectRoot).toURI().toString()
        emitVertex("metaData") {
            put("version", "0.4.3")
            put("projectRoot", rootUri)
            put("positionEncoding", "utf-16")
            putJsonObject("toolInfo") {
                put("name", "groovy-lsp")
                put("version", "0.0.1")
            }
        }
    }

    private var currentDocumentId: Int? = null

    override fun visitDocumentStart(path: String, content: String) {
        val fileUri = java.io.File(projectRoot, path).toURI().toString()
        // Note: we intentionally do not set a "contents" field here. If desired, the file contents
        // could be embedded (for example, as Base64-encoded text) in the LSIF index, but this
        // implementation omits them to keep the index smaller and avoid duplicating source files.
        val docId = emitVertex("document") {
            put("uri", fileUri)
            put("languageId", "groovy")
        }
        currentDocumentId = docId
    }

    override fun visitDocumentEnd() {
        currentDocumentId = null
    }

    override fun visitDefinition(range: Range, symbol: String, isLocal: Boolean, documentation: String?) {
        val docId = currentDocumentId ?: return

        // Range Vertex
        val rangeId = emitVertex("range") {
            putJsonObject("start") {
                put("line", range.startLine - 1)
                put("character", range.startCol - 1)
            }
            putJsonObject("end") {
                put("line", range.endLine - 1)
                put("character", range.endCol - 1)
            }
        }

        // contains edge from doc to range
        emitEdge("contains", docId, rangeId)

        // Result Set
        val resultSetId = emitVertex("resultSet")
        emitEdge("next", rangeId, resultSetId)

        // Moniker
        val monikerId = emitVertex("moniker") {
            put("scheme", "scip-groovy")
            put("identifier", symbol)
            put("kind", if (isLocal) "local" else "export")
        }
        emitEdge("moniker", resultSetId, monikerId)

        // Hover
        if (documentation != null) {
            val hoverId = emitVertex("hoverResult") {
                putJsonObject("result") {
                    putJsonObject("contents") {
                        put("kind", "markdown")
                        put("value", documentation)
                    }
                }
            }
            emitEdge("textDocument/hover", resultSetId, hoverId)
        }
    }

    override fun visitReference(range: Range, symbol: String, isDefinition: Boolean) {
        val docId = currentDocumentId ?: return

        val rangeId = emitVertex("range") {
            putJsonObject("start") {
                put("line", range.startLine - 1)
                put("character", range.startCol - 1)
            }
            putJsonObject("end") {
                put("line", range.endLine - 1)
                put("character", range.endCol - 1)
            }
        }
        emitEdge("contains", docId, rangeId)

        val resultSetId = emitVertex("resultSet")
        emitEdge("next", rangeId, resultSetId)

        val monikerId = emitVertex("moniker") {
            put("scheme", "scip-groovy")
            put("identifier", symbol)
            put("kind", "import")
        }
        emitEdge("moniker", resultSetId, monikerId)
    }

    override fun close() {
        writer.flush()
        writer.close()
    }
}
