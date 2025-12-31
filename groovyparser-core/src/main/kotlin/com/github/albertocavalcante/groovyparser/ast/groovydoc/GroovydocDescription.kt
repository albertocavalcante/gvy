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
                if (i + 1 < text.length && text[i] == '{' && text[i + 1] == '@') {
                    // Found start of inline tag
                    val tagStart = i
                    val tagEnd = text.indexOf('}', tagStart)
                    if (tagEnd != -1) {
                        val tagContent = text.substring(tagStart + 2, tagEnd)
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
                        processedText.append(text.substring(tagStart, tagEnd + 1))
                        i = tagEnd + 1
                        continue
                    }
                }
                processedText.append(text[i])
                i++
            }

            return GroovydocDescription(processedText.toString().trim(), inlineTags)
        }
    }
}
