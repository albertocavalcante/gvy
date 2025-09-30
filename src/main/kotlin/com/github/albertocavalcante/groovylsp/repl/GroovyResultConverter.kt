package com.github.albertocavalcante.groovylsp.repl

import org.slf4j.LoggerFactory

/**
 * Utility object for converting Groovy REPL results to Kotlin data structures.
 */
@Suppress("TooGenericExceptionCaught") // REPL result conversion handles all serialization errors
object GroovyResultConverter {

    private val logger = LoggerFactory.getLogger(GroovyResultConverter::class.java)

    /**
     * Converts a Groovy REPL result to Kotlin ReplResult.
     */
    fun convertGroovyResult(groovyResult: Any): ReplResult {
        try {
            val success = getFieldValue(groovyResult, "success") as Boolean
            val value = getFieldValue(groovyResult, "value")
            val type = getFieldValue(groovyResult, "type") as String?
            val output = getFieldValue(groovyResult, "output") as String
            val duration = getFieldValue(groovyResult, "duration") as Long
            val diagnostics = getFieldValue(groovyResult, "diagnostics") as List<*>
            val error = getFieldValue(groovyResult, "error") as Throwable?
            val groovyBindings = getFieldValue(groovyResult, "bindings") as Map<*, *>
            val groovySideEffects = getFieldValue(groovyResult, "sideEffects")

            return ReplResult(
                success = success,
                value = value,
                type = type,
                output = output,
                duration = duration,
                bindings = convertBindings(groovyBindings),
                diagnostics = diagnostics.map { it.toString() },
                sideEffects = convertSideEffects(groovySideEffects),
                error = error,
            )
        } catch (e: Exception) {
            logger.error("Failed to convert Groovy result", e)
            throw ReplException("Failed to convert result: ${e.message}", e)
        }
    }

    /**
     * Converts Groovy bindings to Kotlin map.
     */
    fun convertBindings(groovyBindings: Map<*, *>): Map<String, VariableInfo> = groovyBindings.mapKeys {
        it.key.toString()
    }.mapValues { (_, value) -> convertVariableInfo(value!!) }

    /**
     * Converts a single Groovy variable info to Kotlin VariableInfo.
     */
    fun convertVariableInfo(groovyVarInfo: Any): VariableInfo = VariableInfo(
        name = getFieldValue(groovyVarInfo, "name") as String,
        value = getFieldValue(groovyVarInfo, "value") as String,
        type = getFieldValue(groovyVarInfo, "type") as String,
        isNull = getFieldValue(groovyVarInfo, "isNull") as Boolean,
        size = getFieldValue(groovyVarInfo, "size") as Int?,
    )

    /**
     * Converts Groovy type info to Kotlin TypeInfo.
     */
    fun convertTypeInfo(groovyTypeInfo: Any): TypeInfo {
        val methods = (getFieldValue(groovyTypeInfo, "methods") as List<*>).map { convertMethodInfo(it!!) }
        val properties = (getFieldValue(groovyTypeInfo, "properties") as List<*>).map { convertPropertyInfo(it!!) }

        return TypeInfo(
            typeName = getFieldValue(groovyTypeInfo, "typeName") as String,
            hierarchy = getFieldValue(groovyTypeInfo, "hierarchy") as List<String>,
            methods = methods,
            properties = properties,
            documentation = getFieldValue(groovyTypeInfo, "documentation") as String?,
        )
    }

    private fun convertMethodInfo(groovyMethodInfo: Any): MethodInfo = MethodInfo(
        name = getFieldValue(groovyMethodInfo, "name") as String,
        signature = getFieldValue(groovyMethodInfo, "signature") as String,
        returnType = getFieldValue(groovyMethodInfo, "returnType") as String,
        isStatic = getFieldValue(groovyMethodInfo, "isStatic") as Boolean,
        visibility = getFieldValue(groovyMethodInfo, "visibility") as String,
    )

    private fun convertPropertyInfo(groovyPropertyInfo: Any): PropertyInfo = PropertyInfo(
        name = getFieldValue(groovyPropertyInfo, "name") as String,
        type = getFieldValue(groovyPropertyInfo, "type") as String,
        hasGetter = getFieldValue(groovyPropertyInfo, "hasGetter") as Boolean,
        hasSetter = getFieldValue(groovyPropertyInfo, "hasSetter") as Boolean,
        isStatic = getFieldValue(groovyPropertyInfo, "isStatic") as Boolean,
    )

    private fun convertSideEffects(groovySideEffects: Any?): SideEffects {
        if (groovySideEffects == null) return SideEffects()

        return SideEffects(
            printOutput = getFieldValue(groovySideEffects, "printOutput") as String,
            errorOutput = getFieldValue(groovySideEffects, "errorOutput") as String,
            imports = getFieldValue(groovySideEffects, "imports") as List<String>,
            classesLoaded = getFieldValue(groovySideEffects, "classesLoaded") as List<String>,
            systemPropertyChanges = getFieldValue(groovySideEffects, "systemPropertyChanges") as Map<String, String>,
        )
    }

    private fun getFieldValue(instance: Any, fieldName: String): Any? = try {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.get(instance)
    } catch (e: Exception) {
        logger.warn("Failed to get field value: $fieldName", e)
        null
    }
}
