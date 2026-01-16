package com.github.albertocavalcante.groovylsp.providers.completion

/**
 * Best-effort, line-based import parsing for fallback scenarios.
 * Supports simple multi-line imports but does not handle full Groovy syntax.
 */
internal object TextImportParser {
    /**
     * Parses a Groovy source file content to extract package name and imports.
     * Handles multi-line imports and ignores static imports.
     */
    fun parse(content: String): TextImportInfo {
        val state = ParserState()
        val result = ParseResult()

        for (line in content.lineSequence()) {
            if (!processLine(line, state, result)) break
        }

        finalizePendingImport(state, result)
        return TextImportInfo(result.packageName, result.explicitImports, result.starImports)
    }

    private class ParserState {
        var inBlockComment = false
        var pendingImport: String? = null
        var pendingImportIsStatic = false
    }

    private class ParseResult {
        var packageName: String? = null
        val explicitImports = mutableSetOf<String>()
        val starImports = mutableSetOf<String>()
    }

    /**
     * Process a single line of source code.
     * Returns false if we should stop processing (e.g., reached code declaration).
     */
    private fun processLine(line: String, state: ParserState, result: ParseResult): Boolean {
        var trimmed = line.trim()
        if (trimmed.isBlank()) return true

        // Handle block comments
        val afterBlockComment = stripBlockComment(trimmed, state) ?: return true
        trimmed = afterBlockComment

        // Skip line comments
        if (trimmed.startsWith("//")) {
            return true
        }

        // Handle pending multi-line import
        if (state.pendingImport != null) {
            return handlePendingImport(trimmed, state, result)
        }

        // Handle new package/import/declaration lines
        return handleNewLine(trimmed, state, result)
    }

    /**
     * Strips block comments from a line.
     * Returns null if the line should be skipped entirely, otherwise returns the cleaned line.
     */
    private fun stripBlockComment(trimmed: String, state: ParserState): String? {
        var result = trimmed

        // If we're already in a block comment, look for the end
        if (state.inBlockComment) {
            if (result.contains("*/")) {
                result = result.substringAfter("*/").trim()
                state.inBlockComment = false
            } else {
                return null
            }
        }

        // Handle inline block comments (/* ... */ on same line)
        if (result.contains("/*") && result.contains("*/")) {
            val before = result.substringBefore("/*")
            val after = result.substringAfter("*/")
            result = "$before $after".trim()
            if (result.isBlank()) return null
        }

        // Handle start of block comment
        if (result.startsWith("/*")) {
            if (!result.contains("*/")) {
                state.inBlockComment = true
            }
            return null
        }

        return result
    }

    /**
     * Handle continuation of a multi-line import.
     * Returns false if we should stop processing lines.
     */
    private fun handlePendingImport(trimmed: String, state: ParserState, result: ParseResult): Boolean {
        // If we hit a new statement, finalize the pending import
        if (trimmed.startsWith("package ") || trimmed.startsWith("import") || isCodeDeclarationLine(trimmed)) {
            if (!state.pendingImportIsStatic && state.pendingImport!!.isNotBlank() &&
                !state.pendingImport!!.endsWith(".")
            ) {
                recordImport(state.pendingImport!!, result)
            }
            state.pendingImport = null
            state.pendingImportIsStatic = false
            // Don't continue - let handleNewLine process this line
            return handleNewLine(trimmed, state, result)
        }

        // Continue building the multi-line import
        val separator = if (state.pendingImport!!.endsWith(".") || trimmed.startsWith(".")) "" else " "
        val combined = (state.pendingImport + separator + trimmed).trim()
        val cleaned = combined.substringBefore(";").trim()
        val hasSemicolon = combined.contains(";")
        val continues = isImportContinuation(cleaned)

        if (hasSemicolon || !continues) {
            if (!state.pendingImportIsStatic && cleaned.isNotBlank()) {
                recordImport(cleaned.removeSuffix("\\").trim(), result)
            }
            state.pendingImport = null
            state.pendingImportIsStatic = false
        } else {
            state.pendingImport = cleaned.removeSuffix("\\").trim()
        }

        return true
    }

    /**
     * Handle a new line that might be a package, import, or code declaration.
     * Returns false if we should stop processing lines.
     */
    private fun handleNewLine(trimmed: String, state: ParserState, result: ParseResult): Boolean {
        when {
            trimmed.startsWith("package ") -> {
                result.packageName = trimmed.removePrefix("package ").removeSuffix(";").trim()
            }

            trimmed.startsWith("import") -> {
                val isStatic = trimmed.startsWith("import static")
                val importPrefix = if (isStatic) "import static" else "import"
                val value = trimmed.removePrefix(importPrefix).trim()

                if (value.isBlank()) {
                    state.pendingImport = ""
                    state.pendingImportIsStatic = isStatic
                    return true
                }

                val cleaned = value.substringBefore(";").trim()
                val hasSemicolon = value.contains(";")
                val continues = isImportContinuation(cleaned)

                if (hasSemicolon || !continues) {
                    if (!isStatic && cleaned.isNotBlank()) {
                        recordImport(cleaned.removeSuffix("\\").trim(), result)
                    }
                } else {
                    state.pendingImport = cleaned.removeSuffix("\\").trim()
                    state.pendingImportIsStatic = isStatic
                }
            }

            isCodeDeclarationLine(trimmed) -> return false
        }

        return true
    }

    /**
     * Records an import, distinguishing between star imports and explicit imports.
     */
    private fun recordImport(value: String, result: ParseResult) {
        if (value.endsWith(".*")) {
            result.starImports.add(value.removeSuffix(".*"))
        } else {
            result.explicitImports.add(value)
        }
    }

    /**
     * Checks if an import statement continues on the next line.
     */
    private fun isImportContinuation(value: String): Boolean = value.endsWith(".") || value.endsWith("\\")

    /**
     * Matches Groovy code declarations with optional modifiers, such as:
     *   class Foo
     *   public class Foo
     *   private static final class Foo
     *   def bar()
     *   public def bar()
     */
    private val CODE_DECLARATION_PATTERN =
        Regex("""^(?:(?:public|protected|private|static|final|abstract)\s+)*(class|interface|enum|trait|def)\b""")

    private fun isCodeDeclarationLine(trimmed: String): Boolean = CODE_DECLARATION_PATTERN.containsMatchIn(trimmed)

    /**
     * Finalize any pending import at the end of file.
     */
    private fun finalizePendingImport(state: ParserState, result: ParseResult) {
        val pending = state.pendingImport
        if (pending != null &&
            !state.pendingImportIsStatic &&
            pending.isNotBlank() &&
            !pending.endsWith(".")
        ) {
            recordImport(pending, result)
        }
    }
}

/**
 * Result of parsing import information from source text.
 */
internal data class TextImportInfo(
    val packageName: String?,
    val explicitImports: Set<String>,
    val starImports: Set<String>,
)
