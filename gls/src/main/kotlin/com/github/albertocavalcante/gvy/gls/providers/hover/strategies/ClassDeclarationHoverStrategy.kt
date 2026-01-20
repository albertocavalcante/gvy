package com.github.albertocavalcante.gvy.gls.providers.hover.strategies

import com.github.albertocavalcante.gvy.gls.providers.hover.TypedHoverStrategy
import org.codehaus.groovy.ast.ClassNode

/**
 * Strategy for generating hover information for class declarations.
 */
class ClassDeclarationHoverStrategy : TypedHoverStrategy<ClassNode>(ClassNode::class)
