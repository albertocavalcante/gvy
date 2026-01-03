package com.github.albertocavalcante.groovylsp.types

import com.github.albertocavalcante.groovylsp.compilation.CompilationContext
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.Expression

/**
 * Interface for calculating types of Groovy expressions.
 * Inspired by IntelliJ's GrTypeCalculator pattern.
 */
interface TypeCalculator {

    /**
     * Calculates the type of the given expression.
     *
     * @param expression The expression to calculate type for
     * @param context The compilation context
     * @return The calculated ClassNode type, or null if this calculator cannot handle it
     */
    suspend fun calculateType(expression: Expression, context: CompilationContext): ClassNode?

    /**
     * Priority of this calculator - higher priority calculators are tried first.
     * Default priority is 0.
     */
    val priority: Int get() = 0
}

/**
 * Manages a chain of TypeCalculators and delegates to them in priority order.
 * Based on IntelliJ's ClassExtension pattern but adapted for Kotlin coroutines.
 */
class GroovyTypeCalculator {

    private val calculators = mutableListOf<TypeCalculator>()

    /**
     * Registers a type calculator.
     */
    fun register(calculator: TypeCalculator) {
        calculators.add(calculator)
        calculators.sortByDescending { it.priority }
    }

    /**
     * Calculates the type of an expression using the registered calculators.
     * Returns the first non-null result.
     */
    suspend fun calculateType(expression: Expression, context: CompilationContext): ClassNode? {
        for (calculator in calculators) {
            val result = calculator.calculateType(expression, context)
            if (result != null) {
                return result
            }
        }
        return null
    }
}

/**
 * Default type calculator for basic expressions.
 * Handles common cases like literals, variables, and method calls.
 */
class DefaultTypeCalculator : TypeCalculator {

    companion object {
        private const val FALLBACK_PRIORITY = -100
    }

    override val priority: Int = FALLBACK_PRIORITY // Low priority - fallback calculator

    override suspend fun calculateType(expression: Expression, context: CompilationContext): ClassNode? = when {
        // Null safety - return null if expression type is null
        expression.type == null -> null

        // Don't return obviously placeholder types
        expression.type.isPlaceholderType() -> null

        // Return the actual type if it looks legitimate
        else -> expression.type
    }

    private fun ClassNode.isPlaceholderType(): Boolean = when {
        // Check for common placeholder patterns
        this.name == "?" -> true
        this.name.isBlank() -> true
        // Be less aggressive with Object - only filter if it's obviously a placeholder
        this.name == ClassHelper.OBJECT_TYPE.name && this.isPlaceholderObject() -> true
        else -> false
    }

    private fun ClassNode.isPlaceholderObject(): Boolean {
        // Only consider Object a placeholder if:
        // 1. It has no generics AND
        // 2. It has no interfaces (except default Object ones) AND
        // 3. It appears to be a synthetic/default type
        return this.genericsTypes.isNullOrEmpty() &&
            (
                this.interfaces.isNullOrEmpty() ||
                    this.interfaces.all {
                        it.name == ClassHelper.OBJECT_TYPE.name ||
                            it.name == "groovy.lang.GroovyObject"
                    }
                ) &&
            this.redirect() == this // Not redirected to another type
    }
}
