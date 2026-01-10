package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.providers.hover.TypedHoverStrategy
import org.codehaus.groovy.ast.FieldNode

/**
 * Strategy for generating hover information for field declarations.
 */
class FieldDeclarationHoverStrategy : TypedHoverStrategy<FieldNode>(FieldNode::class)
