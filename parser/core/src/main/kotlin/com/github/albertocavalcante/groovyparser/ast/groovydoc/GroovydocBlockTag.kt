package com.github.albertocavalcante.groovyparser.ast.groovydoc

/**
 * Represents a block tag in a Groovydoc comment.
 *
 * Block tags start with @ and appear at the beginning of a line.
 * Examples: @param, @return, @throws, @author, @since, @deprecated
 *
 * Some tags have a name parameter (like @param and @throws):
 * - `@param x the x value` - name is "x", content is "the x value"
 * - `@throws IOException if IO fails` - name is "IOException", content is "if IO fails"
 *
 * Similar to JavaParser's `JavadocBlockTag`.
 */
class GroovydocBlockTag(
    /** The type of tag */
    val type: Type,
    /** The original tag name (useful for unknown tags) */
    val tagName: String,
    /** For tags like @param/@throws, the parameter/exception name */
    val name: String? = null,
    /** The tag content/description */
    val content: GroovydocDescription,
) {
    /**
     * Known Groovydoc tag types.
     */
    enum class Type(val keyword: String, val hasName: Boolean = false) {
        AUTHOR("author"),
        DEPRECATED("deprecated"),
        EXCEPTION("exception", hasName = true),
        PARAM("param", hasName = true),
        RETURN("return"),
        SEE("see"),
        SERIAL("serial"),
        SERIAL_DATA("serialData"),
        SERIAL_FIELD("serialField"),
        SINCE("since"),
        THROWS("throws", hasName = true),
        VERSION("version"),
        UNKNOWN("unknown"),
        ;

        companion object {
            fun fromName(tagName: String): Type =
                entries.find { it.keyword.equals(tagName, ignoreCase = true) } ?: UNKNOWN
        }
    }

    /**
     * Returns true if this tag has a name parameter.
     */
    fun hasName(): Boolean = type.hasName && name != null

    /**
     * Converts the tag back to text format.
     */
    fun toText(): String = buildString {
        append("@")
        append(tagName)
        if (name != null) {
            append(" ")
            append(name)
        }
        if (!content.isEmpty()) {
            append(" ")
            append(content.toText())
        }
    }

    override fun toString(): String = "GroovydocBlockTag(type=$type, name=$name, content=$content)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroovydocBlockTag) return false
        return type == other.type && name == other.name && content == other.content
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + content.hashCode()
        return result
    }

    companion object {
        /**
         * Creates a @param tag.
         */
        fun param(paramName: String, description: String): GroovydocBlockTag = GroovydocBlockTag(
            type = Type.PARAM,
            tagName = "param",
            name = paramName,
            content = GroovydocDescription.parseText(description),
        )

        /**
         * Creates a @return tag.
         */
        fun returns(description: String): GroovydocBlockTag = GroovydocBlockTag(
            type = Type.RETURN,
            tagName = "return",
            content = GroovydocDescription.parseText(description),
        )

        /**
         * Creates a @throws tag.
         */
        fun throws(exceptionType: String, description: String): GroovydocBlockTag = GroovydocBlockTag(
            type = Type.THROWS,
            tagName = "throws",
            name = exceptionType,
            content = GroovydocDescription.parseText(description),
        )
    }
}
