// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.metadata

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/** Canonical facts snapshot inputs at construction, copy and deserialization boundaries. */
@Serializable(with = TypedCatalogue.Serializer::class)
class TypedCatalogue(
    schemaMajor: Int = 1,
    symbols: List<TypedSymbol> = emptyList(),
    unresolvedOverrides: List<TypedSymbol> = emptyList(),
) {
    private val state = State(
        schemaMajor = schemaMajor,
        symbols = symbols.immutableSnapshot(),
        unresolvedOverrides = unresolvedOverrides.immutableSnapshot(),
    )

    init {
        require(state.schemaMajor == 1) { "Unsupported typed metadata major version: $schemaMajor" }
        require(
            state.symbols.all { it.id.isNotBlank() } &&
                state.symbols.map { it.id }.distinct().size == state.symbols.size,
        ) {
            "Typed metadata IDs must be non-empty and unique"
        }
    }

    val schemaMajor: Int get() = state.schemaMajor
    val symbols: List<TypedSymbol> get() = state.symbols
    val unresolvedOverrides: List<TypedSymbol> get() = state.unresolvedOverrides

    fun copy(
        schemaMajor: Int = this.schemaMajor,
        symbols: List<TypedSymbol> = this.symbols,
        unresolvedOverrides: List<TypedSymbol> = this.unresolvedOverrides,
    ): TypedCatalogue = TypedCatalogue(
        schemaMajor = schemaMajor,
        symbols = symbols,
        unresolvedOverrides = unresolvedOverrides,
    )

    override fun equals(other: Any?): Boolean = other is TypedCatalogue && state == other.state

    override fun hashCode(): Int = state.hashCode()

    override fun toString(): String = "TypedCatalogue" + state.toString().removePrefix("State")

    fun candidates(kind: SymbolKind, name: String): List<TypedSymbol> = symbols.filter {
        it.kind == kind && (it.name == name || name in it.aliases)
    }

    fun unique(kind: SymbolKind, name: String): TypedSymbol? = candidates(kind, name).singleOrNull()

    /** Apply fallback authority only to the accompanying legacy view, not independent contributions. */
    fun withLegacyFallbacks(fallbacks: List<TypedSymbol>): TypedCatalogue = fallbacks.fold(this) { current, fallback ->
        val owner = fallback.ownerPlugin
        val matchingOwner = current.symbols.count {
            owner != null && fallback.ownerEvidence != OwnerEvidence.UNKNOWN &&
                it.kind == fallback.kind && it.name == fallback.name && it.ownerPlugin == owner &&
                it.contexts == fallback.contexts && it.ownerEvidence != OwnerEvidence.UNKNOWN
        }
        val represented = current.symbols.any {
            it.sameCanonicalFacts(fallback) || it.replacesCanonicalFacts(fallback) ||
                it.representsLegacyFallback(fallback)
        }
        if (represented || matchingOwner == 1) {
            current
        } else {
            current.copy(symbols = current.symbols + current.disambiguate(fallback))
        }
    }

    private fun disambiguate(candidate: TypedSymbol, replacingId: String? = null): TypedSymbol {
        val occupied = symbols.filterNot { it.id == replacingId }.map { it.id }.toSet()
        if (candidate.id !in occupied) return candidate
        var sourceIndex = 1
        var id = "${candidate.id}:source:$sourceIndex"
        while (id in occupied) {
            sourceIndex += 1
            id = "${candidate.id}:source:$sourceIndex"
        }
        return candidate.copy(id = id, sourceId = candidate.sourceId ?: candidate.id)
    }

    /**
     * Compatible legacy facts can enrich one known-owner legacy record. Explicit typed records
     * stay authoritative; unknown or conflicting ownership remains a separate candidate.
     */
    internal fun withLegacyContributions(contributions: List<TypedSymbol>): TypedCatalogue =
        contributions.fold(this) { current, incoming ->
            val target = LegacyContribution.run { current.knownOwnerTarget(incoming) }
            current.applyLegacyContribution(incoming, target)
        }

    private fun applyLegacyContribution(incoming: TypedSymbol, target: TypedSymbol?): TypedCatalogue {
        if (symbols.any { it.sameCanonicalFacts(incoming) || it.replacesCanonicalFacts(incoming) }) return this
        if (target == null) return copy(symbols = symbols + disambiguate(incoming))
        return applyKnownOwnerContribution(incoming, target)
    }

    /** Apply to the unique compatible known-owner target selected before identity checks. */
    private fun applyKnownOwnerContribution(incoming: TypedSymbol, target: TypedSymbol): TypedCatalogue = when {
        target.legacyDerived && incoming.legacyDerived -> {
            val updated = LegacyContribution.run { target.mergeLegacyContribution(incoming) }
            copy(symbols = symbols.map { if (it.id == target.id) updated else it })
        }
        target.legacyDerived && !incoming.legacyDerived -> {
            val replacement = disambiguate(incoming, replacingId = target.id)
            copy(symbols = symbols.map { if (it.id == target.id) replacement else it })
        }
        !target.legacyDerived && incoming.legacyDerived -> this
        else -> copy(symbols = symbols + disambiguate(incoming))
    }

    /** Overrides replace an explicit target or the sole candidate in their namespace; ambiguity is retained. */
    fun withOverrides(overrides: List<TypedSymbol>): TypedCatalogue = overrides.fold(this) { current, override ->
        val matching = if (override.replacesId != null) {
            current.symbols.filter { it.id == override.replacesId && it.kind == override.kind }
        } else {
            current.symbols.filter { it.kind == override.kind && it.name == override.name }
        }
        val target = matching.singleOrNull()
        val ownerConflicts = target != null && override.ownerPlugin != null &&
            override.ownerPlugin != target.ownerPlugin
        val idConflicts = current.symbols.any { it.id == override.id && it !== target }
        if (idConflicts) {
            current.copy(unresolvedOverrides = current.unresolvedOverrides + override)
        } else if (target != null && !ownerConflicts) {
            val replacement = override.copy(
                ownerPlugin = target.ownerPlugin,
                ownerEvidence = target.ownerEvidence,
                replacementLineage = (
                    target.replacementLineage + listOfNotNull(target.lineageEntry())
                    ).distinct(),
                contributors = target.contributors + target.provenance + override.provenance + override.contributors,
            )
            current.copy(symbols = current.symbols.map { if (it === target) replacement else it })
        } else if (matching.isEmpty() && override.replacesId == null) {
            current.insertUnmatchedOverride(override)
        } else {
            current.copy(unresolvedOverrides = current.unresolvedOverrides + override)
        }
    }

    private fun insertUnmatchedOverride(override: TypedSymbol): TypedCatalogue {
        val declaredOwner = override.provenance.legacyPlugin
        val inserted = if (override.ownerPlugin == null && declaredOwner != null) {
            override.copy(ownerPlugin = declaredOwner, ownerEvidence = OwnerEvidence.USER_DECLARED)
        } else {
            override
        }
        return copy(symbols = symbols + inserted)
    }

    /**
     * Legacy JSON overrides patch only fields actually supplied. The historical plugin string is
     * recorded as input evidence; it is not treated as the owner of an existing target.
     */
    internal fun withLegacyOverrides(overrides: Map<String, JenkinsStepMetadata>): TypedCatalogue =
        overrides.entries.fold(this) { current, (name, patch) ->
            val matches = current.symbols.filter { it.kind == SymbolKind.EXECUTABLE_STEP && it.name == name }
            val contribution = MetadataProvenance(SourceKind.USER_OVERRIDE, legacyPlugin = patch.plugin)
            val target = matches.singleOrNull()
            when {
                target != null -> current.patchLegacyTarget(target, patch, contribution)

                matches.isEmpty() -> {
                    val symbol = patch.toTypedSymbol().copy(provenance = contribution)
                    if (current.symbols.any { it.id == symbol.id }) {
                        if (current.unresolvedOverrides.any { it.id == symbol.id }) {
                            current
                        } else {
                            current.copy(unresolvedOverrides = current.unresolvedOverrides + symbol)
                        }
                    } else {
                        current.copy(
                            symbols = current.symbols + symbol.copy(
                                ownerPlugin = patch.plugin,
                                ownerEvidence = OwnerEvidence.USER_DECLARED,
                            ),
                        )
                    }
                }
                else -> {
                    val unresolved = patch.toTypedSymbol().copy(provenance = contribution)
                    if (current.unresolvedOverrides.any { it.id == unresolved.id }) {
                        current
                    } else {
                        current.copy(unresolvedOverrides = current.unresolvedOverrides + unresolved)
                    }
                }
            }
        }

    private fun patchLegacyTarget(
        target: TypedSymbol,
        patch: JenkinsStepMetadata,
        contribution: MetadataProvenance,
    ): TypedCatalogue {
        val parameters = target.parameters.associateBy { it.name }.toMutableMap()
        patch.parameters.forEach { (key, input) ->
            val previous = parameters[key]
            parameters[key] = input.patchParameter(key, previous)
        }
        val updated = target.copy(
            parameters = parameters.values.toList(),
            documentation = if (patch.documentationSpecified) patch.documentation else target.documentation,
            contributors = if (contribution in target.contributors) {
                target.contributors
            } else {
                target.contributors + contribution
            },
        )
        val replacement = if (updated == target) {
            updated
        } else {
            updated.copy(
                replacementLineage = (target.replacementLineage + listOfNotNull(target.lineageEntry())).distinct(),
            )
        }
        return copy(symbols = symbols.map { if (it.id == target.id) replacement else it })
    }

    private fun StepParameter.patchParameter(key: String, previous: TypedParameter?): TypedParameter = TypedParameter(
        name = key,
        type = type,
        requiredness = if (requiredSpecified) {
            when (required) {
                true -> Requiredness.REQUIRED
                false -> Requiredness.OPTIONAL
                null -> Requiredness.UNKNOWN
            }
        } else {
            previous?.requiredness ?: Requiredness.UNKNOWN
        },
        defaultValue = if (defaultSpecified) default else previous?.defaultValue,
        validValues = previous?.validValues,
        documentation = if (documentationSpecified) documentation else previous?.documentation,
    )

    /** Storage is private; decoded values must pass through the public snapshot constructor. */
    @Serializable
    @SerialName("com.github.albertocavalcante.gvy.jenkins.metadata.TypedCatalogue")
    private data class State(
        val schemaMajor: Int = 1,
        val symbols: List<TypedSymbol> = emptyList(),
        val unresolvedOverrides: List<TypedSymbol> = emptyList(),
    )

    object Serializer : KSerializer<TypedCatalogue> {
        private val delegate = State.serializer()
        override val descriptor = delegate.descriptor

        override fun serialize(encoder: Encoder, value: TypedCatalogue) = delegate.serialize(encoder, value.state)

        override fun deserialize(decoder: Decoder): TypedCatalogue {
            val decoded = delegate.deserialize(decoder)
            return TypedCatalogue(
                schemaMajor = decoded.schemaMajor,
                symbols = decoded.symbols,
                unresolvedOverrides = decoded.unresolvedOverrides,
            )
        }
    }
}

