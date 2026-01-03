package com.github.albertocavalcante.groovyparser.provider

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
import org.openrewrite.Tree
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.groovy.tree.G
import org.openrewrite.java.tree.J
import java.nio.file.Path
import org.openrewrite.marker.Range as RewriteRange

/**
 * ParseUnit implementation wrapping OpenRewrite's G.CompilationUnit.
 */
class RewriteParseUnit(
    override val source: String,
    override val path: Path?,
    private val compilationUnit: G.CompilationUnit?,
    private val parseError: String? = null,
) : ParseUnit {

    override val isSuccessful: Boolean = compilationUnit != null && parseError == null

    override fun nodeAt(position: Position): NodeInfo? {
        if (compilationUnit == null) return null
        if (position.line <= 0 || position.column <= 0) return null

        // Convert 1-based to 0-based for OpenRewrite
        val targetLine = position.line - 1
        val targetColumn = position.column - 1

        return NodeAtPositionFinder(targetLine, targetColumn).find(compilationUnit)
    }

    override fun diagnostics(): List<Diagnostic> {
        // OpenRewrite doesn't produce partial parses with diagnostics
        // If parsing failed, we just have an empty compilation unit
        if (parseError != null) {
            return listOf(
                Diagnostic(
                    severity = Severity.ERROR,
                    message = parseError,
                    range = Range(Position.START, Position.START),
                    source = "rewrite-parser",
                ),
            )
        }
        return emptyList()
    }

    override fun symbols(): List<SymbolInfo> {
        if (compilationUnit == null) return emptyList()
        return SymbolExtractorVisitor().extract(compilationUnit)
    }

    override fun typeAt(position: Position): TypeInfo? {
        // Initial implementation - type resolution not exposed
        // OpenRewrite has JavaType attached to nodes, could be exposed in future
        return null
    }
}

/**
 * Visitor to find node at a specific position.
 */
private class NodeAtPositionFinder(private val targetLine: Int, private val targetColumn: Int) {
    private var foundNode: NodeInfo? = null

    fun find(cu: G.CompilationUnit): NodeInfo? {
        val visitor = object : GroovyVisitor<Unit>() {
            override fun visitClassDeclaration(classDecl: J.ClassDeclaration, p: Unit): J {
                checkNode(classDecl, classDecl.simpleName, toNodeKind(classDecl))
                return super.visitClassDeclaration(classDecl, p)
            }

            override fun visitMethodDeclaration(method: J.MethodDeclaration, p: Unit): J {
                checkNode(method, method.simpleName, NodeKind.METHOD)
                return super.visitMethodDeclaration(method, p)
            }

            override fun visitVariableDeclarations(multiVariable: J.VariableDeclarations, p: Unit): J {
                multiVariable.variables.forEach { variable ->
                    checkNode(variable, variable.simpleName, NodeKind.VARIABLE)
                }
                return super.visitVariableDeclarations(multiVariable, p)
            }

            override fun visitMethodInvocation(method: J.MethodInvocation, p: Unit): J {
                checkNode(method, method.name.simpleName, NodeKind.METHOD_CALL)
                return super.visitMethodInvocation(method, p)
            }

            override fun visitIdentifier(ident: J.Identifier, p: Unit): J {
                checkNode(ident, ident.simpleName, NodeKind.VARIABLE_REFERENCE)
                return super.visitIdentifier(ident, p)
            }
        }
        visitor.visit(cu, Unit)
        return foundNode
    }

    private fun checkNode(tree: Tree, name: String?, kind: NodeKind) {
        val range = tree.markers.findFirst(RewriteRange::class.java).orElse(null) ?: return
        val startLine = range.start.line
        val endLine = range.end.line
        val startCol = range.start.column
        val endCol = range.end.column

        // Check if position is within this node's range
        val inRange = when {
            targetLine < startLine -> false
            targetLine > endLine -> false
            targetLine == startLine && targetColumn < startCol -> false
            targetLine == endLine && targetColumn > endCol -> false
            else -> true
        }

        if (inRange) {
            // Keep the most specific (deepest) node
            foundNode = NodeInfo(
                kind = kind,
                name = name,
                range = Range(
                    start = Position(startLine + 1, startCol + 1),
                    end = Position(endLine + 1, endCol + 1),
                ),
            )
        }
    }

    private fun toNodeKind(classDecl: J.ClassDeclaration): NodeKind = when (classDecl.kind) {
        J.ClassDeclaration.Kind.Type.Interface -> NodeKind.INTERFACE
        J.ClassDeclaration.Kind.Type.Enum -> NodeKind.ENUM
        J.ClassDeclaration.Kind.Type.Annotation -> NodeKind.ANNOTATION
        else -> NodeKind.CLASS
    }
}

/**
 * Visitor to extract symbols from the compilation unit.
 */
private class SymbolExtractorVisitor {
    private val symbols = mutableListOf<SymbolInfo>()
    private var currentContainer: String? = null

    fun extract(cu: G.CompilationUnit): List<SymbolInfo> {
        val visitor = object : GroovyVisitor<Unit>() {
            override fun visitClassDeclaration(classDecl: J.ClassDeclaration, p: Unit): J {
                val range = extractRange(classDecl)
                val kind = when (classDecl.kind) {
                    J.ClassDeclaration.Kind.Type.Interface -> SymbolKind.INTERFACE
                    J.ClassDeclaration.Kind.Type.Enum -> SymbolKind.ENUM
                    J.ClassDeclaration.Kind.Type.Annotation -> SymbolKind.INTERFACE
                    else -> SymbolKind.CLASS
                }
                symbols.add(
                    SymbolInfo(
                        name = classDecl.simpleName,
                        kind = kind,
                        range = range,
                        containerName = cu.packageDeclaration?.expression?.toString()?.trim()?.removeSuffix("."),
                    ),
                )

                val previousContainer = currentContainer
                currentContainer = classDecl.simpleName
                val result = super.visitClassDeclaration(classDecl, p)
                currentContainer = previousContainer
                return result
            }

            override fun visitMethodDeclaration(method: J.MethodDeclaration, p: Unit): J {
                val range = extractRange(method)
                symbols.add(
                    SymbolInfo(
                        name = method.simpleName,
                        kind = SymbolKind.METHOD,
                        range = range,
                        containerName = currentContainer,
                        detail = method.returnTypeExpression?.toString(),
                    ),
                )
                return super.visitMethodDeclaration(method, p)
            }

            override fun visitVariableDeclarations(multiVariable: J.VariableDeclarations, p: Unit): J {
                multiVariable.variables.forEach { variable ->
                    val range = extractRange(variable)
                    symbols.add(
                        SymbolInfo(
                            name = variable.simpleName,
                            kind = SymbolKind.FIELD,
                            range = range,
                            containerName = currentContainer,
                        ),
                    )
                }
                return super.visitVariableDeclarations(multiVariable, p)
            }
        }
        visitor.visit(cu, Unit)
        return symbols
    }

    private fun extractRange(tree: Tree): Range {
        val marker = tree.markers.findFirst(RewriteRange::class.java).orElse(null)
        return if (marker != null) {
            Range(
                start = Position(marker.start.line + 1, marker.start.column + 1),
                end = Position(marker.end.line + 1, marker.end.column + 1),
            )
        } else {
            Range.EMPTY
        }
    }
}
