package com.github.albertocavalcante.gvy.gls.providers.hover.strategies

import com.github.albertocavalcante.gvy.gls.providers.hover.TypedHoverStrategy
import org.codehaus.groovy.ast.ImportNode

/**
 * Strategy for generating hover information for import statements.
 */
class ImportHoverStrategy : TypedHoverStrategy<ImportNode>(ImportNode::class)
