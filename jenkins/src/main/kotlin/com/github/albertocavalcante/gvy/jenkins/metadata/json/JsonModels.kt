// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.metadata.json

import com.github.albertocavalcante.gvy.jenkins.metadata.AgentTypeMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.DeclarativeOptionMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.GlobalVariableMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MetadataProvenance
import com.github.albertocavalcante.gvy.jenkins.metadata.OwnerEvidence
import com.github.albertocavalcante.gvy.jenkins.metadata.PostConditionMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.SourceKind
import com.github.albertocavalcante.gvy.jenkins.metadata.StepParameter
import com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind
import com.github.albertocavalcante.gvy.jenkins.metadata.TypedCatalogue
import kotlinx.serialization.Serializable

/**
 * JSON structure for metadata file.
 *
 * Uses kotlinx.serialization for type-safe, reflection-free deserialization.
 */
@Serializable
data class MetadataJson(
    val jenkinsVersion: String? = null,
    val steps: Map<String, StepJson>,
    val globalVariables: Map<String, GlobalVariableJson>,
    val postConditions: Map<String, PostConditionJson> = emptyMap(),
    val declarativeOptions: Map<String, DeclarativeOptionJson> = emptyMap(),
    val agentTypes: Map<String, AgentTypeJson> = emptyMap(),
    /** Entries of declarative directives such as `triggers`, `parameters` and `tools`, keyed by directive. */
    val directiveEntries: Map<String, Map<String, DeclarativeOptionJson>> = emptyMap(),
    val typedCatalogue: TypedCatalogue? = null,
)

@Serializable
data class StepJson(
    val plugin: String,
    val positionalParams: List<String> = emptyList(),
    val parameters: Map<String, ParameterJson>,
    val documentation: String? = null,
    val kind: SymbolKind = SymbolKind.EXECUTABLE_STEP,
    val returnType: String? = null,
)

@Serializable
data class ParameterJson(
    val type: String,
    val required: Boolean? = null,
    val default: String? = null,
    val documentation: String? = null,
)

@Serializable
data class GlobalVariableJson(val type: String, val documentation: String? = null)

@Serializable
data class PostConditionJson(val description: String, val executionOrder: Int = 0)

@Serializable
data class DeclarativeOptionJson(
    val plugin: String,
    val parameters: Map<String, ParameterJson> = emptyMap(),
    val documentation: String? = null,
)

@Serializable
data class AgentTypeJson(val parameters: Map<String, ParameterJson> = emptyMap(), val documentation: String? = null)

/**
 * Extension function to convert JSON model to domain model.
 */
fun MetadataJson.toBundledMetadata(): BundledJenkinsMetadata = BundledJenkinsMetadata(
    steps = steps.filterValues { it.kind == SymbolKind.EXECUTABLE_STEP }
        .mapValues { (stepName, step) -> step.toDomain(stepName) },
    nestedDescribables = steps.filterValues { it.kind == SymbolKind.NESTED_DESCRIBABLE }
        .mapValues { (stepName, step) -> step.toDomain(stepName) },
    globalVariables = globalVariables.mapValues { (varName, globalVar) ->
        GlobalVariableMetadata(
            name = varName,
            type = globalVar.type,
            documentation = globalVar.documentation,
        )
    },
    postConditions = postConditions.mapValues { (condName, cond) ->
        PostConditionMetadata(
            name = condName,
            description = cond.description,
            executionOrder = cond.executionOrder,
        )
    },
    declarativeOptions = declarativeOptions.mapValues { (optName, opt) -> opt.toDomain(optName) },
    agentTypes = agentTypes.mapValues { (agentName, agent) -> agent.toDomain(agentName) },
    directiveEntries = directiveEntries.mapValues { (_, entries) ->
        entries.mapValues { (entryName, entry) -> entry.toDomain(entryName) }
    },
    jenkinsVersion = jenkinsVersion,
    typedCatalogue = typedCatalogue,
)

private fun StepJson.toDomain(stepName: String): JenkinsStepMetadata = JenkinsStepMetadata(
    name = stepName,
    plugin = plugin,
    positionalParams = positionalParams,
    parameters = parameters.toStepParameters(),
    kind = kind,
    returnType = returnType,
    provenance = MetadataProvenance(SourceKind.CURATED, plugin = plugin),
    ownerPlugin = plugin,
    ownerEvidence = OwnerEvidence.CURATED,
    documentation = documentation,
)

private fun DeclarativeOptionJson.toDomain(optionName: String): DeclarativeOptionMetadata = DeclarativeOptionMetadata(
    name = optionName,
    plugin = plugin,
    parameters = parameters.toStepParameters(),
    documentation = documentation,
)

private fun AgentTypeJson.toDomain(agentName: String): AgentTypeMetadata = AgentTypeMetadata(
    name = agentName,
    parameters = parameters.toStepParameters(),
    documentation = documentation,
)

private fun Map<String, ParameterJson>.toStepParameters(): Map<String, StepParameter> =
    mapValues { (paramName, param) ->
        StepParameter(
            name = paramName,
            type = param.type,
            required = param.required,
            default = param.default,
            documentation = param.documentation,
        )
    }
