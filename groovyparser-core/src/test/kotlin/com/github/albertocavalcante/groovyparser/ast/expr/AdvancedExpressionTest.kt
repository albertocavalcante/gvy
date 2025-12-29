package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.StaticGroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdvancedExpressionTest {

    @Test
    fun `parse list expression`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                def bar() {
                    return [1, 2, 3]
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement
        val returnStmt = block.statements[0] as ReturnStatement
        val listExpr = returnStmt.expression as ListExpr

        assertThat(listExpr.elements).hasSize(3)
    }

    @Test
    fun `parse map expression`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                def bar() {
                    return [name: "John", age: 30]
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement
        val returnStmt = block.statements[0] as ReturnStatement
        val mapExpr = returnStmt.expression as MapExpr

        assertThat(mapExpr.entries).hasSize(2)
    }

    @Test
    fun `parse range expression`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                def bar() {
                    return 1..10
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement
        val returnStmt = block.statements[0] as ReturnStatement
        val rangeExpr = returnStmt.expression as RangeExpr

        assertThat(rangeExpr.inclusive).isTrue()
    }

    @Test
    fun `parse ternary expression`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                def bar(boolean flag) {
                    return flag ? "yes" : "no"
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement
        val returnStmt = block.statements[0] as ReturnStatement
        val ternaryExpr = returnStmt.expression as TernaryExpr

        assertThat(ternaryExpr.condition).isNotNull
        assertThat(ternaryExpr.trueExpression).isNotNull
        assertThat(ternaryExpr.falseExpression).isNotNull
    }

    @Test
    fun `parse unary expressions`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                void bar() {
                    def x = 5
                    x++
                    ++x
                    -x
                    !true
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement

        // Should have multiple statements with unary expressions
        assertThat(block.statements.size).isGreaterThan(1)
    }

    @Test
    fun `parse cast expression`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                void bar(Object obj) {
                    def str = (String) obj
                    def num = obj as Integer
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement

        assertThat(block.statements).hasSize(2)
    }

    @Test
    fun `parse constructor call expression`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                void bar() {
                    def list = new ArrayList()
                    def map = new HashMap(16)
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement

        assertThat(block.statements).hasSize(2)
        val stmt1 = block.statements[0] as ExpressionStatement
        val binary1 = stmt1.expression as BinaryExpr
        val constructorCall = binary1.right as ConstructorCallExpr
        assertThat(constructorCall.typeName).contains("ArrayList")
    }
}
