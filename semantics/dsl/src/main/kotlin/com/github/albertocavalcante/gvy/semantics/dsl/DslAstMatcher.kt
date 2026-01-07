package com.github.albertocavalcante.gvy.semantics.dsl

import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement

sealed interface DslMatchResult {
    data class Match(val captures: Map<String, Any>) : DslMatchResult
    data object NoMatch : DslMatchResult
}

interface DslAstMatcher {
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
