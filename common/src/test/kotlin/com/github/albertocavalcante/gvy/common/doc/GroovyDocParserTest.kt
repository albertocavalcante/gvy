package com.github.albertocavalcante.gvy.common.doc

import com.github.albertocavalcante.groovyparser.ast.groovydoc.Groovydoc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroovyDocParserTest {

    /**
     * Helper function to extract content from a Groovydoc comment.
     * Removes the /** and */ delimiters and cleans up leading asterisks.
     */
    private fun extractContent(comment: String): String = comment
        .trim()
        .removePrefix("/**")
        .removeSuffix("*/")
        .lines()
        .joinToString("\n") { line ->
            line.trim().removePrefix("*").trimStart()
        }
        .trim()

    @Test
    fun `parse empty comment`() {
        val comment = """
            /**
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals("", result.description.text)
        assertEquals(emptyList<Any>(), result.getParamTags())
        assertNull(result.getReturnTag())
        assertEquals(emptyList<Any>(), result.getThrowsTags())
        assertEquals(emptyList<Any>(), result.getSeeTags())
        assertNull(result.getSinceTag())
        assertNull(result.getDeprecatedTag())
        assertNull(result.getAuthorTag())
    }

    @Test
    fun `parse description only`() {
        val comment = """
            /**
             * This is a simple description.
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals("This is a simple description.", result.description.text)
        assertEquals(emptyList<Any>(), result.getParamTags())
        assertNull(result.getReturnTag())
    }

    @Test
    fun `parse multiline description`() {
        val comment = """
            /**
             * This is the first line of description.
             * This is the second line.
             * And a third line.
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        val expected = "This is the first line of description.\nThis is the second line.\nAnd a third line."
        assertEquals(expected, result.description.text)
    }

    @Test
    fun `parse single param tag`() {
        val comment = """
            /**
             * Method description.
             * @param name the name parameter
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals("Method description.", result.description.text)
        assertEquals(1, result.getParamTags().size)
        assertEquals("name", result.getParamTags()[0].name)
        assertEquals("the name parameter", result.getParamTags()[0].content.text)
    }

    @Test
    fun `parse multiple param tags`() {
        val comment = """
            /**
             * Method with multiple parameters.
             * @param first the first parameter
             * @param second the second parameter
             * @param third the third parameter
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals("Method with multiple parameters.", result.description.text)
        assertEquals(3, result.getParamTags().size)
        assertEquals("first", result.getParamTags()[0].name)
        assertEquals("the first parameter", result.getParamTags()[0].content.text)
        assertEquals("second", result.getParamTags()[1].name)
        assertEquals("the second parameter", result.getParamTags()[1].content.text)
        assertEquals("third", result.getParamTags()[2].name)
        assertEquals("the third parameter", result.getParamTags()[2].content.text)
    }

    @Test
    fun `parse param with multiline description`() {
        val comment = """
            /**
             * Method description.
             * @param name the name parameter with
             *             a multiline description
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals(1, result.getParamTags().size)
        assertEquals("name", result.getParamTags()[0].name)
        assertEquals("the name parameter with a multiline description", result.getParamTags()[0].content.text)
    }

    @Test
    fun `parse return tag`() {
        val comment = """
            /**
             * Method description.
             * @return the result value
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals("Method description.", result.description.text)
        assertNotNull(result.getReturnTag())
        assertEquals("the result value", result.getReturnTag()?.content?.text)
    }

    @Test
    fun `parse returns tag (non-standard tag treated as unknown)`() {
        val comment = """
            /**
             * Method description.
             * @returns the result value
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        // @returns is not a standard Javadoc/Groovydoc tag (it's JSDoc)
        // The new parser treats it as an unknown tag, not as @return
        assertNull(result.getReturnTag())
        // Instead, it should be captured as an unknown block tag
        assertEquals(1, result.blockTags.size)
        assertEquals("returns", result.blockTags[0].tagName)
    }

    @Test
    fun `parse throws tag with exception type`() {
        val comment = """
            /**
             * Method description.
             * @throws IllegalArgumentException if argument is invalid
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals("Method description.", result.description.text)
        assertEquals(1, result.getThrowsTags().size)
        assertEquals("IllegalArgumentException", result.getThrowsTags()[0].name)
        assertEquals("if argument is invalid", result.getThrowsTags()[0].content.text)
    }

    @Test
    fun `parse exception tag (alternative form)`() {
        val comment = """
            /**
             * Method description.
             * @exception IOException if IO error occurs
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals(1, result.getThrowsTags().size)
        assertEquals("IOException", result.getThrowsTags()[0].name)
        assertEquals("if IO error occurs", result.getThrowsTags()[0].content.text)
    }

    @Test
    fun `parse multiple throws tags`() {
        val comment = """
            /**
             * Method description.
             * @throws IllegalArgumentException if argument is invalid
             * @throws IOException if IO error occurs
             * @throws NullPointerException if parameter is null
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals(3, result.getThrowsTags().size)
        assertEquals("IllegalArgumentException", result.getThrowsTags()[0].name)
        assertEquals("IOException", result.getThrowsTags()[1].name)
        assertEquals("NullPointerException", result.getThrowsTags()[2].name)
    }

    @Test
    fun `parse see tag`() {
        val comment = """
            /**
             * Method description.
             * @see SomeOtherClass
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals("Method description.", result.description.text)
        assertEquals(1, result.getSeeTags().size)
        assertEquals("SomeOtherClass", result.getSeeTags()[0].content.text)
    }

    @Test
    fun `parse multiple see tags`() {
        val comment = """
            /**
             * Method description.
             * @see FirstClass
             * @see SecondClass#method()
             * @see <a href="http://example.com">Example</a>
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals(3, result.getSeeTags().size)
        assertEquals("FirstClass", result.getSeeTags()[0].content.text)
        assertEquals("SecondClass#method()", result.getSeeTags()[1].content.text)
        assertEquals("<a href=\"http://example.com\">Example</a>", result.getSeeTags()[2].content.text)
    }

    @Test
    fun `parse since tag`() {
        val comment = """
            /**
             * Method description.
             * @since 1.0
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals("Method description.", result.description.text)
        assertEquals("1.0", result.getSinceTag()?.content?.text)
    }

    @Test
    fun `parse deprecated tag`() {
        val comment = """
            /**
             * Method description.
             * @deprecated Use newMethod() instead
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals("Method description.", result.description.text)
        assertEquals("Use newMethod() instead", result.getDeprecatedTag()?.content?.text)
    }

    @Test
    fun `parse author tag`() {
        val comment = """
            /**
             * Method description.
             * @author John Doe
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals("Method description.", result.description.text)
        assertEquals("John Doe", result.getAuthorTag()?.content?.text)
    }

    @Test
    fun `parse full GroovyDoc with all tags`() {
        val comment = """
            /**
             * Processes the given input and returns a result.
             * This method performs complex operations.
             *
             * @param input the input string to process
             * @param options configuration options
             * @return the processed result
             * @throws IllegalArgumentException if input is null or empty
             * @throws IOException if processing fails
             * @see ProcessorUtils
             * @since 2.0
             * @deprecated Use newProcess() instead
             * @author Jane Smith
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals(
            "Processes the given input and returns a result.\nThis method performs complex operations.",
            result.description.text,
        )
        assertEquals(2, result.getParamTags().size)
        assertEquals("input", result.getParamTags()[0].name)
        assertEquals("the input string to process", result.getParamTags()[0].content.text)
        assertEquals("options", result.getParamTags()[1].name)
        assertEquals("configuration options", result.getParamTags()[1].content.text)
        assertNotNull(result.getReturnTag())
        assertEquals("the processed result", result.getReturnTag()?.content?.text)
        assertEquals(2, result.getThrowsTags().size)
        assertEquals("IllegalArgumentException", result.getThrowsTags()[0].name)
        assertEquals("IOException", result.getThrowsTags()[1].name)
        assertEquals(1, result.getSeeTags().size)
        assertEquals("ProcessorUtils", result.getSeeTags()[0].content.text)
        assertEquals("2.0", result.getSinceTag()?.content?.text)
        assertEquals("Use newProcess() instead", result.getDeprecatedTag()?.content?.text)
        assertEquals("Jane Smith", result.getAuthorTag()?.content?.text)
    }

    @Test
    fun `parse comment without description but with tags`() {
        val comment = """
            /**
             * @param name the name
             * @return the result
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals("", result.description.text)
        assertEquals(1, result.getParamTags().size)
        assertNotNull(result.getReturnTag())
    }

    @Test
    fun `parse comment with blank lines in description`() {
        val comment = """
            /**
             * First paragraph.
             *
             * Second paragraph after blank line.
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertTrue(result.description.text.contains("First paragraph."))
        assertTrue(result.description.text.contains("Second paragraph after blank line."))
    }

    @Test
    fun `parse param tag without description`() {
        val comment = """
            /**
             * Method description.
             * @param name
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals(1, result.getParamTags().size)
        assertEquals("name", result.getParamTags()[0].name)
        assertEquals("", result.getParamTags()[0].content.text)
    }

    @Test
    fun `parse throws tag without description`() {
        val comment = """
            /**
             * Method description.
             * @throws IOException
             */
        """.trimIndent()

        val result = Groovydoc.parse(extractContent(comment))

        assertEquals(1, result.getThrowsTags().size)
        assertEquals("IOException", result.getThrowsTags()[0].name)
        assertEquals("", result.getThrowsTags()[0].content.text)
    }

    @Test
    fun `parse single line comment`() {
        val comment = "/** This is a single line comment. */"
        val result = Groovydoc.parse(extractContent(comment))
        assertEquals("This is a single line comment.", result.description.text)
    }
}
