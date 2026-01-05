package com.github.albertocavalcante.gvy.semantics.native.adapters

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.native.NativeTypeContext
import org.codehaus.groovy.ast.FieldNode
import kotlin.reflect.KClass

/**
 * Calculator for FieldNode - resolves to the field's declared type.
 */
object FieldNodeCalculator : TypeCalculator<FieldNode> {
    override val nodeType: KClass<FieldNode> = FieldNode::class
    override val priority: Int = 20 // High priority for direct node types

    override fun calculate(node: FieldNode, context: TypeContext): SemanticType? =
        NativeTypeContext.fromClassNode(node.type)
}
