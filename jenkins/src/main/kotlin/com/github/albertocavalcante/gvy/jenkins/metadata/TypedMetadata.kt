// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.metadata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Namespaces remain distinct even when two symbols have the same spelling. */
@Serializable
enum class SymbolKind {
    EXECUTABLE_STEP,
    NESTED_DESCRIBABLE,
    DIRECTIVE_ENTRY,
    OPTION,
    CONDITION,
    AGENT,
    TOOL,
    TRIGGER,
    GLOBAL,
}

@Serializable
enum class Requiredness { REQUIRED, OPTIONAL, UNKNOWN }

@Serializable
enum class Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }

@Serializable
enum class Knowledge { YES, NO, UNKNOWN }

@Serializable
enum class SourceKind { CURATED, STATIC_DISCOVERY, GDSL, USER_OVERRIDE, DESCRIPTOR_PROFILE, UNKNOWN }

@Serializable
enum class OwnerEvidence { CURATED, DESCRIPTOR, USER_DECLARED, UNKNOWN }

@Serializable
data class MetadataProvenance(
    val source: SourceKind,
    val plugin: String? = null,
    val pluginVersion: String? = null,
    val profileId: String? = null,
    val artifactDigest: String? = null,
    val legacyPlugin: String? = null,
    /** File or discovery input label; it is not evidence of plugin ownership. */
    val origin: String? = null,
)

private fun legacyId(kind: SymbolKind, name: String, owner: String?, source: SourceKind = SourceKind.CURATED): String =
    listOf("legacy", source.name, kind.name, owner ?: "", name).joinToString(":") { "${it.length}:$it" }

internal fun JenkinsStepMetadata.toTypedSymbol(): TypedSymbol =
    canonicalProjection?.takeIf { it.matches(this) }?.symbol ?: TypedSymbol(
        id = legacyId(
            kind,
            name,
            ownerPlugin,
            provenance.source,
        ),
        name = name,
        kind = kind,
        ownerPlugin = ownerPlugin,
        ownerEvidence = ownerEvidence,
        legacyDerived = true,
        contributors = if (ownerPlugin == null && provenance.source == SourceKind.UNKNOWN) {
            listOf(MetadataProvenance(SourceKind.UNKNOWN, legacyPlugin = plugin))
        } else {
            emptyList()
        },
        aliases = aliases,
        contexts = contexts,
        namedBinding = namedBinding,
        block = block,
        valueDomain = valueDomain,
        parameters = parameters.values.map { it.toTypedParameter() },
        positional = positionalParams,
        returnType = returnType,
        provenance = provenance,
        documentation = documentation,
    )

private fun StepParameter.toTypedParameter(): TypedParameter = TypedParameter(
    name = name,
    type = type,
    requiredness = when (required) {
        true -> Requiredness.REQUIRED
        false -> Requiredness.OPTIONAL
        null -> Requiredness.UNKNOWN
    },
    defaultValue = default,
    documentation = documentation,
)

