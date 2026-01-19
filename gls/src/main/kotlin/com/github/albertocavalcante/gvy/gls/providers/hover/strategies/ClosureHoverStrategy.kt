package com.github.albertocavalcante.gvy.gls.providers.hover.strategies

import com.github.albertocavalcante.gvy.gls.providers.hover.TypedHoverStrategy
import org.codehaus.groovy.ast.expr.ClosureExpression

/**
 * Strategy for generating hover information for closure expressions.
 *
 * Handles closure expressions with:
 * - Parameters (explicit and implicit 'it')
 * - Delegate type information
 * - Owner context
 * - Variable scope information
 */
class ClosureHoverStrategy : TypedHoverStrategy<ClosureExpression>(ClosureExpression::class)
