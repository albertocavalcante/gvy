package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BlockStatementTest {

    private val parser = GroovyParser()

    @Test
    fun `parse method with block statement`() {
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

        assertThat(method.body).isNotNull
        assertThat(method.body).isInstanceOf(BlockStatement::class.java)
    }
}
