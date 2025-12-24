package com.github.albertocavalcante.groovyjenkins.gdsl

import com.github.albertocavalcante.groovygdsl.model.GdslParseResult
import com.github.albertocavalcante.groovygdsl.model.MethodDescriptor
import com.github.albertocavalcante.groovygdsl.model.NamedParameterDescriptor
import com.github.albertocavalcante.groovygdsl.model.ParameterDescriptor
import com.github.albertocavalcante.groovygdsl.model.PropertyDescriptor
import com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.GlobalVariableMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.StepParameter

/**
 * Adapter that converts GDSL parse results to Jenkins metadata.
 *
 * This replaces the regex-based GdslParser with execution-based parsing.
 * The groovy-gdsl module executes GDSL scripts natively, and this adapter
 * converts the captured contributions to Jenkins-specific metadata types.
 *
 * Example usage:
 * ```kotlin
 * val executor = GdslExecutor()
 * val parseResult = executor.executeAndCapture(gdslContent)
 *
 * val adapter = JenkinsGdslAdapter()
 * val jenkinsMetadata = adapter.convert(parseResult)
 * ```
 */
class JenkinsGdslAdapter {

    companion object {
        /** Default plugin name for GDSL-extracted metadata */
        const val DEFAULT_PLUGIN = "gdsl-extracted"

        /** Java package prefixes to simplify in type names */
        private val TYPE_SIMPLIFICATIONS = mapOf(
            "java.lang." to "",
            "groovy.lang." to "",
            "java.util." to "",
        )
    }

    /**
     * Converts a complete GDSL parse result to Jenkins metadata.
     *
     * @param result The parse result from GdslExecutor
     * @param pluginSource Optional plugin name for attribution
     * @return BundledJenkinsMetadata containing steps and global variables
     */
    fun convert(result: GdslParseResult, pluginSource: String = DEFAULT_PLUGIN): BundledJenkinsMetadata {
        if (!result.success) {
            return BundledJenkinsMetadata(steps = emptyMap(), globalVariables = emptyMap())
        }

        val steps = result.methods
            .map { convertMethod(it, pluginSource) }
            .associateBy { it.name }

        val globalVariables = result.properties
            .map { convertProperty(it) }
            .associateBy { it.name }

        return BundledJenkinsMetadata(
            steps = steps,
            globalVariables = globalVariables,
        )
    }

    /**
     * Converts a single method descriptor to Jenkins step metadata.
     *
     * @param method The method descriptor from GDSL
     * @param pluginSource Optional plugin name for attribution
     * @return JenkinsStepMetadata for the step
     */
    fun convertMethod(method: MethodDescriptor, pluginSource: String = DEFAULT_PLUGIN): JenkinsStepMetadata {
        // Prefer named parameters over positional (they have more metadata)
        val parameters = if (method.namedParameters.isNotEmpty()) {
            convertNamedParameters(method.namedParameters)
        } else {
            convertPositionalParameters(method.parameters)
        }

        return JenkinsStepMetadata(
            name = method.name,
            plugin = pluginSource,
            parameters = parameters,
            documentation = method.documentation,
        )
    }

    /**
     * Converts a single property descriptor to global variable metadata.
     *
     * @param property The property descriptor from GDSL
     * @return GlobalVariableMetadata for the global variable
     */
    fun convertProperty(property: PropertyDescriptor): GlobalVariableMetadata = GlobalVariableMetadata(
        name = property.name,
        type = property.type,
        documentation = property.documentation,
    )

    /**
     * Converts named parameters to step parameters.
     */
    private fun convertNamedParameters(params: List<NamedParameterDescriptor>): Map<String, StepParameter> =
        params.associate { param ->
            param.name to StepParameter(
                name = param.name,
                type = simplifyType(param.type),
                required = param.required,
                default = param.defaultValue,
                documentation = param.documentation,
            )
        }

    /**
     * Converts positional parameters to step parameters.
     */
    private fun convertPositionalParameters(params: List<ParameterDescriptor>): Map<String, StepParameter> =
        params.associate { param ->
            param.name to StepParameter(
                name = param.name,
                type = simplifyType(param.type),
                required = false, // Positional params don't have required info
                default = null,
                documentation = param.documentation,
            )
        }

    /**
     * Simplifies Java type names to more readable forms.
     *
     * Examples:
     * - java.lang.String → String
     * - groovy.lang.Closure → Closure
     * - java.util.Map → Map
     */
    private fun simplifyType(type: String): String {
        var simplified = type
        for ((prefix, replacement) in TYPE_SIMPLIFICATIONS) {
            if (simplified.startsWith(prefix)) {
                simplified = replacement + simplified.removePrefix(prefix)
                break
            }
        }
        return simplified
    }
}
