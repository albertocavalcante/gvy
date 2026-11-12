// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.spock

/** The first segment of an unroll expression. */
sealed interface UnrollRoot {
    /** A Spock built-in: `featureName`, `iterationIndex`, `dataVariables` or `dataVariablesWithIndex`. */
    data class Builtin(val name: String) : UnrollRoot

    /** Resolved at runtime against the iteration's data variables; existence is not checked here. */
    data class DataVariable(val name: String) : UnrollRoot

    /**
     * Never a data variable or built-in (for example `a()`). Like an unknown data variable, it fails
     * the iteration with `unroll.validateExpressions` (default true) or renders `#Error:…` when false.
     */
    data class Invalid(val text: String) : UnrollRoot
}

enum class UnrollSegmentKind {
    /** Read with Groovy property access at runtime. */
    PROPERTY,

    /** A zero-argument method call; [UnrollSegment.name] excludes the parentheses. */
    METHOD,

    /**
     * Not a well-formed property or method name (empty, or containing parentheses). The runtime
     * outcome still depends on the receiver: a Map or a `propertyMissing` handler may return null
     * without error, while other receivers fail like an invalid root.
     */
    INVALID,
}

/** One `.`-separated segment after the root; [range] is into the runtime pattern, end exclusive. */
data class UnrollSegment(val name: String, val kind: UnrollSegmentKind, val range: IntRange)

/**
 * One placeholder in a runtime unroll pattern. [range] covers the replaced text including `#`
 * (and any trailing dots, as upstream replaces them too); [rootRange] covers only the root.
 */
data class UnrollPlaceholder(
    val expression: String,
    val range: IntRange,
    val root: UnrollRoot,
    val rootRange: IntRange,
    val tail: List<UnrollSegment>,
)

/**
 * The placeholder language of Spock's `UnrollIterationNameProvider` (identical at spock-2.3,
 * spock-2.4 and spock-2.5), applied to the runtime pattern string: decode source escapes first.
 *
 * Retained regex: [EXPRESSION] is the upstream grammar itself, an exact regular language, so it is
 * not a heuristic. Segmentation mirrors upstream `String.split("\\.")`, which drops trailing empty
 * segments. Replace this only if the upstream provider changes; parse results never evaluate tails.
 */
object SpockUnrollPattern {
    private val EXPRESSION = Regex("#([a-zA-Z_$]([\\w$.]|\\(\\))*)")
    private val BUILTINS = setOf("featureName", "iterationIndex", "dataVariables", "dataVariablesWithIndex")
    private const val CALL = "()"

    fun parse(pattern: String): List<UnrollPlaceholder> = EXPRESSION.findAll(pattern).map { match ->
        val expression = match.groupValues[1]
        val expressionStart = match.range.first + 1
        val segments = segments(expression)
        val rootText = segments.first().second
        UnrollPlaceholder(
            expression = expression,
            range = match.range.first until match.range.last + 1,
            root = root(rootText),
            rootRange = expressionStart until expressionStart + rootText.length,
            tail = segments.drop(1).map { (offset, text) -> segment(text, expressionStart + offset) },
        )
    }.toList()

    /** `(offset in expression, text)` pairs with Java `split` semantics: trailing empties dropped. */
    private fun segments(expression: String): List<Pair<Int, String>> {
        val parts = mutableListOf<Pair<Int, String>>()
        var start = 0
        while (true) {
            val dot = expression.indexOf('.', start)
            val end = if (dot < 0) expression.length else dot
            parts += start to expression.substring(start, end)
            if (dot < 0) break
            start = dot + 1
        }
        while (parts.size > 1 && parts.last().second.isEmpty()) parts.removeAt(parts.lastIndex)
        return parts
    }

    private fun root(text: String): UnrollRoot = when {
        '(' in text || ')' in text -> UnrollRoot.Invalid(text)
        text in BUILTINS -> UnrollRoot.Builtin(text)
        else -> UnrollRoot.DataVariable(text)
    }

    private fun segment(text: String, start: Int): UnrollSegment {
        val range = start until start + text.length
        val method = text.endsWith(CALL)
        val name = if (method) text.dropLast(CALL.length) else text
        return when {
            name.isEmpty() || '(' in name || ')' in name -> UnrollSegment(name, UnrollSegmentKind.INVALID, range)
            method -> UnrollSegment(name, UnrollSegmentKind.METHOD, range)
            else -> UnrollSegment(name, UnrollSegmentKind.PROPERTY, range)
        }
    }
}
