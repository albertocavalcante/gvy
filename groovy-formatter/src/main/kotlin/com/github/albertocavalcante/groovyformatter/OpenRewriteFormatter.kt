package com.github.albertocavalcante.groovyformatter

import com.github.albertocavalcante.groovycommon.text.ShebangUtils
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
        // Delegated to shared ShebangUtils to avoid duplication.
        val extraction = ShebangUtils.extractShebang(text)

        // Handle shebang-only files (no content to format)
        if (extraction.shebang != null && extraction.content.isBlank()) {
            return extraction.shebang!!.trimEnd()
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
    }
}
