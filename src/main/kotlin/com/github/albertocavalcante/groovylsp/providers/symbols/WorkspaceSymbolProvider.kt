package com.github.albertocavalcante.groovylsp.providers.symbols

import com.github.albertocavalcante.groovylsp.ast.SymbolExtractor
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.compilation.WorkspaceCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides workspace-wide symbol search functionality for Groovy files.
 * Maintains an index of all symbols across the workspace and supports fuzzy search.
 */
@Suppress("TooGenericExceptionCaught") // Symbol extraction needs robust error handling for various file access issues
class WorkspaceSymbolProvider(
    private val compilationService: GroovyCompilationService,
    private val workspaceCompilationService: WorkspaceCompilationService?,
    private val coroutineScope: CoroutineScope,
) {

    private val logger = LoggerFactory.getLogger(WorkspaceSymbolProvider::class.java)

    // URI-based index for incremental updates
    private val uriToSymbols = ConcurrentHashMap<URI, List<SymbolInformation>>()

    // Flag to track if initial indexing is complete
    @Volatile
    private var indexingComplete = false

    companion object {
        // Search result limits to avoid overwhelming clients
        private const val MAX_ALL_SYMBOLS = 100
        private const val MAX_SEARCH_RESULTS = 50
    }

    /**
     * Searches for symbols matching the given query across the entire workspace.
     *
     * @param query The search query (supports fuzzy matching)
     * @return List of matching workspace symbols
     */
    fun searchSymbols(query: String): List<Either<SymbolInformation, WorkspaceSymbol>> {
        logger.debug("Searching workspace symbols for query: '$query'")

        // If indexing isn't complete, trigger it and return limited results
        if (!indexingComplete) {
            triggerWorkspaceIndexing()
        }

        val allSymbols = getAllIndexedSymbols()
        val matchingSymbols = if (query.isBlank()) {
            // Return all symbols if query is empty
            allSymbols.take(MAX_ALL_SYMBOLS)
        } else {
            // Fuzzy search across symbol names
            allSymbols.filter { symbol ->
                matchesQuery(symbol.name, query)
            }.take(MAX_SEARCH_RESULTS)
        }

        logger.debug("Found ${matchingSymbols.size} matching symbols for query '$query'")
        return matchingSymbols.map { Either.forLeft<SymbolInformation, WorkspaceSymbol>(it) }
    }

    /**
     * Updates the symbol index for a specific file.
     * Called when files are opened, changed, or saved.
     */
    fun updateFileSymbols(uri: URI) {
        logger.debug("Updating symbols for file: $uri")

        coroutineScope.launch {
            try {
                val symbols = extractSymbolsFromFile(uri)
                uriToSymbols[uri] = symbols
                logger.debug("Updated ${symbols.size} symbols for $uri")
            } catch (e: Exception) {
                logger.error("Error updating symbols for $uri", e)
                uriToSymbols[uri] = emptyList()
            }
        }
    }

    /**
     * Removes symbols for a file when it's deleted or closed.
     */
    fun removeFileSymbols(uri: URI) {
        logger.debug("Removing symbols for file: $uri")
        uriToSymbols.remove(uri)
    }

    /**
     * Triggers initial workspace indexing in the background.
     */
    private fun triggerWorkspaceIndexing() {
        if (indexingComplete) return

        logger.info("Starting workspace symbol indexing")
        coroutineScope.launch {
            try {
                indexWorkspace()
                indexingComplete = true
                logger.info("Workspace symbol indexing completed")
            } catch (e: Exception) {
                logger.error("Error during workspace indexing", e)
                indexingComplete = false
            }
        }
    }

    /**
     * Indexes all Groovy files in the workspace.
     */
    private suspend fun indexWorkspace() {
        val workspaceService = workspaceCompilationService
        if (workspaceService == null) {
            logger.debug("No workspace compilation service available, indexing current files only")
            indexCurrentFiles()
            return
        }

        try {
            // Get all Groovy files from workspace
            val groovyFiles = workspaceService.getGroovyFiles()
            logger.debug("Found ${groovyFiles.size} Groovy files to index")

            groovyFiles.forEach { file ->
                try {
                    val uri = file.toUri()
                    val symbols = extractSymbolsFromFile(uri)
                    uriToSymbols[uri] = symbols
                    logger.debug("Indexed ${symbols.size} symbols from $uri")
                } catch (e: Exception) {
                    logger.warn("Failed to index file $file", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Error accessing workspace files", e)
            // Fallback to indexing currently compiled files
            indexCurrentFiles()
        }
    }

    /**
     * Indexes files that are currently compiled (fallback strategy).
     */
    private fun indexCurrentFiles() {
        try {
            // Get all currently compiled URIs from compilation service cache
            val compiledUris = compilationService.astCache.getAllCachedUris()
            logger.debug("Indexing ${compiledUris.size} currently compiled files")

            compiledUris.forEach { uri ->
                try {
                    val symbols = extractSymbolsFromFile(uri)
                    uriToSymbols[uri] = symbols
                } catch (e: Exception) {
                    logger.warn("Failed to index compiled file $uri", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Error indexing current files", e)
        }
    }

    /**
     * Extracts symbols from a single file.
     */
    private fun extractSymbolsFromFile(uri: URI): List<SymbolInformation> {
        val ast = compilationService.getAst(uri) ?: return emptyList()

        val symbols = mutableListOf<SymbolInformation>()

        // Extract class symbols
        val classSymbols = SymbolExtractor.extractClassSymbols(ast)
        classSymbols.forEach { classSymbol ->
            val classInfo = createSymbolInformation(
                name = classSymbol.name,
                kind = determineClassSymbolKind(classSymbol.astNode as org.codehaus.groovy.ast.ClassNode),
                location = createLocation(uri, classSymbol.line, classSymbol.column, classSymbol.name.length),
                containerName = classSymbol.packageName,
            )
            symbols.add(classInfo)

            // Extract methods and fields from the class
            val classNode = classSymbol.astNode as org.codehaus.groovy.ast.ClassNode

            val methodSymbols = SymbolExtractor.extractMethodSymbols(classNode)
            methodSymbols.forEach { methodSymbol ->
                val methodInfo = createSymbolInformation(
                    name = methodSymbol.name,
                    kind = if (methodSymbol.name == "<init>") SymbolKind.Constructor else SymbolKind.Method,
                    location = createLocation(uri, methodSymbol.line, methodSymbol.column, methodSymbol.name.length),
                    containerName = classSymbol.name,
                )
                symbols.add(methodInfo)
            }

            val fieldSymbols = SymbolExtractor.extractFieldSymbols(classNode)
            fieldSymbols.forEach { fieldSymbol ->
                val fieldInfo = createSymbolInformation(
                    name = fieldSymbol.name,
                    kind = SymbolKind.Field,
                    location = createLocation(uri, fieldSymbol.line, fieldSymbol.column, fieldSymbol.name.length),
                    containerName = classSymbol.name,
                )
                symbols.add(fieldInfo)
            }
        }

        return symbols
    }

    /**
     * Determines the appropriate SymbolKind for a ClassNode.
     */
    private fun determineClassSymbolKind(classNode: org.codehaus.groovy.ast.ClassNode): SymbolKind = when {
        classNode.isInterface -> SymbolKind.Interface
        classNode.isEnum -> SymbolKind.Enum
        classNode.isAnnotationDefinition -> SymbolKind.Class // LSP doesn't have annotation kind
        else -> SymbolKind.Class
    }

    /**
     * Creates a SymbolInformation object.
     */
    private fun createSymbolInformation(
        name: String,
        kind: SymbolKind,
        location: Location,
        containerName: String? = null,
    ): SymbolInformation = SymbolInformation().apply {
        this.name = name
        this.kind = kind
        this.location = location
        this.containerName = containerName
    }

    /**
     * Creates a Location object for a symbol.
     */
    private fun createLocation(uri: URI, line: Int, column: Int, nameLength: Int): Location {
        val range = Range(
            Position(line, column),
            Position(line, column + nameLength),
        )
        return Location(uri.toString(), range)
    }

    /**
     * Gets all currently indexed symbols.
     */
    private fun getAllIndexedSymbols(): List<SymbolInformation> = uriToSymbols.values.flatten()

    /**
     * Checks if a symbol name matches the query using fuzzy matching.
     */
    private fun matchesQuery(symbolName: String, query: String): Boolean {
        if (query.isBlank()) return true

        val lowerSymbol = symbolName.lowercase()
        val lowerQuery = query.lowercase()

        // Exact match gets highest priority
        if (lowerSymbol.contains(lowerQuery)) return true

        // Fuzzy matching: check if all query characters appear in order
        var queryIndex = 0
        for (char in lowerSymbol) {
            if (queryIndex < lowerQuery.length && char == lowerQuery[queryIndex]) {
                queryIndex++
            }
        }

        return queryIndex == lowerQuery.length
    }

    /**
     * Gets the current indexing status.
     */
    fun isIndexingComplete(): Boolean = indexingComplete

    /**
     * Gets the number of indexed symbols.
     */
    fun getIndexedSymbolCount(): Int = getAllIndexedSymbols().size
}
