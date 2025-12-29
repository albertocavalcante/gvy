package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StatementTest {

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
