package com.github.albertocavalcante.gvy.semantics.calculator.impl

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.calculator.ReflectionAccess
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculator
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import kotlin.reflect.KClass

/**
 * Calculates types for constructor call expressions (e.g., `new ArrayList()`).
 *
 * For constructor calls, the type is simply the type being constructed,
 * which is available via the `type` property of ConstructorCallExpression.
 */
class ConstructorCallExpressionCalculator : TypeCalculator<Any> {

    override val nodeType: KClass<Any> = Any::class

    override fun calculate(node: Any, context: TypeContext): SemanticType? {
        // Check if this is a ConstructorCallExpression by class name
        // (avoiding direct dependency on Groovy AST classes)
        // Using endsWith() to match any variant (e.g., GroovyConstructorCallExpression)
        // while avoiding false matches on unrelated wrapper classes.
        if (!node::class.java.simpleName.endsWith("ConstructorCallExpression")) {
            return null
        }

        // Get the type being constructed via reflection
        val typeNode = ReflectionAccess.getProperty(node, "type") ?: return null
        val typeName = ReflectionAccess.getProperty(typeNode, "name") as? String ?: return null

        return SemanticType.Known(typeName)
    }
}
