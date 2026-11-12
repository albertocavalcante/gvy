// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.Hover

/**
 * Strategy interface for generating hover information for specific AST node types.
 *
 * Each strategy is responsible for:
 * 1. Determining if it can handle a given node type
 * 2. Generating appropriate hover content for that node
 *
 * This pattern allows for:
 * - Clear separation of concerns
 * - Easy addition of new node type handlers
 * - Testable individual strategies
 * - Prioritization of strategies via ordering
 */
interface HoverStrategy {
    /**
     * Determines if this strategy can handle the given node.
     *
     * @param node The AST node to check
     * @return true if this strategy can generate hover content for this node
     */
    fun canHandle(node: ASTNode): Boolean

    /**
     * Generates hover information for the given node.
     *
     * @param node The AST node to generate hover for
     * @param context Additional context needed for hover generation
     * @return Hover information, or null if unable to generate
     */
    fun generateHover(node: ASTNode, context: HoverContext): Hover?
}

/**
 * Context information needed for hover generation.
 *
 * @property moduleNode The module containing the node (if available)
 * @property contentGenerator The service for generating hover content
 * @property documentUri The URI of the document containing the node
 * @property astModel Parent/child index of the document, when available (needed to see a node's context)
 */
data class HoverContext(
    val moduleNode: ModuleNode?,
    val contentGenerator: HoverContentGenerator,
    val documentUri: java.net.URI,
    val astModel: GroovyAstModel? = null,
)
