// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.metadata

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Canonical facts snapshot inputs at construction, copy and deserialization boundaries. */
@Serializable(with = TypedParameter.Serializer::class)
class TypedParameter(
    name: String,
    type: String?,
    requiredness: Requiredness = Requiredness.UNKNOWN,
    defaultValue: String? = null,
    validValues: List<String>? = null,
    documentation: String? = null,
) {
    private val state = State(
        name = name,
        type = type,
        requiredness = requiredness,
        defaultValue = defaultValue,
        validValues = validValues?.immutableSnapshot(),
        documentation = documentation,
    )

    init {
        require(state.name.isNotBlank()) { "Typed parameter names must be non-blank" }
    }

    val name: String get() = state.name
    val type: String? get() = state.type
    val requiredness: Requiredness get() = state.requiredness
    val defaultValue: String? get() = state.defaultValue
    val validValues: List<String>? get() = state.validValues
    val documentation: String? get() = state.documentation

    // TODO(#2085): Review copy arity by 2026-11-19 or the next affected change, whichever comes first.
    //   See: https://github.com/albertocavalcante/gvy/issues/2085
    @Suppress("LongParameterList")
    fun copy(
        name: String = this.name,
        type: String? = this.type,
        requiredness: Requiredness = this.requiredness,
        defaultValue: String? = this.defaultValue,
        validValues: List<String>? = this.validValues,
        documentation: String? = this.documentation,
    ): TypedParameter = TypedParameter(
        name = name,
        type = type,
        requiredness = requiredness,
        defaultValue = defaultValue,
        validValues = validValues,
        documentation = documentation,
    )

    override fun equals(other: Any?): Boolean = other is TypedParameter && state == other.state

    override fun hashCode(): Int = state.hashCode()

    override fun toString(): String = "TypedParameter" + state.toString().removePrefix("State")

    /** Storage is private; decoded values must pass through the public snapshot constructor. */
    @Serializable
    @SerialName("com.github.albertocavalcante.gvy.jenkins.metadata.TypedParameter")
    private data class State(
        val name: String,
        val type: String?,
        val requiredness: Requiredness = Requiredness.UNKNOWN,
        val defaultValue: String? = null,
        val validValues: List<String>? = null,
        val documentation: String? = null,
    )

    object Serializer : KSerializer<TypedParameter> {
        private val delegate = State.serializer()
        override val descriptor = delegate.descriptor

        override fun serialize(encoder: Encoder, value: TypedParameter) = delegate.serialize(encoder, value.state)

        override fun deserialize(decoder: Decoder): TypedParameter {
            val decoded = delegate.deserialize(decoder)
            return TypedParameter(
                name = decoded.name,
                type = decoded.type,
                requiredness = decoded.requiredness,
                defaultValue = decoded.defaultValue,
                validValues = decoded.validValues,
                documentation = decoded.documentation,
            )
        }
    }
}
