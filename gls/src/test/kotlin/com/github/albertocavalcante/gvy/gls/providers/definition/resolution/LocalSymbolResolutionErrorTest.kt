// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Local resolution never reports a fatal [Error] as "symbol not found": a recursion bug must surface, not hide as a
 * missing definition (#1311). Since #1378 no hierarchy walk on this path recurses, so a cycle resolves normally.
 */
class LocalSymbolResolutionErrorTest {
    private val compilation = GroovyCompilationService()

    private fun compiled(uri: URI, source: String): Pair<GroovyAstModel, SymbolTable> {
        runBlocking { compilation.compile(uri, source) }
        return checkNotNull(compilation.getAstModel(uri)) to checkNotNull(compilation.getSymbolTable(uri))
    }

    private fun contextOf(node: ASTNode, uri: URI) =
        ResolutionContext(node, uri, Position(node.lineNumber - 1, node.columnNumber - 1))

    /** Delegates to a real model, but overflows when asked where [target] lives: a recursion bug in resolution. */
    private class OverflowingModel(private val real: GroovyAstModel, private val target: ASTNode) :
        GroovyAstModel by real {
        override fun getUri(node: ASTNode): URI? =
            if (node === target) throw StackOverflowError("simulated deep recursion") else real.getUri(node)
    }

    @Test
    fun `a stack overflow during local resolution propagates instead of reading as not found`() {
        val uri = URI.create("file:///test/Calls.groovy")
        val (model, symbols) = compiled(uri, "class Calls {\n    def m() { n() }\n    def n() {}\n}\n")
        val call = model.getAllNodes().filterIsInstance<MethodCallExpression>().first { it.methodAsString == "n" }
        val strategy = LocalSymbolResolutionStrategy(OverflowingModel(model, call), symbols)

        assertFailsWith<StackOverflowError> { runBlocking { strategy.resolve(contextOf(call, uri)) } }
        assertFailsWith<StackOverflowError>("the pipeline must not turn it into a result either") {
            runBlocking { SymbolResolutionStrategy.pipeline(strategy).resolve(contextOf(call, uri)) }
        }
    }

    @Test
    fun `local resolution on an inheritance cycle completes without a circular-reference result`() {
        val uri = URI.create("file:///test/Cycle.groovy")
        val source = """
            class A extends B {
                def x
                def m() {
                    B b = null
                    b.m()
                    this.x
                    super.toString()
                }
            }
            class B extends A {}
        """.trimIndent() + "\n"
        val (model, symbols) = compiled(uri, source)
        val strategy = LocalSymbolResolutionStrategy(model, symbols)
        val nodes = model.getAllNodes()
        val typedCall = nodes.filterIsInstance<MethodCallExpression>()
            .first { it.methodAsString == "m" && (it.objectExpression as? VariableExpression)?.name == "b" }
        val thisX = nodes.filterIsInstance<PropertyExpression>().first { it.propertyAsString == "x" }
        val superCall = nodes.filterIsInstance<MethodCallExpression>().first { it.methodAsString == "toString" }

        val targets = listOf<Expression>(typedCall, thisX, superCall)
        val timeout = Duration.ofSeconds(TIMEOUT_SECONDS)
        val results = assertTimeoutPreemptively<Map<Expression, ResolutionResult>>(timeout) {
            targets.associateWith { runBlocking { strategy.resolve(contextOf(it, uri)) } }
        }

        results.forEach { (node, result) ->
            result.onLeft { error ->
                assertFalse("CIRCULAR_REFERENCE" in error.reason, "${node.text} read as a circular reference")
            }
        }
        val found = assertNotNull(results.getValue(thisX).getOrNull(), "this.x must resolve on a cycle")
        val definition = (found as? DefinitionResolver.DefinitionResult.Source)?.node
        assertTrue(definition is FieldNode || definition is PropertyNode, "this.x resolved to $definition")
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
