package com.github.albertocavalcante.gvy.gls.indexing

import com.github.albertocavalcante.gvy.semantics.db.DeclarationIndexSnapshot
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.gvy.semantics.db.ImplementationDocument
import com.github.albertocavalcante.gvy.semantics.db.ImplementationFacts
import com.github.albertocavalcante.gvy.semantics.db.IndexedOccurrence
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentSnapshot
import com.github.albertocavalcante.gvy.semantics.db.SymbolInfo
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.workspace.MemberInfo
import com.github.albertocavalcante.gvy.semantics.workspace.MemberLookup
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI
import java.util.Collections

/**
 * Workspace-wide symbol index that aggregates semantic information from all files.
 * Provides fast lookup for symbols, references, class hierarchy, and member resolution.
 *
 * This index delegates to [GroovySemanticDB] for underlying storage and keeps no state of its own, so it is
 * thread-safe exactly as the database is.
 *
 * Workspace-wide reads leave out [shadowed] documents: listed copies that a buffer open under another spelling
 * stands for (#1446), so a file answers once, from the buffer. Reads of one named document are not filtered.
 *
 * @property semanticDb The underlying semantic database storing per-file information
 */
@Suppress("TooManyFunctions") // Index facade keeps lookup and publication APIs together.
class WorkspaceSymbolIndex(
    private val semanticDb: GroovySemanticDB,
    private val shadowed: () -> Set<URI> = { emptySet() },
) : MemberLookup {

    /**
     * Captures a detached member view. [hiddenUris] is copied but is not coordinated with DB publication;
     * request owners must capture and validate document/workspace identities separately. Capturing copies
     * the declaration indexes; reuse the result rather than capturing for each member lookup.
     */
    fun captureMembers(hiddenUris: Set<URI>): MemberLookup = FrozenWorkspaceMemberLookup(
        semanticDb.snapshotDeclarations(),
        Collections.unmodifiableSet(hiddenUris.toSet()),
    )

    /** The definition of [symbolId] in a document not [shadowed]: the database's first one when none is. */
    private fun definition(symbolId: String): Pair<URI, SymbolInfo>? {
        val hidden = shadowed()
        return if (hidden.isEmpty()) {
            semanticDb.findSymbolDefinition(symbolId)
        } else {
            semanticDb.findAllSymbolDefinitions(symbolId).firstOrNull { it.first !in hidden }
        }
    }

    /**
     * Get the semantic document for a URI.
     *
     * @param uri The document URI
     * @return The semantic document, or null if not found
     */
    fun getDocument(uri: URI): SemanticDocument? = semanticDb.getDocument(uri)

    /** Import bindings, raw Groovy ranges and source text captured together. */
    fun getDocumentSnapshot(uri: URI): SemanticDocumentSnapshot? = semanticDb.getDocumentSnapshot(uri)

    /**
     * Find a symbol by its unique ID.
     *
     * @param symbolId The symbol ID in SemanticDB format (e.g., "com/example/MyClass#")
     * @return The symbol information, or null if not found
     */
    fun findSymbol(symbolId: String): SymbolInfo? = definition(symbolId)?.second

    /**
     * Find the definition location of a symbol.
     *
     * @param symbolId The symbol ID in SemanticDB format
     * @return The LSP Location of the definition, or null if not found
     */
    fun findDefinition(symbolId: String): Location? {
        val (uri, symbolInfo) = definition(symbolId) ?: return null
        return Location(uri.toString(), rangeToLspRange(symbolInfo.range))
    }

    /**
     * Find all references to a symbol across the workspace.
     * Includes the definition occurrence.
     *
     * @param symbolId The symbol ID in SemanticDB format
     * @return List of all locations where this symbol is used
     */
    fun findReferences(symbolId: String): List<Location> {
        val hidden = shadowed()
        val occurrences = semanticDb.findAllOccurrences(symbolId).filter { it.first !in hidden }
        return occurrences.map { (uri, occurrence) ->
            Location(uri.toString(), rangeToLspRange(occurrence.range))
        }
    }

    /** Reference occurrences with the exact source text used to build their ranges. */
    fun findReferenceSnapshots(symbolId: String): List<IndexedOccurrence> {
        val hidden = shadowed()
        return semanticDb.findOccurrenceSnapshots(symbolId).filter { it.uri !in hidden }
    }

    /** One snapshot per matching document; callers can reuse its source index for every occurrence. */
    fun findReferenceDocuments(symbolId: String): Map<URI, SemanticDocumentSnapshot> {
        val hidden = shadowed()
        return semanticDb.findReferenceDocuments(symbolId).filterKeys { it !in hidden }
    }

    /** Hierarchy, source and semantic ranges from one atomic database read. */
    val implementationSnapshot: Map<URI, ImplementationDocument> get() {
        val hidden = shadowed()
        val snapshot = semanticDb.implementationSnapshot
        return if (hidden.isEmpty()) snapshot else snapshot.filterKeys { it !in hidden }
    }

    /** The single indexed document defining [symbolId], without copying the rest of the workspace. */
    fun findImplementationDocument(symbolId: String): ImplementationDocument? {
        val hidden = shadowed()
        if (hidden.isEmpty()) return semanticDb.findImplementationDocument(symbolId)
        // A shadowed copy is a second definition the database would refuse: pick among the visible ones, which
        // copies every document, but only while a buffer shadows one.
        val uri = semanticDb.findAllSymbolDefinitions(symbolId).map { it.first }.distinct()
            .filter { it !in hidden }.singleOrNull() ?: return null
        return semanticDb.implementationSnapshot[uri]
    }

    /**
     * Get the superclass of a class: always null for now, since [SymbolInfo] does not track inheritance yet.
     *
     * If inheritance lands with a cache in front of it (#1434), keep it from serving a pre-clear hierarchy: read
     * the cache holder once before reading the semantic DB and write results only into that holder, and on
     * [clear] swap in a fresh DB before a fresh cache holder. A lookup that raced the clear then fills a holder
     * nobody reads any more. Add a test racing a lookup against [clear].
     *
     * @param classFqn The fully qualified class name (e.g., "com/example/MyClass")
     * @return The FQN of the superclass; null until inheritance is tracked
     */
    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // A stub until SymbolInfo carries inheritance.
    fun getSuperclass(classFqn: String): String? = null

    /**
     * Get the interfaces implemented by a class: always empty for now, since [SymbolInfo] does not track
     * inheritance yet. A cache added with inheritance follows the same rules as for [getSuperclass].
     *
     * @param classFqn The fully qualified class name (e.g., "com/example/MyClass")
     * @return List of interface FQNs; empty until inheritance is tracked
     */
    @Suppress("UnusedParameter") // A stub until SymbolInfo carries inheritance.
    fun getInterfaces(classFqn: String): List<String> = emptyList()

    /**
     * Get the full inheritance chain for a class (including transitive superclasses).
     *
     * Note: Currently returns empty list as SymbolInfo doesn't track inheritance yet.
     * This will be implemented when Phase 0 is extended with class hierarchy information.
     *
     * @param classFqn The fully qualified class name (e.g., "com/example/MyClass")
     * @return List of FQNs in the inheritance chain, from direct superclass to Object
     */
    fun getInheritanceChain(classFqn: String): List<String> {
        val chain = mutableListOf<String>()
        var current: String? = getSuperclass(classFqn)

        // Walk up the inheritance hierarchy
        val visited = mutableSetOf<String>()
        while (current != null && current !in visited) {
            chain.add(current)
            visited.add(current)
            current = getSuperclass(current)
        }

        return chain
    }

    /**
     * Find a field in a class (optionally including inherited fields).
     *
     * Note: Inheritance search not yet implemented as SymbolInfo doesn't track inheritance.
     * Currently only searches direct fields.
     *
     * @param classFqn The fully qualified class name (e.g., "com/example/MyClass")
     * @param fieldName The simple field name
     * @return The field member info, or null if not found
     */
    override fun findField(classFqn: String, fieldName: String): MemberInfo? {
        // Look for field in the class
        val classSymbolId = "$classFqn#"
        val fieldSymbolId = "$classSymbolId$fieldName."

        val field = findSymbol(fieldSymbolId)
        if (field != null) {
            return MemberInfo(
                name = field.name,
                kind = field.kind,
                type = field.type,
                signature = null,
                symbolId = field.symbol,
            )
        }

        // TODO: Search inherited fields when hierarchy is available
        return null
    }

    /**
     * Find a method in a class (optionally including inherited methods).
     *
     * Note: Inheritance search not yet implemented as SymbolInfo doesn't track inheritance.
     * Currently only searches direct methods.
     *
     * @param classFqn The fully qualified class name (e.g., "com/example/MyClass")
     * @param methodName The simple method name
     * @param arity The number of parameters, or null to match any arity
     * @return The method member info, or null if not found
     */
    override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo? =
        findMethods(classFqn, methodName, arity).firstOrNull()

    /** Every overload of [methodName] with [arity] parameters (any arity when null), direct members only. */
    override fun findMethods(classFqn: String, methodName: String, arity: Int?): List<MemberInfo> =
        directMembers(classFqn)
            .filter { it.kind == SymbolKind.METHOD && it.name == methodName }
            .filter { arity == null || extractParameterCount(it.symbol) == arity }
            .map { it.toMemberInfo() }

    override val version: Long get() = semanticDb.version

    /** The symbols owned by the class, from the owner index; empty for a class the workspace does not define. */
    private fun directMembers(classFqn: String): List<SymbolInfo> {
        val classSymbolId = "$classFqn#"
        findSymbol(classSymbolId) ?: return emptyList() // an unknown class has no members
        val hidden = shadowed()
        return semanticDb.findMembers(classSymbolId).filter { it.first !in hidden }.map { it.second }
    }

    /**
     * Get all members of a class (fields, methods, properties).
     *
     * @param classFqn The fully qualified class name (e.g., "com/example/MyClass")
     * @param includeInherited Whether to include inherited members (not yet implemented)
     * @return List of all member info
     */
    override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> {
        // TODO: Add inherited members when hierarchy is available
        // For now, just return direct members
        return directMembers(classFqn)
            .filter { it.kind == SymbolKind.FIELD || it.kind == SymbolKind.METHOD || it.kind == SymbolKind.PROPERTY }
            .map { it.toMemberInfo() }
    }

    /**
     * Update a document in the index.
     * This delegates to the underlying SemanticDB and clears relevant caches.
     *
     * @param uri The document URI
     * @param doc The semantic document
     * Use the source-text overload to support precise workspace reference locations.
     */
    fun updateDocument(uri: URI, doc: SemanticDocument) = updateDocument(uri, doc, sourceText = null)

    /** Stores the exact text represented by the semantic document's ranges. */
    fun updateDocument(uri: URI, doc: SemanticDocument, sourceText: String?) =
        updateDocument(uri, doc, sourceText, ImplementationFacts.empty)

    /** Updates hierarchy facts atomically with their semantic document and compiled source. */
    fun updateDocument(
        uri: URI,
        doc: SemanticDocument,
        sourceText: String?,
        implementations: ImplementationFacts.Snapshot,
    ) {
        semanticDb.updateDocument(uri, doc, sourceText, implementations)
    }

    /** Drops every document in O(1): the database swaps in an empty graph under its write lock (#1342). */
    fun clear() {
        semanticDb.clear()
    }

    /**
     * Remove a document from the index.
     * This delegates to the underlying SemanticDB.
     *
     * @param uri The document URI
     */
    fun removeDocument(uri: URI) {
        semanticDb.removeDocument(uri)
    }

    /**
     * Extract the fully qualified class name from a symbol ID.
     *
     * Examples:
     * - "com/example/MyClass#" -> "com/example/MyClass"
     * - "com/example/MyClass#myMethod()." -> "com/example/MyClass"
     * - "com/example/MyClass#myField." -> "com/example/MyClass"
     *
     * @param symbolId The symbol ID
     * @return The class FQN
     */
    fun extractClassFqn(symbolId: String): String {
        val hashIndex = symbolId.indexOf('#')
        return if (hashIndex >= 0) {
            symbolId.substring(0, hashIndex)
        } else {
            symbolId
        }
    }
}

