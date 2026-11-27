// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.documentation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for retrieving documentation for AST nodes.
 * Extracts groovydoc/javadoc from the open buffers of [documentProvider].
 *
 * Each cached entry records the hash of the buffer text it was extracted from and answers only while the buffer
 * still has that text, so a lookup that read the buffer before an edit can never leave the old documentation in
 * place for later lookups, and no edit has to invalidate anything. Thread-safe for concurrent lookups.
 *
 * Each owner builds its own instance over its own [DocumentProvider].
 */
class DocumentationProvider(private val documentProvider: DocumentProvider) {
    private data class Key(val uri: URI, val line: Int)

    private class Entry(val contentHash: String, val documentation: Documentation)

    // Thread-safe cache to avoid recomputing documentation
    private val cache = ConcurrentHashMap<Key, Entry>()

    /**
     * Get documentation for an AST node from the given document.
     *
     * @param node The AST node
     * @param documentUri The URI of the document containing the node
     * @return Documentation if found, or Documentation.EMPTY
     */
    fun getDocumentation(node: ASTNode, documentUri: URI): Documentation {
        // Only annotated nodes with a line can be matched to a comment in the text
        val lineNumber = (node as? AnnotatedNode)?.lineNumber?.takeIf { it > 0 } ?: return Documentation.EMPTY
        val snapshot = documentProvider.getSnapshot(documentUri) ?: return Documentation.EMPTY
        val key = Key(documentUri, lineNumber)
        val cached = cache[key]?.takeIf { it.contentHash == snapshot.contentHash }
        return cached?.documentation ?: DocExtractor.extractDocumentation(snapshot.content, node).also { doc ->
            // Stamped with the text it came from: if the buffer changed meanwhile, the next lookup recomputes.
            cache[key] = Entry(snapshot.contentHash, doc)
        }
    }
}
