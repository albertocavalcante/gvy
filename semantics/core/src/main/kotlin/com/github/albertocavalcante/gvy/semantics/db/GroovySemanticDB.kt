// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import com.github.albertocavalcante.gvy.semantics.SemanticType
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
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
@Suppress("TooManyFunctions") // Publication and lookup APIs share one lock to preserve atomic document/index updates.
class GroovySemanticDB {
    private val lock = ReentrantReadWriteLock()

    private class StoredDocument(
        val document: SemanticDocument,
        val sourceText: String?,
        val implementations: ImplementationFacts.Snapshot,
    )

    // All three are read and replaced only under [lock], so a reader sees one whole graph, never a mix
    private var documents = HashMap<URI, StoredDocument>()

    // symbolId -> definitions, grouped by file in the order the files were stored
    private var symbolIndex = SymbolIndex<SymbolInfo>()

    // symbolId -> occurrences, grouped by file
    private var occurrenceIndex = SymbolIndex<SymbolOccurrence>()

    // owner symbolId -> the symbols it owns (its members), grouped by file
    private var ownerIndex = SymbolIndex<SymbolInfo>()

    private val versionCounter = AtomicLong()

    // Guarded by lock; invalidated by every successful document graph mutation.
    private var cachedDeclarations: DeclarationIndexSnapshot? = null

    /**
     * Increases whenever a stored document's declarations change (a class, method, field or property added,
     * removed, renamed or retyped), or the database is cleared, so a derived value can tell it was computed earlier.
     * Edits inside bodies, moved ranges and an identical recompile leave it unchanged.
     */
    val version: Long get() = versionCounter.get()

    /**
     * Get semantic document for a URI
     */
    fun getDocument(uri: URI): SemanticDocument? = lock.read { documents[uri]?.document }

    /** A later replacement cannot mix this document's import bindings with different source text. */
    fun getDocumentSnapshot(uri: URI): SemanticDocumentSnapshot? = lock.read {
        documents[uri]?.let { SemanticDocumentSnapshot(it.document, it.sourceText) }
    }

    /**
     * Update/store semantic document for a URI.
     * Replaces the previous document and its index entries atomically.
     * Use the source-text overload for queries that need precise identifier ranges.
     */
    fun updateDocument(uri: URI, doc: SemanticDocument) = updateDocument(uri, doc, sourceText = null)

    /**
     * Stores a detached document and source text so readers do not combine facts from different versions.
     * Callers must not mutate the supplied document or nested collections during this call. After it returns,
     * later caller mutations cannot affect the published data. Cyclic types, nesting deeper than 128 nodes,
     * and types larger than 16,384 nodes when shared edges are expanded are rejected with
     * [IllegalArgumentException] before replacing the stored document.
     */
    fun updateDocument(uri: URI, doc: SemanticDocument, sourceText: String?) =
        updateDocument(uri, doc, sourceText, ImplementationFacts.Snapshot())

    /**
     * Hierarchy facts and ranges belong to the same compilation as [doc] and [sourceText].
     * The type graph limits and atomic rejection described by the source-text overload also apply here.
     */
    fun updateDocument(
        uri: URI,
        doc: SemanticDocument,
        sourceText: String?,
        implementations: ImplementationFacts.Snapshot,
    ) {
        val freezer = TypeFreezer()
        val snapshot = doc.copy(
            symbols = Collections.unmodifiableList(doc.symbols.map { it.copy(type = it.type?.let(freezer::freeze)) }),
            occurrences = Collections.unmodifiableList(doc.occurrences.toList()),
            unresolvedNames = Collections.unmodifiableSet(doc.unresolvedNames.toSet()),
        )
        val facts = ImplementationFacts.freeze(implementations)
        lock.write {
            val previous = documents[uri]
            val declarationsChanged = previous == null || declarations(previous.document) != declarations(snapshot)
            documents[uri] = StoredDocument(snapshot, sourceText, facts)
            previous?.let { removeFromIndexes(uri, it.document) }
            addToIndexes(uri, snapshot, symbolIndex, occurrenceIndex, ownerIndex)
            cachedDeclarations = null
            if (declarationsChanged) {
                versionCounter.incrementAndGet()
            }
        }
    }

