package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.TypedHoverStrategy
import org.codehaus.groovy.ast.expr.VariableExpression

/**
 * Strategy for generating hover information for variable references.
 */
class VariableExpressionHoverStrategy : TypedHoverStrategy<VariableExpression>(VariableExpression::class)
