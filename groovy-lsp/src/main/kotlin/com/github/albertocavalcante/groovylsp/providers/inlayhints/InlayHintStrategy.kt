package com.github.albertocavalcante.groovylsp.providers.inlayhints

import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.InlayHint

/**
 * Strategy interface for generating inlay hints for specific AST node types.
 *
 * Each strategy is responsible for:
 * 1. Determining if it can handle a given AST node
 * 2. Generating appropriate inlay hints for supported nodes
 *
 * Implementations should be stateless and thread-safe.
 */
interface InlayHintStrategy {
    /**
     * Check if this strategy can provide hints for the given node.
     *
     * @param node The AST node to check
     * @param context The processing context containing configuration and dependencies
     * @return true if this strategy can handle this node type
     */
    fun canHandle(node: ASTNode, context: HintContext): Boolean

    /**
     * Generate inlay hints for the given node.
     *
     * This method is only called if [canHandle] returns true.
     *
     * @param node The AST node to process
     * @param context The processing context containing configuration and dependencies
     * @return A list of inlay hints (may be empty if none should be shown)
     */
    fun generateHints(node: ASTNode, context: HintContext): List<InlayHint>
}
