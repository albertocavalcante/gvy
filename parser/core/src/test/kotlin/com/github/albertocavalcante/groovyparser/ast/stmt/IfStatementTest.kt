package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IfStatementTest {

    private val parser = GroovyParser()

    @Test
    fun `parse method with if statement`() {
        val result = parser.parse(
            """
            class Foo {
                void bar(boolean flag) {
                    if (flag) {
                        println "yes"
                    }
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement

        val ifStmt = block.statements.filterIsInstance<IfStatement>().firstOrNull()
        assertThat(ifStmt).isNotNull
        assertThat(ifStmt!!.condition).isInstanceOf(VariableExpr::class.java)
    }

    @Test
    fun `parse method with if-else statement`() {
        val result = parser.parse(
            """
            class Foo {
                void bar(boolean flag) {
                    if (flag) {
                        println "yes"
                    } else {
                        println "no"
                    }
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement

        val ifStmt = block.statements.filterIsInstance<IfStatement>().firstOrNull()
        assertThat(ifStmt).isNotNull
        assertThat(ifStmt!!.elseStatement).isNotNull
    }
}
