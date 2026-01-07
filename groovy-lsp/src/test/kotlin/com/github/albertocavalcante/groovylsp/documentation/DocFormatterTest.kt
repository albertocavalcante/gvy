package com.github.albertocavalcante.groovylsp.documentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocFormatterTest {

    @Test
    fun `formats documentation with inline tags in summary`() {
        val doc = Documentation(
            summary = "Use {@code String.valueOf()} to convert values.",
            description = "This method uses {@link java.util.List} internally.",
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        assertTrue(result.contains("`String.valueOf()`"))
        assertTrue(result.contains("`List`"))
    }

    @Test
    fun `formats documentation with inline tags in parameters`() {
        val doc = Documentation(
            summary = "Processes input data.",
            params = mapOf(
                "value" to "The {@code String} value to process",
                "list" to "A {@link java.util.List} of items",
            ),
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        assertTrue(result.contains("`value`: The `String` value to process"))
        assertTrue(result.contains("`list`: A `List` of items"))
    }

    @Test
    fun `formats documentation with inline tags in return doc`() {
        val doc = Documentation(
            summary = "Retrieves data.",
            returnDoc = "A {@link java.util.Map} containing {@code key-value} pairs",
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        assertTrue(result.contains("**Returns:** A `Map` containing `key-value` pairs"))
    }

    @Test
    fun `formats documentation with inline tags in deprecated notice`() {
        val doc = Documentation(
            summary = "Old method.",
            deprecated = "Use {@link #newMethod()} instead of {@code oldMethod()}",
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        assertTrue(result.contains("**Deprecated**: Use `newMethod()` instead of `oldMethod()`"))
    }

    @Test
    fun `formats documentation with inline tags in throws`() {
        val doc = Documentation(
            summary = "Risky operation.",
            throws = mapOf(
                "IOException" to "If {@code file} cannot be read",
                "IllegalArgumentException" to "If {@link #validate()} fails",
            ),
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        assertTrue(result.contains("`IOException`: If `file` cannot be read"))
        assertTrue(result.contains("`IllegalArgumentException`: If `validate()` fails"))
    }

    @Test
    fun `formats documentation with literal tag in description`() {
        val doc = Documentation(
            summary = "Generic processor.",
            description = "Works with {@literal List<String>} and {@literal Map<K,V>} types.",
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        assertTrue(result.contains("List&lt;String&gt;"))
        assertTrue(result.contains("Map&lt;K,V&gt;"))
    }

    @Test
    fun `formats summary with inline tags`() {
        val doc = Documentation(
            summary = "Converts using {@code valueOf()} from {@link String} class.",
        )

        val summary = DocFormatter.formatSummary(doc)

        assertEquals("Converts using `valueOf()` from `String` class.", summary)
    }

    @Test
    fun `formats summary from description with inline tags`() {
        val doc = Documentation(
            description = "Uses {@link List} for storage. Additional details follow.",
        )

        val summary = DocFormatter.formatSummary(doc)

        assertTrue(summary.contains("`List`"))
    }

    @Test
    fun `getParamDoc returns rendered parameter documentation`() {
        val doc = Documentation(
            params = mapOf(
                "input" to "The {@code String} input using {@link Pattern} matching",
            ),
        )

        val paramDoc = DocFormatter.getParamDoc(doc, "input")

        assertEquals("The `String` input using `Pattern` matching", paramDoc)
    }

    @Test
    fun `getParamDoc returns empty string for missing parameter`() {
        val doc = Documentation(
            params = mapOf("other" to "Some description"),
        )

        val paramDoc = DocFormatter.getParamDoc(doc, "missing")

        assertEquals("", paramDoc)
    }

    @Test
    fun `formats documentation with multiple inline tag types`() {
        val doc = Documentation(
            summary = "Complex method with {@code code}, {@link String}, and {@literal <T>}.",
            description = "See {@linkplain Object} for details.",
            params = mapOf("arg" to "Uses {@value #DEFAULT}"),
            returnDoc = "A {@link List} of {@code String} values",
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        // Verify all tag types are rendered
        assertTrue(result.contains("`code`"))
        assertTrue(result.contains("`String`"))
        assertTrue(result.contains("&lt;T&gt;"))
        assertTrue(result.contains("Object"))
        assertTrue(result.contains("`DEFAULT`"))
        assertTrue(result.contains("`List`"))
    }

    @Test
    fun `handles empty documentation`() {
        val doc = Documentation.EMPTY
        val result = DocFormatter.formatAsMarkdown(doc)
        assertEquals("", result)
    }

    @Test
    fun `formats documentation without inline tags`() {
        val doc = Documentation(
            summary = "Simple method.",
            description = "Does something useful.",
            params = mapOf("arg" to "An argument"),
            returnDoc = "A result",
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        assertTrue(result.contains("Simple method."))
        assertTrue(result.contains("Does something useful."))
        assertTrue(result.contains("`arg`: An argument"))
        assertTrue(result.contains("**Returns:** A result"))
    }
}