/** Adapt legacy bundled records without inventing descriptor or controller facts. */
internal fun BundledJenkinsMetadata.toTypedCatalogue(): TypedCatalogue {
    val symbols = mutableListOf<TypedSymbol>()
    // User overrides are patches, not independent discovered callables.
    symbols += steps.filterKeys { it !in legacyOverrides }.values.map { it.toTypedSymbol() }
    symbols += nestedDescribables.values.map { it.toTypedSymbol() }
    fun addEntries(entries: Map<String, DeclarativeOptionMetadata>, kind: SymbolKind, context: String? = null) {
        symbols += entries.values.map { entry ->
            TypedSymbol(
                id = legacyId(kind, entry.name, entry.plugin) + ":" + (context ?: ""),
                name = entry.name,
                kind = kind,
                legacyDerived = true,
                ownerPlugin = entry.plugin,
                ownerEvidence = OwnerEvidence.CURATED,
                parameters = entry.parameters.values.map { it.toTypedParameter() },
                contexts = context?.let(::listOf),
                provenance = MetadataProvenance(SourceKind.CURATED, plugin = entry.plugin),
                documentation = entry.documentation,
            )
        }
    }
    addEntries(declarativeOptions, SymbolKind.OPTION, "options")
    directiveEntries.forEach { (context, entries) ->
        val kind = when (context) {
            "tools" -> SymbolKind.TOOL
            "triggers" -> SymbolKind.TRIGGER
            else -> SymbolKind.DIRECTIVE_ENTRY
        }
        addEntries(entries, kind, context)
    }
    symbols += agentTypes.values.map { agent ->
        TypedSymbol(
            id = legacyId(SymbolKind.AGENT, agent.name, null),
            name = agent.name,
            kind = SymbolKind.AGENT,
            legacyDerived = true,
            parameters = agent.parameters.values.map { it.toTypedParameter() },
            provenance = MetadataProvenance(SourceKind.CURATED),
            documentation = agent.documentation,
        )
    }
    symbols += postConditions.values.map { condition ->
        TypedSymbol(
            id = legacyId(SymbolKind.CONDITION, condition.name, null),
            name = condition.name,
            kind = SymbolKind.CONDITION,
            legacyDerived = true,
            contexts = listOf("post"),
            provenance = MetadataProvenance(SourceKind.CURATED),
            documentation = condition.description,
        )
    }
    symbols += globalVariables.values.map { global ->
        global.canonicalProjection?.takeIf { it.matches(global) }?.symbol ?: TypedSymbol(
            id = legacyId(SymbolKind.GLOBAL, global.name, null),
            name = global.name,
            kind = SymbolKind.GLOBAL,
            legacyDerived = true,
            returnType = global.type,
            provenance = MetadataProvenance(SourceKind.UNKNOWN),
            documentation = global.documentation,
        )
    }
    return typedCatalogue?.withLegacyFallbacks(symbols) ?: TypedCatalogue(symbols = symbols)
}

internal fun MergedStepMetadata.toTypedSymbol(
    provenance: MetadataProvenance,
    kind: SymbolKind = SymbolKind.EXECUTABLE_STEP,
): TypedSymbol = TypedSymbol(
    id = legacyId(
        kind,
        name,
        provenance.plugin.takeIf {
            provenance.source != SourceKind.GDSL && provenance.source != SourceKind.USER_OVERRIDE
        },
        provenance.source,
    ),
    name = name,
    kind = kind,
    ownerPlugin = provenance.plugin.takeIf {
        provenance.source != SourceKind.GDSL && provenance.source != SourceKind.USER_OVERRIDE
    },
    parameters = namedParams.values.map { it.toTypedParameter() },
    positional = positionalParams,
    returnType = returnType,
    provenance = provenance,
    documentation = documentation,
)

/** Adapts the legacy merged constructor only when no explicit catalogue was supplied. */
internal fun legacyMergedCatalogue(
    steps: Map<String, MergedStepMetadata>,
    globals: Map<String, MergedGlobalVariable>,
    options: Map<String, MergedDeclarativeOption>,
    entries: Map<String, Map<String, MergedDeclarativeOption>>,
    nested: Map<String, MergedStepMetadata>,
): TypedCatalogue {
    val symbols = mutableListOf<TypedSymbol>()
    fun addSteps(values: Map<String, MergedStepMetadata>, kind: SymbolKind) {
        symbols += values.values.map { step ->
            step.toTypedSymbol(MetadataProvenance(SourceKind.UNKNOWN, plugin = step.plugin), kind)
                .copy(legacyDerived = true)
        }
    }
    addSteps(steps, SymbolKind.EXECUTABLE_STEP)
    addSteps(nested, SymbolKind.NESTED_DESCRIBABLE)

    fun addEntries(values: Map<String, MergedDeclarativeOption>, kind: SymbolKind, context: String) {
        symbols += values.values.map { entry ->
            TypedSymbol(
                id = legacyId(kind, entry.name, entry.plugin, SourceKind.UNKNOWN) + ":" + context,
                name = entry.name,
                kind = kind,
                legacyDerived = true,
                ownerPlugin = entry.plugin,
                parameters = entry.parameters.values.map { it.toTypedParameter() },
                contexts = listOf(context),
                provenance = MetadataProvenance(SourceKind.UNKNOWN, plugin = entry.plugin),
                documentation = entry.documentation,
            )
        }
    }
    addEntries(options, SymbolKind.OPTION, "options")
    entries.forEach { (context, values) ->
        val kind = when (context) {
            "tools" -> SymbolKind.TOOL
            "triggers" -> SymbolKind.TRIGGER
            else -> SymbolKind.DIRECTIVE_ENTRY
        }
        addEntries(values, kind, context)
    }
    symbols += globals.values.map { global ->
        TypedSymbol(
            id = legacyId(SymbolKind.GLOBAL, global.name, null, SourceKind.UNKNOWN),
            name = global.name,
            kind = SymbolKind.GLOBAL,
            legacyDerived = true,
            returnType = global.type,
            provenance = MetadataProvenance(SourceKind.UNKNOWN),
            documentation = global.documentation,
        )
    }
    return TypedCatalogue(symbols = symbols)
}

