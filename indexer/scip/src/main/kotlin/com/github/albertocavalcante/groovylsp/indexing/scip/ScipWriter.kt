package com.github.albertocavalcante.groovylsp.indexing.scip

import com.github.albertocavalcante.groovylsp.indexing.IndexWriter
import com.github.albertocavalcante.groovylsp.indexing.Range
import scip.Document
import scip.Index
import scip.Metadata
import scip.Occurrence
import scip.SymbolInformation
import scip.ToolInfo
import java.io.OutputStream

class ScipWriter(private val outputStream: OutputStream, private val projectRoot: String) : IndexWriter {
    private val documents = mutableListOf<Document>()

    // Simplified state for the current document being visited
    private var currentDocumentPath: String? = null
    private var currentOccurrences = mutableListOf<Occurrence>()
    private var currentSymbols = mutableListOf<SymbolInformation>()

    override fun visitDocumentStart(path: String, content: String) {
        currentDocumentPath = path
        currentOccurrences.clear()
        currentSymbols.clear()
    }

    override fun visitDocumentEnd() {
        val path = currentDocumentPath ?: return
        if (currentOccurrences.isEmpty() && currentSymbols.isEmpty()) return

        val doc = Document(
            relative_path = path,
            language = "groovy",
            occurrences = currentOccurrences.toList(),
            symbols = currentSymbols.toList(),
        )
        documents.add(doc)
    }

    override fun visitDefinition(range: Range, symbol: String, isLocal: Boolean, documentation: String?) {
        val scipRange = listOf(range.startLine - 1, range.startCol - 1, range.endLine - 1, range.endCol - 1)

        currentOccurrences.add(
            Occurrence(
                range = scipRange,
                symbol = symbol,
                symbol_roles = scip.SymbolRole.Definition.value,
            ),
        )

        if (!isLocal) {
            currentSymbols.add(
                SymbolInformation(
                    symbol = symbol,
                    documentation = documentation?.let { listOf(it) } ?: emptyList(),
                ),
            )
        }
    }

    override fun visitReference(range: Range, symbol: String, isDefinition: Boolean) {
        val scipRange = listOf(range.startLine - 1, range.startCol - 1, range.endLine - 1, range.endCol - 1)

        // If it's a reference (usage), we don't set Definition role (unless it's both, handled by isDefinition flag)
        var roles = 0
        if (isDefinition) {
            roles = roles or scip.SymbolRole.Definition.value
        }

        currentOccurrences.add(
            Occurrence(
                range = scipRange,
                symbol = symbol,
                symbol_roles = roles,
            ),
        )
    }

    override fun close() {
        val index = Index(
            metadata = Metadata(
                version = scip.ProtocolVersion.UnspecifiedProtocolVersion,
                tool_info = ToolInfo(name = "groovy-lsp", version = "0.0.1"),
                project_root = "file://$projectRoot",
            ),
            documents = documents,
        )

        // Wire's encode writes the protobuf binary
        outputStream.write(index.encode())
    }
}
