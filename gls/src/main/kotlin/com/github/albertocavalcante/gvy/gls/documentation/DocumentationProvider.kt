// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.documentation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import java.net.URI

/**
 * Service for retrieving documentation for AST nodes.
 * Extracts groovydoc/javadoc from the open buffers of [documentProvider].
 *
 * Nothing is cached: every lookup reads the buffer as it is now, so an edit can never leave old documentation
 * behind. Its owners build one per hover provider, and production builds a hover provider for each request, so a
 * cache would not outlive the one lookup a request makes. Each owner passes its own [DocumentProvider].
 */
class DocumentationProvider(private val documentProvider: DocumentProvider) {
    /**
     * Get documentation for an AST node from the given document.
     *
     * @param node The AST node
     * @param documentUri The URI of the document containing the node
     * @return Documentation if found, or Documentation.EMPTY
     */
    fun getDocumentation(node: ASTNode, documentUri: URI): Documentation {
        // Only annotated nodes with a line can be matched to a comment in the text
        if ((node as? AnnotatedNode)?.lineNumber?.let { it > 0 } != true) return Documentation.EMPTY
        val text = documentProvider.get(documentUri) ?: return Documentation.EMPTY
        return DocExtractor.extractDocumentation(text, node)
    }
}