    /**
     * Remove document when file is deleted
     */
    fun removeDocument(uri: URI) {
        lock.write {
            documents.remove(uri)?.let {
                removeFromIndexes(uri, it.document)
                cachedDeclarations = null
                if (declarations(it.document).isNotEmpty()) versionCounter.incrementAndGet()
            }
        }
    }

    /**
     * An immutable ordered declaration view. The first capture after a database mutation copies the indexes
     * under the write lock in O(index size); subsequent captures reuse that view in O(1). A declaration version
     * alone does not identify this view: range, body and ordering changes also retire it.
     */
    fun snapshotDeclarations(): DeclarationIndexSnapshot {
        lock.read { cachedDeclarations?.let { return it } }
        return lock.write {
            cachedDeclarations ?: DeclarationIndexSnapshot(
                versionCounter.get(),
                symbolIndex.snapshot(),
                ownerIndex.snapshot(),
            ).also { cachedDeclarations = it }
        }
    }

    /**
     * Get all documents
     */
    fun getAllDocuments(): Map<URI, SemanticDocument> = lock.read { documents.mapValues { it.value.document } }

    /** One coherent read; a later replacement or invalidation cannot change a captured query. */
    val implementationSnapshot: Map<URI, ImplementationDocument> get() = lock.read {
        Collections.unmodifiableMap(documents.toSortedMap().mapValues { (_, stored) -> stored.implementation() })
    }

    /**
     * The one stored document that defines [symbolId], read coherently with its symbols. Returns null
     * when no file or more than one file defines it. Unlike [implementationSnapshot], this copies
     * no other document, so per-keystroke callers can use it.
     */
    fun findImplementationDocument(symbolId: String): ImplementationDocument? = lock.read {
        symbolIndex.files(symbolId).singleOrNull()?.let(documents::get)?.implementation()
    }

    private fun StoredDocument.implementation() =
        ImplementationDocument(document, sourceText, implementations.classes, implementations.hierarchy)

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

    /** The symbols owned by [ownerSymbolId] (a class's members), in the files that define them. */
    fun findMembers(ownerSymbolId: String): List<Pair<URI, SymbolInfo>> = lock.read { ownerIndex.all(ownerSymbolId) }

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

