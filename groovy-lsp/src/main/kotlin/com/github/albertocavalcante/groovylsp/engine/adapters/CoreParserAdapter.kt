package com.github.albertocavalcante.groovylsp.engine.adapters

import com.github.albertocavalcante.groovyparser.ParseResult
import com.github.albertocavalcante.groovyparser.Problem
import com.github.albertocavalcante.groovyparser.ProblemSeverity
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.body.TypeDeclaration
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import com.github.albertocavalcante.groovyparser.Range as CoreRange

/**
 * Adapter that wraps groovyparser-core's [ParseResult] into the unified [ParseUnit] interface.
 *
 * This adapter translates the JavaParser-style AST ([CompilationUnit]) into the
 * parser-agnostic types that LSP features consume.
 */
class CoreParserAdapter(private val result: ParseResult<CompilationUnit>, override val uri: String) : ParseUnit {

    override val isSuccessful: Boolean = result.isSuccessful

    override val diagnostics: List<Diagnostic> = result.problems.map { it.toLspDiagnostic() }

    override fun nodeAt(position: Position): UnifiedNode? {
        val cu = result.result.orElse(null) ?: return null
        // Core AST uses 1-based positions, LSP uses 0-based
        val targetLine = position.line + 1
        val targetCol = position.character + 1

        // Walk the AST to find the most specific node containing the position
        return findNodeAt(cu, targetLine, targetCol)?.toUnifiedNode()
    }

    override fun allSymbols(): List<UnifiedSymbol> {
        val cu = result.result.orElse(null) ?: return emptyList()
        return cu.types.map { it.toUnifiedSymbol() }
    }

    /**
     * Find the most specific node at the given position.
     * Uses a simple recursive descent through the AST.
     *
     * Note: Some synthetic nodes (e.g., script's run() method, CompilationUnit) may not have
     * ranges, so we continue searching their children if the node has no range or doesn't
     * contain the position.
     */
    private fun findNodeAt(node: Node, line: Int, col: Int): Node? {
        val range = node.range

        // If this node has a range and doesn't contain the position, skip it and its children
        if (range != null && !rangeContains(range, line, col)) {
            return null
        }

        // Try to find a more specific child node
        for (child in node.getChildNodes()) {
            val found = findNodeAt(child, line, col)
            if (found != null) return found
        }

        // If we have a range, this node contains the position
        // Return it unless we found a child (handled above)
        return if (range != null) node else null
    }

    private fun rangeContains(range: CoreRange, line: Int, col: Int): Boolean {
        if (line < range.begin.line || line > range.end.line) return false
        if (line == range.begin.line && col < range.begin.column) return false
        if (line == range.end.line && col > range.end.column) return false
        return true
    }
}

/**
 * Extension to convert groovyparser-core Problem to LSP Diagnostic.
 */
private fun Problem.toLspDiagnostic(): Diagnostic {
    val severity = when (this.severity) {
        ProblemSeverity.ERROR -> DiagnosticSeverity.Error
        ProblemSeverity.WARNING -> DiagnosticSeverity.Warning
        ProblemSeverity.INFO -> DiagnosticSeverity.Information
        ProblemSeverity.HINT -> DiagnosticSeverity.Hint
    }

    val lspRange = run {
        val problemRange = range
        val problemPosition = position
        when {
            problemRange != null -> Range(
                Position(problemRange.begin.line - 1, problemRange.begin.column - 1),
                Position(problemRange.end.line - 1, problemRange.end.column),
            )

            problemPosition != null -> {
                val pos = Position(problemPosition.line - 1, problemPosition.column - 1)
                Range(pos, pos)
            }

            else -> Range(Position(0, 0), Position(0, 0))
        }
    }

    return Diagnostic(lspRange, message, severity, "groovyparser-core")
}

/**
 * Extension to convert groovyparser-core Node to UnifiedNode.
 */
private fun Node.toUnifiedNode(): UnifiedNode = UnifiedNode(
    name = extractName(),
    kind = extractKind(),
    type = extractType(),
    documentation = comment?.content,
    range = range?.toLspRange(),
    originalNode = this,
)

private fun Node.extractName(): String? = when (this) {
    is ClassDeclaration -> name
    is MethodDeclaration -> name
    is ConstructorDeclaration -> name
    is FieldDeclaration -> name
    is Parameter -> name
    else -> null
}

private fun Node.extractKind(): UnifiedNodeKind = when (this) {
    is ClassDeclaration -> when {
        isInterface -> UnifiedNodeKind.INTERFACE
        isEnum -> UnifiedNodeKind.ENUM
        // TODO: Add isTrait when ClassDeclaration supports it
        else -> UnifiedNodeKind.CLASS
    }

    is MethodDeclaration -> UnifiedNodeKind.METHOD
    is ConstructorDeclaration -> UnifiedNodeKind.CONSTRUCTOR
    is FieldDeclaration -> UnifiedNodeKind.FIELD
    is Parameter -> UnifiedNodeKind.PARAMETER
    else -> UnifiedNodeKind.OTHER
}

private fun Node.extractType(): String? = when (this) {
    is ClassDeclaration -> name
    is MethodDeclaration -> returnType
    is FieldDeclaration -> type
    is Parameter -> type
    else -> null
}

private fun CoreRange.toLspRange(): Range = Range(
    Position(begin.line - 1, begin.column - 1),
    Position(end.line - 1, end.column),
)

/**
 * Extension to convert TypeDeclaration to UnifiedSymbol with children.
 */
private fun TypeDeclaration.toUnifiedSymbol(): UnifiedSymbol {
    val children = mutableListOf<UnifiedSymbol>()

    if (this is ClassDeclaration) {
        // Add fields
        for (field in fields) {
            field.range?.toLspRange()?.let { range ->
                children.add(
                    UnifiedSymbol(
                        name = field.name,
                        kind = UnifiedNodeKind.FIELD,
                        range = range,
                        selectionRange = range,
                    ),
                )
            }
        }

        // Add methods
        for (method in methods) {
            method.range?.toLspRange()?.let { range ->
                children.add(
                    UnifiedSymbol(
                        name = method.name,
                        kind = UnifiedNodeKind.METHOD,
                        range = range,
                        selectionRange = range,
                    ),
                )
            }
        }

        // Add constructors
        for (constructor in constructors) {
            constructor.range?.toLspRange()?.let { range ->
                children.add(
                    UnifiedSymbol(
                        name = name, // Use class name for constructor display
                        kind = UnifiedNodeKind.CONSTRUCTOR,
                        range = range,
                        selectionRange = range,
                    ),
                )
            }
        }
    }

    val typeRange = range?.toLspRange() ?: Range(Position(0, 0), Position(0, 0))
    return UnifiedSymbol(
        name = name,
        kind = when {
            this is ClassDeclaration && isInterface -> UnifiedNodeKind.INTERFACE
            this is ClassDeclaration && isEnum -> UnifiedNodeKind.ENUM
            // TODO: Add isTrait when ClassDeclaration supports it
            else -> UnifiedNodeKind.CLASS
        },
        range = typeRange,
        selectionRange = typeRange,
        children = children,
    )
}
