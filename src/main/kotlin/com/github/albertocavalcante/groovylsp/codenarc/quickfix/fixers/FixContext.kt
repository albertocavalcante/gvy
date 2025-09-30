package com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Scope of a fix operation.
 */
enum class FixScope {
    LINE, // Single line fix
    RANGE, // Range of lines
    FILE, // Whole file
    WORKSPACE, // Multiple files
}

/**
 * Context information needed for computing fixes.
 *
 * @property uri The URI of the document being fixed
 * @property document The text document identifier
 * @property sourceLines The lines of source code
 * @property compilationUnit The Groovy compilation unit (if available)
 * @property astCache Cache for AST nodes (if available)
 * @property formattingConfig Formatting configuration
 * @property scope The scope of the fix operation
 */
data class FixContext(
    val uri: String,
    val document: TextDocumentIdentifier,
    val sourceLines: List<String>,
    val compilationUnit: SourceUnit?,
    val astCache: ASTNodeCache?,
    val formattingConfig: FormattingConfig,
    val scope: FixScope,
)

/**
 * Configuration for code formatting.
 */
data class FormattingConfig(
    val indentSize: Int = 4,
    val useTabs: Boolean = false,
    val braceStyle: BraceStyle = BraceStyle.SAME_LINE,
    val maxLineLength: Int = 120,
)

/**
 * Brace style options.
 */
enum class BraceStyle {
    SAME_LINE, // Opening brace on same line
    NEW_LINE, // Opening brace on new line
    NEW_LINE_INDENTED, // Opening brace on new line, indented
}

/**
 * Cache for AST nodes to avoid repeated parsing.
 */
interface ASTNodeCache {
    fun getNode(uri: String, line: Int): ASTNode?
    fun putNode(uri: String, line: Int, node: ASTNode)
    fun clear()
}
