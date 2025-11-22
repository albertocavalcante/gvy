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
        if (text.isBlank()) {
            return text
        }

        val sourceFiles = parser.parse(text).collect(Collectors.toList())
        if (sourceFiles.isEmpty()) {
            return text
        }

        val recipeRun = recipe.run(InMemoryLargeSourceSet(sourceFiles), executionContext)
        val formattedSource = recipeRun.changeset.allResults
            .mapNotNull { it.after }
            .firstOrNull()
            ?: return text
        val normalizedSource = PostFormatWhitespaceCollapser().visit(formattedSource, Unit) as SourceFile
        val normalizedText = normalizedSource.printAll()
        // TODO(#formatter-followup): move this token-level collapse into a dedicated OpenRewrite recipe once we upstream Groovy spacing fixes.
        return MULTI_SPACE_WITHIN_TOKEN_REGEX.replace(normalizedText) { " " }
    }

    private class PostFormatWhitespaceCollapser : GroovyIsoVisitor<Unit>() {
        override fun visitSpace(space: Space?, loc: Space.Location, p: Unit): Space {
            val actualSpace = space ?: return super.visitSpace(null, loc, p)
            val whitespace = actualSpace.whitespace
            val collapsedWhitespace =
                if (whitespace.length > 1 && '\n' !in whitespace && space.comments.isEmpty()) {
                    MULTIPLE_SPACES_REGEX.replace(whitespace, " ")
                } else {
                    whitespace
                }

            val updatedSpace = if (collapsedWhitespace != whitespace) {
                actualSpace.withWhitespace(collapsedWhitespace)
            } else {
                actualSpace
            }

            // TODO(#formatter-upstream): once OpenRewrite collapses redundant spaces for Groovy scripts, drop this visitor.
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
