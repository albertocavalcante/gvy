package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConstantExprTest {

    private val parser = GroovyParser()

    @Test
    fun `parse constant expression - integer`() {
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
        val returnStmt = block.statements[0] as ReturnStatement
        val constant = returnStmt.expression as ConstantExpr

        assertThat(constant.value).isEqualTo(42)
    }

    @Test
    fun `parse constant expression - string`() {
        val result = parser.parse(
            """
            class Foo {
                String bar() {
                    return "hello"
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement
        val returnStmt = block.statements[0] as ReturnStatement
        val constant = returnStmt.expression as ConstantExpr

        assertThat(constant.value).isEqualTo("hello")
    }
}
