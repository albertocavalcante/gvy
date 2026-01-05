package com.github.albertocavalcante.groovylsp.indexing

import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SymbolInfo
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.workspace.MemberInfo
import com.github.albertocavalcante.gvy.semantics.workspace.MemberLookup
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Workspace-wide symbol index that aggregates semantic information from all files.
 * Provides fast lookup for symbols, references, class hierarchy, and member resolution.
 *
 * This index delegates to [GroovySemanticDB] for underlying storage and maintains
 * additional caches for efficient workspace-wide queries.
 *
 * Thread-safe implementation using ConcurrentHashMap for concurrent LSP operations.
 *
 * @property semanticDb The underlying semantic database storing per-file information
 */
class WorkspaceSymbolIndex(private val semanticDb: GroovySemanticDB) : MemberLookup {

    // Cache for class hierarchy information (will be populated when Phase 0 is extended)
    // For now, these remain empty as SymbolInfo doesn't track inheritance yet
    private val superclassCache = ConcurrentHashMap<String, String>()
    private val interfacesCache = ConcurrentHashMap<String, List<String>>()

    /**
     * Get the semantic document for a URI.
     *
     * @param uri The document URI
     * @return The semantic document, or null if not found
     */
    fun getDocument(uri: URI): SemanticDocument? = semanticDb.getDocument(uri)

    /**
     * Find a symbol by its unique ID.
     *
     * @param symbolId The symbol ID in SemanticDB format (e.g., "com/example/MyClass#")
     * @return The symbol information, or null if not found
     */
    fun findSymbol(symbolId: String): SymbolInfo? = semanticDb.findSymbolDefinition(symbolId)?.second

    /**
     * Find the definition location of a symbol.
     *
     * @param symbolId The symbol ID in SemanticDB format
     * @return The LSP Location of the definition, or null if not found
     */
    fun findDefinition(symbolId: String): Location? {
        val (uri, symbolInfo) = semanticDb.findSymbolDefinition(symbolId) ?: return null
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
        val occurrences = semanticDb.findAllOccurrences(symbolId)
        return occurrences.map { (uri, occurrence) ->
            Location(uri.toString(), rangeToLspRange(occurrence.range))
        }
    }

    /**
     * Get the superclass of a class.
     *
     * Note: Currently returns null as SymbolInfo doesn't track inheritance yet.
     * This will be implemented when Phase 0 is extended with class hierarchy information.
     *
     * @param classFqn The fully qualified class name (e.g., "com/example/MyClass")
     * @return The FQN of the superclass, or null if none or not found
     */
    fun getSuperclass(classFqn: String): String? {
        // Check cache first
        val cached = superclassCache[classFqn]
        if (cached != null) return cached

        // Look up class symbol
        val classSymbolId = "$classFqn#"
        val classSymbol = findSymbol(classSymbolId) ?: return null

        // TODO: Extract superclass info when SymbolInfo is extended
        // For now, return null as we don't have this information
        return null
    }

