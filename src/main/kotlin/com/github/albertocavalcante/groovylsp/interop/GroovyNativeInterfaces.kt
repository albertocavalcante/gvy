package com.github.albertocavalcante.groovylsp.interop

import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.Position

/**
 * Kotlin interfaces for interoperating with Groovy native components.
 * These provide type-safe bridges to call Groovy components from Kotlin.
 */

private object ConfidenceLevel {
    const val COMPILE_TIME = 100
    const val CLASSPATH_STATIC = 90
    const val CONVENTION_BASED = 70
    const val DYNAMIC_INFERRED = 50
    const val UNKNOWN = 0
}

enum class ResolutionConfidence(val percentage: Int) {
    COMPILE_TIME(ConfidenceLevel.COMPILE_TIME), // Found in compilation unit
    CLASSPATH_STATIC(ConfidenceLevel.CLASSPATH_STATIC), // Found in classpath JARs
    CONVENTION_BASED(ConfidenceLevel.CONVENTION_BASED), // Follows Groovy conventions
    DYNAMIC_INFERRED(ConfidenceLevel.DYNAMIC_INFERRED), // Likely available at runtime
    UNKNOWN(ConfidenceLevel.UNKNOWN), // Can't determine
}

data class ResolutionResult(
    val symbol: String,
    val type: String,
    val confidence: ResolutionConfidence,
    val documentation: String? = null,
    val sourceLocation: String? = null,
    val metadata: Map<String, Any> = emptyMap(),
) {
    val isFound: Boolean
        get() = confidence != ResolutionConfidence.UNKNOWN
}

data class DynamicCapabilities(
    val hasMethodMissing: Boolean = false,
    val hasPropertyMissing: Boolean = false,
    val hasGetPropertyMissing: Boolean = false,
    val hasSetPropertyMissing: Boolean = false,
    val delegateFields: List<String> = emptyList(),
    val categoryClasses: List<String> = emptyList(),
    val mixinClasses: List<String> = emptyList(),
    val traitClasses: List<String> = emptyList(),
    val expandoMethods: Map<String, String> = emptyMap(),
    val expandoProperties: Map<String, String> = emptyMap(),
    val isExpandoMetaClass: Boolean = false,
    val hasCustomMetaClass: Boolean = false,
    val transformationAnnotations: List<String> = emptyList(),
    val metadata: Map<String, Any> = emptyMap(),
) {
    val isDynamic: Boolean
        get() = hasMethodMissing || hasPropertyMissing ||
            delegateFields.isNotEmpty() || categoryClasses.isNotEmpty() ||
            mixinClasses.isNotEmpty() || isExpandoMetaClass

    val dynamicScore: Int
        get() {
            var score = 0
            if (hasMethodMissing) score += METHOD_MISSING_SCORE
            if (hasPropertyMissing) score += PROPERTY_MISSING_SCORE
            score += delegateFields.size * DELEGATE_FIELD_SCORE
            score += categoryClasses.size * CATEGORY_CLASS_SCORE
            score += mixinClasses.size * MIXIN_CLASS_SCORE
            if (isExpandoMetaClass) score += EXPANDO_METACLASS_SCORE
            return minOf(score, MAX_DYNAMIC_SCORE)
        }

    companion object {
        private const val METHOD_MISSING_SCORE = 50
        private const val PROPERTY_MISSING_SCORE = 30
        private const val DELEGATE_FIELD_SCORE = 20
        private const val CATEGORY_CLASS_SCORE = 15
        private const val MIXIN_CLASS_SCORE = 10
        private const val EXPANDO_METACLASS_SCORE = 25
        private const val MAX_DYNAMIC_SCORE = 100
    }
}

enum class DSLMethodKind {
    BUILDER, // Returns same type for chaining
    CONFIGURATOR, // Accepts closure for configuration
    PROPERTY_SETTER, // Sets a property value
    FACTORY, // Creates new objects
    COLLECTION_ADDER, // Adds to collection
    UTILITY, // Utility method
}

