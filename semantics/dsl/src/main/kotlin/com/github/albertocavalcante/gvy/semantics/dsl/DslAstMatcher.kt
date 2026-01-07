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

        // Check receiver if specified
        if (receiverMatcher != null) {
            val receiverResult = receiverMatcher.match(node.objectExpression)
            if (receiverResult is DslMatchResult.NoMatch) {
                return DslMatchResult.NoMatch
            }
        }

        // Check arguments if specified
        if (argumentMatchers.isNotEmpty()) {
            val args = node.arguments
            if (args is ArgumentListExpression) {
                val argExpressions = args.expressions
                if (argExpressions.size != argumentMatchers.size) {
                    return DslMatchResult.NoMatch
                }

                val allCaptures = mutableMapOf<String, Any>()
                for ((index, matcher) in argumentMatchers.withIndex()) {
                    val argResult = matcher.match(argExpressions[index])
                    if (argResult is DslMatchResult.NoMatch) {
                        return DslMatchResult.NoMatch
                    }
                    if (argResult is DslMatchResult.Match) {
                        allCaptures.putAll(argResult.captures)
                    }
                }
                return DslMatchResult.Match(allCaptures)
            }
        }

        return DslMatchResult.Match(emptyMap())
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
            val code = node.code
            if (code is BlockStatement) {
                // For simplicity, check first statement in block
                val statements = code.statements
                if (statements.isNotEmpty()) {
                    val firstStmt = statements[0]
                    if (firstStmt is ExpressionStatement) {
                        val bodyResult = bodyMatcher.match(firstStmt.expression)
                        if (bodyResult is DslMatchResult.NoMatch) {
                            return DslMatchResult.NoMatch
                        }
                        if (bodyResult is DslMatchResult.Match) {
                            captures.putAll(bodyResult.captures)
                        }
                    }
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
        // This is a placeholder implementation
        // In a real implementation, this would match a sequence of statements
        // For now, we'll just try to match the first matcher
        if (matchers.isEmpty()) {
            return DslMatchResult.Match(emptyMap())
        }

        return matchers[0].match(node)
    }
}
