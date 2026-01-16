package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ClassNode
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Creates an identity-based set from ClassNodes.
 *
 * ClassNode may override equals/hashCode, so we use IdentityHashMap to ensure
 * reference equality comparison. This is critical for distinguishing actual class
 * definitions from type references (e.g., String in "String name" declarations).
 *
 * @param nodes Collection of ClassNodes to convert to identity set
 * @return Identity-based set containing the ClassNodes
 */
fun createIdentitySet(nodes: Collection<ClassNode>): Set<ClassNode> =
    nodes.toCollection(Collections.newSetFromMap(IdentityHashMap()))
