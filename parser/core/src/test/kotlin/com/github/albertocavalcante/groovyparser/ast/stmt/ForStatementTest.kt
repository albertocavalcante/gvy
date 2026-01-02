package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForStatementTest {

    private val parser = GroovyParser()

    @Test
    fun `parse method with for loop`() {
        val result = parser.parse(
            """
            class Foo {
                void bar() {
                    for (i in 1..10) {
                        println i
                    }
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement

        val forStmt = block.statements.filterIsInstance<ForStatement>().firstOrNull()
        assertThat(forStmt).isNotNull
        assertThat(forStmt!!.variableName).isEqualTo("i")
    }
}
