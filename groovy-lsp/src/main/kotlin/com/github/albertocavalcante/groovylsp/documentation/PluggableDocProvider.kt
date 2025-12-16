package com.github.albertocavalcante.groovylsp.documentation

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import org.codehaus.groovy.ast.ASTNode
import java.net.URI

/**
 * Interface for pluggable documentation sources.
 *
 * Allows different documentation sources (Jenkins, Groovy, external) to be
 * plugged in with priority-based resolution.
 *
 * Separate from [DocumentationProvider] which handles GroovyDoc extraction.
 */
interface PluggableDocProvider {

    /**
     * Generate documentation for the given AST node.
     *
     * @param node The AST node to document
     * @param model The AST model for context (parent lookup, etc.)
     * @param documentUri The URI of the document containing the node
     * @return Documentation if this provider can handle the node, null otherwise
     */
    fun generateDoc(node: ASTNode, model: GroovyAstModel, documentUri: URI): GroovyDocumentation?

    /**
     * Generate quick navigation info (shorter than full documentation).
     * Used for hover tooltips and quick info popups.
     *
     * @param node The AST node
     * @return Short info string or null if not applicable
     */
    fun getQuickNavigateInfo(node: ASTNode): String? = null

    /**
     * Check if this provider can handle the given node.
     * Used for fast rejection before attempting generation.
     */
    fun canHandle(node: ASTNode, documentUri: URI): Boolean = true

    /**
     * Provider priority. Higher values are checked first.
     * Allows specialized providers (Jenkins) to take precedence over generic ones.
     */
    val priority: Int get() = 0

    /**
     * Provider name for debugging and logging.
     */
    val name: String
}