private fun MergedParameter.toTypedParameter(): TypedParameter = TypedParameter(
    name = name,
    type = type,
    requiredness = when (required) {
        true -> Requiredness.REQUIRED
        false -> Requiredness.OPTIONAL
        null -> Requiredness.UNKNOWN
    },
    defaultValue = defaultValue,
    validValues = validValues,
    documentation = description,
)

/** Singular legacy consumers receive a result only when a namespace/name resolves uniquely. */
internal fun TypedCatalogue.projectSteps(
    kind: SymbolKind,
    legacy: Map<String, MergedStepMetadata> = emptyMap(),
    legacyIds: Set<String> = emptySet(),
    priorCatalogue: TypedCatalogue? = null,
): Map<String, MergedStepMetadata> = symbols.filter { it.kind == kind }
    .groupBy { it.name }
    .mapNotNull { (name, entries) ->
        val symbol = entries.legacyCandidateFor(name, symbols) ?: return@mapNotNull null
        val projected = symbol.toLegacyStep() ?: return@mapNotNull null
        val prior = priorCatalogue?.symbols?.singleOrNull {
            CanonicalViewBinding.run { it.sameProjectionDeclaration(symbol) }
        }
        val binding = if (priorCatalogue == null) projected else prior?.toLegacyStep()
        val original = legacy[name]?.takeIf {
            symbol.legacyDerived && symbol.id in legacyIds && binding != null &&
                CanonicalViewBinding.run { it.matchesCanonicalStep(binding) }
        }
        val parameters = CanonicalViewBinding.run { projected.parametersWithExamples(original) }
        val documentationChanged = original != null && symbol.documentation != original.documentation
        val result = original?.copy(
            namedParams = parameters,
            plugin = projected.plugin,
            positionalParams = projected.positionalParams,
            returnType = projected.returnType,
            extractedDocumentation = if (documentationChanged) {
                symbol.documentation
            } else {
                original.extractedDocumentation
            },
            enrichedDescription = if (documentationChanged) null else original.enrichedDescription,
        ) ?: projected
        name to result
    }.toMap()

private fun TypedSymbol.toLegacyStep(): MergedStepMetadata? {
    if (parameters.any { it.type == null } || parameters.map { it.name }.distinct().size != parameters.size) return null
    return MergedStepMetadata(
        name = name,
        scope = com.github.albertocavalcante.gvy.jenkins.metadata.extracted.StepScope.UNKNOWN,
        positionalParams = positional,
        namedParams = parameters.associate { parameter ->
            parameter.name to MergedParameter(
                name = parameter.name,
                type = checkNotNull(parameter.type),
                defaultValue = parameter.defaultValue,
                description = parameter.documentation,
                required = when (parameter.requiredness) {
                    Requiredness.REQUIRED -> true
                    Requiredness.OPTIONAL -> false
                    Requiredness.UNKNOWN -> null
                },
                validValues = parameter.validValues,
                examples = emptyList(),
            )
        },
        extractedDocumentation = documentation,
        returnType = returnType,
        plugin = ownerPlugin,
        enrichedDescription = null,
        documentationUrl = null,
        category = null,
        examples = emptyList(),
        deprecation = null,
    )
}