/** Compare stable ingestion identity and facts, ignoring only merge-local ID rewrites. */
internal fun TypedSymbol.sameCanonicalFacts(other: TypedSymbol): Boolean {
    val namespace = sourceNamespace ?: return false
    val declaration = sourceId ?: id
    if (namespace != other.sourceNamespace || declaration != (other.sourceId ?: other.id) || kind != other.kind) {
        return false
    }
    return copy(id = declaration, sourceId = null) == other.copy(id = declaration, sourceId = null)
}

/** Match the exact source declaration and facts replaced, never an unrelated same-name or ID record. */
private fun TypedSymbol.replacesCanonicalFacts(other: TypedSymbol): Boolean =
    other.lineageEntry()?.let { it in replacementLineage } == true

private fun TypedSymbol.lineageEntry(): ReplacementLineage? = sourceNamespace?.let { namespace ->
    ReplacementLineage(namespace, sourceId ?: id, canonicalFactsDigest())
}

private fun TypedSymbol.canonicalFactsDigest(): String {
    val declaration = sourceId ?: id
    val normalized = copy(id = declaration, sourceId = null)
    val json = Json.encodeToJsonElement(TypedSymbol.serializer(), normalized).sortedJson()
    val bytes = MessageDigest.getInstance("SHA-256").digest(json.toString().encodeToByteArray())
    return bytes.joinToString("") { "%02x".format(it.toUByte().toInt()) }
}

