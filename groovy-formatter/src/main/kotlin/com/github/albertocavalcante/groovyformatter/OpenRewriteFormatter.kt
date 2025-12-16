package com.github.albertocavalcante.groovyformatter

import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.SourceFile
import org.openrewrite.groovy.GroovyIsoVisitor
import org.openrewrite.groovy.GroovyParser
import org.openrewrite.groovy.format.AutoFormat
import org.openrewrite.internal.InMemoryLargeSourceSet
import org.openrewrite.java.tree.Space
import java.util.stream.Collectors

class OpenRewriteFormatter {

    private val parser: GroovyParser = GroovyParser.builder().build()
    private val executionContext: ExecutionContext = InMemoryExecutionContext { t: Throwable -> t.printStackTrace() }
    private val recipe = AutoFormat()

    fun format(text: String): String {
        if (text.isBlank()) return text

        // NOTE: Shebang extraction is a heuristic workaround. OpenRewrite's GroovyParser doesn't
        // recognize shebangs because they're Unix shell directives, not Groovy syntax. We extract
        // them before parsing to prevent mangling, then restore them after formatting.
        //
        // TODO: Monitor OpenRewrite releases. If they add native shebang support for script files,
        // remove this pre/post-processing logic and rely on semantic parsing instead.
        //
        // Trade-off: This string-based approach works for 99% of cases but could theoretically
        // fail if someone has a multi-line string that starts with "#!" as the first line (extremely
        // unlikely in practice, as shebangs are always the actual first line of executable scripts).
        val extraction = extractShebang(text)

        // Handle shebang-only files (no content to format)
        if (extraction.shebang != null && extraction.content.isBlank()) {
            return extraction.shebang.trimEnd()
        }

        val sourceFiles = parser.parse(extraction.content).collect(Collectors.toList())
        if (sourceFiles.isEmpty()) return text

        val formattedSource = recipe.run(InMemoryLargeSourceSet(sourceFiles), executionContext)
            .changeset
            .allResults
            .mapNotNull { it.after }
            .firstOrNull()
            ?: return text

        val normalizedSource = PostFormatWhitespaceCollapser().visit(formattedSource, Unit) as SourceFile
        // TODO(#formatter-followup): move this token-level collapse into a dedicated OpenRewrite recipe once we upstream Groovy spacing fixes.
        val formattedText = normalizedSource.printAll()
            .let { MULTI_SPACE_WITHIN_TOKEN_REGEX.replace(it) { " " } }
            .normalizeLineEndings()

        // Restore shebang with normalized spacing (always one blank line)
        return extraction.shebang?.let { shebang ->
            "$shebang\n${formattedText.trimStart()}"
        } ?: formattedText
    }

    /**
     * Result of shebang extraction with descriptive property names.
     *
     * @property shebang The extracted shebang line (null if not present), normalized to Unix line endings
     * @property content The remaining script content after shebang extraction
     */
    private data class ShebangExtraction(val shebang: String?, val content: String)

    /**
     * Detects and extracts a shebang line from the beginning of the script.
     *
     * NOTE: This is a heuristic approach. Shebangs are Unix shell directives, not part of
     * the Groovy language spec, so OpenRewrite's GroovyParser doesn't handle them. We work
     * around this by extracting the shebang before parsing and restoring it after formatting.
     *
     * TODO: If OpenRewrite adds native shebang support for scripts in the future, remove this
     * pre/post-processing and rely on the parser's semantic understanding.
     *
     * @param text The script text to analyze
     * @return ShebangExtraction containing the shebang (if present) and remaining content
     */
    private fun extractShebang(text: String): ShebangExtraction = SHEBANG_REGEX.find(text)?.let { match ->
        val normalizedShebang = match.value
            .normalizeLineEndings()
            .trimEnd('\n', '\r') + "\n"
        val remainingContent = text.substring(match.value.length)
        ShebangExtraction(normalizedShebang, remainingContent)
    } ?: ShebangExtraction(null, text)

    /**
     * Normalizes line endings to Unix style (LF) for consistency.
     */
    private fun String.normalizeLineEndings(): String = replace("\r\n", "\n")

    /**
     * Visitor that collapses multiple consecutive spaces within code.
     *
     * TODO(#formatter-upstream): once OpenRewrite collapses redundant spaces for Groovy scripts, drop this visitor.
     */
    private class PostFormatWhitespaceCollapser : GroovyIsoVisitor<Unit>() {
        override fun visitSpace(space: Space?, loc: Space.Location, p: Unit): Space {
            val actualSpace = space ?: return super.visitSpace(null, loc, p)

            val shouldCollapse = actualSpace.whitespace.length > 1 &&
                '\n' !in actualSpace.whitespace &&
                actualSpace.comments.isEmpty()

            val updatedSpace = if (shouldCollapse) {
                val collapsed = MULTIPLE_SPACES_REGEX.replace(actualSpace.whitespace, " ")
                actualSpace.withWhitespace(collapsed)
            } else {
                actualSpace
            }

            return super.visitSpace(updatedSpace, loc, p)
        }

        companion object {
            private val MULTIPLE_SPACES_REGEX = Regex(" {2,}")
        }
    }

    companion object {
        private val MULTI_SPACE_WITHIN_TOKEN_REGEX = Regex("(?<=\\S) {2,}(?=\\S)")
        private val SHEBANG_REGEX = Regex("^#![^\\n]*(\\r?\\n)?")
    }
}
