package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.Range

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
 * A Javadoc comment starting with slash-star-star.
 *
 * Example:
 * ```groovy
 * /** This is a Javadoc comment. @param x the parameter */
 * ```
 */
class JavadocComment(content: String, range: Range? = null) : Comment(content, range) {
    override val isLineComment: Boolean = false
    override val isBlockComment: Boolean = true

    /** Returns true since Javadoc is a special form of block comment */
    val isJavadoc: Boolean = true

    override fun toString(): String = "/** $content */"
}