private fun JsonElement.sortedJson(): JsonElement = when (this) {
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { it.key to it.value.sortedJson() })
    is JsonArray -> JsonArray(map { it.sortedJson() })
    else -> this
}

/** Explicit catalogue authority or exact repeated adaptation of an accompanying legacy view. */
private fun TypedSymbol.representsLegacyFallback(fallback: TypedSymbol): Boolean {
    if (!fallback.legacyDerived || fallback.sourceNamespace != null) return false
    val declaration = sourceId ?: id
    val sameDeclaration = declaration == (fallback.sourceId ?: fallback.id) &&
        kind == fallback.kind && name == fallback.name
    val sameScope = sameDeclaration && contexts == fallback.contexts && ownerPlugin == fallback.ownerPlugin &&
        sourceNamespace == null
    if (!sameScope) return false
    if (!legacyDerived) return true
    return copy(id = declaration, sourceId = null) == fallback.copy(id = declaration, sourceId = null)
}

private object LegacyContribution {
    fun TypedCatalogue.knownOwnerTarget(incoming: TypedSymbol): TypedSymbol? {
        val owner = incoming.ownerPlugin
        return symbols.filter {
            owner != null && incoming.ownerEvidence != OwnerEvidence.UNKNOWN &&
                it.kind == incoming.kind && it.name == incoming.name && it.contexts == incoming.contexts &&
                it.ownerPlugin == owner && it.ownerEvidence != OwnerEvidence.UNKNOWN
        }.singleOrNull()
    }

    fun TypedSymbol.mergeLegacyContribution(incoming: TypedSymbol): TypedSymbol {
        val params = parameters.associateBy { it.name }.toMutableMap()
        incoming.parameters.forEach { params[it.name] = it }
        return copy(
            parameters = params.values.toList(),
            positional = incoming.positional.ifEmpty { positional },
            returnType = incoming.returnType ?: returnType,
            documentation = incoming.documentation ?: documentation,
            contributors = contributors + incoming.provenance,
        )
    }
}
