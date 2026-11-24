// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.types.Position
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.EmptyStatement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.net.URI
import java.time.Duration
import kotlin.test.assertNull
import kotlin.test.assertSame

class SuperMethodResolutionTest {
    /** Only the parent links matter to `resolveSuperMethod`. */
    private class ParentOnlyModel(private val parents: Map<ASTNode, ASTNode>) : GroovyAstModel {
        override fun getParent(node: ASTNode): ASTNode? = parents[node]

        override fun getChildren(node: ASTNode): List<ASTNode> = emptyList()

        override fun getUri(node: ASTNode): URI? = null

        override fun getNodes(uri: URI): List<ASTNode> = emptyList()

        override fun getAllNodes(): List<ASTNode> = emptyList()

        override fun getAllClassNodes(): List<ClassNode> = emptyList()

        override fun getNodeAt(uri: URI, position: Position): ASTNode? = null

        override fun getNodeAt(uri: URI, line: Int, character: Int): ASTNode? = null

        override fun contains(ancestor: ASTNode, descendant: ASTNode): Boolean = false
    }

    private fun method(owner: ClassNode, name: String) =
        MethodNode(name, 1, ClassHelper.VOID_TYPE, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, EmptyStatement())
            .also { owner.addMethod(it) }

    private fun superCall(name: String) =
        MethodCallExpression(VariableExpression.SUPER_EXPRESSION, name, ArgumentListExpression.EMPTY_ARGUMENTS)

    private fun resolve(enclosing: ClassNode, call: MethodCallExpression, candidates: List<MethodNode>) =
        assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS)) {
            resolveSuperMethod(call, candidates, ParentOnlyModel(mapOf(call to enclosing)))
        }

    /** `class A extends B {}; class B extends A {}`, as Groovy leaves it after reporting "Cycle detected". */
    private fun cycle(): Pair<ClassNode, ClassNode> {
        val a = ClassNode("A", 1, ClassHelper.OBJECT_TYPE)
        val b = ClassNode("B", 1, a).also { a.superClass = it }
        return a to b
    }

    @Test
    fun `super on a cycle does not resolve to the overriding method itself`() {
        val (a, _) = cycle()
        val own = method(a, "m")

        assertNull(resolve(a, superCall("m"), listOf(own)))
    }

    @Test
    fun `super on a cycle still finds the method of the other class`() {
        val (a, b) = cycle()
        method(a, "m")
        val inherited = method(b, "m")

        assertSame(inherited, resolve(a, superCall("m"), listOf(a.methods.single(), inherited)))
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
