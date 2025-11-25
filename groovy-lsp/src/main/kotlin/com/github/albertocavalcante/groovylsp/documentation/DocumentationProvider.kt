package com.github.albertocavalcante.groovylsp.documentation

import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for retrieving documentation for AST nodes.
 * Extracts groovydoc/javadoc from workspace sources.
 * Thread-safe for concurrent access from coroutines.
 */
class DocumentationProvider(private val documentProvider: DocumentProvider) {
    private val logger = LoggerFactory.getLogger(DocumentationProvider::class.java)

    // Thread-safe cache to avoid recomputing documentation
    private val cache = ConcurrentHashMap<String, Documentation>()

    /**
     * Get documentation for an AST node from the given document.
     *
     * @param node The AST node
     * @param documentUri The URI of the document containing the node
     * @return Documentation if found, or Documentation.EMPTY
     */
    fun getDocumentation(node: ASTNode, documentUri: URI): Documentation {
        // Only annotated nodes have line information we can use
        if (node !is AnnotatedNode) {
            return Documentation.EMPTY
        }

        val lineNumber = node.lineNumber
        if (lineNumber <= 0) {
            return Documentation.EMPTY
        }

        // Create a cache key
        val cacheKey = "$documentUri:$lineNumber"

        // Check cache first
        cache[cacheKey]?.let { return it }

        // Get the source text
        val sourceText = try {
            documentProvider.get(documentUri) ?: return Documentation.EMPTY
        } catch (e: Exception) {
            logger.warn("Failed to get document content for $documentUri", e)
            return Documentation.EMPTY
        }

        // Extract documentation
        val doc = DocExtractor.extractDocumentation(sourceText, node)

        // Cache the result
        cache[cacheKey] = doc

        return doc
    }

    /**
     * Clear the documentation cache.
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Clear cached documentation for a specific document.
     * Thread-safe: uses ConcurrentHashMap's atomic operations.
     */
    fun clearCache(documentUri: URI) {
        val prefix = "$documentUri:"
        cache.keys.removeIf { it.startsWith(prefix) }
    }

    companion object {
        // Shared instance for use across the application
        @Volatile
        private var instance: DocumentationProvider? = null

        /**
         * Get or create a shared DocumentationProvider instance.
         * This ensures cache invalidation works across all hover requests.
         */
        fun getInstance(documentProvider: DocumentProvider): DocumentationProvider = instance ?: synchronized(this) {
            instance ?: DocumentationProvider(documentProvider).also { instance = it }
        }

        /**
         * Clear cache for a document across the shared instance.
         * Call this when a document changes.
         */
        fun invalidateDocument(documentUri: URI) {
            instance?.clearCache(documentUri)
        }

        /**
         * Reset the singleton instance. Useful for testing.
         */
        fun reset() {
            instance = null
        }
    }
}