data class DSLMethod(
    val name: String,
    val returnType: String,
    val parameterTypes: List<String> = emptyList(),
    val acceptsClosure: Boolean = false,
    val closureDelegateType: String? = null,
    val documentation: String? = null,
    val kind: DSLMethodKind = DSLMethodKind.UTILITY,
)

data class DSLContext(
    val builderClass: String,
    val availableMethods: List<DSLMethod> = emptyList(),
    val implicitProperties: Map<String, String> = emptyMap(),
    val commonPatterns: List<String> = emptyList(),
    val isBuilder: Boolean = false,
    val isConfigurable: Boolean = false,
)

/**
 * Interface for dynamic symbol resolution using Groovy's native semantics.
 */
interface GroovyDynamicResolver {
    /**
     * Resolve a method call using Groovy's dynamic dispatch rules.
     */
    fun resolveMethod(
        methodName: String,
        receiverType: ClassNode,
        argTypes: List<ClassNode> = emptyList(),
    ): ResolutionResult

    /**
     * Resolve a property access using Groovy's property resolution rules.
     */
    fun resolveProperty(propertyName: String, receiverType: ClassNode): ResolutionResult

    /**
     * Check if a class is statically compiled (@CompileStatic or @TypeChecked).
     */
    fun isStaticallyCompiled(classNode: ClassNode): Boolean

    /**
     * Get all possible completions for a given receiver type.
     */
    fun getAllPossibleMembers(receiverType: ClassNode): List<ResolutionResult>

    /**
     * Determine if a method call would likely succeed at runtime.
     */
    fun wouldMethodSucceedAtRuntime(
        classNode: ClassNode,
        methodName: String,
        argTypes: List<ClassNode> = emptyList(),
    ): Boolean
}

/**
 * Interface for analyzing metaclass capabilities of Groovy classes.
 */
interface GroovyMetaClassAnalyzer {
    /**
     * Analyze the dynamic capabilities of a ClassNode.
     */
    fun analyze(classNode: ClassNode): DynamicCapabilities

    /**
     * Get dynamic method suggestions for completion.
     */
    fun getDynamicMethodSuggestions(classNode: ClassNode): List<String>

    /**
     * Determine if a method call would likely succeed at runtime.
     */
    fun wouldMetaClassMethodSucceedAtRuntime(
        classNode: ClassNode,
        methodName: String,
        argTypes: List<ClassNode> = emptyList(),
    ): Boolean
}

/**
 * Interface for DSL introspection and analysis.
 */
interface GroovyDSLIntrospector {
    /**
     * Introspect a class to determine its DSL capabilities.
     */
    fun introspectDSL(classNode: ClassNode): DSLContext

    /**
     * Get completion suggestions for a DSL context.
     */
    fun getDSLCompletions(contextClass: ClassNode, prefix: String = ""): List<String>

    /**
     * Check if a class is likely a DSL/Builder.
     */
    fun isDSLClass(classNode: ClassNode): Boolean

    /**
     * Get method signature help for DSL methods.
     */
    fun getDSLMethodSignature(contextClass: ClassNode, methodName: String): String?
}

/**
 * Combined interface for all Groovy native resolution capabilities.
 */
interface GroovyNativeResolver :
    GroovyDynamicResolver,
    GroovyMetaClassAnalyzer,
    GroovyDSLIntrospector {
    /**
     * Comprehensive symbol resolution that considers all Groovy features.
     */
    fun resolveSymbol(symbol: String, context: ClassNode, position: Position? = null): List<ResolutionResult>

    /**
     * Get comprehensive completions for a context.
     */
    fun getCompletions(
        context: ClassNode,
        prefix: String = "",
        includeStatic: Boolean = true,
        includeDynamic: Boolean = true,
        includeDSL: Boolean = true,
    ): List<ResolutionResult>

    /**
     * Analyze overall "groovieness" of a class.
     */
    fun analyzeGroovyCharacteristics(classNode: ClassNode): Map<String, Any>
}
