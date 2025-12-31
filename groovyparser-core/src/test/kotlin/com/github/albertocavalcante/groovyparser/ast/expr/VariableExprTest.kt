package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VariableExprTest {

    private val parser = GroovyParser()

    @Test
    fun `parse variable expression`() {
        val result = parser.parse(
            """
            class Foo {
                int bar(int x) {
                    return x
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement
        val returnStmt = block.statements[0] as ReturnStatement
        val variable = returnStmt.expression as VariableExpr

        assertThat(variable.name).isEqualTo("x")
    }
}
