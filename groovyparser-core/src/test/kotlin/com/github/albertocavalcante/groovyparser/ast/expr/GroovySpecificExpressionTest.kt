package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.StaticGroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
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
        val body = method.body
        assertNotNull(body)

        // Elvis is converted to TernaryExpr by Groovy, or handled as ElvisExpr
        // The exact representation depends on how Groovy's parser handles it
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
        assertNotNull(method.body)
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
        assertNotNull(method.body)
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
        assertNotNull(method.body)
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
        assertNotNull(method.body)
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
        val body = method.body
        assertNotNull(body)

        // The return statement contains a UnaryExpr with "!" operator
        // (since NotExpression is converted to UnaryExpr in the converter)
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
        assertNotNull(method.body)
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
        assertNotNull(method.body)
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
        assertEquals(2, unit.types.size)
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
        assertNotNull(method.body)
    }
}
