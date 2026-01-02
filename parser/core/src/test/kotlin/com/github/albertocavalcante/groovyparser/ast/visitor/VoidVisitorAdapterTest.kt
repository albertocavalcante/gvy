package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.StaticGroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VoidVisitorAdapterTest {

    @Test
    fun `collect all method names`() {
        val unit = StaticGroovyParser.parse(
            """
            class Calculator {
                int add(int a, int b) { return a + b }
                int subtract(int a, int b) { return a - b }
                int multiply(int a, int b) { return a * b }
            }
            """.trimIndent(),
        )

        val methodNames = mutableListOf<String>()
        val collector = object : VoidVisitorAdapter<MutableList<String>>() {
            override fun visit(n: MethodDeclaration, arg: MutableList<String>) {
                arg.add(n.name)
                super.visit(n, arg)
            }
        }

        collector.visit(unit, methodNames)

        assertThat(methodNames).containsExactly("add", "subtract", "multiply")
    }

    @Test
    fun `collect all method calls`() {
        val unit = StaticGroovyParser.parse(
            """
            class Pipeline {
                void run() {
                    echo "Starting"
                    stage("Build") {
                        sh "mvn clean install"
                    }
                    echo "Done"
                }
            }
            """.trimIndent(),
        )

        val methodCalls = mutableListOf<String>()
        val collector = object : VoidVisitorAdapter<MutableList<String>>() {
            override fun visit(n: MethodCallExpr, arg: MutableList<String>) {
                arg.add(n.methodName)
                super.visit(n, arg)
            }
        }

        collector.visit(unit, methodCalls)

        assertThat(methodCalls).contains("echo", "stage", "sh")
    }

    @Test
    fun `collect all variable references`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                int calculate(int x, int y) {
                    def z = x + y
                    return z * x
                }
            }
            """.trimIndent(),
        )

        val variables = mutableSetOf<String>()
        val collector = object : VoidVisitorAdapter<MutableSet<String>>() {
            override fun visit(n: VariableExpr, arg: MutableSet<String>) {
                arg.add(n.name)
                super.visit(n, arg)
            }
        }

        collector.visit(unit, variables)

        assertThat(variables).contains("x", "y", "z")
    }

    @Test
    fun `visitor traverses nested structures`() {
        val unit = StaticGroovyParser.parse(
            """
            class Nested {
                void outer() {
                    if (true) {
                        for (i in 1..10) {
                            while (i < 5) {
                                println i
                            }
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        var nodeCount = 0
        val counter = object : VoidVisitorAdapter<Unit>() {
            override fun visit(n: com.github.albertocavalcante.groovyparser.ast.stmt.IfStatement, arg: Unit) {
                nodeCount++
                super.visit(n, arg)
            }
            override fun visit(n: com.github.albertocavalcante.groovyparser.ast.stmt.ForStatement, arg: Unit) {
                nodeCount++
                super.visit(n, arg)
            }
            override fun visit(n: com.github.albertocavalcante.groovyparser.ast.stmt.WhileStatement, arg: Unit) {
                nodeCount++
                super.visit(n, arg)
            }
        }

        counter.visit(unit, Unit)

        assertThat(nodeCount).isEqualTo(3) // if, for, while
    }

    @Test
    fun `visitor can be used for validation`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                void bar() {
                    Thread.sleep(1000)
                }
            }
            """.trimIndent(),
        )

        val issues = mutableListOf<String>()
        val validator = object : VoidVisitorAdapter<MutableList<String>>() {
            override fun visit(n: MethodCallExpr, arg: MutableList<String>) {
                if (n.methodName == "sleep") {
                    val obj = n.objectExpression
                    if (obj is VariableExpr && obj.name == "Thread") {
                        arg.add("Thread.sleep() detected at ${n.range}")
                    }
                }
                super.visit(n, arg)
            }
        }

        validator.visit(unit, issues)

        assertThat(issues).hasSize(1)
        assertThat(issues[0]).contains("Thread.sleep()")
    }
}
