package com.github.albertocavalcante.groovyparser.ast.groovydoc

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ast.JavadocComment
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for Groovydoc parsing functionality.
 */
class GroovydocTest {

    @Test
    fun `parse simple description`() {
        val content = "This is a simple description."
        val groovydoc = Groovydoc.parse(content)

        assertEquals("This is a simple description.", groovydoc.description.text)
        assertTrue(groovydoc.blockTags.isEmpty())
    }

    @Test
    fun `parse multiline description`() {
        val content = """
            This is a multiline
            description that spans
            multiple lines.
        """.trimIndent()

        val groovydoc = Groovydoc.parse(content)

        assertTrue(groovydoc.description.text.contains("multiline"))
        assertTrue(groovydoc.description.text.contains("multiple lines"))
        assertTrue(groovydoc.blockTags.isEmpty())
    }

    @Test
    fun `parse description with asterisks cleaned`() {
        val content = """
            * This is a description
            * with leading asterisks
            * that should be removed.
        """.trimIndent()

        val groovydoc = Groovydoc.parse(content)

        assertTrue(groovydoc.description.text.contains("This is a description"))
        assertTrue(!groovydoc.description.text.startsWith("*"))
    }

    @Test
    fun `parse param tag`() {
        val content = "@param x the x value"
        val groovydoc = Groovydoc.parse(content)

        assertTrue(groovydoc.description.isEmpty())
        assertEquals(1, groovydoc.blockTags.size)

        val tag = groovydoc.blockTags[0]
        assertEquals(GroovydocBlockTag.Type.PARAM, tag.type)
        assertEquals("x", tag.name)
        assertEquals("the x value", tag.content.text)
    }

    @Test
    fun `parse multiple param tags`() {
        val content = """
            @param x the x value
            @param y the y value
            @param z the z value
        """.trimIndent()

        val groovydoc = Groovydoc.parse(content)
        val paramTags = groovydoc.getParamTags()

        assertEquals(3, paramTags.size)
        assertEquals("x", paramTags[0].name)
        assertEquals("y", paramTags[1].name)
        assertEquals("z", paramTags[2].name)
    }

    @Test
    fun `parse return tag`() {
        val content = "@return the computed result"
        val groovydoc = Groovydoc.parse(content)

        val returnTag = groovydoc.getReturnTag()
        assertNotNull(returnTag)
        assertEquals(GroovydocBlockTag.Type.RETURN, returnTag.type)
        assertEquals("the computed result", returnTag.content.text)
        assertNull(returnTag.name)
    }

    @Test
    fun `parse throws tag`() {
        val content = "@throws IllegalArgumentException if the argument is invalid"
        val groovydoc = Groovydoc.parse(content)

        val throwsTags = groovydoc.getThrowsTags()
        assertEquals(1, throwsTags.size)

        val tag = throwsTags[0]
        assertEquals(GroovydocBlockTag.Type.THROWS, tag.type)
        assertEquals("IllegalArgumentException", tag.name)
        assertEquals("if the argument is invalid", tag.content.text)
    }

    @Test
    fun `parse exception tag (alias for throws)`() {
        val content = "@exception IOException if IO fails"
        val groovydoc = Groovydoc.parse(content)

        val throwsTags = groovydoc.getThrowsTags()
        assertEquals(1, throwsTags.size)
        assertEquals(GroovydocBlockTag.Type.EXCEPTION, throwsTags[0].type)
        assertEquals("IOException", throwsTags[0].name)
    }

    @Test
    fun `parse author tag`() {
        val content = "@author John Doe"
        val groovydoc = Groovydoc.parse(content)

        val authorTag = groovydoc.getAuthorTag()
        assertNotNull(authorTag)
        assertEquals(GroovydocBlockTag.Type.AUTHOR, authorTag.type)
        assertEquals("John Doe", authorTag.content.text)
    }

    @Test
    fun `parse since tag`() {
        val content = "@since 1.0.0"
        val groovydoc = Groovydoc.parse(content)

        val sinceTag = groovydoc.getSinceTag()
        assertNotNull(sinceTag)
        assertEquals(GroovydocBlockTag.Type.SINCE, sinceTag.type)
        assertEquals("1.0.0", sinceTag.content.text)
    }

    @Test
    fun `parse deprecated tag`() {
        val content = "@deprecated use newMethod() instead"
        val groovydoc = Groovydoc.parse(content)

        val deprecatedTag = groovydoc.getDeprecatedTag()
        assertNotNull(deprecatedTag)
        assertEquals(GroovydocBlockTag.Type.DEPRECATED, deprecatedTag.type)
        assertEquals("use newMethod() instead", deprecatedTag.content.text)
    }

    @Test
    fun `parse see tag`() {
        val content = "@see SomeOtherClass"
        val groovydoc = Groovydoc.parse(content)

        val seeTags = groovydoc.getSeeTags()
        assertEquals(1, seeTags.size)
        assertEquals(GroovydocBlockTag.Type.SEE, seeTags[0].type)
        assertEquals("SomeOtherClass", seeTags[0].content.text)
    }

    @Test
    fun `parse unknown tag`() {
        val content = "@customTag some value"
        val groovydoc = Groovydoc.parse(content)

        assertEquals(1, groovydoc.blockTags.size)
        val tag = groovydoc.blockTags[0]
        assertEquals(GroovydocBlockTag.Type.UNKNOWN, tag.type)
        assertEquals("customTag", tag.tagName)
        assertEquals("some value", tag.content.text)
    }

