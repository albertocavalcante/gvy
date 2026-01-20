package com.github.albertocavalcante.gvy.gls.providers.hover.strategies

import com.github.albertocavalcante.gvy.gls.providers.hover.TypedHoverStrategy
import org.codehaus.groovy.ast.MethodNode

/**
 * Strategy for generating hover information for method declarations.
 */
class MethodDeclarationHoverStrategy : TypedHoverStrategy<MethodNode>(MethodNode::class)
