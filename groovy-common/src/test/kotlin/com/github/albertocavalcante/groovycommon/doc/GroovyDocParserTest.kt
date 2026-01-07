package com.github.albertocavalcante.groovycommon.doc

import com.github.albertocavalcante.groovycommon.doc.GroovyDoc
import com.github.albertocavalcante.groovycommon.doc.GroovyDocParser
import com.github.albertocavalcante.groovycommon.doc.ParamTag
import com.github.albertocavalcante.groovycommon.doc.SeeTag
import com.github.albertocavalcante.groovycommon.doc.ThrowsTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroovyDocParserTest {

    @Test
    fun `parse empty comment`() {
        val comment = """
            /**
             */
        """.trimIndent()

        val result = GroovyDocParser.parse(comment)

        assertEquals("", result.description)
        assertEquals(emptyList<ParamTag>(), result.params)
        assertNull(result.returns)
        assertEquals(emptyList<ThrowsTag>(), result.throws)
        assertEquals(emptyList<SeeTag>(), result.see)
        assertNull(result.since)
        assertNull(result.deprecated)
        assertNull(result.author)
    }

    @Test
    fun `parse description only`() {
        val comment = """
            /**
             * This is a simple description.
             */
        """.trimIndent()

        val result = GroovyDocParser.parse(comment)

        assertEquals("This is a simple description.", result.description)
        assertEquals(emptyList<ParamTag>(), result.params)
        assertNull(result.returns)
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

        val result = GroovyDocParser.parse(comment)

        val expected = "This is the first line of description.\nThis is the second line.\nAnd a third line."
        assertEquals(expected, result.description)
    }

    @Test
    fun `parse single param tag`() {
        val comment = """
            /**
             * Method description.
             * @param name the name parameter
             */
        """.trimIndent()

        val result = GroovyDocParser.parse(comment)

        assertEquals("Method description.", result.description)
        assertEquals(1, result.params.size)
        assertEquals("name", result.params[0].name)
        assertEquals("the name parameter", result.params[0].description)
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

        val result = GroovyDocParser.parse(comment)

        assertEquals("Method with multiple parameters.", result.description)
        assertEquals(3, result.params.size)
        assertEquals("first", result.params[0].name)
        assertEquals("the first parameter", result.params[0].description)
        assertEquals("second", result.params[1].name)
        assertEquals("the second parameter", result.params[1].description)
        assertEquals("third", result.params[2].name)
        assertEquals("the third parameter", result.params[2].description)
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

        val result = GroovyDocParser.parse(comment)

        assertEquals(1, result.params.size)
        assertEquals("name", result.params[0].name)
        assertEquals("the name parameter with a multiline description", result.params[0].description)
    }

    @Test
    fun `parse return tag`() {
        val comment = """
            /**
             * Method description.
             * @return the result value
             */
        """.trimIndent()

        val result = GroovyDocParser.parse(comment)

        assertEquals("Method description.", result.description)
        assertNotNull(result.returns)
        assertEquals("the result value", result.returns?.description)
    }

    @Test
    fun `parse returns tag (alternative form)`() {
        val comment = """
            /**
             * Method description.
             * @returns the result value
             */
        """.trimIndent()

        val result = GroovyDocParser.parse(comment)

        assertNotNull(result.returns)
        assertEquals("the result value", result.returns?.description)
    }

    @Test
    fun `parse throws tag with exception type`() {
        val comment = """
            /**
             * Method description.
             * @throws IllegalArgumentException if argument is invalid
             */
        """.trimIndent()

        val result = GroovyDocParser.parse(comment)

        assertEquals("Method description.", result.description)
        assertEquals(1, result.throws.size)
        assertEquals("IllegalArgumentException", result.throws[0].exception)
        assertEquals("if argument is invalid", result.throws[0].description)
    }

    @Test
    fun `parse exception tag (alternative form)`() {
        val comment = """
            /**
             * Method description.
             * @exception IOException if IO error occurs
             */
        """.trimIndent()

        val result = GroovyDocParser.parse(comment)

        assertEquals(1, result.throws.size)
        assertEquals("IOException", result.throws[0].exception)
        assertEquals("if IO error occurs", result.throws[0].description)
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

        val result = GroovyDocParser.parse(comment)

        assertEquals(3, result.throws.size)
        assertEquals("IllegalArgumentException", result.throws[0].exception)
        assertEquals("IOException", result.throws[1].exception)
        assertEquals("NullPointerException", result.throws[2].exception)
    }

    @Test
    fun `parse see tag`() {
        val comment = """
            /**
             * Method description.
             * @see SomeOtherClass
             */
        """.trimIndent()

        val result = GroovyDocParser.parse(comment)

        assertEquals("Method description.", result.description)
        assertEquals(1, result.see.size)
        assertEquals("SomeOtherClass", result.see[0].reference)
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

        val result = GroovyDocParser.parse(comment)

        assertEquals(3, result.see.size)
        assertEquals("FirstClass", result.see[0].reference)
        assertEquals("SecondClass#method()", result.see[1].reference)
        assertEquals("<a href=\"http://example.com\">Example</a>", result.see[2].reference)
    }

    @Test
    fun `parse since tag`() {
        val comment = """
            /**
             * Method description.
             * @since 1.0
             */
        """.trimIndent()

        val result = GroovyDocParser.parse(comment)

        assertEquals("Method description.", result.description)
        assertEquals("1.0", result.since)
    }

    @Test
    fun `parse deprecated tag`() {
        val comment = """
            /**
             * Method description.
             * @deprecated Use newMethod() instead
             */
        """.trimIndent()

        val result = GroovyDocParser.parse(comment)

        assertEquals("Method description.", result.description)
        assertEquals("Use newMethod() instead", result.deprecated)
    }

    @Test
    fun `parse author tag`() {
        val comment = """
            /**
             * Method description.
             * @author John Doe
             */
        """.trimIndent()

        val result = GroovyDocParser.parse(comment)

        assertEquals("Method description.", result.description)
        assertEquals("John Doe", result.author)
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

        val result = GroovyDocParser.parse(comment)

        assertEquals(
            "Processes the given input and returns a result.\nThis method performs complex operations.",
            result.description,
        )
        assertEquals(2, result.params.size)
        assertEquals("input", result.params[0].name)
        assertEquals("the input string to process", result.params[0].description)
        assertEquals("options", result.params[1].name)
        assertEquals("configuration options", result.params[1].description)
        assertNotNull(result.returns)
        assertEquals("the processed result", result.returns?.description)
        assertEquals(2, result.throws.size)
        assertEquals("IllegalArgumentException", result.throws[0].exception)
        assertEquals("IOException", result.throws[1].exception)
        assertEquals(1, result.see.size)
        assertEquals("ProcessorUtils", result.see[0].reference)
        assertEquals("2.0", result.since)
        assertEquals("Use newProcess() instead", result.deprecated)
        assertEquals("Jane Smith", result.author)
    }

    @Test
    fun `parse comment without description but with tags`() {
        val comment = """
            /**
             * @param name the name
             * @return the result
             */
        """.trimIndent()

        val result = GroovyDocParser.parse(comment)

        assertEquals("", result.description)
        assertEquals(1, result.params.size)
        assertNotNull(result.returns)
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

        val result = GroovyDocParser.parse(comment)

        assertTrue(result.description.contains("First paragraph."))
        assertTrue(result.description.contains("Second paragraph after blank line."))
    }

    @Test
    fun `parse param tag without description`() {
        val comment = """
            /**
             * Method description.
             * @param name
             */
        """.trimIndent()

        val result = GroovyDocParser.parse(comment)

        assertEquals(1, result.params.size)
        assertEquals("name", result.params[0].name)
        assertEquals("", result.params[0].description)
    }

    @Test
    fun `parse throws tag without description`() {
        val comment = """
            /**
             * Method description.
             * @throws IOException
             */
        """.trimIndent()

        val result = GroovyDocParser.parse(comment)

        assertEquals(1, result.throws.size)
        assertEquals("IOException", result.throws[0].exception)
        assertEquals("", result.throws[0].description)
    }

    @Test
    fun `parse single line comment`() {
        val comment = "/** This is a single line comment. */"
        val result = GroovyDocParser.parse(comment)
        assertEquals("This is a single line comment.", result.description)
    }
}
