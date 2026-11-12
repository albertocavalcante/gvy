// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.net.URI
import java.time.Duration

/**
 * The AST model's parent links are walked to find scopes and the enclosing class. A cycle in them, however it
 * arose, must end the walk instead of hanging the request (#1411).
 */
class SymbolResolverParentCycleTest {
    private val parser = ParserTestFixture()

    /**
     * Delegates to [real], except that [from]'s parent chain enters a two-node loop of non-scope nodes, and
     * [uris] answers for nodes the real model never saw.
     */
    private class CyclicParents(
        private val real: GroovyAstModel,
        private val from: ASTNode,
        private val uris: Map<ASTNode, URI> = emptyMap(),
    ) : GroovyAstModel by real {
        private val first = ConstantExpression("loop-1")
        private val second = ConstantExpression("loop-2")

        override fun getParent(node: ASTNode): ASTNode? = when {
            node === from || node === second -> first
            node === first -> second
            else -> real.getParent(node)
        }

        override fun getUri(node: ASTNode): URI? = uris[node] ?: real.getUri(node)
    }

    private fun resolvesInTime(block: () -> Unit) = assertTimeoutPreemptively(TIMEOUT, block)

    @Test
    fun `the scope chain of a reference ends on a parent cycle`() {
        val result = parser.parse(DUPLICATED_PARAMS)
        val reference = result.astModel.getAllNodes().filterIsInstance<VariableExpression>()
            .first { it.name == "param" }

        resolvesInTime { result.symbolTable.resolveSymbol(reference, CyclicParents(result.astModel, reference)) }
    }

    @Test
    fun `the enclosing scope of a candidate declaration ends on a parent cycle`() {
        val result = parser.parse(DUPLICATED_PARAMS)
        val model = result.astModel
        val reference = model.getAllNodes().filterIsInstance<VariableExpression>().first { it.name == "param" }
        val candidate = model.getAllNodes().filterIsInstance<Parameter>().first { it.name == "param" }

        resolvesInTime { result.symbolTable.resolveSymbol(reference, CyclicParents(model, candidate)) }
    }

    @Test
    fun `the enclosing class of a field reference ends on a parent cycle`() {
        val result = parser.parse("class Sample {}\n")
        val uri = URI.create("file:///Test.groovy")
        // A reference named like a class declared in the file takes the field fallback (findFieldInScope).
        val reference = VariableExpression("Sample")

        val model = CyclicParents(result.astModel, reference, mapOf(reference to uri))

        resolvesInTime { result.symbolTable.resolveSymbol(reference, model) }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(20)

        /** Two candidate declarations of `param`, so resolution compares their scopes with the reference's. */
        val DUPLICATED_PARAMS = """
            class Sample {
                def method1(param) {
                    println param
                }
                def method2(param) {
                    println param
                }
            }
        """.trimIndent()
    }
}
