// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.metadata

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject

/** Canonical facts snapshot inputs at construction, copy and deserialization boundaries. */
// Preserve the canonical wire fields and named constructor arguments.
@Serializable(with = TypedSymbol.Serializer::class)
class TypedSymbol
// TODO(#2085): Review copy arity by 2026-11-19 or the next affected change, whichever comes first.
//   See: https://github.com/albertocavalcante/gvy/issues/2085
@Suppress("LongParameterList")
constructor(
    id: String,
    sourceId: String? = null,
    name: String,
    kind: SymbolKind,
    ownerPlugin: String? = null,
    ownerEvidence: OwnerEvidence = OwnerEvidence.UNKNOWN,
    legacyDerived: Boolean = false,
    contributors: List<MetadataProvenance> = emptyList(),
    aliases: List<String> = emptyList(),
    parameters: List<TypedParameter> = emptyList(),
    positional: List<String> = emptyList(),
    namedBinding: Knowledge = Knowledge.UNKNOWN,
    returnType: String? = null,
    valueDomain: List<String>? = null,
    block: Knowledge = Knowledge.UNKNOWN,
    contexts: List<String>? = null,
    provenance: MetadataProvenance = MetadataProvenance(SourceKind.UNKNOWN),
    availability: Availability = Availability.UNKNOWN,
    documentation: String? = null,
    replacesId: String? = null,
    gdslContext: JsonObject? = null,
    sourceNamespace: String? = null,
    replacementLineage: List<ReplacementLineage> = emptyList(),
) {
    private val state = State(
        id = id,
        sourceId = sourceId,
        name = name,
        kind = kind,
        ownerPlugin = ownerPlugin,
        ownerEvidence = ownerEvidence,
        legacyDerived = legacyDerived,
        contributors = contributors.immutableSnapshot(),
        aliases = aliases.immutableSnapshot(),
        parameters = parameters.immutableSnapshot(),
        positional = positional.immutableSnapshot(),
        namedBinding = namedBinding,
        returnType = returnType,
        valueDomain = valueDomain?.immutableSnapshot(),
        block = block,
        contexts = contexts?.immutableSnapshot(),
        provenance = provenance,
        availability = availability,
        documentation = documentation,
        replacesId = replacesId,
        gdslContext = gdslContext?.immutableSnapshot(),
        sourceNamespace = sourceNamespace,
        replacementLineage = replacementLineage.immutableSnapshot(),
    )

    init {
        require(state.name.isNotBlank()) { "Typed symbol names must be non-blank" }
        require(state.aliases.none { it.isBlank() }) { "Typed aliases must be non-blank" }
    }

    val id: String get() = state.id

    /** Original document-scoped ID when a cross-source collision required a merge-local ID. */
    val sourceId: String? get() = state.sourceId
    val name: String get() = state.name
    val kind: SymbolKind get() = state.kind
    val ownerPlugin: String? get() = state.ownerPlugin
    val ownerEvidence: OwnerEvidence get() = state.ownerEvidence

    /** True only for a record adapted from the legacy schema; not serialized as an external fact. */
    val legacyDerived: Boolean get() = state.legacyDerived
    val contributors: List<MetadataProvenance> get() = state.contributors
    val aliases: List<String> get() = state.aliases
    val parameters: List<TypedParameter> get() = state.parameters
    val positional: List<String> get() = state.positional
    val namedBinding: Knowledge get() = state.namedBinding
    val returnType: String? get() = state.returnType
    val valueDomain: List<String>? get() = state.valueDomain
    val block: Knowledge get() = state.block
    val contexts: List<String>? get() = state.contexts
    val provenance: MetadataProvenance get() = state.provenance
    val availability: Availability get() = state.availability
    val documentation: String? get() = state.documentation

    /** Explicit override target; never inferred from an unknown owner. */
    val replacesId: String? get() = state.replacesId

    /** Exact bounded context facts from the GDSL interpreter, detached from all caller collections. */
    val gdslContext: JsonObject? get() = state.gdslContext

    /** Stable ingestion namespace, separate from ownership and presentation IDs. */
    val sourceNamespace: String? get() = state.sourceNamespace

    /** Resolved source declarations replaced during catalogue composition. */
    val replacementLineage: List<ReplacementLineage> get() = state.replacementLineage

    // Preserve named copy compatibility for all canonical fields.
    // TODO(#2085): Review copy arity by 2026-11-19 or the next affected change, whichever comes first.
    //   See: https://github.com/albertocavalcante/gvy/issues/2085
    @Suppress("LongParameterList")
    fun copy(
        id: String = this.id,
        sourceId: String? = this.sourceId,
        name: String = this.name,
        kind: SymbolKind = this.kind,
        ownerPlugin: String? = this.ownerPlugin,
        ownerEvidence: OwnerEvidence = this.ownerEvidence,
        legacyDerived: Boolean = this.legacyDerived,
        contributors: List<MetadataProvenance> = this.contributors,
        aliases: List<String> = this.aliases,
        parameters: List<TypedParameter> = this.parameters,
        positional: List<String> = this.positional,
        namedBinding: Knowledge = this.namedBinding,
        returnType: String? = this.returnType,
        valueDomain: List<String>? = this.valueDomain,
        block: Knowledge = this.block,
        contexts: List<String>? = this.contexts,
        provenance: MetadataProvenance = this.provenance,
        availability: Availability = this.availability,
        documentation: String? = this.documentation,
        replacesId: String? = this.replacesId,
        gdslContext: JsonObject? = this.gdslContext,
        sourceNamespace: String? = this.sourceNamespace,
        replacementLineage: List<ReplacementLineage> = this.replacementLineage,
    ): TypedSymbol = TypedSymbol(
        id = id,
        sourceId = sourceId,
        name = name,
        kind = kind,
        ownerPlugin = ownerPlugin,
        ownerEvidence = ownerEvidence,
        legacyDerived = legacyDerived,
        contributors = contributors,
        aliases = aliases,
        parameters = parameters,
        positional = positional,
        namedBinding = namedBinding,
        returnType = returnType,
        valueDomain = valueDomain,
        block = block,
        contexts = contexts,
        provenance = provenance,
        availability = availability,
        documentation = documentation,
        replacesId = replacesId,
        gdslContext = gdslContext,
        sourceNamespace = sourceNamespace,
        replacementLineage = replacementLineage,
    )

    override fun equals(other: Any?): Boolean = other is TypedSymbol && state == other.state

    override fun hashCode(): Int = state.hashCode()

    override fun toString(): String = "TypedSymbol" + state.toString().removePrefix("State")

    /** Storage is private; decoded values must pass through the public snapshot constructor. */
    @Serializable
    @SerialName("com.github.albertocavalcante.gvy.jenkins.metadata.TypedSymbol")
    private data class State(
        val id: String,
        val sourceId: String? = null,
        val name: String,
        val kind: SymbolKind,
        val ownerPlugin: String? = null,
        val ownerEvidence: OwnerEvidence = OwnerEvidence.UNKNOWN,
        @Transient val legacyDerived: Boolean = false,
        val contributors: List<MetadataProvenance> = emptyList(),
        val aliases: List<String> = emptyList(),
        val parameters: List<TypedParameter> = emptyList(),
        val positional: List<String> = emptyList(),
        val namedBinding: Knowledge = Knowledge.UNKNOWN,
        val returnType: String? = null,
        val valueDomain: List<String>? = null,
        val block: Knowledge = Knowledge.UNKNOWN,
        val contexts: List<String>? = null,
        val provenance: MetadataProvenance = MetadataProvenance(SourceKind.UNKNOWN),
        val availability: Availability = Availability.UNKNOWN,
        val documentation: String? = null,
        val replacesId: String? = null,
        val gdslContext: JsonObject? = null,
        val sourceNamespace: String? = null,
        val replacementLineage: List<ReplacementLineage> = emptyList(),
    )

    object Serializer : KSerializer<TypedSymbol> {
        private val delegate = State.serializer()
        override val descriptor = delegate.descriptor

        override fun serialize(encoder: Encoder, value: TypedSymbol) = delegate.serialize(encoder, value.state)

        override fun deserialize(decoder: Decoder): TypedSymbol {
            val decoded = delegate.deserialize(decoder)
            return TypedSymbol(
                id = decoded.id,
                sourceId = decoded.sourceId,
                name = decoded.name,
                kind = decoded.kind,
                ownerPlugin = decoded.ownerPlugin,
                ownerEvidence = decoded.ownerEvidence,
                legacyDerived = decoded.legacyDerived,
                contributors = decoded.contributors,
                aliases = decoded.aliases,
                parameters = decoded.parameters,
                positional = decoded.positional,
                namedBinding = decoded.namedBinding,
                returnType = decoded.returnType,
                valueDomain = decoded.valueDomain,
                block = decoded.block,
                contexts = decoded.contexts,
                provenance = decoded.provenance,
                availability = decoded.availability,
                documentation = decoded.documentation,
                replacesId = decoded.replacesId,
                gdslContext = decoded.gdslContext,
                sourceNamespace = decoded.sourceNamespace,
                replacementLineage = decoded.replacementLineage,
            )
        }
    }
}

/** One resolved source declaration and its facts, independent of merge-local IDs. */
@Serializable
data class ReplacementLineage(val sourceNamespace: String, val sourceId: String, val factsDigest: String)
