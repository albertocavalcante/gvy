package com.github.albertocavalcante.groovyparser.provider

import com.github.albertocavalcante.groovyparser.api.ParseUnit
import com.github.albertocavalcante.groovyparser.api.ParserSeverity
import com.github.albertocavalcante.groovyparser.api.model.Diagnostic
import com.github.albertocavalcante.groovyparser.api.model.NodeInfo
import com.github.albertocavalcante.groovyparser.api.model.NodeKind
import com.github.albertocavalcante.groovyparser.api.model.Position
import com.github.albertocavalcante.groovyparser.api.model.Range
import com.github.albertocavalcante.groovyparser.api.model.Severity
import com.github.albertocavalcante.groovyparser.api.model.SymbolInfo
import com.github.albertocavalcante.groovyparser.api.model.SymbolKind
import com.github.albertocavalcante.groovyparser.api.model.TypeInfo
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PackageNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.WhileStatement
import java.net.URI
import java.nio.file.Path
import com.github.albertocavalcante.groovyparser.api.ParseResult as NativeParseResult
import com.github.albertocavalcante.groovyparser.ast.types.Position as NativePosition

class NativeParseUnit(override val source: String, override val path: Path?, private val result: NativeParseResult) :
    ParseUnit {

    override val isSuccessful: Boolean = result.isSuccessful

    override fun nodeAt(position: Position): NodeInfo? {
        if (position.line <= 0 || position.column <= 0) return null

        // Convert 1-based Position to 0-based for native parser
        val nativePos = NativePosition(
            position.line - 1,
            position.column - 1,
        )
        val uri = path?.toUri() ?: URI.create("file:///unnamed.groovy")
        val node = result.astModel.getNodeAt(uri, nativePos) ?: return null

        return NodeInfo(
            kind = mapNodeKind(node),
            name = extractNodeName(node),
            range = extractNodeRange(node),
        )
    }

    override fun diagnostics(): List<Diagnostic> = result.diagnostics.map { diag ->
        Diagnostic(
            severity = mapSeverity(diag.severity),
            message = diag.message,
            range = Range(
                start = Position(diag.range.start.line + 1, diag.range.start.character + 1),
                end = Position(diag.range.end.line + 1, diag.range.end.character + 1),
            ),
            source = diag.source,
            code = diag.code,
        )
    }

    override fun symbols(): List<SymbolInfo> {
        val ast = result.ast ?: return emptyList()

        return ast.classes.flatMap { classNode ->
            buildList {
                add(
                    SymbolInfo(
                        name = classNode.nameWithoutPackage,
                        kind = if (classNode.isInterface) SymbolKind.INTERFACE else SymbolKind.CLASS,
                        range = extractNodeRange(classNode),
                        containerName = ast.packageName?.removeSuffix("."),
                    ),
                )
                classNode.methods.mapTo(this) { method ->
                    SymbolInfo(
                        name = method.name,
                        kind = SymbolKind.METHOD,
                        range = extractNodeRange(method),
                        containerName = classNode.nameWithoutPackage,
                        detail = method.typeDescriptor,
                    )
                }
                classNode.fields.mapTo(this) { field ->
                    SymbolInfo(
                        name = field.name,
                        kind = SymbolKind.FIELD,
                        range = extractNodeRange(field),
                        containerName = classNode.nameWithoutPackage,
                    )
                }
            }
        }
    }

    override fun typeAt(position: Position): TypeInfo? {
        // TODO(#552): Implement type resolution using SymbolTable
        return null
    }

    private fun mapSeverity(severity: ParserSeverity): Severity = when (severity) {
        ParserSeverity.ERROR -> Severity.ERROR
        ParserSeverity.WARNING -> Severity.WARNING
        ParserSeverity.INFORMATION -> Severity.INFO
        ParserSeverity.HINT -> Severity.HINT
    }

    private val nodeKindMappings: List<Pair<Class<out ASTNode>, NodeKind>> = listOf(
        ClassNode::class.java to NodeKind.CLASS,
        MethodNode::class.java to NodeKind.METHOD,
        FieldNode::class.java to NodeKind.FIELD,
        PropertyNode::class.java to NodeKind.PROPERTY,
        Parameter::class.java to NodeKind.PARAMETER,
        VariableExpression::class.java to NodeKind.VARIABLE_REFERENCE,
        MethodCallExpression::class.java to NodeKind.METHOD_CALL,
        ClosureExpression::class.java to NodeKind.CLOSURE,
        IfStatement::class.java to NodeKind.IF,
        ForStatement::class.java to NodeKind.FOR,
        WhileStatement::class.java to NodeKind.WHILE,
        ReturnStatement::class.java to NodeKind.RETURN,
        PackageNode::class.java to NodeKind.PACKAGE,
    )

    private fun mapNodeKind(node: ASTNode): NodeKind = nodeKindMappings
        .firstOrNull { (nodeType, _) -> nodeType.isInstance(node) }
        ?.second
        ?: NodeKind.UNKNOWN

    private fun extractNodeName(node: ASTNode): String? = when (node) {
        is ClassNode -> node.nameWithoutPackage
        is MethodNode -> node.name
        is FieldNode -> node.name
        is PropertyNode -> node.name
        is Parameter -> node.name
        is VariableExpression -> node.name
        is PackageNode -> node.name.removeSuffix(".")
        else -> null
    }

    private fun extractNodeRange(node: ASTNode): Range {
        if (node.lineNumber == -1) return Range.EMPTY
        return Range(
            start = Position(node.lineNumber.coerceAtLeast(1), node.columnNumber.coerceAtLeast(1)),
            end = Position(node.lastLineNumber.coerceAtLeast(1), node.lastColumnNumber.coerceAtLeast(1)),
        )
    }
}