    /** Matching metadata and source for each file containing [symbolId], shared by all its occurrences. */
    fun findReferenceDocuments(symbolId: String): Map<URI, SemanticDocumentSnapshot> = lock.read {
        occurrenceIndex.all(symbolId).map { it.first }.distinct().associateWith { uri ->
            val stored = checkNotNull(documents[uri])
            SemanticDocumentSnapshot(stored.document, stored.sourceText)
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
     * Drops every document by swapping in an empty graph under the write lock: O(1) whatever the size, and a reader
     * holding the read lock sees either the old graph or the new one, never a mix (#1342). The old graph is left
     * to the garbage collector.
     */
    fun clear() {
        lock.write {
            documents = HashMap()
            symbolIndex = SymbolIndex()
            occurrenceIndex = SymbolIndex()
            ownerIndex = SymbolIndex()
            cachedDeclarations = null
            versionCounter.incrementAndGet()
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

    private data class Declaration(
        val symbol: String,
        val kind: SymbolKind,
        val owner: String?,
        val type: com.github.albertocavalcante.gvy.semantics.SemanticType?,
    )

    // What other files can see of a document: member declarations and their types, not locals or ranges.
    private fun declarations(doc: SemanticDocument): Set<Declaration> = doc.symbols
        .filter { it.kind != SymbolKind.VARIABLE && it.kind != SymbolKind.PARAMETER && it.kind != SymbolKind.IMPORT }
        .mapTo(HashSet()) { Declaration(it.symbol, it.kind, it.owner, it.type) }

    // Callers hold the write lock.
    private fun removeFromIndexes(uri: URI, doc: SemanticDocument) {
        doc.symbols.forEach { symbolIndex.remove(it.symbol, uri) }
        doc.occurrences.forEach { occurrenceIndex.remove(it.symbol, uri) }
        doc.symbols.forEach { symbol -> symbol.owner?.let { ownerIndex.remove(it, uri) } }
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

    fun files(id: String): Set<URI> = bySymbol[id]?.keys?.toSet().orEmpty()

    fun all(id: String): List<Pair<URI, T>> =
        bySymbol[id]?.flatMap { (uri, entries) -> entries.map { uri to it } } ?: emptyList()

    fun snapshot(): Map<String, List<Pair<URI, T>>> = Collections.unmodifiableMap(
        bySymbol.mapValues { (_, files) ->
            Collections.unmodifiableList(files.flatMap { (uri, entries) -> entries.map { uri to it } })
        },
    )
}

/** Ordered declaration facts captured independently of later database mutations. */
class DeclarationIndexSnapshot internal constructor(
    val version: Long,
    private val definitions: Map<String, List<Pair<URI, SymbolInfo>>>,
    private val members: Map<String, List<Pair<URI, SymbolInfo>>>,
) {
    fun findDefinitions(symbolId: String): List<Pair<URI, SymbolInfo>> = definitions[symbolId].orEmpty()

    fun findMembers(ownerId: String): List<Pair<URI, SymbolInfo>> = members[ownerId].orEmpty()
}

/**
 * Copies an acyclic type graph while preserving sharing. Expanded size is bounded as the data classes'
 * structural equals/hashCode traverse shared edges again; depth is bounded before recursion can exhaust the stack.
 */
private class TypeFreezer {
    private data class Frozen(val type: SemanticType, val size: Int, val height: Int)

    private val completed = IdentityHashMap<SemanticType, Frozen>()
    private val active = IdentityHashMap<SemanticType, Boolean>()

    fun freeze(type: SemanticType): SemanticType = visit(type, 1).type

    private fun visit(type: SemanticType, depth: Int): Frozen {
        require(depth <= MAX_DEPTH) { "Semantic type exceeds maximum depth $MAX_DEPTH" }
        completed[type]?.let {
            require(depth + it.height - 1 <= MAX_DEPTH) { "Semantic type exceeds maximum depth $MAX_DEPTH" }
            return it
        }
        require(active.put(type, true) == null) { "Semantic type contains a cycle" }
        try {
            var size = 1
            var height = 1
            fun child(value: SemanticType): SemanticType {
                val result = visit(value, depth + 1)
                require(result.size <= MAX_EXPANDED_SIZE - size) {
                    "Semantic type exceeds maximum expanded size $MAX_EXPANDED_SIZE"
                }
                size += result.size
                height = maxOf(height, result.height + 1)
                return result.type
            }
            val detached = when (type) {
                is SemanticType.Known -> {
                    val arguments = mutableListOf<SemanticType>()
                    type.typeArgs.forEach { arguments.add(child(it)) }
                    type.copy(typeArgs = Collections.unmodifiableList(arguments))
                }
                is SemanticType.Array -> type.copy(componentType = child(type.componentType))
                is SemanticType.Union -> type.copy(
                    types = Collections.unmodifiableSet(type.types.mapTo(linkedSetOf(), ::child)),
                )
                else -> type
            }
            return Frozen(detached, size, height).also { completed[type] = it }
        } finally {
            active.remove(type)
        }
    }

    private companion object {
        const val MAX_DEPTH = 128
        const val MAX_EXPANDED_SIZE = 16384
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
)

// Caller holds the database's write lock; both indexes change with the stored document.
private fun addToIndexes(
    uri: URI,
    doc: SemanticDocument,
    symbolIndex: SymbolIndex<SymbolInfo>,
    occurrenceIndex: SymbolIndex<SymbolOccurrence>,
    ownerIndex: SymbolIndex<SymbolInfo>,
) {
    doc.symbols.groupBy { it.symbol }.forEach { (id, symbols) -> symbolIndex.put(id, uri, symbols) }
    doc.occurrences.groupBy { it.symbol }.forEach { (id, occurrences) -> occurrenceIndex.put(id, uri, occurrences) }
    doc.symbols.filter {
        it.owner != null
    }.groupBy { it.owner!! }.forEach { (id, owned) -> ownerIndex.put(id, uri, owned) }
}
