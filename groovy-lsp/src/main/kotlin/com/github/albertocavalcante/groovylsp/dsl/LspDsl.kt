package com.github.albertocavalcante.groovylsp.dsl

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * DSL marker annotation to prevent accidental nesting of DSL builders.
 * This provides type safety and better IDE support.
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class LspDslMarker

/**
 * Base interface for all LSP builders.
 * Provides a consistent way to create LSP4J objects with immutable-style builders.
 */
@LspDslMarker
interface LspBuilder<T> {
    /**
     * Build the final LSP4J object.
     * This should be called after configuring all properties.
     */
    fun build(): T
}

/**
 * Common position and range utilities for DSL builders.
 */
object LspDslUtils {
    /**
     * Create a position with 0-based line and character indices.
     */
    fun position(line: Int, character: Int): Position = Position(line, character)

    /**
     * Create a range from start and end positions.
     */
    fun range(start: Position, end: Position): Range = Range(start, end)

    /**
     * Create a range on a single line from start to end character.
     */
    fun range(line: Int, startChar: Int, endChar: Int): Range =
        Range(position(line, startChar), position(line, endChar))

    /**
     * Create a range spanning multiple lines.
     */
    fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int): Range =
        Range(position(startLine, startChar), position(endLine, endChar))

    /**
     * Create a zero-width range at a specific position (for insertions).
     */
    fun pointRange(line: Int, character: Int): Range {
        val pos = position(line, character)
        return Range(pos, pos)
    }

    /**
     * Create a range covering an entire line.
     */
    fun lineRange(line: Int): Range = Range(position(line, 0), position(line, Int.MAX_VALUE))
}

/**
 * Extension functions to make DSL usage more natural.
 */

/**
 * Shorthand for creating positions in DSL contexts.
 */
fun pos(line: Int, character: Int): Position = LspDslUtils.position(line, character)

/**
 * Shorthand for creating ranges in DSL contexts.
 */
fun range(line: Int, startChar: Int, endChar: Int): Range = LspDslUtils.range(line, startChar, endChar)

/**
 * Shorthand for creating multi-line ranges in DSL contexts.
 */
fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int): Range =
    LspDslUtils.range(startLine, startChar, endLine, endChar)
