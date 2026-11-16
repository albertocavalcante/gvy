// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.gdsl

import com.github.albertocavalcante.gvy.gdsl.model.GdslParseResult
import com.github.albertocavalcante.gvy.gdsl.model.MethodDescriptor
import com.github.albertocavalcante.gvy.gdsl.model.NamedParameterDescriptor
import com.github.albertocavalcante.gvy.gdsl.model.ParameterDescriptor
import com.github.albertocavalcante.gvy.gdsl.model.PropertyDescriptor
import com.github.albertocavalcante.gvy.jenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.GlobalVariableMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.Knowledge
import com.github.albertocavalcante.gvy.jenkins.metadata.MetadataProvenance
import com.github.albertocavalcante.gvy.jenkins.metadata.SourceKind
import com.github.albertocavalcante.gvy.jenkins.metadata.StepParameter
import com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind
import com.github.albertocavalcante.gvy.jenkins.metadata.TypedCatalogue
import com.github.albertocavalcante.gvy.jenkins.metadata.TypedSymbol
import com.github.albertocavalcante.gvy.jenkins.metadata.isActionableUnqualified
import com.github.albertocavalcante.gvy.jenkins.metadata.toTypedSymbol
import com.github.albertocavalcante.gvy.jenkins.metadata.withCanonicalProjection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Adapter that converts GDSL parse results to Jenkins metadata.
 *
 * The groovy-gdsl module reads GDSL scripts from their AST without running them, and this adapter
 * converts the captured contributions to Jenkins-specific metadata types.
 *
 * GDSL files come from the workspace, so their text is untrusted, while completion and hover render
 * metadata as markdown. The adapter is that boundary: documentation becomes escaped literal text (no
 * links, `command:` URIs, HTML or emphasis), names must be identifiers, types must look like type names
 * (otherwise `Object`), and a default value that could break out of a code span is dropped.
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

        private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val TYPE_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$.<>\\[\\], ?]*")
        private const val FALLBACK_TYPE = "Object"
        private const val MAX_TYPE_LENGTH = 200

        /** CommonMark lets a backslash escape any ASCII punctuation character, which then renders literally. */
        private const val MARKDOWN_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

        /** [text] as markdown that renders as exactly that text. */
        internal fun literalMarkdown(text: String): String = buildString {
            text.replace("\r\n", "\n").forEach { char ->
                if (char in MARKDOWN_PUNCTUATION) append('\\')
                append(char)
            }
        }

        private fun isIdentifier(name: String): Boolean = IDENTIFIER.matches(name)
    }

    /**
     * Converts a complete GDSL parse result to Jenkins metadata.
     *
     * @param result The parse result from GdslExecutor
     * @param pluginSource Legacy compatibility label, not plugin ownership
     * @param sourceOrigin File or discovery input label for canonical provenance
     * @param sourceNamespace Stable identity of a known source, separate from provenance and owner
     * @return BundledJenkinsMetadata containing steps and global variables
     */
    fun convert(
        result: GdslParseResult,
        pluginSource: String = DEFAULT_PLUGIN,
        sourceOrigin: String? = null,
        sourceNamespace: String? = null,
    ): BundledJenkinsMetadata {
        if (!result.success) {
            return BundledJenkinsMetadata(steps = emptyMap(), globalVariables = emptyMap())
        }

        val origin = sourceOrigin ?: pluginSource
        val namespace = sourceNamespace?.let { "gdsl-source:$it" } ?: "gdsl-ingestion:${UUID.randomUUID()}"
        val methods = result.methods.filter { isIdentifier(it.name) }
        val properties = result.properties.filter { isIdentifier(it.name) }
        val symbols = methods.mapIndexed { index, method ->
            convertMethod(method, pluginSource, origin).toTypedSymbol().copy(
                id = "gdsl:method:$index:${method.name}",
                ownerPlugin = null,
                legacyDerived = false,
                sourceNamespace = namespace,
                gdslContext = method.context.toJsonContext(),
            )
        } + properties.mapIndexed { index, property ->
            val converted = convertProperty(property)
            TypedSymbol(
                id = "gdsl:property:$index:${property.name}",
                name = property.name,
                kind = SymbolKind.GLOBAL,
                returnType = converted.type,
                contexts = property.enclosingCall?.let(::listOf),
                gdslContext = property.context.toJsonContext(),
                provenance = MetadataProvenance(SourceKind.GDSL, origin = origin),
                sourceNamespace = namespace,
                documentation = converted.documentation,
            )
        }
        val eligible = symbols.groupBy { it.kind to it.name }.values.mapNotNull { candidates ->
            candidates.singleOrNull()?.takeIf { it.isActionableUnqualified() }
        }
        val steps = methods.mapIndexedNotNull { index, method ->
            val symbol = symbols[index]
            if (symbol !in eligible) return@mapIndexedNotNull null
            convertMethod(method, pluginSource, origin).withCanonicalProjection(symbol)
        }.associateBy { it.name }
        val globals = properties.mapIndexedNotNull { index, property ->
            val symbol = symbols[methods.size + index]
            if (symbol !in eligible) return@mapIndexedNotNull null
            convertProperty(property).withCanonicalProjection(symbol)
        }.associateBy { it.name }

        return BundledJenkinsMetadata(
            steps = steps,
            globalVariables = globals,
            typedCatalogue = TypedCatalogue(symbols = symbols),
        )
    }

    /**
     * Converts a single method descriptor to Jenkins step metadata.
     *
     * @param method The method descriptor from GDSL
     * @param pluginSource Legacy compatibility label
     * @param sourceOrigin File or discovery input label for canonical provenance
     * @return JenkinsStepMetadata for the step
     */
    fun convertMethod(
        method: MethodDescriptor,
        pluginSource: String = DEFAULT_PLUGIN,
        sourceOrigin: String = pluginSource,
    ): JenkinsStepMetadata {
        // Named descriptors enrich matching positional parameters without discarding either form.
        val parameters = convertPositionalParameters(method.parameters) + convertNamedParameters(method.namedParameters)

        return JenkinsStepMetadata(
            name = method.name,
            plugin = pluginSource,
            provenance = MetadataProvenance(SourceKind.GDSL, origin = sourceOrigin),
            parameters = parameters,
            positionalParams = method.parameters.map { it.name }.filter(::isIdentifier),
            namedBinding = if (method.namedParameters.isNotEmpty()) Knowledge.YES else Knowledge.UNKNOWN,
            contexts = method.enclosingCall?.let(::listOf),
            returnType = method.returnType.takeIf { it.length <= MAX_TYPE_LENGTH && TYPE_NAME.matches(it) }
                ?: FALLBACK_TYPE,
            documentation = method.documentation?.let(::literalMarkdown),
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
        type = simplifyType(property.type),
        documentation = property.documentation?.let(::literalMarkdown),
    )

    /**
     * Converts named parameters to step parameters.
     */
    private fun convertNamedParameters(params: List<NamedParameterDescriptor>): Map<String, StepParameter> =
        params.filter { isIdentifier(it.name) }.associate { param ->
            param.name to StepParameter(
                name = param.name,
                type = simplifyType(param.type),
                required = param.required,
                // Hover shows the default inside a code span, where escapes do not apply.
                default = param.defaultValue?.takeUnless { value -> value.any { it == '`' || it.isISOControl() } },
                documentation = param.documentation?.let(::literalMarkdown),
            )
        }

    /**
     * Converts positional parameters to step parameters.
     */
    private fun convertPositionalParameters(params: List<ParameterDescriptor>): Map<String, StepParameter> =
        params.filter { isIdentifier(it.name) }.associate { param ->
            param.name to StepParameter(
                name = param.name,
                type = simplifyType(param.type),
                required = null, // Positional params do not supply requiredness
                default = null,
                documentation = param.documentation?.let(::literalMarkdown),
            )
        }

    /**
     * Simplifies Java type names to more readable forms.
     *
     * Examples:
     * - java.lang.String → String
     * - groovy.lang.Closure → Closure
     * - java.util.Map → Map
     *
     * Anything that does not look like a type name (it is shown inside a code span) becomes `Object`.
     */
    private fun simplifyType(type: String): String {
        if (type.length > MAX_TYPE_LENGTH || !TYPE_NAME.matches(type)) return FALLBACK_TYPE
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

/** The AST interpreter admits scalar facts, scalar lists and scalar scope maps, never live objects. */
private fun Map<String, Any>.toJsonContext(): JsonObject = JsonObject(mapValues { (_, value) -> value.toContextJson() })

private fun Any?.toContextJson(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is List<*> -> JsonArray(map { it.toContextJson() })
    is Map<*, *> -> JsonObject(
        entries.associate { (key, value) ->
            require(key is String) { "GDSL context keys must be strings" }
            key to value.toContextJson()
        },
    )
    else -> error("Unsupported GDSL context fact: ${javaClass.name}")
}
