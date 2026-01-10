package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.MultiTypeHoverStrategy
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression

/**
 * Strategy for generating hover information for literal expressions.
 *
 * Handles literals including:
 * - String literals (`"hello"`)
 * - Integer literals (`42`)
 * - Decimal literals (`3.14`)
 * - Boolean literals (`true`, `false`)
 * - List literals (`[1, 2, 3]`)
 * - Map literals (`[a: 1, b: 2]`)
 * - GString literals (`"Hello, ${name}"`)
 * - Null (`null`)
 */
class LiteralHoverStrategy :
    MultiTypeHoverStrategy(
        ConstantExpression::class,
        GStringExpression::class,
        ListExpression::class,
        MapExpression::class,
    )
