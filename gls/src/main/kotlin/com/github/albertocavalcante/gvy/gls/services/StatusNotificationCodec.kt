package com.github.albertocavalcante.gvy.gls.services

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The status wire format, independent of the LSP4J transport. */
@OptIn(ExperimentalSerializationApi::class)
object StatusNotificationCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(status: StatusNotification): String = json.encodeToString(
        WireStatus(
            status.health,
            status.quiescent,
            status.message,
            status.filesIndexed,
            status.filesTotal,
            status.errorCode,
            status.errorDetails?.let(::encodeDetails),
        ),
    )

    fun decode(value: String): StatusNotification {
        val wire = json.decodeFromString<WireStatus>(value)
        val details = wire.errorDetails?.let {
            try {
                decodeDetails(ErrorFields(it as? JsonObject ?: return@let null))
            } catch (
                _: SerializationException,
            ) {
                null
            }
        }
        return StatusNotification(
            wire.health,
            wire.quiescent,
            wire.message,
            wire.filesIndexed,
            wire.filesTotal,
            wire.errorCode,
            details,
        )
    }

    private fun encodeDetails(value: ErrorDetails): JsonElement = when (value) {
        is GradleJdkIncompatibleError -> json.encodeToJsonElement(GradleJdkIncompatibleError.serializer(), value)
        is NoBuildToolError -> json.encodeToJsonElement(NoBuildToolError.serializer(), value)
        is DependencyResolutionError -> json.encodeToJsonElement(DependencyResolutionError.serializer(), value)
        is JavaNotFoundError -> json.encodeToJsonElement(JavaNotFoundError.serializer(), value)
        is ToolchainProvisioningError -> json.encodeToJsonElement(ToolchainProvisioningError.serializer(), value)
        is GroovyJdkIncompatibleError -> json.encodeToJsonElement(GroovyJdkIncompatibleError.serializer(), value)
        is ProjectJdkIncompatibleError -> json.encodeToJsonElement(ProjectJdkIncompatibleError.serializer(), value)
        is ProjectJdkNewerWarning -> json.encodeToJsonElement(ProjectJdkNewerWarning.serializer(), value)
        is GenericError -> json.encodeToJsonElement(GenericError.serializer(), value)
    }

    private fun decodeDetails(fields: ErrorFields): ErrorDetails {
        val type = fields.requiredString("type")
        val suggestions = fields.strings("suggestions")
        // GenericError can deliberately share a named variant's discriminator (see #1198).
        if (fields.has("errorCode")) {
            if (fields.requiredString("errorCode") != type) throw SerializationException("Conflicting error codes")
            return GenericError(type, fields.details(), suggestions)
        }
        return when (type) {
            "GRADLE_JDK_INCOMPATIBLE" -> GradleJdkIncompatibleError(
                fields.requiredString("gradleVersion"),
                fields.requiredInt("jdkVersion"),
                fields.requiredString("minGradleVersion"),
                fields.string("maxJdkVersion"),
                suggestions,
            )
            "NO_BUILD_TOOL" -> NoBuildToolError(fields.strings("searchedPaths"), suggestions)
            "DEPENDENCY_RESOLUTION_FAILED" -> DependencyResolutionError(
                fields.requiredString("buildTool"),
                fields.string("cause"),
                suggestions,
            )
            "JAVA_NOT_FOUND" -> JavaNotFoundError(
                fields.string("configuredPath"),
                fields.strings("searchedLocations"),
                suggestions,
            )
            "TOOLCHAIN_PROVISIONING_FAILED" -> ToolchainProvisioningError(
                fields.int("requiredVersion"),
                fields.string("vendor"),
                fields.string("platform"),
            )
            "GROOVY_JDK_INCOMPATIBLE" -> GroovyJdkIncompatibleError(
                fields.string("groovyVersion"),
                fields.requiredInt("jdkVersion"),
                fields.requiredInt("classFileMajorVersion"),
                fields.string("minGroovyVersion"),
                fields.string("maxJdkVersion"),
                suggestions,
            )
            "PROJECT_JDK_INCOMPATIBLE" -> ProjectJdkIncompatibleError(
                fields.requiredInt("runningJdkVersion"),
                fields.requiredInt("requiredJdkVersion"),
                fields.requiredString("configurationSource"),
                suggestions,
            )
            "PROJECT_JDK_NEWER_WARNING" -> ProjectJdkNewerWarning(
                fields.requiredInt("runningJdkVersion"),
                fields.requiredInt("targetJdkVersion"),
                fields.requiredString("configurationSource"),
                suggestions,
            )
            else -> GenericError(type, fields.details(), suggestions)
        }
    }
}

@Serializable
private data class WireStatus(
    val health: Health = Health.Ok,
    val quiescent: Boolean = true,
    val message: String? = null,
    val filesIndexed: Int? = null,
    val filesTotal: Int? = null,
    val errorCode: String? = null,
    val errorDetails: JsonElement? = null,
)

/** Strict shape readers avoid accepting invalid optional error details. */
private class ErrorFields(private val value: JsonObject) {
    fun has(name: String): Boolean = name in value
    fun requiredString(name: String): String = string(name) ?: throw SerializationException("Missing $name")
    fun requiredInt(name: String): Int = int(name) ?: throw SerializationException("Missing $name")
    fun string(name: String): String? = optional(name)?.let(::stringValue)
    fun int(name: String): Int? = optional(name)?.let {
        val primitive = it as? JsonPrimitive ?: throw SerializationException("Invalid $name")
        if (primitive.isString) throw SerializationException("Invalid $name")
        primitive.content.toIntOrNull() ?: throw SerializationException("Invalid integer $name")
    }
    fun strings(name: String): List<String> = optional(name)?.let {
        (it as? JsonArray ?: throw SerializationException("Invalid $name")).map(::stringValue)
    }.orEmpty()
    fun details(): Map<String, String> = optional("details")?.let {
        (it as? JsonObject ?: throw SerializationException("Invalid details")).mapValues { (_, field) ->
            stringValue(field)
        }
    }.orEmpty()
    private fun optional(name: String): JsonElement? = value[name]?.takeUnless { it is JsonNull }
    private fun stringValue(field: JsonElement): String {
        val primitive = field as? JsonPrimitive ?: throw SerializationException("Invalid string")
        if (!primitive.isString) throw SerializationException("Invalid string")
        return primitive.content
    }
}
