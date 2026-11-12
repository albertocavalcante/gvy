package com.github.albertocavalcante.gvy.common.logging

import java.net.URI
import java.nio.file.InvalidPathException
import java.nio.file.Paths

private const val MAX_SCAN_CHARS = 16_384
private const val MAX_RAW_SCAN_CHARS = 65_536
private const val MAX_URL_CHARS = 2_048
private const val MAX_RECORD_BYTES = 65_536
private const val TRUNCATION_MARKER = "[truncated]"
private const val REDACTED = "[REDACTED]"
private const val HEX_RADIX = 16
private const val UNICODE_ESCAPE_WIDTH = 4
private const val UTF8_ASCII_MAX = 0x7f
private const val UTF8_TWO_BYTE_MAX = 0x7ff
private const val UTF8_THREE_BYTE_MAX = 0xffff
private const val UTF8_THREE_BYTES = 3
private const val UTF8_FOUR_BYTES = 4

/** A local diagnostic excerpt; known-pattern redaction cannot identify every secret.
 *
 * [unscannedChars] counts UTF-16 units never examined, and [omittedRenderedBytes] counts bytes dropped after
 * sanitization. The two counts describe different stages and must not be added together.
 */
data class LocalText(
    val text: String,
    val truncated: Boolean,
    val examinedChars: Int,
    val unscannedChars: Int,
    val omittedRenderedBytes: Int,
)

/**
 * Bounded helpers for local logs. These do not make untrusted text safe for support capture.
 * Local rendering scans at most 16,384 UTF-16 units even when the byte budget is larger.
 */
object SafeLogging {
    /**
     * Truncation uses `[truncated]`, or `~` when the budget cannot fit it; a zero-byte budget reports truncation
     * only in [LocalText.truncated]. Both markers count against [maxBytes].
     */
    fun localText(text: String, maxBytes: Int): LocalText {
        require(maxBytes in 0..MAX_RECORD_BYTES)
        val scanned = scanPrefix(text)
        val inputTruncated = scanned.second < text.length
        val sanitized = escapeControls(redact(scanned.first, inputTruncated))
        val rendered = fitUtf8(sanitized, maxBytes, inputTruncated)
        return LocalText(
            rendered.text,
            rendered.truncated,
            scanned.second,
            text.length - scanned.second,
            rendered.omittedBytes,
        )
    }

    /** Reports only an HTTP(S) origin, discarding credentials, paths, queries and fragments. */
    fun repositoryIdentity(url: String): String {
        val uri = parseRepositoryUri(url)
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host
        val port = uri?.port ?: -1
        return if (scheme in setOf("http", "https") && host != null) {
            val portSuffix = if (port >= 0) ":$port" else ""
            "$scheme://$host$portSuffix"
        } else {
            "<invalid-url>"
        }
    }

    /** Returns a lexical workspace-relative path, or a fixed label for anything outside it. */
    fun workspacePath(path: String, workspace: String): String {
        if (path.length > MAX_URL_CHARS || workspace.length > MAX_URL_CHARS) return "<external-path>"
        return try {
            val root = Paths.get(workspace).normalize()
            val candidate = Paths.get(path).normalize()
            if (!root.isAbsolute || !candidate.isAbsolute || !candidate.startsWith(root)) {
                "<external-path>"
            } else {
                val relative = root.relativize(candidate).toString().replace('\\', '/')
                if (relative.isEmpty()) "<workspace>" else "<workspace>/${escapeControls(relative)}"
            }
        } catch (_: InvalidPathException) {
            "<external-path>"
        }
    }

    /** Describes a launch without accepting or inspecting its potentially sensitive arguments. */
    fun launchMetadata(argumentCount: Int): LaunchMetadata = LaunchMetadata(argumentCount)
}

data class LaunchMetadata(val argumentCount: Int) {
    init {
        require(argumentCount >= 0)
    }
}

