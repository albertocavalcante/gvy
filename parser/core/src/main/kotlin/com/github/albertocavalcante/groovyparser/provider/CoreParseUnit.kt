// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.provider

import com.github.albertocavalcante.groovyparser.ParseResult
import com.github.albertocavalcante.groovyparser.ProblemSeverity
import com.github.albertocavalcante.groovyparser.api.ParseUnit
import com.github.albertocavalcante.groovyparser.api.model.Diagnostic
import com.github.albertocavalcante.groovyparser.api.model.NodeInfo
import com.github.albertocavalcante.groovyparser.api.model.NodeKind
import com.github.albertocavalcante.groovyparser.api.model.Position
import com.github.albertocavalcante.groovyparser.api.model.Range
import com.github.albertocavalcante.groovyparser.api.model.Severity
import com.github.albertocavalcante.groovyparser.api.model.SymbolInfo
import com.github.albertocavalcante.groovyparser.api.model.SymbolKind
import com.github.albertocavalcante.groovyparser.api.model.TypeInfo
import com.github.albertocavalcante.groovyparser.ast.AnnotationExpr
import com.github.albertocavalcante.groovyparser.ast.Comment
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.ImportDeclaration
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.PackageDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClosureExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ListExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PropertyExpr
import com.github.albertocavalcante.groovyparser.ast.expr.RangeExpr
import com.github.albertocavalcante.groovyparser.ast.expr.UnaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import com.github.albertocavalcante.groovyparser.ast.stmt.AssertStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ForStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.IfStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.SwitchStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ThrowStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.TryCatchStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.WhileStatement
import java.nio.file.Path

/**
 * Public adapter over parser-produced core nodes. Their source spans retain Groovy's
 * 1-based, exclusive end positions; lookup and text extraction use those half-open spans.
 * Query columns remain native parser coordinates, without LSP UTF-16 normalization.
 * Native supplementary-character columns have mixed units: lookup and text stay null
 * where a line prefix makes conversion ambiguous, rather than returning a wrong slice.
 * Preserved comments use source-scanner rows, translated to native lookup rows here.
 */
