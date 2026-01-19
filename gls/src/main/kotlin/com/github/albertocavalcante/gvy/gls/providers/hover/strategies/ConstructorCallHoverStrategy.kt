package com.github.albertocavalcante.gvy.gls.providers.hover.strategies

import com.github.albertocavalcante.gvy.gls.providers.hover.TypedHoverStrategy
import org.codehaus.groovy.ast.expr.ConstructorCallExpression

/**
 * Strategy for generating hover information for constructor calls.
 *
 * Handles constructor invocations like:
 * - `new ArrayList<String>()` - Generic constructor
 * - `new Person("Alice", 30)` - Constructor with parameters
 * - `new Config(host: "localhost", port: 8080)` - Constructor with named arguments
 * - `new Outer.Inner()` - Nested class constructor
 */
class ConstructorCallHoverStrategy : TypedHoverStrategy<ConstructorCallExpression>(ConstructorCallExpression::class)
