package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExpressionStatementTest {

    private val parser = GroovyParser()

    @Test
    fun `parse method with expression statement`() {
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

        val exprStmt = block.statements.filterIsInstance<ExpressionStatement>().firstOrNull()
        assertThat(exprStmt).isNotNull
        assertThat(exprStmt!!.expression).isInstanceOf(MethodCallExpr::class.java)
    }
}
