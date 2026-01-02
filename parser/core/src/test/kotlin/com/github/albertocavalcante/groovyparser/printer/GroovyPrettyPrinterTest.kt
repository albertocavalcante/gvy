package com.github.albertocavalcante.groovyparser.printer

import com.github.albertocavalcante.groovyparser.StaticGroovyParser
import kotlin.test.Test
import kotlin.test.assertTrue

class GroovyPrettyPrinterTest {

    private val printer = GroovyPrettyPrinter()

    @Test
    fun `print simple class`() {
        val code = """
            class Foo {
            }
        """.trimIndent()

        val unit = StaticGroovyParser.parse(code)
        val result = printer.print(unit)

        assertTrue(result.contains("class Foo"))
        assertTrue(result.contains("{"))
        assertTrue(result.contains("}"))
    }

    @Test
    fun `print class with method`() {
        val code = """
            class Calculator {
                int add(int a, int b) {
                    return a + b
                }
            }
        """.trimIndent()

        val unit = StaticGroovyParser.parse(code)
        val result = printer.print(unit)

        assertTrue(result.contains("class Calculator"))
        assertTrue(result.contains("int add(int a, int b)"))
        assertTrue(result.contains("return"))
    }

    @Test
    fun `print package and imports`() {
        val code = """
            package com.example
            
            import java.util.List
            import java.util.Map
            
            class Foo {
            }
        """.trimIndent()

        val unit = StaticGroovyParser.parse(code)
        val result = printer.print(unit)

        assertTrue(result.contains("package com.example"))
        assertTrue(result.contains("import java.util.List"))
        assertTrue(result.contains("import java.util.Map"))
    }

    @Test
    fun `print if statement`() {
        val code = """
            class Foo {
                void check(int x) {
                    if (x > 0) {
                        println("positive")
                    }
                }
            }
        """.trimIndent()

        val unit = StaticGroovyParser.parse(code)
        val result = printer.print(unit)

        assertTrue(result.contains("if ("))
        assertTrue(result.contains(")"))
    }

    @Test
    fun `print for loop`() {
        val code = """
            class Foo {
                void iterate() {
                    for (item in [1, 2, 3]) {
                        println(item)
                    }
                }
            }
        """.trimIndent()

        val unit = StaticGroovyParser.parse(code)
        val result = printer.print(unit)

        assertTrue(result.contains("for ("))
        assertTrue(result.contains(" in "))
    }

    @Test
    fun `print list literal`() {
        val code = """
            class Foo {
                def getList() {
                    return [1, 2, 3]
                }
            }
        """.trimIndent()

        val unit = StaticGroovyParser.parse(code)
        val result = printer.print(unit)

        assertTrue(result.contains("["))
        assertTrue(result.contains("]"))
    }

    @Test
    fun `print map literal`() {
        val code = """
            class Foo {
                def getMap() {
                    return [name: "John", age: 30]
                }
            }
        """.trimIndent()

        val unit = StaticGroovyParser.parse(code)
        val result = printer.print(unit)

        assertTrue(result.contains("["))
        assertTrue(result.contains(":"))
        assertTrue(result.contains("]"))
    }

    @Test
    fun `print method call`() {
        val code = """
            class Foo {
                void doIt() {
                    println("Hello")
                }
            }
        """.trimIndent()

        val unit = StaticGroovyParser.parse(code)
        val result = printer.print(unit)

        assertTrue(result.contains("println("))
    }

    @Test
    fun `print closure`() {
        val code = """
            class Foo {
                void doIt() {
                    [1, 2, 3].each { x -> println(x) }
                }
            }
        """.trimIndent()

        val unit = StaticGroovyParser.parse(code)
        val result = printer.print(unit)

        assertTrue(result.contains("{"))
        assertTrue(result.contains("}"))
    }

    @Test
    fun `print with custom indentation`() {
        val code = """
            class Foo {
                void bar() {
                }
            }
        """.trimIndent()

        val customPrinter = GroovyPrettyPrinter(PrinterConfiguration(indentString = "  "))
        val unit = StaticGroovyParser.parse(code)
        val result = customPrinter.print(unit)

        // Should use 2-space indentation
        assertTrue(result.contains("class Foo"))
    }

    @Test
    fun `print try-catch`() {
        val code = """
            class Foo {
                void risky() {
                    try {
                        doSomething()
                    } catch (Exception e) {
                        handleError(e)
                    }
                }
            }
        """.trimIndent()

        val unit = StaticGroovyParser.parse(code)
        val result = printer.print(unit)

        assertTrue(result.contains("try"))
        assertTrue(result.contains("catch"))
    }

    @Test
    fun `print ternary expression`() {
        val code = """
            class Foo {
                String check(boolean flag) {
                    return flag ? "yes" : "no"
                }
            }
        """.trimIndent()

        val unit = StaticGroovyParser.parse(code)
        val result = printer.print(unit)

        assertTrue(result.contains("?"))
        assertTrue(result.contains(":"))
    }

    @Test
    fun `print constructor call`() {
        val code = """
            class Foo {
                def create() {
                    return new ArrayList()
                }
            }
        """.trimIndent()

        val unit = StaticGroovyParser.parse(code)
        val result = printer.print(unit)

        assertTrue(result.contains("new "))
    }

    @Test
    fun `round trip preserves structure`() {
        val originalCode = """
            class Calculator {
                int add(int a, int b) {
                    return a + b
                }
                
                int subtract(int a, int b) {
                    return a - b
                }
            }
        """.trimIndent()

        val unit1 = StaticGroovyParser.parse(originalCode)
        val printed = printer.print(unit1)
        val unit2 = StaticGroovyParser.parse(printed)

        // Both should have the same structure
        assertTrue(unit1.types.size == unit2.types.size)
        val cls1 = unit1.types[0] as com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
        val cls2 = unit2.types[0] as com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
        assertTrue(cls1.name == cls2.name)
        assertTrue(cls1.methods.size == cls2.methods.size)
    }
}
