package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.TypedHoverStrategy
import org.codehaus.groovy.ast.expr.MethodCallExpression

/**
 * Strategy for generating hover information for method calls.
 */
class MethodCallHoverStrategy : TypedHoverStrategy<MethodCallExpression>(MethodCallExpression::class)
