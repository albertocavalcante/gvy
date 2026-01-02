package com.github.albertocavalcante.groovylsp.documentation

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.stmt.BlockStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocExtractorTest {

    private fun lineOf(source: String, contains: String): Int {
        val idx = source.lines().indexOfFirst { it.contains(contains) }
        require(idx >= 0) { "Could not find line containing '$contains'" }
        return idx + 1 // Groovy AST is 1-based
    }

    @Test
    fun `extracts simple summary from groovydoc`() {
        val source = """
            package com.example
            
            /**
             * This is a simple class.
             */
            class SimpleClass {
            }
        """.trimIndent()

        // Create a dummy node to match the class name
        val node = ClassNode("SimpleClass", 0, null)
        node.lineNumber = lineOf(source, "class SimpleClass")
        val doc = DocExtractor.extractDocumentation(source, node)

        assertEquals("This is a simple class.", doc.summary)
        assertTrue(doc.isNotEmpty())
    }

    @Test
    fun `extracts method documentation with params and return`() {
        val source = """
            /**
             * Calculates the sum of two numbers.
             * This method adds a and b together.
             *
             * @param a the first number
             * @param b the second number
             * @return the sum of a and b
             */
            def add(int a, int b) {
                return a + b
            }
        """.trimIndent()

        // Create a dummy method node
        val params = arrayOf(
            Parameter(ClassHelper.int_TYPE, "a"),
            Parameter(ClassHelper.int_TYPE, "b"),
        )
        val classNode = ClassNode("Script", 0, null)
        val node = MethodNode("add", 0, ClassHelper.dynamicType(), params, null, BlockStatement())
        node.declaringClass = classNode
        node.lineNumber = lineOf(source, "def add")

        val doc = DocExtractor.extractDocumentation(source, node)

        assertEquals("Calculates the sum of two numbers", doc.summary)
        assertTrue(doc.description.contains("This method adds a and b together"))
        assertEquals(2, doc.params.size)
        assertEquals("the first number", doc.params["a"])
        assertEquals("the second number", doc.params["b"])
        assertEquals("the sum of a and b", doc.returnDoc)
    }

    @Test
    fun `extracts documentation with throws and deprecated`() {
        val source = """
            /**
             * Old method that throws exceptions.
             *
             * @param input the input string
             * @throws IllegalArgumentException if input is null
             * @throws IOException if I/O error occurs
             * @deprecated Use newMethod instead
             */
            void oldMethod(String input) {
            }
        """.trimIndent()

        val params = arrayOf(Parameter(ClassHelper.STRING_TYPE, "input"))
        val classNode = ClassNode("Script", 0, null)
        val node = MethodNode("oldMethod", 0, ClassHelper.VOID_TYPE, params, null, BlockStatement())
        node.declaringClass = classNode
        node.lineNumber = lineOf(source, "void oldMethod")

        val doc = DocExtractor.extractDocumentation(source, node)

        assertEquals("Old method that throws exceptions", doc.summary)
        assertEquals(2, doc.throws.size)
        assertTrue(doc.throws.containsKey("IllegalArgumentException"))
        assertTrue(doc.throws.containsKey("IOException"))
        assertTrue(doc.deprecated.contains("Use newMethod instead"))
    }

    @Test
    fun `returns empty documentation when no doc comment exists`() {
        val source = """
            package com.example
            
            class NoDocClass {
                def method() {
                    return 42
                }
            }
        """.trimIndent()

        val node = ClassNode("NoDocClass", 0, null)
        node.lineNumber = lineOf(source, "class NoDocClass")
        val doc = DocExtractor.extractDocumentation(source, node)

        assertTrue(doc.isEmpty())
        assertEquals("", doc.summary)
    }

    @Test
    fun `handles documentation with annotations before method`() {
        val source = """
            /**
             * Annotated method with documentation.
             *
             * @param name the name parameter
             * @return a greeting string
             */
            @Override
            @Deprecated
            String greet(String name) {
                return "Hello"
            }
        """.trimIndent()

        val params = arrayOf(Parameter(ClassHelper.STRING_TYPE, "name"))
        val classNode = ClassNode("Script", 0, null)
        val node = MethodNode("greet", 0, ClassHelper.STRING_TYPE, params, null, BlockStatement())
        node.declaringClass = classNode
        node.lineNumber = lineOf(source, "String greet")

        val doc = DocExtractor.extractDocumentation(source, node)

        assertEquals("Annotated method with documentation", doc.summary)
        assertEquals("the name parameter", doc.params["name"])
        assertEquals("a greeting string", doc.returnDoc)
    }

    @Test
    fun `returns empty documentation when node not found`() {
        val source = """
            class Test {
            }
        """.trimIndent()

        val node = ClassNode("NonExistentClass", 0, null)
        val doc = DocExtractor.extractDocumentation(source, node)

        assertTrue(doc.isEmpty())
    }
}
