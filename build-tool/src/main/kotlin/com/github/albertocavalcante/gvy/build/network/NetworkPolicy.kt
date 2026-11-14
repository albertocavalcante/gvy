// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URISyntaxException
import java.util.Collections

private const val MAX_REVISION = 9_007_199_254_740_991L
private const val MAX_ASCII = 127
private const val MAX_HOST_LENGTH = 253
private const val MAX_LABEL_LENGTH = 63
private const val IPV4_PARTS = 4
private const val MAX_IPV4_OCTET = 255
private const val UNSPECIFIED_PORT = -1
private const val HTTP_PORT = 80
private const val HTTPS_PORT = 443
private const val MAX_PORT = 65535

enum class PolicyMode(val value: String) {
    CACHE_ONLY("cache-only"),
    CONNECTED("connected"),
}

enum class PolicyFailure {
    INVALID_CONFIGURATION,
    CACHE_ONLY,
    ORIGIN_NOT_ALLOWED,
    INVALID_URL,
    STALE_POLICY,
}

/** A policy is an immutable permission snapshot, independent of dependency generations. */
class NetworkPolicy private constructor(
    val revision: Long,
    val valid: Boolean,
    val mode: PolicyMode,
    origins: Set<String>,
) {
    val allowedOrigins: Set<String> = Collections.unmodifiableSet(origins.toSet())

    fun authorize(url: String): PolicyFailure? = when {
        !valid -> PolicyFailure.INVALID_CONFIGURATION
        mode == PolicyMode.CACHE_ONLY -> PolicyFailure.CACHE_ONLY
        else -> authorizeOrigin(url)
    }

    private fun authorizeOrigin(url: String): PolicyFailure? {
        val origin = normalizeOrigin(url, originOnly = false) ?: return PolicyFailure.INVALID_URL
        return if (origin in allowedOrigins) null else PolicyFailure.ORIGIN_NOT_ALLOWED
    }

    companion object {
        fun parse(input: JsonElement?, revision: Long): NetworkPolicy {
            require(revision in 0..MAX_REVISION) { "Policy revision is outside the safe integer range" }
            if (input == null || input == JsonNull) {
                return NetworkPolicy(revision, true, PolicyMode.CACHE_ONLY, emptySet())
            }
            val parsed = parseConfiguration(input)
            return NetworkPolicy(
                revision,
                parsed != null,
                parsed?.mode ?: PolicyMode.CACHE_ONLY,
                parsed?.origins ?: emptySet(),
            )
        }
    }
}

private data class ParsedPolicy(val mode: PolicyMode, val origins: Set<String>)

private fun parseConfiguration(input: JsonElement): ParsedPolicy? {
    val obj = input as? JsonObject ?: return null
    if (obj.keys.any { it != "mode" && it != "allowedOrigins" }) return null
    val mode = parseMode(obj["mode"])
    val origins = parseOrigins(obj["allowedOrigins"])
    return if (mode != null && origins != null) ParsedPolicy(mode, origins) else null
}

private fun parseMode(value: JsonElement?): PolicyMode? = when (value) {
    null -> PolicyMode.CACHE_ONLY
    is JsonPrimitive -> if (value.isString) PolicyMode.entries.find { it.value == value.content } else null
    else -> null
}

private fun parseOrigins(value: JsonElement?): Set<String>? {
    if (value == null) return emptySet()
    val values = value as? JsonArray ?: return null
    val origins = values.map { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { normalizeOrigin(it) }
    }
    return if (origins.any { it == null }) null else origins.filterNotNull().toSet()
}

/** Strictly validates the authority before using it as an exact-match origin. */
fun normalizeOrigin(input: String, originOnly: Boolean = true): String? {
    val uri = parseHttpUri(input, originOnly) ?: return null
    return parseAuthority(uri)?.let { authority ->
        val scheme = uri.scheme.lowercase()
        val defaultPort = if (scheme == "https") HTTPS_PORT else HTTP_PORT
        val portSuffix = if (authority.port == UNSPECIFIED_PORT || authority.port == defaultPort) {
            ""
        } else {
            ":${authority.port}"
        }
        "$scheme://${authority.host}$portSuffix"
    }
}

private fun parseHttpUri(input: String, originOnly: Boolean): URI? {
    val uri = try {
        URI(input)
    } catch (_: URISyntaxException) {
        return null
    }
    val allowedScheme = uri.scheme?.lowercase() in setOf("http", "https")
    val allowedCharacters = input.none { it <= ' ' || it == '\u007f' || it == '\\' }
    return uri.takeIf { allowedScheme && allowedCharacters && hasAllowedShape(uri, originOnly) }
}

private fun hasAllowedShape(uri: URI, originOnly: Boolean): Boolean {
    if (uri.rawFragment != null) return false
    val isOrigin = uri.rawPath in listOf("", "/") && uri.rawQuery == null
    return !originOnly || isOrigin
}

private data class Authority(val host: String, val port: Int)

private fun parseAuthority(uri: URI): Authority? {
    val raw = uri.rawAuthority ?: return null
    val host = uri.host?.lowercase() ?: return null
    val port = parsePort(raw.substring(host.length))
    val allowedAuthority = raw.none { it.code > MAX_ASCII || it == '%' || it == '@' }
    val allowedHost = !host.startsWith('[') && isAllowedHostname(host)
    return if (allowedAuthority && allowedHost && port != null) Authority(host, port) else null
}

private fun parsePort(suffix: String): Int? = when {
    suffix.isEmpty() -> UNSPECIFIED_PORT
    !suffix.startsWith(':') || suffix.length == 1 -> null
    suffix.drop(1).any { !it.isDigit() } -> null
    else -> suffix.drop(1).toIntOrNull()?.takeIf { it in 1..MAX_PORT }
}

private fun isAllowedHostname(host: String): Boolean {
    val labels = host.split('.')
    val allowedLength = host.length <= MAX_HOST_LENGTH
    return allowedLength && labels.all(::isAllowedLabel) && hasCanonicalNumericEnd(labels)
}

private fun isAllowedLabel(label: String): Boolean {
    val allowedLength = label.isNotEmpty() && label.length <= MAX_LABEL_LENGTH
    val allowedEdges = !label.startsWith('-') && !label.endsWith('-')
    val allowedCharacters = label.all { it.isLetterOrDigit() || it == '-' }
    return allowedLength && allowedEdges && allowedCharacters
}

private fun hasCanonicalNumericEnd(labels: List<String>): Boolean {
    val last = labels.last()
    val decimalEnd = last.all { it.isDigit() }
    val hexEnd = last.startsWith("0x") && last.drop(2).all { it in '0'..'9' || it in 'a'..'f' }
    if (!decimalEnd && !hexEnd) return true
    return labels.size == IPV4_PARTS && labels.all(::isCanonicalOctet)
}

private fun isCanonicalOctet(label: String): Boolean {
    val decimal = label.all { it.isDigit() }
    val noLeadingZero = label.length == 1 || !label.startsWith('0')
    val inRange = label.toIntOrNull()?.let { it in 0..MAX_IPV4_OCTET } ?: false
    return decimal && noLeadingZero && inRange
}