    @Test
    fun `parse complete method documentation`() {
        val content = """
            Calculates the sum of two numbers.
            
            This method performs addition and handles edge cases
            like overflow.
            
            @param x the first number
            @param y the second number
            @return the sum of x and y
            @throws ArithmeticException if overflow occurs
            @since 1.0
            @see Math#addExact
        """.trimIndent()

        val groovydoc = Groovydoc.parse(content)

        // Check description
        assertTrue(groovydoc.description.text.contains("Calculates the sum"))
        assertTrue(groovydoc.description.text.contains("overflow"))

        // Check params
        val params = groovydoc.getParamTags()
        assertEquals(2, params.size)
        assertEquals("x", params[0].name)
        assertEquals("y", params[1].name)

        // Check return
        val returnTag = groovydoc.getReturnTag()
        assertNotNull(returnTag)
        assertEquals("the sum of x and y", returnTag.content.text)

        // Check throws
        val throwsTags = groovydoc.getThrowsTags()
        assertEquals(1, throwsTags.size)
        assertEquals("ArithmeticException", throwsTags[0].name)

        // Check since
        val sinceTag = groovydoc.getSinceTag()
        assertNotNull(sinceTag)
        assertEquals("1.0", sinceTag.content.text)

        // Check see
        val seeTags = groovydoc.getSeeTags()
        assertEquals(1, seeTags.size)
    }

    @Test
    fun `parse multiline tag content`() {
        val content = """
            @param x the x value which can be
                any positive integer or zero
        """.trimIndent()

        val groovydoc = Groovydoc.parse(content)
        val paramTag = groovydoc.getParamTags()[0]

        assertEquals("x", paramTag.name)
        assertTrue(paramTag.content.text.contains("any positive integer"))
    }

    @Test
    fun `parse inline code tag in description`() {
        val content = "Use {@code someMethod()} to do something."
        val groovydoc = Groovydoc.parse(content)

        assertEquals(1, groovydoc.description.inlineTags.size)
        val inlineTag = groovydoc.description.inlineTags[0]
        assertEquals(GroovydocInlineTag.Type.CODE, inlineTag.type)
        assertEquals("someMethod()", inlineTag.content)
    }

    @Test
    fun `parse inline link tag`() {
        val content = "See {@link SomeClass#method} for details."
        val groovydoc = Groovydoc.parse(content)

        assertEquals(1, groovydoc.description.inlineTags.size)
        val inlineTag = groovydoc.description.inlineTags[0]
        assertEquals(GroovydocInlineTag.Type.LINK, inlineTag.type)
        assertEquals("SomeClass#method", inlineTag.content)
    }

    @Test
    fun `toText recreates readable format`() {
        val content = """
            Does something important.
            
            @param x the value
            @return the result
        """.trimIndent()

        val groovydoc = Groovydoc.parse(content)
        val text = groovydoc.toText()

        assertTrue(text.contains("Does something important"))
        assertTrue(text.contains("@param x the value"))
        assertTrue(text.contains("@return the result"))
    }

    @Test
    fun `empty description with only tags`() {
        val content = """
            @param x value
            @return result
        """.trimIndent()

        val groovydoc = Groovydoc.parse(content)

        assertTrue(groovydoc.description.isEmpty())
        assertEquals(2, groovydoc.blockTags.size)
    }

    // Integration tests with parser

    @Test
    fun `parse groovydoc from actual parsed code - class`() {
        val code = """
            /**
             * A sample class for testing.
             *
             * @author Test Author
             * @since 2.0
             */
            class Foo {
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val classDecl = result.result.get().types[0]
        val comment = classDecl.comment
        assertNotNull(comment)
        assertIs<JavadocComment>(comment)

        val groovydoc = comment.parse()
        assertTrue(groovydoc.description.text.contains("sample class"))

        val authorTag = groovydoc.getAuthorTag()
        assertNotNull(authorTag)
        assertEquals("Test Author", authorTag.content.text)

        val sinceTag = groovydoc.getSinceTag()
        assertNotNull(sinceTag)
        assertEquals("2.0", sinceTag.content.text)
    }

    @Test
    fun `parse groovydoc from actual parsed code - method`() {
        val code = """
            class Foo {
                /**
                 * Adds two numbers together.
                 *
                 * @param a the first number
                 * @param b the second number
                 * @return the sum
                 */
                int add(int a, int b) {
                    return a + b
                }
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods.find { it.name == "add" }
        assertNotNull(method)

        val comment = method.comment
        assertNotNull(comment)
        assertIs<JavadocComment>(comment)

        val groovydoc = comment.parse()
        assertTrue(groovydoc.description.text.contains("Adds two numbers"))

        val params = groovydoc.getParamTags()
        assertEquals(2, params.size)
        assertEquals("a", params[0].name)
        assertEquals("b", params[1].name)

        val returnTag = groovydoc.getReturnTag()
        assertNotNull(returnTag)
        assertEquals("the sum", returnTag.content.text)
    }

    @Test
    fun `JavadocComment isGroovydoc alias works`() {
        val comment = JavadocComment("Test content")
        assertTrue(comment.isJavadoc)
        assertTrue(comment.isGroovydoc)
    }

    @Test
    fun `GroovydocBlockTag factory methods`() {
        val paramTag = GroovydocBlockTag.param("x", "the x value")
        assertEquals(GroovydocBlockTag.Type.PARAM, paramTag.type)
        assertEquals("x", paramTag.name)
        assertEquals("the x value", paramTag.content.text)

        val returnTag = GroovydocBlockTag.returns("the result")
        assertEquals(GroovydocBlockTag.Type.RETURN, returnTag.type)
        assertEquals("the result", returnTag.content.text)

        val throwsTag = GroovydocBlockTag.throws("IOException", "if IO fails")
        assertEquals(GroovydocBlockTag.Type.THROWS, throwsTag.type)
        assertEquals("IOException", throwsTag.name)
        assertEquals("if IO fails", throwsTag.content.text)
    }
}
