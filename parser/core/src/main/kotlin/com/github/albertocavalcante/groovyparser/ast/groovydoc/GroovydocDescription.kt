package com.github.albertocavalcante.groovyparser.ast.groovydoc

/**
 * Represents the description part of a Groovydoc comment.
 *
 * The description is the text that appears before any block tags.
 * It may contain inline tags like `{@code ...}` or `{@link ...}`.
 */
class GroovydocDescription(
    /** The raw text content */
    val text: String,
    /** Inline tags found within the description */
    val inlineTags: List<GroovydocInlineTag> = emptyList(),
) {
    /**
     * Returns true if the description is empty.
     */
    fun isEmpty(): Boolean = text.isBlank()

    /**
     * Returns the text content.
     */
    fun toText(): String = text

    override fun toString(): String = text

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroovydocDescription) return false
        return text == other.text && inlineTags == other.inlineTags
    }

    override fun hashCode(): Int = 31 * text.hashCode() + inlineTags.hashCode()

    companion object {
        val EMPTY = GroovydocDescription("")

        /**
         * Parses a description from text, extracting inline tags.
         */
        fun parseText(text: String): GroovydocDescription {
            val inlineTags = mutableListOf<GroovydocInlineTag>()
            val processedText = StringBuilder()
            var i = 0

            while (i < text.length) {
                val nextIndex = tryParseInlineTag(text, i, inlineTags, processedText)
                if (nextIndex > i) {
                    i = nextIndex
                    continue
                }

                processedText.append(text[i])
                i++
            }

            return GroovydocDescription(processedText.toString().trim(), inlineTags)
        }

        private fun tryParseInlineTag(
            text: String,
            startIndex: Int,
            inlineTags: MutableList<GroovydocInlineTag>,
            processedText: StringBuilder,
        ): Int {
            if (startIndex + 1 < text.length && text[startIndex] == '{' && text[startIndex + 1] == '@') {
                // Found start of inline tag
                val tagEnd = text.indexOf('}', startIndex)
                if (tagEnd != -1) {
                    val tagContent = text.substring(startIndex + 2, tagEnd)
                    val spaceIndex = tagContent.indexOf(' ')
                    val tagName = if (spaceIndex != -1) tagContent.substring(0, spaceIndex) else tagContent
                    val tagValue = if (spaceIndex != -1) tagContent.substring(spaceIndex + 1).trim() else ""

                    inlineTags.add(
                        GroovydocInlineTag(
                            type = GroovydocInlineTag.Type.fromName(tagName),
                            tagName = tagName,
                            content = tagValue,
                        ),
                    )
                    processedText.append(text.substring(startIndex, tagEnd + 1))
                    return tagEnd + 1
                }
            }
            return startIndex
        }
    }
}
