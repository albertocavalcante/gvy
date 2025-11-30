package com.github.albertocavalcante.groovyparser.api

import java.net.URI
import java.nio.file.Path

/**
 * Input required to parse a Groovy document.
 */
data class ParseRequest(
    val uri: URI,
    val content: String,
    val classpath: List<Path> = emptyList(),
    val sourceRoots: List<Path> = emptyList(),
    val workspaceSources: List<Path> = emptyList(),
    val locatorCandidates: Set<String> = emptySet(),
    /**
     * Feature flag to enable experimental recursive visitor alongside legacy delegate visitor.
     *
     * When enabled, both visitors run in parallel allowing:
     * - Side-by-side comparison of outputs for validation
     * - Gradual migration with rollback capability
     * - Parity testing in production environments
     *
     * The recursive visitor uses composition over inheritance, avoiding tight coupling
     * to Groovy's ClassCodeVisitorSupport and providing more control over AST traversal.
     *
     * Default: false (uses only delegate visitor for backward compatibility)
     *
     * @see RecursiveAstVisitor for the new implementation
     * @see com.github.albertocavalcante.groovyparser.ast.visitor.NodeVisitorDelegate for the legacy implementation
     */
    val useRecursiveVisitor: Boolean = false,
) {
    val sourceUnitName: String = uri.path ?: uri.toString()
}