private class FrozenWorkspaceMemberLookup(
    private val snapshot: DeclarationIndexSnapshot,
    private val hidden: Set<URI>,
) : MemberLookup {
    override val version: Long get() = snapshot.version

    private fun definition(id: String): SymbolInfo? =
        snapshot.findDefinitions(id).firstOrNull { it.first !in hidden }?.second

    private fun directMembers(classFqn: String): List<SymbolInfo> {
        val owner = "$classFqn#"
        if (definition(owner) == null) return emptyList()
        return snapshot.findMembers(owner).filter { it.first !in hidden }.map { it.second }
    }

    override fun findField(classFqn: String, fieldName: String): MemberInfo? =
        definition("$classFqn#$fieldName.")?.let { field ->
            MemberInfo(field.name, field.kind, field.type, null, field.symbol)
        }

    override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo? =
        findMethods(classFqn, methodName, arity).firstOrNull()

    override fun findMethods(classFqn: String, methodName: String, arity: Int?): List<MemberInfo> =
        directMembers(classFqn).filter { it.kind == SymbolKind.METHOD && it.name == methodName }
            .filter { arity == null || extractParameterCount(it.symbol) == arity }.map { it.toMemberInfo() }

    override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> =
        directMembers(classFqn)
            .filter { it.kind == SymbolKind.FIELD || it.kind == SymbolKind.METHOD || it.kind == SymbolKind.PROPERTY }
            .map { it.toMemberInfo() }
}

