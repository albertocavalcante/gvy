package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyjenkins.metadata.MergedParameter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * TDD: Tests for SnippetBuilder - type-aware parameter snippet generation.
 *
 * Tests written FIRST (RED), then implementation follows (GREEN).
 */
class SnippetBuilderTest {

    @Test
    fun `should build snippet for String parameter with quotes`() {
        val param = MergedParameter(
            name = "message",
            type = "String",
            defaultValue = null,
            description = "A message",
            required = false,
            validValues = null,
            examples = emptyList(),
        )

        val snippet = SnippetBuilder.buildParameterSnippet("message", param)

        assertEquals("message: '\$1'", snippet)
    }

    @Test
    fun `should build snippet for boolean parameter with choice`() {
        val param = MergedParameter(
            name = "returnStdout",
            type = "boolean",
            defaultValue = "false",
            description = null,
            required = false,
            validValues = null,
            examples = emptyList(),
        )

        val snippet = SnippetBuilder.buildParameterSnippet("returnStdout", param)

        assertEquals("returnStdout: \${1|true,false|}", snippet)
    }

    @Test
    fun `should build snippet for Boolean wrapper type with choice`() {
        val param = MergedParameter(
            name = "returnStatus",
            type = "Boolean",
            defaultValue = null,
            description = null,
            required = false,
            validValues = null,
            examples = emptyList(),
        )

        val snippet = SnippetBuilder.buildParameterSnippet("returnStatus", param)

        assertEquals("returnStatus: \${1|true,false|}", snippet)
    }

    @Test
    fun `should build snippet for int parameter without quotes`() {
        val param = MergedParameter(
            name = "time",
            type = "int",
            defaultValue = null,
            description = "Timeout value",
            required = true,
            validValues = null,
            examples = emptyList(),
        )

        val snippet = SnippetBuilder.buildParameterSnippet("time", param)

        assertEquals("time: \$1", snippet)
    }

    @Test
    fun `should build snippet for Integer wrapper type without quotes`() {
        val param = MergedParameter(
            name = "count",
            type = "Integer",
            defaultValue = null,
            description = null,
            required = false,
            validValues = null,
            examples = emptyList(),
        )

        val snippet = SnippetBuilder.buildParameterSnippet("count", param)

        assertEquals("count: \$1", snippet)
    }

    @Test
    fun `should build snippet for enum with validValues`() {
        val param = MergedParameter(
            name = "unit",
            type = "String",
            defaultValue = "MINUTES",
            description = "Time unit",
            required = false,
            validValues = listOf("SECONDS", "MINUTES", "HOURS", "DAYS"),
            examples = emptyList(),
        )

        val snippet = SnippetBuilder.buildParameterSnippet("unit", param)

        assertEquals("unit: '\${1|SECONDS,MINUTES,HOURS,DAYS|}'", snippet)
    }

    @Test
    fun `should build snippet for closure parameter`() {
        val param = MergedParameter(
            name = "body",
            type = "Closure",
            defaultValue = null,
            description = "The body to execute",
            required = true,
            validValues = null,
            examples = emptyList(),
        )

        val snippet = SnippetBuilder.buildParameterSnippet("body", param)

        assertEquals("body: {\n    \$0\n}", snippet)
    }

    @Test
    fun `should build snippet for Map parameter`() {
        val param = MergedParameter(
            name = "env",
            type = "Map",
            defaultValue = null,
            description = "Environment variables",
            required = false,
            validValues = null,
            examples = emptyList(),
        )

        val snippet = SnippetBuilder.buildParameterSnippet("env", param)

        assertEquals("env: [\$1]", snippet)
    }

    @Test
    fun `should build snippet for List parameter`() {
        val param = MergedParameter(
            name = "files",
            type = "List",
            defaultValue = null,
            description = "List of files",
            required = false,
            validValues = null,
            examples = emptyList(),
        )

        val snippet = SnippetBuilder.buildParameterSnippet("files", param)

        assertEquals("files: [\$1]", snippet)
    }

    @Test
    fun `should handle unknown types as String`() {
        val param = MergedParameter(
            name = "custom",
            type = "CustomClass",
            defaultValue = null,
            description = null,
            required = false,
            validValues = null,
            examples = emptyList(),
        )

        val snippet = SnippetBuilder.buildParameterSnippet("custom", param)

        // Unknown types default to quoted string
        assertEquals("custom: '\$1'", snippet)
    }

    @Test
    fun `should escape special characters in validValues`() {
        val param = MergedParameter(
            name = "pattern",
            type = "String",
            defaultValue = null,
            description = null,
            required = false,
            validValues = listOf("a|b", "c,d"),
            examples = emptyList(),
        )

        val snippet = SnippetBuilder.buildParameterSnippet("pattern", param)

        // Special chars in validValues should be escaped
        assertEquals("pattern: '\${1|a\\|b,c\\,d|}'", snippet)
    }

    @Test
    fun `should not quote int validValues`() {
        val param = MergedParameter(
            name = "exitCode",
            type = "int",
            defaultValue = null,
            description = null,
            required = false,
            validValues = listOf("0", "1", "2"),
            examples = emptyList(),
        )

        val snippet = SnippetBuilder.buildParameterSnippet("exitCode", param)

        // Int validValues should not be quoted
        assertEquals("exitCode: \${1|0,1,2|}", snippet)
    }

    @Test
    fun `should handle array types as list`() {
        val param = MergedParameter(
            name = "args",
            type = "String[]",
            defaultValue = null,
            description = null,
            required = false,
            validValues = null,
            examples = emptyList(),
        )

        val snippet = SnippetBuilder.buildParameterSnippet("args", param)

        // Array types should be treated as list
        assertEquals("args: [\$1]", snippet)
    }

    @Test
    fun `should escape dollar sign in validValues`() {
        val param = MergedParameter(
            name = "variable",
            type = "String",
            defaultValue = null,
            description = null,
            required = false,
            validValues = listOf("\$HOME", "\$PATH"),
            examples = emptyList(),
        )

        val snippet = SnippetBuilder.buildParameterSnippet("variable", param)

        // Dollar signs should be escaped
        assertEquals("variable: '\${1|\\\$HOME,\\\$PATH|}'", snippet)
    }
}
