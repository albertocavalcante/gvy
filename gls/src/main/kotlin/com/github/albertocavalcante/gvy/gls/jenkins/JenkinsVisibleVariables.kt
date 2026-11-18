// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.jenkins

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.semantics.native.VariableKind
import com.github.albertocavalcante.gvy.semantics.native.VariableSymbol
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.ast.tools.ClosureUtils
import org.eclipse.lsp4j.Position
import java.net.URI

/** Local variables visible at a use site, restricted to this document and its lexical scopes. */
object JenkinsVisibleVariables {
    fun find(
        model: GroovyAstModel,
        uri: URI,
        useSite: ASTNode,
        position: Position,
        sourceIndex: TextIndex,
    ): List<VariableSymbol> {
        if (model.getUri(useSite) != uri) return emptyList()
        val nativePosition = sourceIndex.codePointPosition(position) ?: return emptyList()
        return VisibilityQuery(model, uri, useSite, nativePosition).find()
    }

    private class VisibilityQuery(
        private val model: GroovyAstModel,
        uri: URI,
        useSite: ASTNode,
        private val position: Position,
    ) {
        private val nodes = model.getNodes(uri)
        private val ancestors = generateSequence(useSite) { model.getParent(it) }.toList()
        private val blocks = ancestors.filterIsInstance<BlockStatement>().toSet()
        private val found = linkedMapOf<String, ScopedVariable>()

        fun find(): List<VariableSymbol> {
            addParameters()
            addDeclarations()
            return found.values.map { it.symbol }
        }

        private fun addParameters() {
            ancestors.forEachIndexed { depth, scope ->
                when (scope) {
                    is ClosureExpression -> addClosureParameters(scope, depth)
                    is MethodNode -> scope.parameters.orEmpty().forEach { found.putParameter(it, depth) }
                }
            }
            // Catch nodes are not retained as parents by the native visitor; check their body ranges instead.
            nodes.filterIsInstance<CatchStatement>().filter { it.code.contains(position.line, position.character) }
                .forEach { found.putParameter(it.variable, depthOf(it.code)) }
            nodes.filterIsInstance<ForStatement>().filter { it.loopBlock.contains(position.line, position.character) }
                .forEach { found.putParameter(it.variable, depthOf(it.loopBlock)) }
        }

        private fun addClosureParameters(closure: ClosureExpression, depth: Int) {
            closure.parameters.orEmpty().forEach { found.putParameter(it, depth) }
            if (ClosureUtils.hasImplicitParameter(closure)) {
                found.putNearest(
                    VariableSymbol("it", "Object", VariableKind.PARAMETER, closure.lineNumber - 1),
                    depth,
                )
            }
        }

        private fun depthOf(node: ASTNode): Int = ancestors.indexOf(node).takeIf { it >= 0 } ?: 0

        private fun addDeclarations() {
            val declarations = nodes.filterIsInstance<DeclarationExpression>()
                .filter { it.before(position.line, position.character) }
                .sortedWith(
                    compareByDescending<DeclarationExpression> { it.lineNumber }.thenByDescending { it.columnNumber },
                )
            for (declaration in declarations) {
                val parents = generateSequence(model.getParent(declaration)) { model.getParent(it) }.toList()
                val block = parents.filterIsInstance<BlockStatement>().firstOrNull()
                if (block !in blocks && !rootIsVisible(block, parents)) continue
                declaration.variables().forEach { variable ->
                    val symbol = VariableSymbol(
                        variable.name,
                        variable.type.nameWithoutPackage,
                        VariableKind.LOCAL_VARIABLE,
                        variable.lineNumber - 1,
                    )
                    found.putNearest(symbol, if (block == null) ancestors.size else ancestors.indexOf(block))
                }
            }
        }

        private fun rootIsVisible(block: BlockStatement?, parents: List<ASTNode>): Boolean {
            if (block != null || parents.any(::preventsRootDeclaration)) return false
            if (parents.filterIsInstance<ClassNode>().any { !it.isScript }) return false
            if (insideExplicitMethod()) return false
            return !insideOrdinaryClass()
        }

        private fun insideExplicitMethod(): Boolean {
            val method = ancestors.filterIsInstance<MethodNode>().firstOrNull()
                ?: nodes.filterIsInstance<MethodNode>().firstOrNull {
                    it.isExplicitMethod() && it.contains(position.line, position.character)
                }
            return method?.isExplicitMethod() == true
        }

        private fun insideOrdinaryClass(): Boolean = ancestors.filterIsInstance<ClassNode>().any { !it.isScript } ||
            nodes.filterIsInstance<ClassNode>().any {
                !it.isScript && it.contains(position.line, position.character)
            }
    }

    private fun preventsRootDeclaration(node: ASTNode): Boolean = when (node) {
        is ClosureExpression, is MethodNode -> true
        is Statement -> node !is ExpressionStatement
        else -> false
    }

    private fun DeclarationExpression.variables(): List<VariableExpression> = when (val left = leftExpression) {
        is VariableExpression -> listOf(left)
        is TupleExpression -> left.expressions.filterIsInstance<VariableExpression>()
        else -> emptyList()
    }

    private data class ScopedVariable(val symbol: VariableSymbol, val depth: Int)

    private fun MutableMap<String, ScopedVariable>.putNearest(symbol: VariableSymbol, depth: Int) {
        val existing = this[symbol.name]
        if (existing == null || depth < existing.depth) this[symbol.name] = ScopedVariable(symbol, depth)
    }

    private fun MutableMap<String, ScopedVariable>.putParameter(parameter: Parameter, depth: Int) {
        if (parameter === ForStatement.FOR_LOOP_DUMMY) return
        val symbol = VariableSymbol(
            parameter.name,
            parameter.type.nameWithoutPackage,
            VariableKind.PARAMETER,
            parameter.lineNumber - 1,
        )
        putNearest(symbol, depth)
    }

    private fun MethodNode.isExplicitMethod(): Boolean {
        val isScriptRun = name == "run" && declaringClass?.isScript == true
        return !(isScriptRun && isSynthetic)
    }

    private fun ASTNode.before(line: Int, character: Int): Boolean {
        if (lineNumber < 1) return false
        val nodeLine = lineNumber - 1
        return nodeLine < line || (nodeLine == line && columnNumber - 1 < character)
    }

    private fun ASTNode.contains(line: Int, character: Int): Boolean {
        if (lineNumber < 1 || columnNumber < 1) return false
        if (lastLineNumber < 1 || lastColumnNumber < 1) return false
        val startLine = lineNumber - 1
        val endLine = lastLineNumber - 1
        if (line !in startLine..endLine || (line == startLine && character < columnNumber - 1)) return false
        return line != endLine || character < lastColumnNumber - 1
    }
}
