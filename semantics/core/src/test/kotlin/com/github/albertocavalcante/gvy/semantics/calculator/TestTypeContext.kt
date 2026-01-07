package com.github.albertocavalcante.gvy.semantics.calculator

import com.github.albertocavalcante.gvy.semantics.SemanticType

/**
 * A minimal TypeContext implementation for unit testing calculators.
 *
 * Provides stub implementations that return Unknown/null for all lookups,
 * suitable for testing calculators in isolation.
 */
class TestTypeContext(override val isStaticCompilation: Boolean = false) : TypeContext {
    override fun resolveType(fqn: String): SemanticType = SemanticType.Unknown("Test context")
    override fun calculateType(node: Any): SemanticType = SemanticType.Unknown("Test context")
    override fun lookupSymbol(name: String): SemanticType? = null
    override fun getMethodReturnType(
        receiverType: SemanticType,
        methodName: String,
        argumentTypes: List<SemanticType>,
    ): SemanticType? = null
    override fun getFieldType(receiverType: SemanticType, fieldName: String): SemanticType? = null
}

/**
 * Creates a test TypeContext with default settings.
 *
 * Use this in calculator tests:
 * ```kotlin
 * val context = testContext()
 * val result = calculator.calculateResult(node, context)
 * ```
 */
fun testContext(isStaticCompilation: Boolean = false): TypeContext = TestTypeContext(isStaticCompilation)
