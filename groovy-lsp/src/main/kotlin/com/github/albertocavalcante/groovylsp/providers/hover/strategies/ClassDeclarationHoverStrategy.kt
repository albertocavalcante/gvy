package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.TypedHoverStrategy
import org.codehaus.groovy.ast.ClassNode

/**
 * Strategy for generating hover information for class declarations.
 */
class ClassDeclarationHoverStrategy : TypedHoverStrategy<ClassNode>(ClassNode::class)
