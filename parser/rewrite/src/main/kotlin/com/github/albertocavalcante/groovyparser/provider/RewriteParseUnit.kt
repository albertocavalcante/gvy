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
import org.openrewrite.Cursor
import org.openrewrite.Tree
import org.openrewrite.groovy.GroovyVisitor
import org.openrewrite.groovy.tree.G
import org.openrewrite.java.tree.J
import java.nio.file.Path

/**
 * ParseUnit implementation wrapping OpenRewrite's G.CompilationUnit.
 * Available ranges use 1-based lines and UTF-16 columns with exclusive ends.
 */
class RewriteParseUnit(
    override val source: String,
    override val path: Path?,
    private val compilationUnit: G.CompilationUnit?,
    private val parseError: String? = null,
) : ParseUnit {

    override val isSuccessful: Boolean = compilationUnit != null && parseError == null
    private val sourceLines by lazy { source.lines() }
    private val sourcePositions by lazy {
        compilationUnit?.let { RewriteSourcePositions.create(source, it) }
    }

    override fun nodeAt(position: Position): NodeInfo? {
        if (compilationUnit == null) return null
        if (position.line <= 0 || position.column <= 0) return null
        val line = sourceLines.getOrNull(position.line - 1) ?: return null
        if (position.column > line.length + 1) return null

        val positions = sourcePositions ?: return null
        return NodeAtPositionFinder(position, positions).find(compilationUnit)
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
        return SymbolExtractorVisitor(sourcePositions).extract(compilationUnit)
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
private class NodeAtPositionFinder(private val target: Position, private val positions: RewriteSourcePositions) {
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
        val range = positions.range(tree) ?: return
        if (target >= range.start && target < range.end) {
            // The visitor descends into children after checking their parent.
            foundNode = NodeInfo(kind = kind, name = name, range = range)
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
private class SymbolExtractorVisitor(private val positions: RewriteSourcePositions?) {
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
                        containerName = cu.packageDeclaration?.expression?.printTrimmed(
                            Cursor(
                                null,
                                cu,
                            ),
                        )?.removeSuffix("."),
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

    private fun extractRange(tree: Tree): Range = positions?.range(tree) ?: Range.EMPTY
}
