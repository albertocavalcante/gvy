package com.github.albertocavalcante.groovylsp.documentation

import com.github.albertocavalcante.groovyparser.ast.groovydoc.Groovydoc
import com.github.albertocavalcante.groovyparser.ast.groovydoc.GroovydocDescription

/**
 * Adapts the new GroovyDoc parser output to the existing Documentation model.
 * This bridges Phase 8 (GroovyDoc parser) with the hover/documentation system.
 */
object GroovyDocAdapter {
    /**
     * Converts a parsed Groovydoc to the Documentation model.
     *
     * @param groovydoc the parsed Groovydoc from the new parser
     * @return the Documentation model used by the hover system
     */
    fun toDocumentation(groovydoc: Groovydoc): Documentation {
        // Use the pre-parsed inline tags and render them to markdown
        val description = renderDescription(groovydoc.description)

        // Extract summary: first sentence or entire description if short
        val summary = extractSummary(description)

        // Extract @param tags with inline tag rendering
        val params = groovydoc.getParamTags().associate { tag ->
            (tag.name ?: "") to renderDescription(tag.content)
        }

        // Extract @return tag with inline tag rendering
        val returnDoc = groovydoc.getReturnTag()?.content?.let { renderDescription(it) } ?: ""

        // Extract @throws/@exception tags with inline tag rendering
        val throws = groovydoc.getThrowsTags().associate { tag ->
            (tag.name ?: "") to renderDescription(tag.content)
        }

        // Extract @since tag with inline tag rendering
        val since = groovydoc.getSinceTag()?.content?.let { renderDescription(it) } ?: ""

        // Extract @author tag with inline tag rendering
        val author = groovydoc.getAuthorTag()?.content?.let { renderDescription(it) } ?: ""

        // Extract @deprecated tag with inline tag rendering
        val deprecated = groovydoc.getDeprecatedTag()?.content?.let { renderDescription(it) } ?: ""

        // Extract @see tags with inline tag rendering
        val see = groovydoc.getSeeTags().map { renderDescription(it.content) }

        return Documentation(
            summary = summary,
            description = description,
            params = params,
            returnDoc = returnDoc,
            throws = throws,
            since = since,
            author = author,
            deprecated = deprecated,
            see = see,
        )
    }

    /**
     * Renders a GroovydocDescription to markdown, properly handling pre-parsed inline tags.
     * This avoids re-parsing inline tags that were already parsed by parser/core.
     */
    private fun renderDescription(description: GroovydocDescription): String {
        if (description.isEmpty()) return ""

        // If there are no inline tags, just return the text
        if (description.inlineTags.isEmpty()) {
            return description.toText()
        }

        // We have pre-parsed inline tags, so we need to render them properly
        // The text still contains the raw {@code ...} syntax, but we have the parsed tags
        // We need to replace them with the rendered markdown
        var result = description.toText()

        // Replace each inline tag with its rendered version
        for (tag in description.inlineTags) {
            val originalTag = tag.toText() // e.g., "{@code foo}"
            val renderedTag = InlineTagRenderer.renderTag(tag) // e.g., "`foo`"
            result = result.replace(originalTag, renderedTag)
        }

        return result
    }

    /**
     * Extracts the summary (first sentence) from a description.
     *
     * The summary is the text up to the first sentence-ending punctuation (. ? !)
     * followed by whitespace, or the first paragraph break, whichever comes first.
     * If the description is short (< 100 chars) and has no paragraph breaks or sentence endings,
     * the entire description is used as the summary.
     *
     * Note: The trailing sentence-ending punctuation is removed from the summary
     * for consistency with the regex-based parser behavior.
     */
    private fun extractSummary(description: String): String {
        if (description.isBlank()) return ""

        val trimmed = description.trim()

        // Find first sentence ending (. ? !) followed by whitespace or end of string
        val sentenceEndRegex = Regex("""[.?!](?:\s+|\s*$)""")
        val sentenceEndMatch = sentenceEndRegex.find(trimmed)

        // Find first paragraph break
        val paragraphBreakIndex = trimmed.indexOf("\n\n")

        val summary = when {
            // Both found - use whichever comes first
            sentenceEndMatch != null && paragraphBreakIndex >= 0 -> {
                if (sentenceEndMatch.range.first < paragraphBreakIndex) {
                    trimmed.substring(0, sentenceEndMatch.range.first + 1).trim()
                } else {
                    trimmed.substring(0, paragraphBreakIndex).trim()
                }
            }
            // Only sentence ending found
            sentenceEndMatch != null -> trimmed.substring(0, sentenceEndMatch.range.first + 1).trim()
            // Only paragraph break found
            paragraphBreakIndex >= 0 -> trimmed.substring(0, paragraphBreakIndex).trim()
            // Neither found - use entire description if short, otherwise truncate
            else -> {
                if (trimmed.length >= SUMMARY_MAX_LENGTH) {
                    // Truncate and return with ellipsis, don't trim it
                    return trimmed.take(SUMMARY_MAX_LENGTH) + "..."
                }
                trimmed
            }
        }

        // Remove trailing punctuation for consistency with regex parser
        return summary.trimEnd('.', '?', '!')
    }

    private const val SUMMARY_MAX_LENGTH = 100
}
