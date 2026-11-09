package com.github.albertocavalcante.gvy.gls.services

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * Reads the existing status discriminator without asking Gson to instantiate an interface.
 * Serialization delegates to the concrete model, preserving its existing wire fields.
 * Unknown types retain generic details; malformed details are omitted so the enclosing failure
 * status still reaches clients. No input names are used to load or instantiate arbitrary classes.
 */
class ErrorDetailsAdapter :
    JsonSerializer<ErrorDetails>,
    JsonDeserializer<ErrorDetails?> {
    override fun serialize(src: ErrorDetails, typeOfSrc: Type, context: JsonSerializationContext): JsonElement =
        context.serialize(src, src.javaClass)

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): ErrorDetails? {
        if (!json.isJsonObject) return null
        return try {
            decode(ErrorFields(json.asJsonObject))
        } catch (_: JsonParseException) {
            // A bad optional detail must not discard the health/message/errorCode notification.
            null
        }
    }

    private fun decode(fields: ErrorFields): ErrorDetails {
        val type = fields.requiredString("type")
        val suggestions = fields.strings("suggestions")
        // GenericError can deliberately share a named variant's discriminator (see #1198).
        if (fields.has("errorCode")) {
            if (fields.requiredString("errorCode") != type) throw JsonParseException("Conflicting error codes")
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

/** Strict shape readers avoid Gson's unsafe construction of Kotlin objects with null required fields. */
private class ErrorFields(private val value: JsonObject) {
    fun has(name: String): Boolean = value.has(name)

    fun requiredString(name: String): String = string(name) ?: throw JsonParseException("Missing $name")

    fun requiredInt(name: String): Int = int(name) ?: throw JsonParseException("Missing $name")

    fun string(name: String): String? = optional(name)?.let { stringValue(it) }

    fun int(name: String): Int? = optional(name)?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber) throw JsonParseException("Invalid $name")
        it.asString.toIntOrNull() ?: throw JsonParseException("Invalid integer $name")
    }

    fun strings(name: String): List<String> = optional(name)?.let {
        if (!it.isJsonArray) throw JsonParseException("Invalid $name")
        it.asJsonArray.map(::stringValue)
    }.orEmpty()

    fun details(): Map<String, String> = optional("details")?.let {
        if (!it.isJsonObject) throw JsonParseException("Invalid details")
        it.asJsonObject.entrySet().associate { (name, field) -> name to stringValue(field) }
    }.orEmpty()

    private fun optional(name: String): JsonElement? = value.get(name)?.takeUnless { it.isJsonNull }

    private fun stringValue(field: JsonElement): String {
        if (!field.isJsonPrimitive || !field.asJsonPrimitive.isString) throw JsonParseException("Invalid string")
        return field.asString
    }
}
