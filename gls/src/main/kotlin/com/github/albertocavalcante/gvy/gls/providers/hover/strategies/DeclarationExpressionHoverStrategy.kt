package com.github.albertocavalcante.gvy.gls.providers.hover.strategies

import com.github.albertocavalcante.gvy.gls.providers.hover.TypedHoverStrategy
import org.codehaus.groovy.ast.expr.DeclarationExpression

/**
 * Strategy for generating hover information for variable declarations.
 */
class DeclarationExpressionHoverStrategy : TypedHoverStrategy<DeclarationExpression>(DeclarationExpression::class)
