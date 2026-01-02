package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WhileStatementTest {

    private val parser = GroovyParser()

    @Test
    fun `parse method with while loop`() {
        val result = parser.parse(
            """
            class Foo {
                void bar() {
                    int i = 0
                    while (i < 10) {
                        i++
                    }
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement

        val whileStmt = block.statements.filterIsInstance<WhileStatement>().firstOrNull()
        assertThat(whileStmt).isNotNull
    }
}
