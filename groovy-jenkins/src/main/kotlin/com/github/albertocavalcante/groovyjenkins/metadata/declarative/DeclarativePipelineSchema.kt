package com.github.albertocavalcante.groovyjenkins.metadata.declarative

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException

object DeclarativePipelineSchema {
    private val logger = LoggerFactory.getLogger(DeclarativePipelineSchema::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val schema: Schema = loadSchema()
    private val blockIndex = (schema.sections + schema.directives).associateBy { it.name }

    enum class CompletionCategory {
        @SerialName("step")
        STEP,

        @SerialName("agent_type")
        AGENT_TYPE,

        @SerialName("declarative_option")
        DECLARATIVE_OPTION,

        @SerialName("post_condition")
        POST_CONDITION,
    }

    val schemaVersion: String get() = schema.schemaVersion
    val sourcePluginVersion: String? get() = schema.sourcePlugin?.version
    val jenkinsBaseline: String? get() = schema.sourcePlugin?.jenkinsBaseline

    fun getCompletionCategories(blockName: String?): Set<CompletionCategory> =
        blockIndex[blockName]?.completionCategories?.toSet().orEmpty()

    fun containsBlock(blockName: String?): Boolean = blockName != null && blockIndex.containsKey(blockName)

    fun getInnerInstructions(blockName: String?): Set<String> =
        blockIndex[blockName]?.innerInstructions?.toSet().orEmpty()

    private fun loadSchema(): Schema {
        val resource = requireNotNull(
            DeclarativePipelineSchema::class.java.classLoader.getResource(
                "schemas/declarative-pipeline-schema.json",
            ),
        ) { "Declarative pipeline schema resource not found" }

        return runCatching {
            val text = resource.readText()
            json.decodeFromString<Schema>(text)
        }.getOrElse { throwable ->
            if (throwable is Error) throw throwable

            val wrapped = when (throwable) {
                is IOException,
                is SerializationException,
                is IllegalArgumentException,
                -> IllegalStateException("Invalid declarative pipeline schema", throwable)
                else -> IllegalStateException("Failed to load declarative pipeline schema", throwable)
            }

            logger.error("Failed to parse declarative pipeline schema", wrapped)
            throw wrapped
        }
    }

    @Serializable
    private data class Schema(
        val schemaVersion: String = "0.0.0",
        val sourcePlugin: PluginInfo? = null,
        val generatedAt: String? = null,
        val sections: List<Block> = emptyList(),
        val directives: List<Block> = emptyList(),
    )

    @Serializable
    private data class PluginInfo(val artifactId: String, val version: String, val jenkinsBaseline: String? = null)

    @Serializable
    private data class Block(
        val name: String,
        val allowedIn: List<String> = emptyList(),
        val innerInstructions: List<String> = emptyList(),
        val completionCategories: List<CompletionCategory> = emptyList(),
    )
}
