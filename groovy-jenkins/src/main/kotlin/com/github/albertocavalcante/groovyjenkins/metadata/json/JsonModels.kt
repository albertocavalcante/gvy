package com.github.albertocavalcante.groovyjenkins.metadata.json

import com.github.albertocavalcante.groovyjenkins.metadata.AgentTypeMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.DeclarativeOptionMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.GlobalVariableMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.PostConditionMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.StepParameter
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
)

@Serializable
data class StepJson(
    val plugin: String,
    val positionalParams: List<String> = emptyList(),
    val parameters: Map<String, ParameterJson>,
    val documentation: String? = null,
)

@Serializable
data class ParameterJson(
    val type: String,
    val required: Boolean = false,
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
fun MetadataJson.toBundledMetadata(): BundledJenkinsMetadata {
    val stepsMap = steps.map { (stepName, step) ->
        stepName to JenkinsStepMetadata(
            name = stepName,
            plugin = step.plugin,
            positionalParams = step.positionalParams,
            parameters = step.parameters.map { (paramName, param) ->
                paramName to StepParameter(
                    name = paramName,
                    type = param.type,
                    required = param.required,
                    default = param.default,
                    documentation = param.documentation,
                )
            }.toMap(),
            documentation = step.documentation,
        )
    }.toMap()

    val globalVarsMap = globalVariables.map { (varName, globalVar) ->
        varName to GlobalVariableMetadata(
            name = varName,
            type = globalVar.type,
            documentation = globalVar.documentation,
        )
    }.toMap()

    val postConditionsMap = postConditions.map { (condName, cond) ->
        condName to PostConditionMetadata(
            name = condName,
            description = cond.description,
            executionOrder = cond.executionOrder,
        )
    }.toMap()

    val declarativeOptionsMap = declarativeOptions.map { (optName, opt) ->
        optName to DeclarativeOptionMetadata(
            name = optName,
            plugin = opt.plugin,
            parameters = opt.parameters.map { (paramName, param) ->
                paramName to StepParameter(
                    name = paramName,
                    type = param.type,
                    required = param.required,
                    default = param.default,
                    documentation = param.documentation,
                )
            }.toMap(),
            documentation = opt.documentation,
        )
    }.toMap()

    val agentTypesMap = agentTypes.map { (agentName, agent) ->
        agentName to AgentTypeMetadata(
            name = agentName,
            parameters = agent.parameters.map { (paramName, param) ->
                paramName to StepParameter(
                    name = paramName,
                    type = param.type,
                    required = param.required,
                    default = param.default,
                    documentation = param.documentation,
                )
            }.toMap(),
            documentation = agent.documentation,
        )
    }.toMap()

    return BundledJenkinsMetadata(
        steps = stepsMap,
        globalVariables = globalVarsMap,
        postConditions = postConditionsMap,
        declarativeOptions = declarativeOptionsMap,
        agentTypes = agentTypesMap,
        jenkinsVersion = jenkinsVersion,
    )
}
