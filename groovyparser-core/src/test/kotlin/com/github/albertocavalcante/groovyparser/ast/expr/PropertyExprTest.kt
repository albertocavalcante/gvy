package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PropertyExprTest {

    private val parser = GroovyParser()

    @Test
    fun `parse property access expression`() {
        val result = parser.parse(
            """
            class Foo {
                int bar(String s) {
                    return s.length
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement
        val returnStmt = block.statements[0] as ReturnStatement

        assertThat(returnStmt.expression).isInstanceOf(PropertyExpr::class.java)
        val prop = returnStmt.expression as PropertyExpr
        assertThat(prop.propertyName).isEqualTo("length")
    }
}
