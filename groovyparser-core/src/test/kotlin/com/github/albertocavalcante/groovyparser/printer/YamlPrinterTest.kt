package com.github.albertocavalcante.groovyparser.printer

import com.github.albertocavalcante.groovyparser.GroovyParser
import kotlin.test.Test
import kotlin.test.assertTrue

class YamlPrinterTest {

    @Test
    fun `prints simple class as YAML`() {
        val code = "class Foo {}"
        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val yaml = YamlPrinter().print(result.result.get())

        assertTrue(yaml.contains("type: CompilationUnit"))
        assertTrue(yaml.contains("type: ClassDeclaration"))
        assertTrue(yaml.contains("name: Foo"))
    }

    @Test
    fun `prints class with fields and methods`() {
        val code = """
            class Person {
                String name
                int age
                
                def greet() {
                    println "Hello"
                }
            }
        """.trimIndent()

        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val yaml = YamlPrinter().print(result.result.get())

        assertTrue(yaml.contains("fields:"))
        assertTrue(yaml.contains("methods:"))
        assertTrue(yaml.contains("type: MethodDeclaration"))
        assertTrue(yaml.contains("name: greet"))
    }

    @Test
    fun `prints package and imports`() {
        val code = """
            package com.example
            
            import java.util.List
            
            class Foo {}
        """.trimIndent()

        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val yaml = YamlPrinter().print(result.result.get())

        assertTrue(yaml.contains("package: com.example"))
        assertTrue(yaml.contains("imports:"))
    }

    @Test
    fun `prints method parameters`() {
        val code = """
            class Foo {
                def add(int a, int b) {
                    return a + b
                }
            }
        """.trimIndent()

        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val yaml = YamlPrinter().print(result.result.get())

        assertTrue(yaml.contains("parameters:"))
        assertTrue(yaml.contains("name: a"))
        assertTrue(yaml.contains("name: b"))
    }

    @Test
    fun `includes range when enabled`() {
        val code = "class Foo {}"
        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val printer = YamlPrinter()
        printer.includeRanges = true
        val yaml = printer.print(result.result.get())

        assertTrue(yaml.contains("range:"))
        assertTrue(yaml.contains("begin:"))
        assertTrue(yaml.contains("end:"))
    }

    @Test
    fun `prints interface`() {
        val code = "interface MyInterface {}"
        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val yaml = YamlPrinter().print(result.result.get())

        assertTrue(yaml.contains("interface: true"))
    }

    @Test
    fun `prints inheritance`() {
        val code = """
            class Child extends Parent implements Foo, Bar {}
        """.trimIndent()

        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val yaml = YamlPrinter().print(result.result.get())

        assertTrue(yaml.contains("extends:"))
        assertTrue(yaml.contains("implements:"))
    }

    @Test
    fun `prints method body`() {
        val code = """
            class Foo {
                def test() {
                    def x = 1
                    return x
                }
            }
        """.trimIndent()

        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val yaml = YamlPrinter().print(result.result.get())

        assertTrue(yaml.contains("body:"))
        assertTrue(yaml.contains("type: BlockStatement"))
    }
}
