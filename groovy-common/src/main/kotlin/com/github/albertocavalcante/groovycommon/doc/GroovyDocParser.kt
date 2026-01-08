package com.github.albertocavalcante.groovycommon.doc

/**
 * Parser for GroovyDoc comments.
 *
 * @deprecated Use `com.github.albertocavalcante.groovyparser.ast.groovydoc.Groovydoc` from parser/core instead.
 * This parser is superseded by the parser in parser/core which provides richer functionality including pre-parsed
 * inline tags. This version is maintained only for backward compatibility and will be removed in a future release.
 */
@Deprecated(
    message = "Use com.github.albertocavalcante.groovyparser.ast.groovydoc.Groovydoc from parser/core instead",
    replaceWith = ReplaceWith(
        "Groovydoc.parse(comment)",
        "com.github.albertocavalcante.groovyparser.ast.groovydoc.Groovydoc",
    ),
    level = DeprecationLevel.WARNING,
)
object GroovyDocParser {
    private val TAG_PATTERN = Regex("""@(\w+)\s*(.*)""")

    /**
     * Parses a GroovyDoc comment string into a structured GroovyDoc object.
     *
     * @param comment The raw GroovyDoc comment including /** and */ delimiters
     * @return A GroovyDoc object containing the parsed documentation
     */
    fun parse(comment: String): GroovyDoc {
        val lines = stripCommentSyntax(comment)

        if (lines.isEmpty()) {
            return GroovyDoc()
        }

        // Split into description and tags
        val tagStartIndex = lines.indexOfFirst { it.trimStart().startsWith("@") }

        val description = if (tagStartIndex == -1) {
            // No tags, entire content is description
            lines.joinToString("\n").trim()
        } else {
            // Extract description before first tag
            lines.subList(0, tagStartIndex).joinToString("\n").trim()
        }

        // Parse tags if any exist
        val tags = if (tagStartIndex == -1) {
            emptyList()
        } else {
            parseTagLines(lines.subList(tagStartIndex, lines.size))
        }

        return buildGroovyDoc(description, tags)
    }

    /**
     * Removes comment delimiters (/** and */) and leading asterisks (*) from each line.
     */
    private fun stripCommentSyntax(comment: String): List<String> = comment
        .lines()
        .map { it.trim() }
        .dropWhile { it.isEmpty() || it == "/**" }
        .dropLastWhile { it.isEmpty() || it == "*/" || it == "*" }
        .map { line ->
            var processed = line
            if (processed.startsWith("/**")) {
                processed = processed.removePrefix("/**").trim()
            }
            if (processed.endsWith("*/")) {
                processed = processed.removeSuffix("*/").trim()
            }

            if (processed.startsWith("*")) {
                processed.removePrefix("*").trimStart()
            } else {
                processed
            }
        }

    /**
     * Parses tag lines, handling multiline tag descriptions.
     */
    private fun parseTagLines(tagLines: List<String>): List<ParsedTag> {
        val tags = mutableListOf<ParsedTag>()
        var currentTag: ParsedTag? = null

        for (line in tagLines) {
            val trimmedLine = line.trim()
            val tagMatch = TAG_PATTERN.matchEntire(trimmedLine)

            if (tagMatch != null) {
                // Start of a new tag
                currentTag?.let { tags.add(it) }
                val tagName = tagMatch.groupValues[1]
                val tagContent = tagMatch.groupValues[2].trim()
                currentTag = ParsedTag(tagName, tagContent)
            } else if (currentTag != null && trimmedLine.isNotEmpty()) {
                // Continuation of current tag
                currentTag = currentTag.copy(
                    content = if (currentTag.content.isEmpty()) {
                        trimmedLine
                    } else {
                        currentTag.content + " " + trimmedLine
                    },
                )
            }
        }

        // Add the last tag
        currentTag?.let { tags.add(it) }

        return tags
    }

    /**
     * Builds a GroovyDoc object from parsed description and tags.
     */
    private fun buildGroovyDoc(description: String, tags: List<ParsedTag>): GroovyDoc {
        val params = mutableListOf<ParamTag>()
        val throwsTags = mutableListOf<ThrowsTag>()
        val seeTags = mutableListOf<SeeTag>()
        var returnTag: ReturnTag? = null
        var since: String? = null
        var deprecated: String? = null
        var author: String? = null

        for (tag in tags) {
            when (tag.name.lowercase()) {
                "param" -> {
                    val parts = tag.content.split(Regex("""\s+"""), 2)
                    if (parts.isNotEmpty()) {
                        val paramName = parts[0]
                        val paramDesc = if (parts.size > 1) parts[1] else ""
                        params.add(ParamTag(paramName, paramDesc))
                    }
                }
                "return", "returns" -> {
                    returnTag = ReturnTag(tag.content)
                }
                "throws", "exception" -> {
                    val parts = tag.content.split(Regex("""\s+"""), 2)
                    if (parts.isNotEmpty()) {
                        val exceptionType = parts[0]
                        val exceptionDesc = if (parts.size > 1) parts[1] else ""
                        throwsTags.add(ThrowsTag(exceptionType, exceptionDesc))
                    }
                }
                "see" -> {
                    seeTags.add(SeeTag(tag.content))
                }
                "since" -> {
                    since = tag.content
                }
                "deprecated" -> {
                    deprecated = tag.content
                }
                "author" -> {
                    author = tag.content
                }
            }
        }

        return GroovyDoc(
            description = description,
            params = params,
            returns = returnTag,
            throws = throwsTags,
            see = seeTags,
            since = since,
            deprecated = deprecated,
            author = author,
        )
    }

    /**
     * Internal representation of a parsed tag before conversion to specific tag types.
     */
    private data class ParsedTag(val name: String, val content: String)
}
