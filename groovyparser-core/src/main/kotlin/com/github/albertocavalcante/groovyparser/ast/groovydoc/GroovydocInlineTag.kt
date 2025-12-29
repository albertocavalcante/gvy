package com.github.albertocavalcante.groovyparser.ast.groovydoc

/**
 * Represents an inline tag in a Groovydoc comment.
 *
 * Inline tags are enclosed in braces and can appear anywhere in the text:
 * - `{@code someCode}` - displays code in monospace
 * - `{@link SomeClass}` - creates a link to another class
 * - `{@link SomeClass#method}` - creates a link to a method
 * - `{@literal <T>}` - displays literal text without processing HTML
 * - `{@value #CONSTANT}` - displays the value of a constant
 */
class GroovydocInlineTag(
    /** The type of inline tag */
    val type: Type,
    /** The original tag name */
    val tagName: String,
    /** The content inside the tag */
    val content: String,
) {
    /**
     * Known inline tag types.
     */
    enum class Type(val keyword: String) {
        CODE("code"),
        DOC_ROOT("docRoot"),
        INHERIT_DOC("inheritDoc"),
        LINK("link"),
        LINKPLAIN("linkplain"),
        LITERAL("literal"),
        VALUE("value"),
        UNKNOWN("unknown"),
        ;

        companion object {
            fun fromName(tagName: String): Type =
                entries.find { it.keyword.equals(tagName, ignoreCase = true) } ?: UNKNOWN
        }
    }

    /**
     * Returns true if this is a @code tag.
     */
    fun isCode(): Boolean = type == Type.CODE

    /**
     * Returns true if this is a @link tag.
     */
    fun isLink(): Boolean = type == Type.LINK || type == Type.LINKPLAIN

    /**
     * Converts the inline tag back to text format.
     */
    fun toText(): String = "{@$tagName $content}"

    override fun toString(): String = "GroovydocInlineTag(type=$type, content=$content)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroovydocInlineTag) return false
        return type == other.type && content == other.content
    }

    override fun hashCode(): Int = 31 * type.hashCode() + content.hashCode()
}
