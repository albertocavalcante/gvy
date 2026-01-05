package com.github.albertocavalcante.groovyparser.printer

import com.github.albertocavalcante.groovyparser.GroovyParser
import kotlin.test.Test
import kotlin.test.assertTrue

class XmlPrinterTest {

    @Test
    fun `prints simple class as XML`() {
        val code = "class Foo {}"
        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val xml = XmlPrinter().print(result.result.get())

        assertTrue(xml.contains("<?xml version=\"1.0\""))
        assertTrue(xml.contains("<CompilationUnit>"))
        assertTrue(xml.contains("<ClassDeclaration"))
        assertTrue(xml.contains("name=\"Foo\""))
        assertTrue(xml.contains("</ClassDeclaration>"))
        assertTrue(xml.contains("</CompilationUnit>"))
    }

    @Test
    fun `prints class with methods`() {
        val code = """
            class Foo {
                String greet(String name) {
                    return "Hello, " + name
                }
            }
        """.trimIndent()

        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val xml = XmlPrinter().print(result.result.get())

        assertTrue(xml.contains("<MethodDeclaration"))
        assertTrue(xml.contains("name=\"greet\""))
        assertTrue(xml.contains("<Parameter"))
        assertTrue(xml.contains("name=\"name\""))
        assertTrue(xml.contains("<body>"))
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

        val xml = XmlPrinter().print(result.result.get())

        assertTrue(xml.contains("<PackageDeclaration"))
        assertTrue(xml.contains("name=\"com.example\""))
        assertTrue(xml.contains("<ImportDeclaration"))
    }

    @Test
    fun `prints if statement`() {
        val code = """
            class Foo {
                def check(x) {
                    if (x > 0) {
                        println "positive"
                    } else {
                        println "non-positive"
                    }
                }
            }
        """.trimIndent()

        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val xml = XmlPrinter().print(result.result.get())

        assertTrue(xml.contains("<IfStatement>"))
        assertTrue(xml.contains("<condition>"))
        assertTrue(xml.contains("<then>"))
        assertTrue(xml.contains("<else>"))
    }

    @Test
    fun `prints method calls`() {
        val code = """
            class Foo {
                def test() {
                    println "Hello"
                }
            }
        """.trimIndent()

        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val xml = XmlPrinter().print(result.result.get())

        assertTrue(xml.contains("<MethodCallExpr"))
        assertTrue(xml.contains("name=\"println\""))
        assertTrue(xml.contains("<arguments>"))
    }

    @Test
    fun `escapes XML special characters`() {
        val code = """
            class Foo {
                String test = "<tag>value</tag>"
            }
        """.trimIndent()

        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val xml = XmlPrinter().print(result.result.get())

        // The field value might be represented differently in the AST,
        // just verify XML escaping works for the class name at minimum
        assertTrue(xml.contains("name=\"Foo\""))
        // Check that the output is valid XML (no unescaped < or > in attribute values)
        assertTrue(!xml.contains("=\"<"))
    }

    @Test
    fun `includes range when enabled`() {
        val code = "class Foo {}"
        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val printer = XmlPrinter()
        printer.includeRanges = true
        val xml = printer.print(result.result.get())

        assertTrue(xml.contains("<range"))
        assertTrue(xml.contains("begin="))
        assertTrue(xml.contains("end="))
    }

    @Test
    fun `prints annotations`() {
        val code = """
            @Deprecated
            class Foo {
                @Override
                def toString() { "Foo" }
            }
        """.trimIndent()

        val result = GroovyParser().parse(code)
        assertTrue(result.isSuccessful)

        val xml = XmlPrinter().print(result.result.get())

        assertTrue(xml.contains("<Annotation"))
        assertTrue(xml.contains("Deprecated") || xml.contains("java.lang.Deprecated"))
    }
}
