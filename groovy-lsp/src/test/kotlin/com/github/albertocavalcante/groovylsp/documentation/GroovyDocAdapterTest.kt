package com.github.albertocavalcante.groovylsp.documentation

import com.github.albertocavalcante.groovyparser.ast.groovydoc.Groovydoc
import com.github.albertocavalcante.groovyparser.ast.groovydoc.GroovydocBlockTag
import com.github.albertocavalcante.groovyparser.ast.groovydoc.GroovydocDescription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroovyDocAdapterTest {
    @Test
    fun `converts basic description`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText("This is a simple method."),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("This is a simple method", doc.summary)
        assertEquals("This is a simple method.", doc.description)
        assertTrue(doc.params.isEmpty())
        assertTrue(doc.returnDoc.isEmpty())
    }

    @Test
    fun `converts multi-sentence description - extracts first sentence as summary`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText(
                "This is the first sentence. This is the second sentence. This is the third.",
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("This is the first sentence", doc.summary)
        assertEquals("This is the first sentence. This is the second sentence. This is the third.", doc.description)
    }

    @Test
    fun `converts multi-paragraph description - extracts first paragraph as summary`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText(
                "This is the first paragraph.\n\nThis is the second paragraph.",
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("This is the first paragraph", doc.summary)
        assertEquals("This is the first paragraph.\n\nThis is the second paragraph.", doc.description)
    }

    @Test
    fun `converts param tags`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText("Adds two numbers."),
            blockTags = listOf(
                GroovydocBlockTag.param("x", "the first number"),
                GroovydocBlockTag.param("y", "the second number"),
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("Adds two numbers", doc.summary)
        assertEquals(2, doc.params.size)
        assertEquals("the first number", doc.params["x"])
        assertEquals("the second number", doc.params["y"])
    }

    @Test
    fun `converts return tag`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText("Calculates sum."),
            blockTags = listOf(
                GroovydocBlockTag.returns("the sum of the two numbers"),
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("Calculates sum", doc.summary)
        assertEquals("the sum of the two numbers", doc.returnDoc)
    }

    @Test
    fun `converts throws tags`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText("Performs operation."),
            blockTags = listOf(
                GroovydocBlockTag.throws("IOException", "if IO fails"),
                GroovydocBlockTag.throws("IllegalArgumentException", "if argument is invalid"),
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("Performs operation", doc.summary)
        assertEquals(2, doc.throws.size)
        assertEquals("if IO fails", doc.throws["IOException"])
        assertEquals("if argument is invalid", doc.throws["IllegalArgumentException"])
    }

    @Test
    fun `converts since tag`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText("New method."),
            blockTags = listOf(
                GroovydocBlockTag(
                    type = GroovydocBlockTag.Type.SINCE,
                    tagName = "since",
                    content = GroovydocDescription.parseText("1.0"),
                ),
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("New method", doc.summary)
        assertEquals("1.0", doc.since)
    }

    @Test
    fun `converts author tag`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText("A class."),
            blockTags = listOf(
                GroovydocBlockTag(
                    type = GroovydocBlockTag.Type.AUTHOR,
                    tagName = "author",
                    content = GroovydocDescription.parseText("John Doe"),
                ),
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("A class", doc.summary)
        assertEquals("John Doe", doc.author)
    }

    @Test
    fun `converts deprecated tag`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText("Old method."),
            blockTags = listOf(
                GroovydocBlockTag(
                    type = GroovydocBlockTag.Type.DEPRECATED,
                    tagName = "deprecated",
                    content = GroovydocDescription.parseText("Use newMethod() instead"),
                ),
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("Old method", doc.summary)
        assertEquals("Use newMethod() instead", doc.deprecated)
    }

    @Test
    fun `converts see tags`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText("A method."),
            blockTags = listOf(
                GroovydocBlockTag(
                    type = GroovydocBlockTag.Type.SEE,
                    tagName = "see",
                    content = GroovydocDescription.parseText("OtherClass"),
                ),
                GroovydocBlockTag(
                    type = GroovydocBlockTag.Type.SEE,
                    tagName = "see",
                    content = GroovydocDescription.parseText("AnotherClass#method()"),
                ),
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("A method", doc.summary)
        assertEquals(2, doc.see.size)
        assertEquals("OtherClass", doc.see[0])
        assertEquals("AnotherClass#method()", doc.see[1])
    }

    @Test
    fun `converts complete groovydoc with all tags`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText(
                "Calculates the sum of two numbers. This is a detailed description.",
            ),
            blockTags = listOf(
                GroovydocBlockTag.param("x", "the first number"),
                GroovydocBlockTag.param("y", "the second number"),
                GroovydocBlockTag.returns("the sum of x and y"),
                GroovydocBlockTag.throws("IllegalArgumentException", "if values are negative"),
                GroovydocBlockTag(
                    type = GroovydocBlockTag.Type.SINCE,
                    tagName = "since",
                    content = GroovydocDescription.parseText("1.0"),
                ),
                GroovydocBlockTag(
                    type = GroovydocBlockTag.Type.AUTHOR,
                    tagName = "author",
                    content = GroovydocDescription.parseText("Jane Smith"),
                ),
                GroovydocBlockTag(
                    type = GroovydocBlockTag.Type.SEE,
                    tagName = "see",
                    content = GroovydocDescription.parseText("Calculator#subtract"),
                ),
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("Calculates the sum of two numbers", doc.summary)
        assertEquals("Calculates the sum of two numbers. This is a detailed description.", doc.description)
        assertEquals(2, doc.params.size)
        assertEquals("the first number", doc.params["x"])
        assertEquals("the second number", doc.params["y"])
        assertEquals("the sum of x and y", doc.returnDoc)
        assertEquals(1, doc.throws.size)
        assertEquals("if values are negative", doc.throws["IllegalArgumentException"])
        assertEquals("1.0", doc.since)
        assertEquals("Jane Smith", doc.author)
        assertEquals(1, doc.see.size)
        assertEquals("Calculator#subtract", doc.see[0])
    }

    @Test
    fun `handles empty groovydoc`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText(""),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertTrue(doc.isEmpty())
        assertEquals("", doc.summary)
        assertEquals("", doc.description)
    }

    @Test
    fun `handles groovydoc with only whitespace`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText("   \n  \n  "),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertTrue(doc.isEmpty())
        assertEquals("", doc.summary)
        assertEquals("", doc.description)
    }

    @Test
    fun `handles param tag with missing name`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText("A method."),
            blockTags = listOf(
                GroovydocBlockTag(
                    type = GroovydocBlockTag.Type.PARAM,
                    tagName = "param",
                    name = null,
                    content = GroovydocDescription.parseText("some description"),
                ),
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("A method", doc.summary)
        assertEquals(1, doc.params.size)
        assertEquals("some description", doc.params[""])
    }

    @Test
    fun `handles question mark sentence ending`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText(
                "Is this a question? Yes it is. More text here.",
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("Is this a question", doc.summary)
        assertEquals("Is this a question? Yes it is. More text here.", doc.description)
    }

    @Test
    fun `handles exclamation mark sentence ending`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText(
                "This is important! Really important. End.",
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("This is important", doc.summary)
        assertEquals("This is important! Really important. End.", doc.description)
    }

    @Test
    fun `uses entire description as summary if short and no punctuation`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText("A short description"),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("A short description", doc.summary)
        assertEquals("A short description", doc.description)
    }

    @Test
    fun `parses from raw comment string`() {
        val rawComment = """
            Calculates the sum of two numbers.

            @param x the first number
            @param y the second number
            @return the sum of x and y
        """.trimIndent()

        val groovydoc = Groovydoc.parse(rawComment)
        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("Calculates the sum of two numbers", doc.summary)
        assertEquals(2, doc.params.size)
        assertEquals("the first number", doc.params["x"])
        assertEquals("the second number", doc.params["y"])
        assertEquals("the sum of x and y", doc.returnDoc)
    }

    @Test
    fun `truncates long description without sentence ending to 100 characters`() {
        // Create a description longer than 100 chars without sentence ending
        val longText = "a".repeat(150)
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText(longText),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        // Summary should be truncated to 100 chars + "..."
        assertEquals(103, doc.summary.length)
        assertEquals("a".repeat(100) + "...", doc.summary)
        assertEquals(longText, doc.description) // Full description preserved
    }

    @Test
    fun `renders inline code tag in description`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText("Use {@code String.valueOf()} to convert."),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("Use `String.valueOf()` to convert.", doc.description)
    }

    @Test
    fun `renders empty-content inline tag without trailing space`() {
        // Regression test: toText() always adds space between tagName and content,
        // producing "{@inheritDoc }" for empty content, but original text is "{@inheritDoc}"
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText("See parent. {@inheritDoc}"),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        // Should render {@inheritDoc} as placeholder since we can't resolve parent docs
        assertEquals("See parent. `inheritDoc`", doc.description)
    }

    @Test
    fun `renders multiple inline tags in description`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText(
                "Use {@code foo} and {@link Bar} together.",
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("Use `foo` and `Bar` together.", doc.description)
    }

    @Test
    fun `renders inline tags in param description`() {
        val groovydoc = Groovydoc(
            description = GroovydocDescription.parseText("A method."),
            blockTags = listOf(
                GroovydocBlockTag.param("value", "the {@code String} to process"),
            ),
        )

        val doc = GroovyDocAdapter.toDocumentation(groovydoc)

        assertEquals("the `String` to process", doc.params["value"])
    }
}
