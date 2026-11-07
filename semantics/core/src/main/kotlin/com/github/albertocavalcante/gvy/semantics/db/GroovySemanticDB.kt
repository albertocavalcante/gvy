package com.github.albertocavalcante.gvy.semantics.db

import java.net.URI
import java.util.Collections
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Metals-inspired semantic database for Groovy.
 * Stores per-file semantic information (symbols, occurrences, types).
 *
 * Thread safety: one read-write lock guards the documents and both indexes together, so a writer
 * replaces a document and its index entries atomically and a reader never sees them out of step.
 * Lookups return detached result collections and read-only document lists.
 */
class GroovySemanticDB {
    private val lock = ReentrantReadWriteLock()

    private class StoredDocument(val document: SemanticDocument, val sourceText: String?)

    private val documents = HashMap<URI, StoredDocument>()

    // symbolId -> definitions, grouped by file in the order the files were stored
    private val symbolIndex = SymbolIndex<SymbolInfo>()

    // symbolId -> occurrences, grouped by file
    private val occurrenceIndex = SymbolIndex<SymbolOccurrence>()

    /**
     * Get semantic document for a URI
     */
    fun getDocument(uri: URI): SemanticDocument? = lock.read { documents[uri]?.document }

    /**
     * Update/store semantic document for a URI.
     * Replaces the previous document and its index entries atomically.
     * Use the source-text overload for queries that need precise identifier ranges.
     */
    fun updateDocument(uri: URI, doc: SemanticDocument) = updateDocument(uri, doc, sourceText = null)

    /** Stores source text with the document so readers never combine ranges and text from different versions. */
    fun updateDocument(uri: URI, doc: SemanticDocument, sourceText: String?) {
        val snapshot = doc.copy(
            symbols = Collections.unmodifiableList(doc.symbols.toList()),
            occurrences = Collections.unmodifiableList(doc.occurrences.toList()),
        )
        lock.write {
            documents.put(uri, StoredDocument(snapshot, sourceText))?.let { removeFromIndexes(uri, it.document) }
            addToIndexes(uri, snapshot)
        }
    }

    /**
     * Remove document when file is deleted
     */
    fun removeDocument(uri: URI) {
        lock.write { documents.remove(uri)?.let { removeFromIndexes(uri, it.document) } }
    }

    /**
     * Get all documents
     */
    fun getAllDocuments(): Map<URI, SemanticDocument> = lock.read { documents.mapValues { it.value.document } }

    /**
     * Find symbol definition by ID.
     * Returns the first matching symbol if multiple exist (shouldn't happen in well-formed code).
     */
    fun findSymbolDefinition(symbolId: String): Pair<URI, SymbolInfo>? = lock.read { symbolIndex.first(symbolId) }

    /**
     * Find all definitions of a symbol (handles duplicate symbols across files if they exist)
     */
    fun findAllSymbolDefinitions(symbolId: String): List<Pair<URI, SymbolInfo>> =
        lock.read { symbolIndex.all(symbolId) }

    /**
     * Find all occurrences of a symbol across all files
     */
    fun findAllOccurrences(symbolId: String): List<Pair<URI, SymbolOccurrence>> =
        lock.read { occurrenceIndex.all(symbolId) }

    /** Returns ranges and text from the same stored document, even if it is replaced after this query. */
    fun findOccurrenceSnapshots(symbolId: String): List<IndexedOccurrence> = lock.read {
        occurrenceIndex.all(symbolId).map { (uri, occurrence) ->
            IndexedOccurrence(uri, occurrence, checkNotNull(documents[uri]).sourceText)
        }
    }

    /**
     * Find all symbols in a file by kind
     */
    fun findSymbolsByKind(uri: URI, kind: SymbolKind): List<SymbolInfo> =
        getDocument(uri)?.findSymbolsByKind(kind) ?: emptyList()

    /**
     * Find all occurrences in a file by role
     */
    fun findOccurrencesByRole(uri: URI, role: OccurrenceRole): List<SymbolOccurrence> =
        getDocument(uri)?.findOccurrencesByRole(role) ?: emptyList()

    /**
     * Find symbol at a specific position in a file
     */
    fun findSymbolAtPosition(uri: URI, line: Int, column: Int): SymbolInfo? =
        getDocument(uri)?.symbols?.find { it.range.contains(line, column) }

    /**
     * Find occurrence at a specific position in a file
     */
    fun findOccurrenceAtPosition(uri: URI, line: Int, column: Int): SymbolOccurrence? =
        getDocument(uri)?.occurrences?.find { it.range.contains(line, column) }

    /**
     * Clear all documents
     */
    fun clear() {
        lock.write {
            documents.clear()
            symbolIndex.clear()
            occurrenceIndex.clear()
        }
    }

    /**
     * Get statistics about the database
     */
    fun getStatistics(): SemanticDBStatistics {
        val allDocuments = lock.read { documents.values.map { it.document } }
        return SemanticDBStatistics(
            documentCount = allDocuments.size,
            totalSymbols = allDocuments.sumOf { it.symbols.size },
            totalOccurrences = allDocuments.sumOf { it.occurrences.size },
            symbolsByKind = SymbolKind.entries.associateWith { kind ->
                allDocuments.sumOf { doc -> doc.findSymbolsByKind(kind).size }
            },
            occurrencesByRole = OccurrenceRole.entries.associateWith { role ->
                allDocuments.sumOf { doc -> doc.findOccurrencesByRole(role).size }
            },
        )
    }

    // Callers hold the write lock.
    private fun addToIndexes(uri: URI, doc: SemanticDocument) {
        doc.symbols.groupBy { it.symbol }.forEach { (id, symbols) -> symbolIndex.put(id, uri, symbols) }
        doc.occurrences.groupBy { it.symbol }.forEach { (id, occurrences) -> occurrenceIndex.put(id, uri, occurrences) }
    }

    // Callers hold the write lock.
    private fun removeFromIndexes(uri: URI, doc: SemanticDocument) {
        doc.symbols.forEach { symbolIndex.remove(it.symbol, uri) }
        doc.occurrences.forEach { occurrenceIndex.remove(it.symbol, uri) }
    }
}

/**
 * symbolId -> (file -> that file's entries). Keeping entries per file makes adding or removing a file
 * cost only that file's entries, however many other files mention the same symbol. Files keep the
 * order they were stored in. Not thread-safe; [GroovySemanticDB] guards it.
 */
private class SymbolIndex<T> {
    private val bySymbol = HashMap<String, LinkedHashMap<URI, List<T>>>()

    fun put(id: String, uri: URI, entries: List<T>) {
        bySymbol.getOrPut(id) { LinkedHashMap() }[uri] = entries
    }

    fun remove(id: String, uri: URI) {
        val files = bySymbol[id] ?: return
        files.remove(uri)
        if (files.isEmpty()) bySymbol.remove(id)
    }

    fun first(id: String): Pair<URI, T>? = bySymbol[id]?.entries?.firstOrNull()?.let { (uri, entries) ->
        uri to entries.first()
    }

    fun all(id: String): List<Pair<URI, T>> =
        bySymbol[id]?.flatMap { (uri, entries) -> entries.map { uri to it } } ?: emptyList()

    fun clear() = bySymbol.clear()
}

/**
 * Statistics about the semantic database
 */
data class SemanticDBStatistics(
    val documentCount: Int,
    val totalSymbols: Int,
    val totalOccurrences: Int,
    val symbolsByKind: Map<SymbolKind, Int>,
    val occurrencesByRole: Map<OccurrenceRole, Int>,
)
