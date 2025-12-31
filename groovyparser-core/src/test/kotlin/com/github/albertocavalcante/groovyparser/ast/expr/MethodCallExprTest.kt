package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MethodCallExprTest {

    private val parser = GroovyParser()

    @Test
    fun `parse method call expression`() {
        val result = parser.parse(
            """
            class Foo {
                void bar() {
                    println "hello"
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement
        val exprStmt = block.statements[0] as ExpressionStatement
        val call = exprStmt.expression as MethodCallExpr

        assertThat(call.methodName).isEqualTo("println")
    }

    @Test
    fun `parse method call with object expression`() {
        val result = parser.parse(
            """
            class Foo {
                void bar() {
                    System.out.println("hello")
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement
        val exprStmt = block.statements[0] as ExpressionStatement
        val call = exprStmt.expression as MethodCallExpr

        assertThat(call.methodName).isEqualTo("println")
        assertThat(call.objectExpression).isNotNull
    }
}