/** Preserve ambiguity and unknown ownership instead of inventing a legacy option. */
internal fun TypedCatalogue.projectOptions(kind: SymbolKind, context: String): Map<String, MergedDeclarativeOption> =
    symbols.filter { it.kind == kind && context in it.contexts.orEmpty() }
        .groupBy { it.name }
        .mapNotNull { (name, entries) ->
            val applicable = symbols.filter { it.kind == kind && context in it.contexts.orEmpty() }
            val symbol = entries.legacyCandidateFor(name, applicable) ?: return@mapNotNull null
            val owner = symbol.ownerPlugin ?: return@mapNotNull null
            if (symbol.parameters.any { it.type == null } ||
                symbol.parameters.map { it.name }.distinct().size != symbol.parameters.size
            ) {
                return@mapNotNull null
            }
            name to MergedDeclarativeOption(
                name = name,
                plugin = owner,
                documentation = symbol.documentation,
                parameters = symbol.parameters.associate { parameter ->
                    parameter.name to MergedParameter(
                        name = parameter.name,
                        type = checkNotNull(parameter.type),
                        defaultValue = parameter.defaultValue,
                        description = parameter.documentation,
                        required = when (parameter.requiredness) {
                            Requiredness.REQUIRED -> true
                            Requiredness.OPTIONAL -> false
                            Requiredness.UNKNOWN -> null
                        },
                        validValues = parameter.validValues,
                        examples = emptyList(),
                    )
                },
            )
        }.toMap()

internal fun TypedCatalogue.projectGlobals(
    legacy: Map<String, MergedGlobalVariable> = emptyMap(),
    legacyIds: Set<String> = emptySet(),
): Map<String, MergedGlobalVariable> = symbols.filter { it.kind == SymbolKind.GLOBAL }
    .groupBy { it.name }
    .mapNotNull { (name, entries) ->
        val symbol = entries.legacyCandidateFor(name, symbols) ?: return@mapNotNull null
        val type = symbol.returnType ?: return@mapNotNull null
        val original = legacy[name]?.takeIf { symbol.legacyDerived && symbol.id in legacyIds }
        name to (
            original ?: MergedGlobalVariable(
                name = name,
                type = type,
                extractedDocumentation = symbol.documentation,
                enrichedDescription = null,
                documentationUrl = null,
                properties = emptyMap(),
            )
            )
    }.toMap()

/** Compatibility projection of executable symbols; ambiguous or unrepresentable entries are omitted. */
internal fun TypedCatalogue.projectBundledSteps(
    legacy: Map<String, JenkinsStepMetadata> = emptyMap(),
): Map<String, JenkinsStepMetadata> = symbols.filter { it.kind == SymbolKind.EXECUTABLE_STEP }
    .groupBy { it.name }
    .mapNotNull { (name, entries) ->
        val symbol = entries.legacyCandidateFor(name, symbols) ?: return@mapNotNull null
        val original = legacy[name]?.takeIf { view ->
            val projection = view.canonicalProjection
            val matchesProjection = projection != null && projection.matches(view) &&
                (projection.symbol == symbol || projection.symbol.sameCanonicalFacts(symbol))
            matchesProjection || (
                symbol.legacyDerived && CanonicalViewBinding.run { view.matchesCanonicalSymbol(symbol) } &&
                    symbol.contributors.none { source -> source.source == SourceKind.USER_OVERRIDE }
                )
        }
        if (original != null) return@mapNotNull name to original
        name to (symbol.toBundledStep() ?: return@mapNotNull null)
    }.toMap()

private fun TypedSymbol.toBundledStep(): JenkinsStepMetadata? {
    val symbol = this
    val owner = symbol.ownerPlugin ?: return null
    if (symbol.parameters.any { it.type == null } ||
        symbol.parameters.map { it.name }.distinct().size != symbol.parameters.size
    ) {
        return null
    }
    return JenkinsStepMetadata(
        name = name,
        plugin = owner,
        ownerPlugin = owner,
        ownerEvidence = symbol.ownerEvidence,
        provenance = symbol.provenance,
        kind = symbol.kind,
        aliases = symbol.aliases,
        contexts = symbol.contexts,
        namedBinding = symbol.namedBinding,
        block = symbol.block,
        valueDomain = symbol.valueDomain,
        positionalParams = symbol.positional,
        returnType = symbol.returnType,
        documentation = symbol.documentation,
        parameters = symbol.parameters.associate { parameter ->
            parameter.name to StepParameter(
                name = parameter.name,
                type = checkNotNull(parameter.type),
                required = when (parameter.requiredness) {
                    Requiredness.REQUIRED -> true
                    Requiredness.OPTIONAL -> false
                    Requiredness.UNKNOWN -> null
                },
                default = parameter.defaultValue,
                documentation = parameter.documentation,
            )
        },
    ).withCanonicalProjection(symbol)
}

