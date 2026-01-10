package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.TypedHoverStrategy
import org.codehaus.groovy.ast.expr.DeclarationExpression

/**
 * Strategy for generating hover information for variable declarations.
 */
class DeclarationExpressionHoverStrategy : TypedHoverStrategy<DeclarationExpression>(DeclarationExpression::class)
