package com.github.albertocavalcante.groovylsp.documentation

import com.github.albertocavalcante.groovyparser.ast.groovydoc.Groovydoc

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
        val description = groovydoc.description.toText()

        // Extract summary: first sentence or entire description if short
        val summary = extractSummary(description)

        // Extract @param tags
        val params = groovydoc.getParamTags().associate { tag ->
            (tag.name ?: "") to tag.content.toText()
        }

        // Extract @return tag
        val returnDoc = groovydoc.getReturnTag()?.content?.toText() ?: ""

        // Extract @throws/@exception tags
        val throws = groovydoc.getThrowsTags().associate { tag ->
            (tag.name ?: "") to tag.content.toText()
        }

        // Extract @since tag
        val since = groovydoc.getSinceTag()?.content?.toText() ?: ""

        // Extract @author tag
        val author = groovydoc.getAuthorTag()?.content?.toText() ?: ""

        // Extract @deprecated tag
        val deprecated = groovydoc.getDeprecatedTag()?.content?.toText() ?: ""

        // Extract @see tags
        val see = groovydoc.getSeeTags().map { it.content.toText() }

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
            // Neither found - use entire description if short
            else -> if (trimmed.length < SUMMARY_MAX_LENGTH) trimmed else trimmed
        }

        // Remove trailing punctuation for consistency with regex parser
        return summary.trimEnd('.', '?', '!')
    }

    private const val SUMMARY_MAX_LENGTH = 100
}
