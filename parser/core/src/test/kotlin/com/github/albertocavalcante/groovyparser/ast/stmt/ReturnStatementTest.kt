package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReturnStatementTest {

    private val parser = GroovyParser()

    @Test
    fun `parse method with return statement`() {
        val result = parser.parse(
            """
            class Foo {
                int bar() {
                    return 42
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement

        assertThat(block.statements).isNotEmpty
        val returnStmt = block.statements.filterIsInstance<ReturnStatement>().firstOrNull()
        assertThat(returnStmt).isNotNull
        assertThat(returnStmt!!.expression).isInstanceOf(ConstantExpr::class.java)
    }
}
