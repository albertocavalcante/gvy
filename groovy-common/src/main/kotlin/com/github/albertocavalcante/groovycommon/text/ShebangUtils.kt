package com.github.albertocavalcante.groovycommon.text

/**
 * Utilities for handling Unix shebang lines (e.g., #!/usr/bin/env groovy) in source code.
 */
object ShebangUtils {
    private val SHEBANG_REGEX = Regex("^#![^\\n]*(\\r?\\n)?")

    /**
     * Result of shebang extraction.
     *
     * @property shebang The extracted shebang line (e.g., "#!/usr/bin/env groovy\n"), or null if not present.
     * @property content The remaining content of the source, with the shebang removed.
     */
    data class ExtractionResult(val shebang: String?, val content: String)

    /**
     * Replaces the shebang line with an empty line to preserve line numbers.
     * This is useful for parsers (like Groovy's generic parser) that don't support shebangs
     * but where AST nodes need to map to the correct lines in the original file.
     *
     * Example:
     * Input:
     * #!/usr/bin/env groovy
     * pipeline { ... }
     *
     * Output:
     *
     * pipeline { ... }
     * (First line is empty)
     */
    fun replaceShebangWithEmptyLine(source: String): String {
        if (!source.startsWith("#!")) return source

        val lines = source.lines().toMutableList()
        if (lines.isNotEmpty()) {
            lines[0] = "" // Replace content with empty string, lines.joinToString will restore newline separators
        }
        return lines.joinToString("\n")
    }

    /**
     * Extracts the shebang from the source code.
     *
     * returns a [ExtractionResult] containing the normalized shebang (always ending in \n if present)
     * and the remaining content.
     */
    fun extractShebang(source: String): ExtractionResult {
        val match = SHEBANG_REGEX.find(source) ?: return ExtractionResult(null, source)

        // Normalize to Unix line ending for the shebang part
        // Review suggestion: Simplify normalization logic
        val normalizedShebang = match.value.trimEnd('\r', '\n') + "\n"

        val remainingContent = source.substring(match.range.last + 1)
        return ExtractionResult(normalizedShebang, remainingContent)
    }
}
