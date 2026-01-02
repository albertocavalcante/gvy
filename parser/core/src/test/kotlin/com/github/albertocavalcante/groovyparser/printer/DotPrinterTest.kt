package com.github.albertocavalcante.groovyparser.printer

import com.github.albertocavalcante.groovyparser.GroovyParser
import kotlin.test.Test
import kotlin.test.assertTrue

class DotPrinterTest {

    @Test
    fun `prints simple class as DOT`() {
        val code = "class Foo {}"
        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val dot = DotPrinter().print(result.result.get())

        assertTrue(dot.contains("digraph AST"))
        assertTrue(dot.contains("rankdir=TB"))
        assertTrue(dot.contains("CompilationUnit"))
        assertTrue(dot.contains("ClassDeclaration"))
        assertTrue(dot.contains("name: Foo"))
        assertTrue(dot.contains("->")) // edges
    }

    @Test
    fun `prints class with methods`() {
        val code = """
            class Foo {
                def greet() {
                    println "Hello"
                }
            }
        """.trimIndent()

        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val dot = DotPrinter().print(result.result.get())

        assertTrue(dot.contains("MethodDeclaration"))
        assertTrue(dot.contains("name: greet"))
    }

    @Test
    fun `custom graph name`() {
        val code = "class Foo {}"
        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val printer = DotPrinter()
        printer.graphName = "MyAST"
        val dot = printer.print(result.result.get())

        assertTrue(dot.contains("digraph MyAST"))
    }

    @Test
    fun `non-record nodes`() {
        val code = "class Foo {}"
        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val printer = DotPrinter()
        printer.useRecordNodes = false
        val dot = printer.print(result.result.get())

        assertTrue(dot.contains("shape=box"))
        assertTrue(dot.contains("style=rounded"))
    }

    @Test
    fun `record nodes by default`() {
        val code = "class Foo {}"
        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val dot = DotPrinter().print(result.result.get())

        assertTrue(dot.contains("shape=record"))
    }

    @Test
    fun `prints method call details`() {
        val code = """
            class Foo {
                def test() {
                    println "Hello"
                }
            }
        """.trimIndent()

        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val dot = DotPrinter().print(result.result.get())

        assertTrue(dot.contains("MethodCallExpr"))
        assertTrue(dot.contains("method: println"))
    }

    @Test
    fun `prints binary expression details`() {
        val code = """
            class Foo {
                def test() {
                    def x = 1 + 2
                }
            }
        """.trimIndent()

        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val dot = DotPrinter().print(result.result.get())

        assertTrue(dot.contains("BinaryExpr"))
    }

    @Test
    fun `creates valid DOT format`() {
        val code = """
            class Foo {
                String name
                int count
                
                def greet(String msg) {
                    println msg
                    return msg
                }
            }
        """.trimIndent()

        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val dot = DotPrinter().print(result.result.get())

        // Check proper DOT structure
        assertTrue(dot.startsWith("digraph"))
        assertTrue(dot.endsWith("}\n"))
        assertTrue(dot.contains("{"))
        assertTrue(dot.contains("}"))

        // Should have multiple nodes and edges
        val nodeCount = dot.split("[label=").size - 1
        val edgeCount = dot.split("->").size - 1
        assertTrue(nodeCount > 5)
        assertTrue(edgeCount > 5)
    }
}