class CoreParseUnit(
    override val source: String,
    override val path: Path?,
    private val result: ParseResult<CompilationUnit>,
) : ParseUnit {

    override val isSuccessful: Boolean = result.isSuccessful

    private val lineStartOffsets by lazy {
        buildList {
            add(0)
            source.forEachIndexed { index, character ->
                if (character == '\n') {
                    add(index + 1)
                }
            }
        }
    }

    private val commentLineStartOffsets by lazy {
        buildList {
            add(0)
            source.forEachIndexed { index, character ->
                if (character == '\n' || (character == '\r' && source.getOrNull(index + 1) != '\n')) {
                    add(index + 1)
                }
            }
        }
    }

    override fun nodeAt(position: Position): NodeInfo? {
        val unit = result.result.orElse(null) ?: return null
        if (sourceOffset(position.line, position.column) == null) return null

        val node = findNodeAt(unit, position) ?: return null

        return NodeInfo(
            kind = mapNodeKind(node),
            name = extractNodeName(node),
            range = extractRange(node),
            text = extractNodeText(node),
        )
    }

    /** Metadata can precede its owner or an ancestor, so owner ranges cannot prune these visits. */
    private fun findNodeAt(node: Node, position: Position): Node? {
        val range = extractRange(node)
        return node.annotations.firstNotNullOfOrNull { findNodeAt(it, position) }
            ?: node.comment?.let { findNodeAt(it, position) }
            ?: node.orphanComments.firstNotNullOfOrNull { findNodeAt(it, position) }
            ?: node.getChildNodes().firstNotNullOfOrNull { findNodeAt(it, position) }
            ?: node.takeIf { position >= range.start && position < range.end }
    }

    override fun diagnostics(): List<Diagnostic> = result.problems.map { problem ->
        Diagnostic(
            severity = mapSeverity(problem.severity),
            message = problem.message,
            range = problem.range?.let { r ->
                Range(
                    start = Position(r.begin.line, r.begin.column),
                    end = Position(r.end.line, r.end.column),
                )
            } ?: Range.EMPTY,
            source = "groovy-parser-core",
        )
    }

    override fun symbols(): List<SymbolInfo> {
        val unit = result.result.orElse(null) ?: return emptyList()

        return unit.types.flatMap { type ->
            when (type) {
                is ClassDeclaration -> buildList {
                    add(
                        SymbolInfo(
                            name = type.name,
                            kind = if (type.isInterface) SymbolKind.INTERFACE else SymbolKind.CLASS,
                            range = extractRange(type),
                            containerName = unit.packageDeclaration.orElse(null)?.name,
                        ),
                    )
                    type.methods.mapTo(this) { method ->
                        SymbolInfo(
                            name = method.name,
                            kind = SymbolKind.METHOD,
                            range = extractRange(method),
                            containerName = type.name,
                        )
                    }
                    type.fields.mapTo(this) { field ->
                        SymbolInfo(
                            name = field.name,
                            kind = SymbolKind.FIELD,
                            range = extractRange(field),
                            containerName = type.name,
                        )
                    }
                }

                else -> emptyList()
            }
        }
    }

    override fun typeAt(position: Position): TypeInfo? {
        // Internal core resolution is not exposed by this public adapter yet.
        // CoreParserProvider advertises this limitation through supportsSymbolResolution.
        return null
    }

    private fun mapSeverity(severity: ProblemSeverity): Severity = when (severity) {
        ProblemSeverity.ERROR -> Severity.ERROR
        ProblemSeverity.WARNING -> Severity.WARNING
        ProblemSeverity.INFO -> Severity.INFO
        ProblemSeverity.HINT -> Severity.HINT
    }

    private fun extractRange(node: Node): Range {
        val range = node.range ?: return Range.EMPTY
        if (node is Comment) {
            // The source scanner advances rows at bare CR, unlike Groovy's native AST.
            val start = rawSourceOffset(range.begin.line, range.begin.column, commentLineStartOffsets)
                ?: return Range.EMPTY
            val end = rawSourceOffset(range.end.line, range.end.column, commentLineStartOffsets)
                ?: return Range.EMPTY
            return Range(sourcePosition(start), sourcePosition(end))
        }
        return Range(
            start = Position(range.begin.line, range.begin.column),
            end = Position(range.end.line, range.end.column),
        )
    }

    private fun mapNodeKind(node: Node): NodeKind = when (node) {
        is ClassDeclaration -> {
            if (node.isInterface) NodeKind.INTERFACE else NodeKind.CLASS
        }
        is MethodDeclaration -> NodeKind.METHOD
        is ConstructorDeclaration -> NodeKind.CONSTRUCTOR
        is FieldDeclaration -> NodeKind.FIELD
        is Parameter -> NodeKind.PARAMETER
        is VariableExpr -> NodeKind.VARIABLE_REFERENCE
        is MethodCallExpr -> NodeKind.METHOD_CALL
        is PropertyExpr -> NodeKind.PROPERTY_ACCESS
        is ClosureExpr -> NodeKind.CLOSURE
        is ListExpr -> NodeKind.LIST
        is MapExpr -> NodeKind.MAP
        is RangeExpr -> NodeKind.RANGE
        is BinaryExpr -> NodeKind.BINARY_EXPRESSION
        is UnaryExpr -> NodeKind.UNARY_EXPRESSION
        is ConstantExpr -> NodeKind.LITERAL
        is IfStatement -> NodeKind.IF
        is ForStatement -> NodeKind.FOR
        is WhileStatement -> NodeKind.WHILE
        is SwitchStatement -> NodeKind.SWITCH
        is TryCatchStatement -> NodeKind.TRY
        is ReturnStatement -> NodeKind.RETURN
        is ThrowStatement -> NodeKind.THROW
        is AssertStatement -> NodeKind.ASSERT
        is BlockStatement -> NodeKind.BLOCK
        is ImportDeclaration -> NodeKind.IMPORT
        is PackageDeclaration -> NodeKind.PACKAGE
        is AnnotationExpr -> NodeKind.ANNOTATION
        is Comment -> NodeKind.COMMENT
        else -> NodeKind.UNKNOWN
    }

    private fun extractNodeName(node: Node): String? = when (node) {
        is ClassDeclaration -> node.name
        is ConstructorDeclaration -> node.name
        is MethodDeclaration -> node.name
        is FieldDeclaration -> node.name
        is Parameter -> node.name
        is VariableExpr -> node.name
        is MethodCallExpr -> node.methodName
        is PropertyExpr -> node.propertyName
        is PackageDeclaration -> node.name
        is AnnotationExpr -> node.name
        else -> null
    }

    private fun extractNodeText(node: Node): String? {
        val range = extractRange(node)
        val start = sourceOffset(range.start.line, range.start.column) ?: return null
        val end = sourceOffset(range.end.line, range.end.column) ?: return null
        return if (start < end) source.substring(start, end) else null
    }

    private fun sourceOffset(line: Int, column: Int): Int? {
        val offset = rawSourceOffset(line, column, lineStartOffsets) ?: return null
        val start = lineStartOffsets[line - 1]
        // TODO(#1190): Normalize supplementary-character coordinate units across parser providers.
        //   See: https://github.com/albertocavalcante/gvy/issues/1190
        return offset.takeUnless { (start until offset).any { source[it].isSurrogate() } }
    }

    private fun rawSourceOffset(line: Int, column: Int, starts: List<Int>): Int? {
        if (line < 1 || column < 1) return null
        val start = starts.getOrNull(line - 1) ?: return null
        val nextLineStart = starts.getOrNull(line)
        var end = nextLineStart ?: source.length
        if (nextLineStart != null && end > start && source[end - 1] == '\n') end--
        if (nextLineStart != null && end > start && source[end - 1] == '\r') end--
        if (column > end - start + 1) return null
        return start + column - 1
    }

    private fun sourcePosition(offset: Int): Position {
        val search = lineStartOffsets.binarySearch(offset)
        val line = if (search >= 0) search else -search - 2
        return Position(line + 1, offset - lineStartOffsets[line] + 1)
    }
}
