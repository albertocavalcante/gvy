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

class CoreParseUnit(
    override val source: String,
    override val path: Path?,
    private val result: ParseResult<CompilationUnit>,
) : ParseUnit {

    override val isSuccessful: Boolean = result.isSuccessful

    private val lineCache by lazy { source.lines() }

    override fun nodeAt(position: Position): NodeInfo? {
        val unit = result.result.orElse(null) ?: return null

        // Convert API position (1-based) to core Position (1-based)
        val corePosition = com.github.albertocavalcante.groovyparser.Position(position.line, position.column)

        // Create a range for the position (single point)
        val targetRange = com.github.albertocavalcante.groovyparser.Range(corePosition, corePosition)

        // Find the deepest node containing this position
        val node = unit.findByRange(targetRange).orElse(null) ?: return null

        return NodeInfo(
            kind = mapNodeKind(node),
            name = extractNodeName(node),
            range = extractRange(node),
            text = extractNodeText(node),
        )
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
        // TODO(#552): Implement type resolution using GroovySymbolResolver
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
        is MethodDeclaration -> node.name
        is FieldDeclaration -> node.name
        is Parameter -> node.name
        is VariableExpr -> node.name
        is MethodCallExpr -> node.methodName
        is PropertyExpr -> node.propertyName
        is PackageDeclaration -> node.name
        else -> null
    }

    private fun extractNodeText(node: Node): String? {
        val range = node.range ?: return null
        // Extract text from source using the node's range
        val lines = lineCache
        // Validate line numbers are within bounds (1-based line numbers)
        if (lines.isEmpty() ||
            range.begin.line < 1 || range.begin.line > lines.size ||
            range.end.line < 1 || range.end.line > lines.size
        ) {
            return null
        }
        return if (range.begin.line == range.end.line) {
            // Single line
            val line = lines[range.begin.line - 1]
            val startCol = (range.begin.column - 1).coerceAtLeast(0)
            val endCol = (range.end.column - 1).coerceAtMost(line.length)
            if (startCol <= endCol && startCol < line.length) {
                line.substring(startCol, endCol)
            } else {
                null
            }
        } else {
            // Multi-line
            val result = StringBuilder()
            for (lineNum in range.begin.line..range.end.line) {
                val line = lines[lineNum - 1]
                when (lineNum) {
                    range.begin.line -> {
                        val startCol = (range.begin.column - 1).coerceAtLeast(0)
                        if (startCol < line.length) {
                            result.append(line.substring(startCol))
                        }
                    }
                    range.end.line -> {
                        val endCol = (range.end.column - 1).coerceAtMost(line.length)
                        if (endCol >= 0) {
                            result.append(line.substring(0, endCol))
                        }
                    }
                    else -> {
                        result.append(line)
                    }
                }
                if (lineNum < range.end.line) {
                    result.append("\n")
                }
            }
            result.toString()
        }
    }
}
