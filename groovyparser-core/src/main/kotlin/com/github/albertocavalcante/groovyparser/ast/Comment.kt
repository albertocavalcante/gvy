package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.Range
import com.github.albertocavalcante.groovyparser.ast.groovydoc.Groovydoc

/**
 * Represents a comment in the source code.
 */
sealed class Comment(
    /** The content of the comment (without delimiters) */
    val content: String,
    /** The range in the source code */
    override var range: Range? = null,
) : Node() {

    /** Returns true if this is a line comment (// ...) */
    abstract val isLineComment: Boolean

    /** Returns true if this is a block comment or Javadoc */
    abstract val isBlockComment: Boolean

    override fun getChildNodes(): List<Node> = emptyList()
}

/**
 * A single-line comment starting with //.
 *
 * Example:
 * ```groovy
 * // This is a line comment
 * ```
 */
class LineComment(content: String, range: Range? = null) : Comment(content, range) {
    override val isLineComment: Boolean = true
    override val isBlockComment: Boolean = false

    override fun toString(): String = "// $content"
}

/**
 * A multi-line block comment enclosed in slash-star ... star-slash.
 *
 * Example:
 * ```groovy
 * /* This is a block comment */
 * ```
 */
class BlockComment(content: String, range: Range? = null) : Comment(content, range) {
    override val isLineComment: Boolean = false
    override val isBlockComment: Boolean = true

    override fun toString(): String = "/* $content */"
}

/**
 * A Javadoc/Groovydoc comment starting with slash-star-star.
 *
 * Example:
 * ```groovy
 * /** This is a Javadoc comment. @param x the parameter */
 * ```
 *
 * Use [parse] to extract structured information like @param, @return tags.
 */
class JavadocComment(content: String, range: Range? = null) : Comment(content, range) {
    override val isLineComment: Boolean = false
    override val isBlockComment: Boolean = true

    /** Returns true since Javadoc is a special form of block comment */
    val isJavadoc: Boolean = true

    /** Alias for Groovy naming convention */
    val isGroovydoc: Boolean = true

    /**
     * Parses the Javadoc/Groovydoc content into structured form.
     *
     * Extracts:
     * - Description (text before block tags)
     * - Block tags (@param, @return, @throws, etc.)
     * - Inline tags ({@code}, {@link}, etc.)
     *
     * @return the parsed Groovydoc structure
     */
    fun parse(): Groovydoc = Groovydoc.parse(content)

    override fun toString(): String = "/** $content */"
}