/** A caller owns this immutable policy for the lifetime of one logging session. */
data class LoggingSession(val rawDetail: RawDetailPolicy = RawDetailPolicy.Disabled)

/**
 * Raw details are explicitly enabled on one session and never form a support event.
 * Capture scans at most 65,536 UTF-16 units; the byte budget includes the truncation marker.
 */
sealed interface RawDetailPolicy {
    fun capture(text: String): RawDetail?

    data object Disabled : RawDetailPolicy {
        override fun capture(text: String): RawDetail? = null
    }

    data class Enabled(val maxBytes: Int) : RawDetailPolicy {
        init {
            require(maxBytes in 0..MAX_RECORD_BYTES)
        }

        override fun capture(text: String): RawDetail {
            val scanned = scanPrefix(text, MAX_RAW_SCAN_CHARS)
            val rendered = fitUtf8(scanned.first, maxBytes, scanned.second < text.length)
            return RawDetail(rendered.text, rendered.truncated, text.length - scanned.second, rendered.omittedBytes)
        }
    }
}

/** Bounded session detail may be truncated and replaces malformed UTF-16 with U+FFFD. */
class RawDetail internal constructor(
    val text: String,
    val truncated: Boolean,
    val unscannedChars: Int,
    val omittedRenderedBytes: Int,
) {
    val potentiallySensitive: Boolean get() = true
    override fun toString(): String = "RawDetail(potentiallySensitive=true, truncated=$truncated)"
}

enum class SupportOperation { BUILD, BSP, DIAGNOSTICS, FORMATTER }
enum class SupportOutcome { SUCCESS, FAILURE, CANCELLED, TIMEOUT }

/** Only closed enums and numeric metadata can enter this support representation. */
data class SupportEvent(
    val operation: SupportOperation,
    val outcome: SupportOutcome,
    val durationMillis: Long,
    val itemCount: Int,
) {
    init {
        require(durationMillis >= 0)
        require(itemCount >= 0)
    }
}

private fun parseRepositoryUri(url: String): URI? = if (url.length > MAX_URL_CHARS) {
    null
} else {
    try {
        URI(url)
    } catch (_: java.net.URISyntaxException) {
        null
    }
}

private fun scanPrefix(text: String, maxChars: Int = MAX_SCAN_CHARS): Pair<String, Int> {
    var end = text.length.coerceAtMost(maxChars)
    if (end > 0 && end < text.length) {
        if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
    }
    return text.substring(0, end) to end
}

private val SECRET_KEYS = listOf("authorization", "password", "passwd", "token", "api_key", "api-key")
private val URL_PREFIXES = listOf("https://", "http://")
private data class Replacement(val text: String, val end: Int)

private fun redact(text: String, inputTruncated: Boolean): String {
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val replacement = urlReplacement(text, index, inputTruncated) ?: secretReplacement(text, index)
        if (replacement == null) {
            out.append(text[index++])
        } else {
            out.append(replacement.text)
            index = replacement.end
        }
    }
    return out.toString()
}

private fun urlReplacement(text: String, index: Int, inputTruncated: Boolean): Replacement? {
    val matches = URL_PREFIXES.any { text.regionMatches(index, it, 0, it.length, ignoreCase = true) }
    if (!matches) return null
    var end = index
    while (end < text.length && !text[end].isWhitespace() && !text[end].isISOControl()) end++
    val safeUrl = if (inputTruncated && end == text.length) {
        "<incomplete-url>"
    } else {
        SafeLogging.repositoryIdentity(text.substring(index, end))
    }
    return Replacement(safeUrl, end)
}

