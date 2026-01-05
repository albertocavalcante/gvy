package com.github.albertocavalcante.gvy.semantics.native.adapters

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.native.NativeTypeContext
import org.codehaus.groovy.ast.MethodNode
import kotlin.reflect.KClass

/**
 * Calculator for MethodNode - resolves to the method's return type.
 */
object MethodNodeCalculator : TypeCalculator<MethodNode> {
    override val nodeType: KClass<MethodNode> = MethodNode::class
    override val priority: Int = 20 // High priority for direct node types

    override fun calculate(node: MethodNode, context: TypeContext): SemanticType? =
        NativeTypeContext.fromClassNode(node.returnType)
}
