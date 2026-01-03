package com.github.albertocavalcante.groovyjenkins.metadata.declarative

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

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

    private fun loadSchema(): Schema = try {
        val resource = requireNotNull(
            DeclarativePipelineSchema::class.java.classLoader.getResource(
                "schemas/declarative-pipeline-schema.json",
            ),
        ) { "Declarative pipeline schema resource not found" }

        val text = resource.readText()
        json.decodeFromString<Schema>(text)
    } catch (cause: Exception) {
        logger.error("Failed to parse declarative pipeline schema", cause)
        throw IllegalStateException("Invalid declarative pipeline schema", cause)
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
