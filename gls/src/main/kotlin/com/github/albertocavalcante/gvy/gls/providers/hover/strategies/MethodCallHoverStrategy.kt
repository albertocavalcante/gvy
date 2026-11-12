// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover.strategies

import com.github.albertocavalcante.gvy.gls.providers.hover.TypedHoverStrategy
import org.codehaus.groovy.ast.expr.MethodCallExpression

/**
 * Strategy for generating hover information for method calls.
 */
class MethodCallHoverStrategy : TypedHoverStrategy<MethodCallExpression>(MethodCallExpression::class)
