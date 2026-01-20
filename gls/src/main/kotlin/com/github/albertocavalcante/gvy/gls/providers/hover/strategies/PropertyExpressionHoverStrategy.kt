package com.github.albertocavalcante.gvy.gls.providers.hover.strategies

import com.github.albertocavalcante.gvy.gls.providers.hover.TypedHoverStrategy
import org.codehaus.groovy.ast.expr.PropertyExpression

/**
 * Strategy for generating hover information for property access expressions.
 *
 * Handles expressions like:
 * - `object.property` - Simple property access
 * - `map.key` - Map key access
 * - `person.address.city` - Nested property access
 * - `object?.property` - Safe navigation
 */
class PropertyExpressionHoverStrategy : TypedHoverStrategy<PropertyExpression>(PropertyExpression::class)
