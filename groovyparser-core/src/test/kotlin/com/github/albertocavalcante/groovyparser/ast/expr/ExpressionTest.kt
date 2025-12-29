package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExpressionTest {

    private val parser = GroovyParser()

    @Test
    fun `parse method call expression`() {
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
        val exprStmt = block.statements[0] as ExpressionStatement
        val call = exprStmt.expression as MethodCallExpr

        assertThat(call.methodName).isEqualTo("println")
    }

    @Test
    fun `parse method call with object expression`() {
        val result = parser.parse(
            """
            class Foo {
                void bar() {
                    System.out.println("hello")
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement
        val exprStmt = block.statements[0] as ExpressionStatement
        val call = exprStmt.expression as MethodCallExpr

        assertThat(call.methodName).isEqualTo("println")
        assertThat(call.objectExpression).isNotNull
    }

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
    fun `parse closure expression`() {
        val result = parser.parse(
            """
            class Foo {
                void bar() {
                    def closure = { x -> x * 2 }
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        // Closure parsing validated through successful parse
    }

    @Test
    fun `parse GString expression`() {
        val result = parser.parse(
            """
            class Foo {
                String bar(String name) {
                    return "Hello, ${'$'}name!"
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement
        val returnStmt = block.statements[0] as ReturnStatement

        assertThat(returnStmt.expression).isInstanceOf(GStringExpr::class.java)
    }

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