private fun secretReplacement(text: String, index: Int): Replacement? {
    if (index > 0 && text[index - 1].isLetterOrDigit()) return null
    val key = SECRET_KEYS.firstOrNull { text.regionMatches(index, it, 0, it.length, ignoreCase = true) }
        ?: return null
    val keyEnd = index + key.length
    val openingQuote = text.getOrNull(index - 1)
    val quotedKey = openingQuote in listOf('\"', '\'') && text.getOrNull(keyEnd) == openingQuote
    val separator = skipHorizontalSpace(text, keyEnd + if (quotedKey) 1 else 0)
    if (separator >= text.length || text[separator] !in "=:") return null
    val end = secretValueEnd(text, separator + 1, key == "authorization")
    return Replacement(text.substring(index, separator + 1) + REDACTED, end)
}

private fun skipHorizontalSpace(text: String, start: Int): Int {
    var index = start
    while (index < text.length && text[index] in " \t") index++
    return index
}

private fun secretValueEnd(text: String, start: Int, authorization: Boolean): Int {
    var index = skipHorizontalSpace(text, start)
    if (authorization) {
        val schemes = listOf("Bearer", "Basic")
        val scheme = schemes.firstOrNull { candidate ->
            text.regionMatches(index, candidate, 0, candidate.length, ignoreCase = true) &&
                text.getOrNull(index + candidate.length) in listOf(' ', '\t')
        }
        if (scheme != null) index = skipHorizontalSpace(text, index + scheme.length)
    }
    return if (index < text.length && text[index] in "\"'") {
        quotedValueEnd(text, index)
    } else {
        unquotedValueEnd(text, index)
    }
}

private fun quotedValueEnd(text: String, start: Int): Int {
    val quote = text[start]
    var index = start + 1
    while (index < text.length && text[index] != quote && text[index] !in "\n\r") {
        index += if (text[index] == '\\' && index + 1 < text.length) 2 else 1
    }
    return if (index < text.length && text[index] == quote) index + 1 else index
}

private fun unquotedValueEnd(text: String, start: Int): Int {
    var index = start
    while (index < text.length && !text[index].isWhitespace() && text[index] !in ",;&") index++
    return index
}

private fun escapeControls(text: String): String = buildString {
    for (char in text) {
        when (char) {
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.isISOControl() || char == '\u2028' || char == '\u2029') {
                append("\\u").append(char.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_WIDTH, '0'))
            } else {
                append(char)
            }
        }
    }
}

private data class FitResult(val text: String, val truncated: Boolean, val omittedBytes: Int)

private fun fitUtf8(input: String, budget: Int, inputTruncated: Boolean): FitResult {
    // Normalize malformed UTF-16 to U+FFFD, matching standard streaming UTF-8 encoders.
    val bytes = normalizeSurrogates(input).toByteArray(Charsets.UTF_8)
    val text = bytes.toString(Charsets.UTF_8)
    if (!inputTruncated && bytes.size <= budget) return FitResult(text, false, 0)
    val marker = when {
        budget >= TRUNCATION_MARKER.length -> TRUNCATION_MARKER
        budget > 0 -> "~"
        else -> ""
    }
    val available = budget - marker.length
    val prefix = StringBuilder()
    var index = 0
    var used = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val size = when {
            codePoint <= UTF8_ASCII_MAX -> 1
            codePoint <= UTF8_TWO_BYTE_MAX -> 2
            codePoint <= UTF8_THREE_BYTE_MAX -> UTF8_THREE_BYTES
            else -> UTF8_FOUR_BYTES
        }
        if (used + size > available) break
        prefix.appendCodePoint(codePoint)
        used += size
        index += Character.charCount(codePoint)
    }
    return FitResult(prefix.append(marker).toString(), true, bytes.size - used)
}

private fun normalizeSurrogates(input: String): String = buildString(input.length) {
    var index = 0
    while (index < input.length) {
        val char = input[index]
        if (Character.isHighSurrogate(char) && index + 1 < input.length && Character.isLowSurrogate(input[index + 1])) {
            append(char)
            append(input[index + 1])
            index += 2
        } else {
            append(if (Character.isSurrogate(char)) '\uFFFD' else char)
            index++
        }
    }
}
