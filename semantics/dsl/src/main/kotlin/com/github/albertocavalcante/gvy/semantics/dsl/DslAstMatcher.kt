package com.github.albertocavalcante.gvy.semantics.dsl

import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement

/**
 * Result of attempting to match a Groovy AST node against a [DslAstMatcher].
 *
 * Implementations of [DslAstMatcher] must never throw to indicate that a node
 * did not match; instead they return [NoMatch]. When the matcher succeeds, it
 * returns [Match] with an optional set of named captures.
 *
 * ### Captures
 *
 * Many matcher implementations allow parts of the AST to be "captured" under
 * a user-provided name (for example, the body of a closure or a string
 * literal). These captures are collected into the [Match.captures] map so that
 * callers can inspect the matched structure without re-traversing the AST.
 *
 * Captures are intentionally untyped (`Any`) because different matchers may
 * store different kinds of values (AST nodes, primitive values, etc.). Callers
 * are expected to down-cast as needed, based on the matcher configuration.
 *
 * ### Usage example
 *
 * ```kotlin
 * val matcher: DslAstMatcher = StringLiteralMatcher(captureName = "value")
 * when (val result = matcher.match(expression)) {
 *     is DslMatchResult.Match -> {
 *         val value = result.captures["value"] as? String
 *         // handle matched string literal
 *     }
 *     DslMatchResult.NoMatch -> {
 *         // expression is not a string literal
 *     }
 * }
 * ```
 */
sealed interface DslMatchResult {
    /**
     * Successful match, optionally containing named [captures] produced while
     * matching the AST.
     */
    data class Match(val captures: Map<String, Any>) : DslMatchResult

    /**
     * Indicates that the given AST node did not satisfy the matcher.
     *
     * This is used instead of throwing exceptions for non-matching nodes so
     * that matchers can be freely composed.
     */
    data object NoMatch : DslMatchResult
}

/**
 * Strategy interface for matching Groovy [Expression] nodes used in the
 * semantics DSL.
 *
 * Concrete implementations (such as [MethodCallMatcher], [ClosureMatcher] or
 * [StringLiteralMatcher]) encapsulate structural checks over the AST and can
 * be combined to express higher-level patterns in the DSL.
 *
 * Implementations should:
 *  * Return [DslMatchResult.NoMatch] when the given [node] does not satisfy the
 *    matcher (for example, it is of an unexpected AST type).
 *  * Return [DslMatchResult.Match] when the [node] matches, optionally
 *    providing named captures for interesting sub-expressions.
 *
 * ### Usage example
 *
 * ```kotlin
 * val matcher: DslAstMatcher =
 *     MethodCallMatcher("foo", argumentMatchers = listOf(StringLiteralMatcher("arg")))
 *
 * val result = matcher.match(callExpression)
 * if (result is DslMatchResult.Match) {
 *     val arg = result.captures["arg"]
 *     // work with the captured argument
 * }
 * ```
 */
interface DslAstMatcher {
    /**
     * Attempts to match the given [node] and returns a [DslMatchResult]
     * describing the outcome.
     */
    fun match(node: Expression): DslMatchResult
}

// Matchers
class MethodCallMatcher(
    val methodName: String,
    val receiverMatcher: DslAstMatcher? = null,
    val argumentMatchers: List<DslAstMatcher> = emptyList(),
) : DslAstMatcher {
    override fun match(node: Expression): DslMatchResult {
        if (node !is MethodCallExpression) {
            return DslMatchResult.NoMatch
        }

        // Check method name
        val methodText = node.methodAsString
        if (methodText != methodName) {
            return DslMatchResult.NoMatch
        }

        val allCaptures = mutableMapOf<String, Any>()

        // Check receiver if specified
        if (receiverMatcher != null) {
            when (val receiverResult = receiverMatcher.match(node.objectExpression)) {
                is DslMatchResult.NoMatch -> return DslMatchResult.NoMatch
                is DslMatchResult.Match -> allCaptures.putAll(receiverResult.captures)
            }
        }

        // Check arguments
        val argExpressions = (node.arguments as? ArgumentListExpression)?.expressions ?: emptyList()
        if (argumentMatchers.size != argExpressions.size) {
            return DslMatchResult.NoMatch
        }

        for ((matcher, arg) in argumentMatchers.zip(argExpressions)) {
            when (val argResult = matcher.match(arg)) {
                is DslMatchResult.NoMatch -> return DslMatchResult.NoMatch
                is DslMatchResult.Match -> allCaptures.putAll(argResult.captures)
            }
        }

        return DslMatchResult.Match(allCaptures)
    }
}

class ClosureMatcher(val captureName: String? = null, val bodyMatcher: DslAstMatcher? = null) : DslAstMatcher {
    override fun match(node: Expression): DslMatchResult {
        if (node !is ClosureExpression) {
            return DslMatchResult.NoMatch
        }

        val captures = mutableMapOf<String, Any>()

        // Capture the closure if name is specified
        if (captureName != null) {
            captures[captureName] = node
        }

        // Check body if matcher is specified
        if (bodyMatcher != null) {
            when (val code = node.code) {
                is BlockStatement -> {
                    val firstStmt = code.statements.firstOrNull()
                    if (firstStmt is ExpressionStatement) {
                        when (val bodyResult = bodyMatcher.match(firstStmt.expression)) {
                            is DslMatchResult.NoMatch -> return DslMatchResult.NoMatch
                            is DslMatchResult.Match -> captures.putAll(bodyResult.captures)
                        }
                    } else {
                        // Body is empty or first statement is not an expression, but a matcher was provided.
                        return DslMatchResult.NoMatch
                    }
                }
                else -> {
                    // A body matcher was provided, but the closure code is not a block statement.
                    return DslMatchResult.NoMatch
                }
            }
        }

        return DslMatchResult.Match(captures)
    }
}

class StringLiteralMatcher(val captureName: String? = null) : DslAstMatcher {
    override fun match(node: Expression): DslMatchResult {
        if (node !is ConstantExpression || node.value !is String) {
            return DslMatchResult.NoMatch
        }

        val captures = if (captureName != null) {
            mapOf(captureName to node.value)
        } else {
            emptyMap()
        }

        return DslMatchResult.Match(captures)
    }
}

class AnyMatcher(val captureName: String? = null) : DslAstMatcher {
    override fun match(node: Expression): DslMatchResult {
        val captures = if (captureName != null) {
            mapOf(captureName to node)
        } else {
            emptyMap()
        }

        return DslMatchResult.Match(captures)
    }
}

class SequenceMatcher(val matchers: List<DslAstMatcher>) : DslAstMatcher {
    override fun match(node: Expression): DslMatchResult {
        // TODO(#696): Implement full sequence matching for multiple statements
        if (matchers.isEmpty()) {
            return DslMatchResult.Match(emptyMap())
        }

        return matchers[0].match(node)
    }
}
