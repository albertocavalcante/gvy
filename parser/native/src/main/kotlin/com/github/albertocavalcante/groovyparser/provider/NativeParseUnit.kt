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
import java.net.URI
import java.nio.file.Path
import com.github.albertocavalcante.groovyparser.api.ParseResult as NativeParseResult

class NativeParseUnit(override val source: String, override val path: Path?, private val result: NativeParseResult) :
    ParseUnit {

    override val isSuccessful: Boolean = result.isSuccessful

    override fun nodeAt(position: Position): NodeInfo? {
        // Convert 1-based Position to 0-based for native parser
        val nativePos = com.github.albertocavalcante.groovyparser.ast.types.Position(
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
        val symbols = mutableListOf<SymbolInfo>()
        val ast = result.ast ?: return symbols

        // Extract classes
        ast.classes.forEach { classNode ->
            symbols.add(
                SymbolInfo(
                    name = classNode.nameWithoutPackage,
                    kind = SymbolKind.CLASS,
                    range = extractNodeRange(classNode),
                    containerName = ast.packageName,
                ),
            )

            // Extract methods
            classNode.methods.forEach { method ->
                symbols.add(
                    SymbolInfo(
                        name = method.name,
                        kind = SymbolKind.METHOD,
                        range = extractNodeRange(method),
                        containerName = classNode.nameWithoutPackage,
                        detail = method.typeDescriptor,
                    ),
                )
            }

            // Extract fields
            classNode.fields.forEach { field ->
                symbols.add(
                    SymbolInfo(
                        name = field.name,
                        kind = SymbolKind.FIELD,
                        range = extractNodeRange(field),
                        containerName = classNode.nameWithoutPackage,
                    ),
                )
            }
        }

        return symbols
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

    private fun mapNodeKind(node: org.codehaus.groovy.ast.ASTNode): NodeKind = when (node) {
        is org.codehaus.groovy.ast.ClassNode -> NodeKind.CLASS
        is org.codehaus.groovy.ast.MethodNode -> NodeKind.METHOD
        is org.codehaus.groovy.ast.FieldNode -> NodeKind.FIELD
        is org.codehaus.groovy.ast.PropertyNode -> NodeKind.PROPERTY
        is org.codehaus.groovy.ast.Parameter -> NodeKind.PARAMETER
        is org.codehaus.groovy.ast.expr.VariableExpression -> NodeKind.VARIABLE_REFERENCE
        is org.codehaus.groovy.ast.expr.MethodCallExpression -> NodeKind.METHOD_CALL
        is org.codehaus.groovy.ast.expr.ClosureExpression -> NodeKind.CLOSURE
        is org.codehaus.groovy.ast.stmt.IfStatement -> NodeKind.IF
        is org.codehaus.groovy.ast.stmt.ForStatement -> NodeKind.FOR
        is org.codehaus.groovy.ast.stmt.WhileStatement -> NodeKind.WHILE
        is org.codehaus.groovy.ast.stmt.ReturnStatement -> NodeKind.RETURN
        else -> NodeKind.UNKNOWN
    }

    private fun extractNodeName(node: org.codehaus.groovy.ast.ASTNode): String? = when (node) {
        is org.codehaus.groovy.ast.ClassNode -> node.nameWithoutPackage
        is org.codehaus.groovy.ast.MethodNode -> node.name
        is org.codehaus.groovy.ast.FieldNode -> node.name
        is org.codehaus.groovy.ast.PropertyNode -> node.name
        is org.codehaus.groovy.ast.Parameter -> node.name
        is org.codehaus.groovy.ast.expr.VariableExpression -> node.name
        else -> null
    }

    private fun extractNodeRange(node: org.codehaus.groovy.ast.ASTNode): Range = Range(
        start = Position(node.lineNumber.coerceAtLeast(1), node.columnNumber.coerceAtLeast(1)),
        end = Position(node.lastLineNumber.coerceAtLeast(1), node.lastColumnNumber.coerceAtLeast(1)),
    )
}
