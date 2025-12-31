package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.StaticGroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.DeclarationExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.UnaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class GroovySpecificExpressionTest {

    @Test
    fun `parse elvis expression`() {
        val code = """
            class Foo {
                String getName(String input) {
                    return input ?: "default"
                }
            }
        """

        val unit = StaticGroovyParser.parse(code)
        val clazz = unit.types[0] as ClassDeclaration
        val method = clazz.methods[0]
        val body = method.body as BlockStatement
        val returnStmt = body.statements[0] as ReturnStatement

        // Elvis is converted to ElvisExpr
        assertIs<ElvisExpr>(returnStmt.expression)
    }

    @Test
    fun `parse spread expression in method call`() {
        val code = """
            class Foo {
                void doIt() {
                    def list = [1, 2, 3]
                    println(*list)
                }
            }
        """

        val unit = StaticGroovyParser.parse(code)
        val clazz = unit.types[0] as ClassDeclaration
        val method = clazz.methods[0]
        val body = method.body as BlockStatement
        val exprStmt = body.statements[1] as ExpressionStatement // index 1 is println call
        val call = exprStmt.expression as MethodCallExpr
        val arg = call.arguments[0]

        assertIs<SpreadExpr>(arg)
    }

    @Test
    fun `parse method pointer expression`() {
        val code = """
            class Foo {
                void doIt() {
                    def printer = System.out.&println
                }
            }
        """

        val unit = StaticGroovyParser.parse(code)
        val clazz = unit.types[0] as ClassDeclaration
        val method = clazz.methods[0]
        val body = method.body as BlockStatement
        val declStmt =
            body.statements[0] as ExpressionStatement // generic declaration is expr stmt
        // Declaration handling might vary, checking expression
        val declExpr = declStmt.expression as DeclarationExpr
        // DeclarationExpr holds the right side directly
        val init = declExpr.rightExpression

        assertIs<MethodPointerExpr>(init)
    }

    @Test
    fun `parse prefix and postfix expressions`() {
        val code = """
            class Foo {
                void doIt() {
                    def i = 0
                    i++
                    ++i
                    i--
                    --i
                }
            }
        """

        val unit = StaticGroovyParser.parse(code)
        val clazz = unit.types[0] as ClassDeclaration
        val method = clazz.methods[0]
        val body = method.body as BlockStatement

        // i++ -> Postfix
        val s2 = body.statements[1] as ExpressionStatement
        assertIs<UnaryExpr>(s2.expression)
        assertEquals("++", s2.expression.operator)
        assertEquals(false, s2.expression.isPrefix)

        // ++i -> Prefix
        val s3 = body.statements[2] as ExpressionStatement
        assertIs<UnaryExpr>(s3.expression)
        assertEquals("++", s3.expression.operator)
        assertEquals(true, s3.expression.isPrefix)
    }

    @Test
    fun `parse bitwise negation expression`() {
        val code = """
            class Foo {
                int invert(int x) {
                    return ~x
                }
            }
        """

        val unit = StaticGroovyParser.parse(code)
        val clazz = unit.types[0] as ClassDeclaration
        val method = clazz.methods[0]
        val body = method.body as BlockStatement
        val ret = body.statements[0] as ReturnStatement
        assertIs<BitwiseNegationExpr>(ret.expression)
    }

    @Test
    fun `parse not expression`() {
        val code = """
            class Foo {
                boolean negate(boolean x) {
                    return !x
                }
            }
        """

        val unit = StaticGroovyParser.parse(code)
        val clazz = unit.types[0] as ClassDeclaration
        val method = clazz.methods[0]
        val body = method.body as BlockStatement
        val ret = body.statements[0] as ReturnStatement

        // Converted to UnaryExpr with !
        // Converted to UnaryExpr with !
        assertIs<UnaryExpr>(ret.expression)
        assertEquals("!", ret.expression.operator)
    }

    @Test
    fun `parse class expression`() {
        val code = """
            class Foo {
                Class getType() {
                    return String
                }
            }
        """

        val unit = StaticGroovyParser.parse(code)
        val clazz = unit.types[0] as ClassDeclaration
        val method = clazz.methods[0]
        val body = method.body as BlockStatement
        val ret = body.statements[0] as ReturnStatement
        // String as a value might be parsed as VariableExpr (if not resolved) or ClassExpr (if resolved/known)
        val expr = ret.expression
        if (expr is VariableExpr) {
            assertEquals("String", expr.name)
        } else {
            assertIs<ClassExpr>(expr)
        }
    }

    @Test
    fun `parse array expression`() {
        val code = """
            class Foo {
                int[] getArray() {
                    return new int[5]
                }
            }
        """

        val unit = StaticGroovyParser.parse(code)
        val clazz = unit.types[0] as ClassDeclaration
        val method = clazz.methods[0]
        val body = method.body as BlockStatement
        val ret = body.statements[0] as ReturnStatement
        assertIs<ArrayExpr>(ret.expression)
    }

    @Test
    fun `parse attribute access expression`() {
        val code = """
            class Person {
                String name
            }
            class Foo {
                void doIt(Person p) {
                    def x = p.@name
                }
            }
        """

        val unit = StaticGroovyParser.parse(code)
        val clazz = unit.types[1] as ClassDeclaration // Foo
        val method = clazz.methods[0]
        val body = method.body as BlockStatement
        val stmt = body.statements[0] as ExpressionStatement
        val decl = stmt.expression

        // p.@name is AttributeExpr
        // DeclarationExpr -> rightExpression is the AttributeExpr
        val expr = decl
        val assign = if (expr is DeclarationExpr) {
            expr.rightExpression
        } else {
            (expr as BinaryExpr).right
        }

        if (assign !is AttributeExpr) {
            println("Actual type of assign: ${assign::class.java.name}")
            println("Actual type of decl: ${decl::class.java.name}")
        }
        assertIs<AttributeExpr>(assign)
    }

    @Test
    fun `parse declaration expression`() {
        val code = """
            class Foo {
                void doIt() {
                    def x = 42
                    String name = "test"
                }
            }
        """

        val unit = StaticGroovyParser.parse(code)
        val clazz = unit.types[0] as ClassDeclaration
        val method = clazz.methods[0]
        val body = method.body as BlockStatement

        assertIs<DeclarationExpr>((body.statements[0] as ExpressionStatement).expression)
        assertIs<DeclarationExpr>((body.statements[1] as ExpressionStatement).expression)
    }
}
