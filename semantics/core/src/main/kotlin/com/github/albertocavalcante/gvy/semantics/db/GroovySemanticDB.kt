package com.github.albertocavalcante.gvy.semantics.db

import java.net.URI
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Metals-inspired semantic database for Groovy.
 * Stores per-file semantic information (symbols, occurrences, types).
 *
 * Thread-safe implementation using ConcurrentHashMap for concurrent LSP operations.
 * Includes LRU cache for frequently accessed symbols to improve performance.
 */
class GroovySemanticDB {
    // Main storage: URI -> SemanticDocument
    private val documents = ConcurrentHashMap<URI, SemanticDocument>()

    // Index for fast symbol lookup: symbolId -> (URI, SymbolInfo)
    private val symbolIndex = ConcurrentHashMap<String, MutableList<Pair<URI, SymbolInfo>>>()

    // Index for fast occurrence lookup: symbolId -> List<(URI, SymbolOccurrence)>
    private val occurrenceIndex = ConcurrentHashMap<String, MutableList<Pair<URI, SymbolOccurrence>>>()

    // LRU cache for frequently accessed symbols (improves repeated lookups)
    private val cacheLock = ReentrantReadWriteLock()
    private val symbolCache = object : LinkedHashMap<String, Pair<URI, SymbolInfo>>(
        CACHE_INITIAL_CAPACITY,
        CACHE_LOAD_FACTOR,
        true, // accessOrder=true for LRU
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<URI, SymbolInfo>>?): Boolean =
            size > MAX_CACHE_SIZE
    }

    // Statistics for cache performance
    private var cacheHits = 0L
    private var cacheMisses = 0L

    /**
     * Get semantic document for a URI
     */
    fun getDocument(uri: URI): SemanticDocument? = documents[uri]

    /**
     * Update/store semantic document for a URI.
     * This rebuilds indexes for the document.
     */
    fun updateDocument(uri: URI, doc: SemanticDocument) {
        // Remove old document indexes if present
        documents[uri]?.let { oldDoc ->
            removeFromIndexes(uri, oldDoc)
        }

        // Store new document
        documents[uri] = doc

        // Build indexes for new document
        buildIndexes(uri, doc)
    }

    /**
     * Remove document when file is deleted
     */
    fun removeDocument(uri: URI) {
        documents.remove(uri)?.let { doc ->
            removeFromIndexes(uri, doc)
        }
    }

    /**
     * Get all documents
     */
    fun getAllDocuments(): Map<URI, SemanticDocument> = documents.toMap()

    /**
     * Find symbol definition by ID.
     * Returns the first matching symbol if multiple exist (shouldn't happen in well-formed code).
     * Uses LRU cache for improved performance on repeated lookups.
     */
    fun findSymbolDefinition(symbolId: String): Pair<URI, SymbolInfo>? {
        // Check cache first
        cacheLock.read {
            symbolCache[symbolId]?.let {
                cacheHits++
                return it
            }
        }

        // Cache miss - look up in index
        cacheMisses++

        val result = symbolIndex[symbolId]?.firstOrNull()

        // Add to cache if found
        if (result != null) {
            cacheLock.write {
                symbolCache[symbolId] = result
            }
        }

        return result
    }

    /**
     * Find all definitions of a symbol (handles duplicate symbols across files if they exist)
     */
    fun findAllSymbolDefinitions(symbolId: String): List<Pair<URI, SymbolInfo>> =
        symbolIndex[symbolId]?.toList() ?: emptyList()

    /**
     * Find all occurrences of a symbol across all files
     */
    fun findAllOccurrences(symbolId: String): List<Pair<URI, SymbolOccurrence>> =
        occurrenceIndex[symbolId]?.toList() ?: emptyList()

    /**
     * Find all symbols in a file by kind
     */
    fun findSymbolsByKind(uri: URI, kind: SymbolKind): List<SymbolInfo> =
        documents[uri]?.findSymbolsByKind(kind) ?: emptyList()

    /**
     * Find all occurrences in a file by role
     */
    fun findOccurrencesByRole(uri: URI, role: OccurrenceRole): List<SymbolOccurrence> =
        documents[uri]?.findOccurrencesByRole(role) ?: emptyList()

    /**
     * Find symbol at a specific position in a file
     */
    fun findSymbolAtPosition(uri: URI, line: Int, column: Int): SymbolInfo? =
        documents[uri]?.symbols?.find { it.range.contains(line, column) }

    /**
     * Find occurrence at a specific position in a file
     */
    fun findOccurrenceAtPosition(uri: URI, line: Int, column: Int): SymbolOccurrence? =
        documents[uri]?.occurrences?.find { it.range.contains(line, column) }

    /**
     * Clear all documents
     */
    fun clear() {
        documents.clear()
        symbolIndex.clear()
        occurrenceIndex.clear()
        cacheLock.write {
            symbolCache.clear()
        }
        cacheHits = 0L
        cacheMisses = 0L
    }

    /**
     * Get statistics about the database
     */
    fun getStatistics(): SemanticDBStatistics {
        val allDocuments = documents.values
        val cacheSize = cacheLock.read { symbolCache.size }
        val hitRate = if (cacheHits + cacheMisses > 0) {
            cacheHits.toDouble() / (cacheHits + cacheMisses)
        } else {
            0.0
        }

        return SemanticDBStatistics(
            documentCount = documents.size,
            totalSymbols = allDocuments.sumOf { it.symbols.size },
            totalOccurrences = allDocuments.sumOf { it.occurrences.size },
            symbolsByKind = SymbolKind.values().associateWith { kind ->
                allDocuments.sumOf { doc -> doc.findSymbolsByKind(kind).size }
            },
            occurrencesByRole = OccurrenceRole.values().associateWith { role ->
                allDocuments.sumOf { doc -> doc.findOccurrencesByRole(role).size }
            },
            cacheSize = cacheSize,
            cacheHits = cacheHits,
            cacheMisses = cacheMisses,
            cacheHitRate = hitRate,
        )
    }

    /**
     * Build indexes for a document
     */
    private fun buildIndexes(uri: URI, doc: SemanticDocument) {
        // Index symbols
        doc.symbols.forEach { symbol ->
            symbolIndex.computeIfAbsent(symbol.symbol) { Collections.synchronizedList(mutableListOf()) }
                .add(uri to symbol)
        }

        // Index occurrences
        doc.occurrences.forEach { occurrence ->
            occurrenceIndex.computeIfAbsent(occurrence.symbol) { Collections.synchronizedList(mutableListOf()) }
                .add(uri to occurrence)
        }
    }

    /**
     * Remove indexes for a document
     */
    private fun removeFromIndexes(uri: URI, doc: SemanticDocument) {
        // Remove symbols from index and cache
        doc.symbols.forEach { symbol ->
            symbolIndex[symbol.symbol]?.removeIf { it.first == uri }
            if (symbolIndex[symbol.symbol]?.isEmpty() == true) {
                symbolIndex.remove(symbol.symbol)
                // Also remove from cache as it's no longer valid
                cacheLock.write {
                    symbolCache.remove(symbol.symbol)
                }
            }
        }

        // Remove occurrences from index
        doc.occurrences.forEach { occurrence ->
            occurrenceIndex[occurrence.symbol]?.removeIf { it.first == uri }
            if (occurrenceIndex[occurrence.symbol]?.isEmpty() == true) {
                occurrenceIndex.remove(occurrence.symbol)
            }
        }
    }

    companion object {
        // Cache configuration
        private const val MAX_CACHE_SIZE = 1000
        private const val CACHE_INITIAL_CAPACITY = 16
        private const val CACHE_LOAD_FACTOR = 0.75f
    }
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
    val cacheSize: Int = 0,
    val cacheHits: Long = 0L,
    val cacheMisses: Long = 0L,
    val cacheHitRate: Double = 0.0,
)
