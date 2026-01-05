package com.github.albertocavalcante.gvy.semantics.native.adapters

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import com.github.albertocavalcante.gvy.semantics.native.NativeTypeContext
import org.codehaus.groovy.ast.Parameter
import kotlin.reflect.KClass

/**
 * Calculator for Parameter - resolves to the parameter's declared type.
 */
object ParameterCalculator : TypeCalculator<Parameter> {
    override val nodeType: KClass<Parameter> = Parameter::class
    override val priority: Int = 20 // High priority for direct node types

    override fun calculate(node: Parameter, context: TypeContext): SemanticType? =
        NativeTypeContext.fromClassNode(node.type)
}
