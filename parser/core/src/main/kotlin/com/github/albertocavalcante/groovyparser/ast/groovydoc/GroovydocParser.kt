package com.github.albertocavalcante.groovyparser.ast.groovydoc

/**
 * Parser for Groovydoc comments.
 *
 * Extracts structured information from raw Groovydoc content:
 * - Description text (before block tags)
 * - Block tags (@param, @return, @throws, etc.)
 * - Inline tags ({@code}, {@link}, etc.)
 */
internal object GroovydocParser {

    private val BLOCK_TAG_PATTERN = Regex("""^\s*@(\w+)\s*(.*)$""")

    /**
     * Parses a Groovydoc comment from raw content.
     *
     * @param content the raw comment content (without delimiters)
     * @return the parsed Groovydoc
     */
    fun parse(content: String): Groovydoc {
        val cleanedLines = cleanContent(content)
        val (descriptionLines, tagLines) = splitDescriptionAndTags(cleanedLines)

        val description = GroovydocDescription.parseText(descriptionLines.joinToString("\n"))
        val blockTags = parseBlockTags(tagLines)

        return Groovydoc(description, blockTags)
    }

    /**
     * Cleans the raw content by removing leading asterisks and normalizing lines.
     */
    private fun cleanContent(content: String): List<String> {
        return content.lines()
            .map { line ->
                // Remove leading whitespace and asterisks (common in Javadoc format)
                line.trim()
                    .removePrefix("*")
                    .trim()
            }
            .dropWhile { it.isEmpty() } // Drop leading empty lines
            .dropLastWhile { it.isEmpty() } // Drop trailing empty lines
    }

    /**
     * Splits content into description lines (before first @tag) and tag lines.
     */
    private fun splitDescriptionAndTags(lines: List<String>): Pair<List<String>, List<String>> {
        val descriptionLines = mutableListOf<String>()
        val tagLines = mutableListOf<String>()
        var inTags = false

        for (line in lines) {
            if (!inTags && line.startsWith("@")) {
                inTags = true
            }

            if (inTags) {
                tagLines.add(line)
            } else {
                descriptionLines.add(line)
            }
        }

        return descriptionLines to tagLines
    }

    /**
     * Parses block tags from tag lines.
     * Handles multi-line tag content.
     */
    private fun parseBlockTags(tagLines: List<String>): List<GroovydocBlockTag> {
        if (tagLines.isEmpty()) return emptyList()

        val tags = mutableListOf<GroovydocBlockTag>()
        var currentTagName: String? = null
        var currentContent = StringBuilder()

        for (line in tagLines) {
            val match = BLOCK_TAG_PATTERN.matchEntire(line)
            if (match != null) {
                // Save previous tag if exists
                currentTagName?.let { tagName ->
                    tags.add(createBlockTag(tagName, currentContent.toString().trim()))
                }

                // Start new tag
                currentTagName = match.groupValues[1]
                currentContent = StringBuilder(match.groupValues[2])
            } else if (currentTagName != null) {
                // Continue current tag (multi-line content)
                if (currentContent.isNotEmpty()) {
                    currentContent.append(" ")
                }
                currentContent.append(line)
            }
        }

        // Don't forget the last tag
        currentTagName?.let { tagName ->
            tags.add(createBlockTag(tagName, currentContent.toString().trim()))
        }

        return tags
    }

    /**
     * Creates a GroovydocBlockTag from tag name and content.
     */
    private fun createBlockTag(tagName: String, content: String): GroovydocBlockTag {
        val type = GroovydocBlockTag.Type.fromName(tagName)

        // For tags with names (like @param x description), extract the name
        return if (type.hasName && content.isNotEmpty()) {
            val parts = content.split(Regex("\\s+"), limit = 2)
            val name = parts[0]
            val description = parts.getOrElse(1) { "" }
            GroovydocBlockTag(
                type = type,
                tagName = tagName,
                name = name,
                content = GroovydocDescription.parseText(description),
            )
        } else {
            GroovydocBlockTag(
                type = type,
                tagName = tagName,
                content = GroovydocDescription.parseText(content),
            )
        }
    }
}
