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

        // Phase 3: Now uses table format for multiple parameters
        assertTrue(result.contains("`value`"))
        assertTrue(result.contains("The `String` value to process"))
        assertTrue(result.contains("`list`"))
        assertTrue(result.contains("A `List` of items"))
    }

    @Test
    fun `formats documentation with inline tags in return doc`() {
        val doc = Documentation(
            summary = "Retrieves data.",
            returnDoc = "A {@link java.util.Map} containing {@code key-value} pairs",
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        // Phase 3: Now uses section header
        assertTrue(result.contains("#### Returns"))
        assertTrue(result.contains("A `Map` containing `key-value` pairs"))
    }

    @Test
    fun `formats documentation with inline tags in deprecated notice`() {
        val doc = Documentation(
            summary = "Old method.",
            deprecated = "Use {@link #newMethod()} instead of {@code oldMethod()}",
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        // Phase 3: Now uses blockquote with emoji
        assertTrue(result.contains("⚠️ **Deprecated**"))
        assertTrue(result.contains("Use `newMethod()` instead of `oldMethod()`"))
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

        // Phase 3: Now uses section header and em dash separator
        assertTrue(result.contains("#### Throws"))
        assertTrue(result.contains("`IOException`"))
        assertTrue(result.contains("If `file` cannot be read"))
        assertTrue(result.contains("`IllegalArgumentException`"))
        assertTrue(result.contains("If `validate()` fails"))
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
        // Phase 3: Single param uses inline format with em dash
        assertTrue(result.contains("**arg** — An argument"))
        // Phase 3: Returns uses section header
        assertTrue(result.contains("#### Returns"))
        assertTrue(result.contains("A result"))
    }

    // Phase 3: Visual Hierarchy Tests

    @Test
    fun `formatAsMarkdown with deprecation shows warning at top with visual prominence`() {
        val doc = Documentation(
            summary = "Old method that should not be used.",
            description = "This method has been superseded.",
            deprecated = "since 2.0: Use Calculator.add() instead.",
            params = mapOf("x" to "first number"),
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        // Deprecation should appear at top as blockquote with warning emoji
        val lines = result.lines()
        val deprecationIndex = lines.indexOfFirst { it.contains("Deprecated") }
        val summaryIndex = lines.indexOfFirst { it.contains("Old method") }

        assertTrue(deprecationIndex >= 0, "Should contain deprecation warning")
        assertTrue(summaryIndex >= 0, "Should contain summary")
        assertTrue(deprecationIndex < summaryIndex, "Deprecation should appear before summary")

        // Should use blockquote format with emoji
        assertTrue(result.contains("> ⚠️ **Deprecated**"), "Should have prominent deprecation warning")
        assertTrue(result.contains("since 2.0: Use Calculator.add() instead."))
    }

    @Test
    fun `formatAsMarkdown with multiple params uses table format with section header`() {
        val doc = Documentation(
            summary = "Calculates sum.",
            params = mapOf(
                "x" to "the first number",
                "y" to "the second number",
                "z" to "the third number",
            ),
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        // Should have section header
        assertTrue(result.contains("#### Parameters"), "Should have Parameters section header")

        // Should have table format (with spaces in separator)
        assertTrue(result.contains("| Name | Type | Description |"), "Should have table headers")
        assertTrue(result.contains("| --- | --- | --- |"), "Should have table separator")
        assertTrue(result.contains("| `x` |"), "Should have x parameter in table")
        assertTrue(result.contains("| `y` |"), "Should have y parameter in table")
        assertTrue(result.contains("| `z` |"), "Should have z parameter in table")
    }

    @Test
    fun `formatAsMarkdown with single param uses inline format`() {
        val doc = Documentation(
            summary = "Validates input.",
            params = mapOf("value" to "the value to validate"),
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        // Should NOT have table format
        assertTrue(!result.contains("| Name | Type |"), "Should NOT use table format for single param")

        // Should use inline format with section header
        assertTrue(result.contains("#### Parameters"), "Should have Parameters section header")
        assertTrue(result.contains("**value** — the value to validate"), "Should have inline parameter format")
    }

    @Test
    fun `formatAsMarkdown with return doc shows section header`() {
        val doc = Documentation(
            summary = "Gets value.",
            returnDoc = "the calculated result",
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        assertTrue(result.contains("#### Returns"), "Should have Returns section header")
        assertTrue(result.contains("the calculated result"))
    }

    @Test
    fun `formatAsMarkdown with throws shows section header`() {
        val doc = Documentation(
            summary = "Risky operation.",
            throws = mapOf(
                "IOException" to "if file cannot be read",
                "IllegalArgumentException" to "if input is invalid",
            ),
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        assertTrue(result.contains("#### Throws"), "Should have Throws section header")
        assertTrue(result.contains("`IOException`"))
        assertTrue(result.contains("`IllegalArgumentException`"))
    }

    @Test
    fun `formatAsMarkdown with metadata shows footer with separators`() {
        val doc = Documentation(
            summary = "Some method.",
            since = "1.0",
            author = "John Doe",
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        // Should have horizontal rule before metadata
        assertTrue(result.contains("---"), "Should have horizontal rule separator")

        // Should show metadata in footer format with italic and separator
        assertTrue(result.contains("*@since 1.0*"), "Should have italic since metadata")
        assertTrue(result.contains("*@author John Doe*"), "Should have italic author metadata")
        assertTrue(result.contains(" · "), "Should have bullet separator between metadata")
    }

    @Test
    fun `formatAsMarkdown uses visual separators between major sections`() {
        val doc = Documentation(
            summary = "Complex method.",
            description = "With detailed description.",
            params = mapOf("x" to "first param", "y" to "second param"),
            returnDoc = "the result",
            throws = mapOf("Exception" to "on error"),
            since = "1.0",
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        // Should have separators between major sections
        assertTrue(result.contains("---"), "Should have horizontal rule separators")

        // Verify structure: description, params section, returns section, throws section, metadata
        assertTrue(result.contains("#### Parameters"))
        assertTrue(result.contains("#### Returns"))
        assertTrue(result.contains("#### Throws"))
        assertTrue(result.contains("*@since 1.0*"))
    }

    @Test
    fun `formatAsMarkdown with see also shows section header`() {
        val doc = Documentation(
            summary = "Related method.",
            see = listOf("Calculator.subtract()", "Math.abs()"),
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        assertTrue(result.contains("#### See Also"), "Should have See Also section header")
        assertTrue(result.contains("Calculator.subtract()"))
        assertTrue(result.contains("Math.abs()"))
    }

    @Test
    fun `formatAsMarkdown without deprecated does not show warning`() {
        val doc = Documentation(
            summary = "Current method.",
            params = mapOf("x" to "param"),
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        assertTrue(!result.contains("Deprecated"), "Should NOT show deprecation warning")
        assertTrue(!result.contains("⚠️"), "Should NOT show warning emoji")
    }

    @Test
    fun `formatAsMarkdown with only metadata shows footer without leading separator`() {
        val doc = Documentation(
            summary = "Simple method.",
            since = "2.0",
        )

        val result = DocFormatter.formatAsMarkdown(doc)

        // Should have metadata but check separator placement
        assertTrue(result.contains("*@since 2.0*"))
        assertTrue(result.contains("---"))
    }
}
