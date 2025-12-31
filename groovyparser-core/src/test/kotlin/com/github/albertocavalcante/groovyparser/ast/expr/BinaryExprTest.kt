package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BinaryExprTest {

    private val parser = GroovyParser()

    @Test
    fun `parse binary expression`() {
        val result = parser.parse(
            """
            class Foo {
                int bar(int x, int y) {
                    return x + y
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement
        val returnStmt = block.statements[0] as ReturnStatement
        val binary = returnStmt.expression as BinaryExpr

        assertThat(binary.operator).isEqualTo("+")
        assertThat(binary.left).isInstanceOf(VariableExpr::class.java)
        assertThat(binary.right).isInstanceOf(VariableExpr::class.java)
    }

    @Test
    fun `parse binary expression - logical AND`() {
        val result = parser.parse(
            """
            class Foo {
                boolean bar(boolean a, boolean b) {
                    return a && b
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement
        val returnStmt = block.statements[0] as ReturnStatement
        val binary = returnStmt.expression as BinaryExpr

        assertThat(binary.operator).isEqualTo("&&")
    }
}