private object CanonicalViewBinding {
    /** A legacy view may supply adjuncts only when its represented facts match the selected symbol. */
    fun JenkinsStepMetadata.matchesCanonicalSymbol(symbol: TypedSymbol): Boolean {
        val adapted = copy(canonicalProjection = null).toTypedSymbol()
        return hasCanonicalOwner(symbol) && hasCanonicalShape(symbol) &&
            adapted.parameters == symbol.parameters.map { it.copy(validValues = null) }
    }

    private fun JenkinsStepMetadata.hasCanonicalOwner(symbol: TypedSymbol): Boolean =
        name == symbol.name && kind == symbol.kind && ownerPlugin == symbol.ownerPlugin &&
            ownerEvidence == symbol.ownerEvidence && provenance == symbol.provenance &&
            (symbol.ownerPlugin == null || plugin == symbol.ownerPlugin)

    private fun JenkinsStepMetadata.hasCanonicalShape(symbol: TypedSymbol): Boolean =
        aliases == symbol.aliases && contexts == symbol.contexts && namedBinding == symbol.namedBinding &&
            block == symbol.block && valueDomain == symbol.valueDomain && positionalParams == symbol.positional &&
            returnType == symbol.returnType && documentation == symbol.documentation

    fun TypedSymbol.sameProjectionDeclaration(other: TypedSymbol): Boolean =
        id == other.id && kind == other.kind && name == other.name && contexts == other.contexts &&
            ownerPlugin == other.ownerPlugin && ownerEvidence == other.ownerEvidence &&
            sourceNamespace == other.sourceNamespace && (sourceId ?: id) == (other.sourceId ?: other.id)

    fun MergedStepMetadata.parametersWithExamples(original: MergedStepMetadata?): Map<String, MergedParameter> =
        namedParams.mapValues { (parameterName, parameter) ->
            val previous = original?.namedParams?.get(parameterName)
            if (previous != null && previous.type == parameter.type) {
                parameter.copy(examples = previous.examples)
            } else {
                parameter
            }
        }

    fun MergedStepMetadata.matchesCanonicalStep(projected: MergedStepMetadata): Boolean =
        name == projected.name && plugin == projected.plugin && positionalParams == projected.positionalParams &&
            returnType == projected.returnType && documentation == projected.documentation &&
            namedParams.mapValues { (_, parameter) -> parameter.copy(examples = emptyList()) } == projected.namedParams
}

/** A spelling claimed as another applicable declaration's alias is not a singular primary view. */
private fun List<TypedSymbol>.legacyCandidateFor(name: String, applicable: List<TypedSymbol>): TypedSymbol? {
    val kind = first().kind
    if (applicable.any { it.kind == kind && it.name != name && name in it.aliases }) return null
    return legacyCandidate()
}

/** Existing descriptor facts retain legacy precedence; all competing GDSL facts remain in the catalogue. */
private fun List<TypedSymbol>.legacyCandidate(): TypedSymbol? {
    val descriptors = filter { it.provenance.source != SourceKind.GDSL }
    val candidate = if (descriptors.isEmpty()) singleOrNull() else descriptors.singleOrNull()
    return candidate?.takeIf { it.isActionableUnqualified() }
}

/** Contextual GDSL contributions need the later contextual resolver before unqualified publication. */
internal fun TypedSymbol.isActionableUnqualified(): Boolean = availability != Availability.UNAVAILABLE &&
    (
        provenance.source != SourceKind.GDSL || (
            contexts.isNullOrEmpty() && (gdslContext.isNullOrEmpty() || gdslContext == SCRIPT_GDSL_CONTEXT)
            )
        )

// GdslInterpreter emits exactly this shape for scriptScope() with no additional restrictions.
private val SCRIPT_GDSL_CONTEXT = JsonObject(
    mapOf("scope" to JsonObject(mapOf("type" to JsonPrimitive("script")))),
)
