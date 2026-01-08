package com.github.albertocavalcante.groovylsp.documentation

import kotlin.test.Test
import kotlin.test.assertEquals

class InlineTagRendererTest {

    @Test
    fun `renders code tag as inline code`() {
        val input = "Use {@code String.valueOf()} to convert."
        val expected = "Use `String.valueOf()` to convert."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders multiple code tags`() {
        val input = "Call {@code foo()} or {@code bar()} methods."
        val expected = "Call `foo()` or `bar()` methods."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders link tag with simple class name`() {
        val input = "See {@link java.util.List} for details."
        val expected = "See `List` for details."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders link tag with class and method`() {
        val input = "Use {@link String#length()} to get size."
        val expected = "Use `String.length()` to get size."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders link tag with fully qualified class and method`() {
        val input = "Call {@link java.lang.String#valueOf(int)} method."
        val expected = "Call `String.valueOf(int)` method."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders link tag with local method reference`() {
        val input = "See {@link #process()} method."
        val expected = "See `process()` method."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders link tag with class and field`() {
        val input = "Use {@link java.lang.Integer#MAX_VALUE} constant."
        val expected = "Use `Integer.MAX_VALUE` constant."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders linkplain tag as plain text`() {
        val input = "See {@linkplain java.util.List} for more info."
        val expected = "See List for more info."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders linkplain tag with method as plain text`() {
        val input = "Call {@linkplain String#length()} to get length."
        val expected = "Call String.length() to get length."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders literal tag with HTML escaping`() {
        val input = "Use {@literal List<String>} for strings."
        val expected = "Use List&lt;String&gt; for strings."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders literal tag with multiple special characters`() {
        val input = "Pattern: {@literal <T extends Comparable<T>>}"
        val expected = "Pattern: &lt;T extends Comparable&lt;T&gt;&gt;"
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders literal tag with ampersand`() {
        val input = "Use {@literal A & B} for intersection."
        val expected = "Use A &amp; B for intersection."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders literal tag with quotes`() {
        val input = "Format: {@literal \"value\"}"
        val expected = "Format: &quot;value&quot;"
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders value tag with field reference`() {
        val input = "Default is {@value #DEFAULT_SIZE}."
        val expected = "Default is `DEFAULT_SIZE`."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders value tag with class and field`() {
        val input = "Max value: {@value Integer#MAX_VALUE}"
        val expected = "Max value: `Integer#MAX_VALUE`"
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `handles text without inline tags`() {
        val input = "This is plain text without any tags."
        val expected = "This is plain text without any tags."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `handles empty string`() {
        val input = ""
        val expected = ""
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `handles malformed tag without closing brace`() {
        val input = "Use {@code foo( for something."
        val expected = "Use {@code foo( for something."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `handles unknown tag type`() {
        val input = "See {@unknown something} for details."
        val expected = "See {@unknown something} for details."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders multiple different tags in same string`() {
        val input = "Use {@code valueOf()} from {@link String} or {@literal <T>} type."
        val expected = "Use `valueOf()` from `String` or &lt;T&gt; type."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `handles nested angle brackets in literal tag`() {
        val input = "Type: {@literal Map<String, List<Integer>>}"
        val expected = "Type: Map&lt;String, List&lt;Integer&gt;&gt;"
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders code tag with generic syntax`() {
        val input = "Use {@code List<String>} for strings."
        val expected = "Use `List<String>` for strings."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `handles whitespace in tag content`() {
        val input = "Call {@link   java.util.List   } method."
        val expected = "Call `List` method."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders link with package path`() {
        val input = "See {@link com.example.package.MyClass#myMethod()} for implementation."
        val expected = "See `MyClass.myMethod()` for implementation."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `handles single quote in literal tag`() {
        val input = "Use {@literal 'value'} format."
        val expected = "Use &#39;value&#39; format."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders consecutive tags without spaces`() {
        val input = "Types: {@code Foo}{@code Bar}"
        val expected = "Types: `Foo``Bar`"
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `handles tag at start of string`() {
        val input = "{@code method()} is the entry point."
        val expected = "`method()` is the entry point."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `handles tag at end of string`() {
        val input = "Entry point is {@code method()}"
        val expected = "Entry point is `method()`"
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders link to constructor`() {
        val input = "Use {@link String#String(byte[])} constructor."
        val expected = "Use `String.String(byte[])` constructor."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `handles value tag without hash prefix`() {
        val input = "Value: {@value MAX_SIZE}"
        val expected = "Value: `MAX_SIZE`"
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders code tag with special method syntax`() {
        val input = "Override {@code equals(Object obj)} method."
        val expected = "Override `equals(Object obj)` method."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `handles link to inner class`() {
        val input = "See {@link java.util.Map.Entry} interface."
        val expected = "See `Entry` interface."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders linkplain with local reference`() {
        val input = "See {@linkplain #helper()} for details."
        val expected = "See helper() for details."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `handles complex documentation example`() {
        val input = """
            This method uses {@link java.util.List} to store {@code String} values.
            Call {@link #process()} with {@literal <T>} type parameter.
            Default size is {@value #DEFAULT_SIZE}.
        """.trimIndent()

        val expected = """
            This method uses `List` to store `String` values.
            Call `process()` with &lt;T&gt; type parameter.
            Default size is `DEFAULT_SIZE`.
        """.trimIndent()

        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders code tag with curly braces in closure`() {
        val input = "Use {@code { x -> x * 2 }} for transformation."
        val expected = "Use `{ x -> x * 2 }` for transformation."
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders code tag with nested curly braces`() {
        val input = "Map: {@code { key: { nested: value } }}"
        val expected = "Map: `{ key: { nested: value } }`"
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders code tag with complex closure syntax`() {
        val input = "Example: {@code list.each { item -> println \"Value: \${'$'}{item}\" }}"
        val expected = "Example: `list.each { item -> println \"Value: \${'$'}{item}\" }`"
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders code tag with deeply nested braces`() {
        val input = "{@code { a: { b: { c: { d: value } } } }}"
        val expected = "`{ a: { b: { c: { d: value } } } }`"
        assertEquals(expected, InlineTagRenderer.render(input))
    }

    @Test
    fun `renders code tag with mixed brackets and braces`() {
        val input = "Use {@code map.collect { [it.key, it.value] }} to transform."
        val expected = "Use `map.collect { [it.key, it.value] }` to transform."
        assertEquals(expected, InlineTagRenderer.render(input))
    }
}
