package com.github.albertocavalcante.groovyparser.ast.groovydoc

/**
 * Represents the structured content of a Groovydoc comment.
 *
 * A Groovydoc comment consists of:
 * - A description (the text before any block tags)
 * - A list of block tags (@param, @return, @throws, etc.)
 *
 * Example:
 * ```groovy
 * /**
 *  * Calculates the sum of two numbers.
 *  *
 *  * @param x the first number
 *  * @param y the second number
 *  * @return the sum of x and y
 *  * @throws IllegalArgumentException if values are negative
 *  * @since 1.0
 *  */
 * ```
 *
 * Similar to JavaParser's `Javadoc` class.
 */
class Groovydoc(
    /** The description text (content before block tags) */
    val description: GroovydocDescription,
    /** The list of block tags */
    val blockTags: List<GroovydocBlockTag> = emptyList(),
) {
    /**
     * Gets all block tags of a specific type.
     */
    fun getBlockTags(type: GroovydocBlockTag.Type): List<GroovydocBlockTag> = blockTags.filter { it.type == type }

    /**
     * Gets all @param tags.
     */
    fun getParamTags(): List<GroovydocBlockTag> = getBlockTags(GroovydocBlockTag.Type.PARAM)

    /**
     * Gets the @return tag, if present.
     */
    fun getReturnTag(): GroovydocBlockTag? = blockTags.find { it.type == GroovydocBlockTag.Type.RETURN }

    /**
     * Gets all @throws/@exception tags.
     */
    fun getThrowsTags(): List<GroovydocBlockTag> =
        blockTags.filter { it.type == GroovydocBlockTag.Type.THROWS || it.type == GroovydocBlockTag.Type.EXCEPTION }

    /**
     * Gets the @author tag, if present.
     */
    fun getAuthorTag(): GroovydocBlockTag? = blockTags.find { it.type == GroovydocBlockTag.Type.AUTHOR }

    /**
     * Gets the @since tag, if present.
     */
    fun getSinceTag(): GroovydocBlockTag? = blockTags.find { it.type == GroovydocBlockTag.Type.SINCE }

    /**
     * Gets the @deprecated tag, if present.
     */
    fun getDeprecatedTag(): GroovydocBlockTag? = blockTags.find { it.type == GroovydocBlockTag.Type.DEPRECATED }

    /**
     * Gets all @see tags.
     */
    fun getSeeTags(): List<GroovydocBlockTag> = getBlockTags(GroovydocBlockTag.Type.SEE)

    /**
     * Converts the Groovydoc back to text format.
     */
    fun toText(): String = buildString {
        if (!description.isEmpty()) {
            append(description.toText())
            if (blockTags.isNotEmpty()) {
                append("\n\n")
            }
        }
        blockTags.forEachIndexed { index, tag ->
            append(tag.toText())
            if (index < blockTags.size - 1) {
                append("\n")
            }
        }
    }

    override fun toString(): String = "Groovydoc(description=$description, blockTags=$blockTags)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Groovydoc) return false
        return description == other.description && blockTags == other.blockTags
    }

    override fun hashCode(): Int = 31 * description.hashCode() + blockTags.hashCode()

    companion object {
        /**
         * Parses a Groovydoc comment from raw content.
         *
         * @param content the raw comment content (without the comment delimiters)
         * @return the parsed Groovydoc
         */
        fun parse(content: String): Groovydoc = GroovydocParser.parse(content)
    }
}