private fun SymbolInfo.toMemberInfo() = MemberInfo(
    name = name,
    kind = kind,
    type = type,
    signature = if (kind == SymbolKind.METHOD) symbol else null,
    symbolId = symbol,
)

/**
 * Extract parameter count from a method symbol ID.
 *
 * Examples:
 * - "com/example/MyClass#myMethod()." -> 0
 * - "com/example/MyClass#myMethod(String)." -> 1
 * - "com/example/MyClass#myMethod(String,int)." -> 2
 * - "com/example/MyClass#myMethod(Map<String,String>)." -> 1
 *
 * @param methodSymbolId The method symbol ID
 * @return The number of parameters
 */
@Suppress("ReturnCount") // Multiple validation checks require early returns
private fun extractParameterCount(methodSymbolId: String): Int {
    val startIndex = methodSymbolId.indexOf('(')
    val endIndex = methodSymbolId.indexOf(')')

    if (startIndex < 0 || endIndex < 0 || startIndex >= endIndex) {
        return 0
    }

    val params = methodSymbolId.substring(startIndex + 1, endIndex)
    if (params.isEmpty()) return 0

    // Split by comma while respecting angle brackets for generics
    var count = 0
    var bracketDepth = 0
    var invalidBrackets = false
    for (char in params) {
        when (char) {
            '<' -> bracketDepth++
            '>' -> {
                bracketDepth--
                if (bracketDepth < 0) {
                    invalidBrackets = true
                    break
                }
            }
            ',' -> if (bracketDepth == 0) count++
        }
    }

    // If brackets are unbalanced, fall back to a simple comma-based count
    if (invalidBrackets || bracketDepth != 0) {
        return params.split(',')
            .map { it.trim() }
            .count { it.isNotEmpty() }
    }

    // Add 1 for the last parameter (no trailing comma)
    return count + 1
}

/**
 * Convert a semantic Range to an LSP Range.
 */
private fun rangeToLspRange(range: com.github.albertocavalcante.gvy.semantics.db.Range): Range = Range(
    Position(range.startLine, range.startColumn),
    Position(range.endLine, range.endColumn),
)
