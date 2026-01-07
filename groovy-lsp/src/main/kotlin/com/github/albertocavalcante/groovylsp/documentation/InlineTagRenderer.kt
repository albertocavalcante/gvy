package com.github.albertocavalcante.groovylsp.documentation

/**
 * Renders GroovyDoc/JavaDoc inline tags to markdown.
 *
 * Supported tags:
 * - {@code ...} → `...`
 * - {@link ...} → formatted reference
 * - {@linkplain ...} → plain text reference
 * - {@literal ...} → escaped text
 * - {@value ...} → constant value (best effort)
 */
object InlineTagRenderer {
    private val INLINE_TAG_PATTERN = """\{@(\w+)\s+([^}]+)\}""".toRegex()

    /**
     * Render all inline tags in the given text to markdown.
     *
     * @param text The text containing inline tags
     * @return Text with inline tags converted to markdown
     */
    fun render(text: String): String = INLINE_TAG_PATTERN.replace(text) { match ->
        val tag = match.groupValues[1]
        val content = match.groupValues[2].trim()
        when (tag) {
            "code" -> renderCode(content)
            "link" -> renderLink(content)
            "linkplain" -> renderLinkPlain(content)
            "literal" -> renderLiteral(content)
            "value" -> renderValue(content)
            else -> match.value // Unknown tag, keep as-is
        }
    }

    /**
     * Render {@code ...} tag as inline code.
     */
    private fun renderCode(content: String): String = "`$content`"

    /**
     * Render {@link ...} tag as formatted reference.
     *
     * Extracts class#method or Class notation and formats as code.
     * Examples:
     * - {@link java.util.List} → `List`
     * - {@link String#length()} → `String.length()`
     * - {@link #method()} → `method()`
     */
    private fun renderLink(content: String): String {
        val formatted = formatReference(content)
        return "`$formatted`"
    }

    /**
     * Render {@linkplain ...} tag as plain text reference.
     *
     * Similar to {@link} but without code formatting.
     */
    private fun renderLinkPlain(content: String): String = formatReference(content)

    /**
     * Render {@literal ...} tag with HTML entity escaping.
     *
     * Escapes special characters that might be interpreted as markdown.
     */
    private fun renderLiteral(content: String): String = content
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /**
     * Render {@value ...} tag.
     *
     * For now, returns the reference as-is since we can't resolve constant values
     * without full type resolution context.
     */
    private fun renderValue(content: String): String {
        // Strip leading # if present
        val reference = content.removePrefix("#")
        return "`$reference`"
    }

    /**
     * Format a reference (class, method, field) for display.
     *
     * Examples:
     * - "java.util.List" → "List"
     * - "String#length()" → "String.length()"
     * - "#method()" → "method()"
     * - "com.example.MyClass#CONSTANT" → "MyClass.CONSTANT"
     */
    private fun formatReference(reference: String): String {
        // Handle #method or #field (local reference)
        if (reference.startsWith("#")) {
            return reference.removePrefix("#")
        }

        // Handle Class#member
        val parts = reference.split("#", limit = 2)
        val className = parts[0].trim()
        val memberName = parts.getOrNull(1)?.trim()

        // Extract simple class name (last part after '.')
        val simpleClassName = className.substringAfterLast('.')

        return if (memberName != null) {
            // Format as ClassName.member
            "$simpleClassName.$memberName"
        } else {
            // Just the class name
            simpleClassName
        }
    }
}