    /**
     * Get the interfaces implemented by a class.
     *
     * Note: Currently returns empty list as SymbolInfo doesn't track inheritance yet.
     * This will be implemented when Phase 0 is extended with class hierarchy information.
     *
     * @param classFqn The fully qualified class name (e.g., "com/example/MyClass")
     * @return List of interface FQNs, or empty list if none or not found
     */
    fun getInterfaces(classFqn: String): List<String> {
        // Check cache first
        val cached = interfacesCache[classFqn]
        if (cached != null) return cached

        // Look up class symbol
        val classSymbolId = "$classFqn#"
        val classSymbol = findSymbol(classSymbolId) ?: return emptyList()

        // TODO: Extract interfaces info when SymbolInfo is extended
        // For now, return empty list as we don't have this information
        return emptyList()
    }

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
     * Internal helper to find a SymbolInfo by symbol ID.
     * Used internally when we need the full SymbolInfo, not just MemberInfo.
     *
     * @param symbolId The symbol ID in SemanticDB format
     * @return The symbol information, or null if not found
     */
    private fun findSymbolInternal(symbolId: String): SymbolInfo? = semanticDb.findSymbolDefinition(symbolId)?.second

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
    override fun findMethod(classFqn: String, methodName: String, arity: Int?): MemberInfo? {
        // Look up the class to get all its members
        val classSymbolId = "$classFqn#"
        val classSymbol = findSymbol(classSymbolId) ?: return null

        // Find all symbols in the workspace that belong to this class
        val allDocuments = semanticDb.getAllDocuments()
        val methods = allDocuments.values.flatMap { doc ->
            doc.symbols.filter { symbol ->
                symbol.owner == classSymbolId &&
                    symbol.kind == SymbolKind.METHOD &&
                    symbol.name == methodName
            }
        }

        // If arity is specified, filter by parameter count
        val matchingMethod = if (arity != null) {
            methods.firstOrNull { method ->
                val paramCount = extractParameterCount(method.symbol)
                paramCount == arity
            }
        } else {
            // Return any method with matching name
            methods.firstOrNull()
        }

        return matchingMethod?.let { method ->
            MemberInfo(
                name = method.name,
                kind = method.kind,
                type = method.type,
                signature = method.symbol,
                symbolId = method.symbol,
            )
        }
    }

    /**
     * Get all members of a class (fields, methods, properties).
     *
     * @param classFqn The fully qualified class name (e.g., "com/example/MyClass")
     * @param includeInherited Whether to include inherited members (not yet implemented)
     * @return List of all member info
     */
    override fun getAllMembers(classFqn: String, includeInherited: Boolean): List<MemberInfo> {
        val classSymbolId = "$classFqn#"
        val classSymbol = findSymbol(classSymbolId) ?: return emptyList()

        // Find all symbols owned by this class
        val allDocuments = semanticDb.getAllDocuments()
        val directMembers = allDocuments.values.flatMap { doc ->
            doc.symbols.filter { symbol ->
                symbol.owner == classSymbolId &&
                    (
                        symbol.kind == SymbolKind.FIELD ||
                            symbol.kind == SymbolKind.METHOD ||
                            symbol.kind == SymbolKind.PROPERTY
                        )
            }
        }

        // TODO: Add inherited members when hierarchy is available
        // For now, just return direct members
        return directMembers.map { symbol ->
            MemberInfo(
                name = symbol.name,
                kind = symbol.kind,
                type = symbol.type,
                signature = if (symbol.kind == SymbolKind.METHOD) symbol.symbol else null,
                symbolId = symbol.symbol,
            )
        }
    }

    /**
     * Update a document in the index.
     * This delegates to the underlying SemanticDB and clears relevant caches.
     *
     * @param uri The document URI
     * @param doc The semantic document
     */
    fun updateDocument(uri: URI, doc: SemanticDocument) {
        semanticDb.updateDocument(uri, doc)

        // Clear caches for updated classes
        doc.symbols.filter { it.kind == SymbolKind.CLASS || it.kind == SymbolKind.INTERFACE }
            .forEach { classSymbol ->
                val classFqn = extractClassFqn(classSymbol.symbol)
                superclassCache.remove(classFqn)
                interfacesCache.remove(classFqn)
            }
    }

    /**
     * Remove a document from the index.
     * This delegates to the underlying SemanticDB and clears relevant caches.
     *
     * @param uri The document URI
     */
    fun removeDocument(uri: URI) {
        // Get the document before removing to clear caches
        val doc = semanticDb.getDocument(uri)
        doc?.symbols?.filter { it.kind == SymbolKind.CLASS || it.kind == SymbolKind.INTERFACE }
            ?.forEach { classSymbol ->
                val classFqn = extractClassFqn(classSymbol.symbol)
                superclassCache.remove(classFqn)
                interfacesCache.remove(classFqn)
            }

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
        for (char in params) {
            when (char) {
                '<' -> bracketDepth++
                '>' -> bracketDepth--
                ',' -> if (bracketDepth == 0) count++
            }
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
}
