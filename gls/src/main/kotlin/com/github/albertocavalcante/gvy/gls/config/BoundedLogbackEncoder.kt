// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.encoder.EncoderBase
import com.github.albertocavalcante.gvy.common.logging.SafeLogging
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.IdentityHashMap

/** Bounds encoder work and output; producers and Logback can allocate before this boundary. */
class BoundedLogbackEncoder : EncoderBase<ILoggingEvent>() {
    override fun headerBytes(): ByteArray? = null
    override fun footerBytes(): ByteArray? = null

    override fun encode(event: ILoggingEvent): ByteArray {
        val record = RecordBuffer()
        record.append(CLOCK.format(Instant.ofEpochMilli(event.timeStamp)))
        record.append(" [")
        record.append(prefixField(event.threadName ?: "", logger = false))
        record.append("] ")
        record.append(level(event.level))
        record.append(" ")
        record.append(prefixField(event.loggerName ?: "", logger = true).takeLast(LOGGER_CHARS))
        record.append(" -- ")
        val message = formatMessage(event.message ?: "", event.argumentArray)
        val rendered = SafeLogging.localText(message.text, MESSAGE_BYTES)
        record.append(rendered.text)
        if (message.truncated || rendered.truncated) record.markTruncated()
        if (record.full) {
            record.markTruncated()
        } else {
            event.throwableProxy?.let {
                renderExceptionSummary(record, it)
                renderThrowable(record, it, ThrowableState(), "", 0)
            }
        }
        return record.finish()
    }

    // Preserve bounded failure identity before potentially voluminous stack frames.
    private fun renderExceptionSummary(record: RecordBuffer, root: IThrowableProxy) {
        val visited = IdentityHashMap<IThrowableProxy, Boolean>()
        var current: IThrowableProxy? = root
        var count = 0
        record.append("\n\tExceptions: ")
        while (current != null && count < MAX_DEPTH && !record.full) {
            if (visited.put(current, true) != null) {
                record.markTruncated()
                return
            }
            if (count > 0) record.append(" -> ")
            record.append(safe(current.className ?: "", METADATA_BYTES))
            current = current.cause
            count++
        }
        if (current != null) record.markTruncated()
    }

    private fun renderThrowable(
        record: RecordBuffer,
        proxy: IThrowableProxy,
        state: ThrowableState,
        label: String,
        depth: Int,
    ) {
        if (record.full || depth >= MAX_DEPTH || state.nodes >= MAX_NODES || state.visited.containsKey(proxy)) {
            record.markTruncated()
            return
        }
        state.visited[proxy] = true
        state.nodes++
        record.append("\n\t")
        record.append(label)
        record.append(safe(proxy.className ?: "", FIELD_BYTES))
        val message = proxy.message
        if (!message.isNullOrEmpty()) {
            record.append(": ")
            record.append(safe(message, FIELD_BYTES))
        }
        val frames = proxy.stackTraceElementProxyArray ?: emptyArray()
        for (frame in frames) {
            if (state.frames >= MAX_FRAMES || record.full) {
                record.markTruncated()
                break
            }
            state.frames++
            val element = frame.stackTraceElement
            record.append("\n\tat ")
            record.append(safe(element.className, FIELD_BYTES))
            record.append(".")
            record.append(safe(element.methodName, FIELD_BYTES))
            record.append("(")
            if (element.isNativeMethod) {
                record.append("Native Method")
            } else {
                record.append(safe(element.fileName ?: "Unknown Source", FIELD_BYTES))
                if (element.lineNumber >= 0) {
                    record.append(":")
                    record.append(element.lineNumber.toString())
                }
            }
            record.append(")")
        }
        if (record.full) {
            record.markTruncated()
            return
        }
        val suppressed = proxy.suppressed ?: emptyArray()
        for (index in 0 until minOf(suppressed.size, MAX_SUPPRESSED)) {
            if (record.full) {
                record.markTruncated()
                break
            }
            renderThrowable(record, suppressed[index], state, "Suppressed: ", depth + 1)
        }
        if (suppressed.size > MAX_SUPPRESSED) record.markTruncated()
        if (record.full) {
            record.markTruncated()
            return
        }
        proxy.cause?.let { renderThrowable(record, it, state, "Caused by: ", depth + 1) }
    }

    private fun prefixField(value: String, logger: Boolean): String = buildString {
        for (character in safe(value, METADATA_BYTES)) {
            when {
                character == '[' -> append("\\u005b")
                character == ']' -> append("\\u005d")
                logger && (character.isWhitespace() || Character.isSpaceChar(character)) ->
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                else -> append(character)
            }
        }
    }

    private fun safe(value: String, bytes: Int): String = SafeLogging.localText(value, bytes).text

    private fun level(value: Level?): String = when (value) {
        Level.ERROR -> "ERROR"
        Level.WARN -> "WARN "
        Level.INFO -> "INFO "
        Level.DEBUG -> "DEBUG"
        Level.TRACE -> "TRACE"
        else -> "UNKWN"
    }

    private class ThrowableState {
        val visited = IdentityHashMap<IThrowableProxy, Boolean>()
        var nodes = 0
        var frames = 0
    }

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
        const val METADATA_BYTES = 256
        const val FIELD_BYTES = 1_024
        const val MESSAGE_BYTES = 8_192
        const val LOGGER_CHARS = 36
        const val MAX_DEPTH = 8
        const val MAX_NODES = 32
        const val MAX_FRAMES = 128
        const val MAX_SUPPRESSED = 8
    }
}

private data class BoundedMessage(val text: String, val truncated: Boolean)

/** SLF4J-style substitution over a fixed raw UTF-16 prefix; never calls arbitrary toString. */
private fun formatMessage(template: String, arguments: Array<out Any?>?): BoundedMessage {
    val buffer = StringBuilder()
    var truncated = false
    fun append(value: String) {
        val remaining = RAW_SCAN_LIMIT - buffer.length
        if (remaining <= 0) {
            truncated = true
            return
        }
        val count = minOf(value.length, remaining)
        buffer.append(value, 0, count)
        if (count < value.length) truncated = true
    }
    var index = 0
    var argument = 0
    fun appendArgument() {
        if (arguments != null && argument < arguments.size && argument < ARGUMENT_LIMIT) {
            append(safeArgument(arguments[argument++]))
        } else {
            append("{}")
            if (arguments != null && argument < arguments.size) truncated = true
        }
    }
    while (index < template.length && buffer.length < RAW_SCAN_LIMIT) {
        if (template[index] == '\\' && index + 3 < template.length &&
            template[index + 1] == '\\' && template[index + 2] == '{' && template[index + 3] == '}'
        ) {
            append("\\")
            appendArgument()
            index += 4
        } else if (template[index] == '\\' && index + 2 < template.length &&
            template[index + 1] == '{' && template[index + 2] == '}'
        ) {
            append("{}")
            index += 3
        } else if (template[index] == '{' && index + 1 < template.length && template[index + 1] == '}') {
            appendArgument()
            index += 2
        } else {
            buffer.append(template[index])
            index++
        }
    }
    if (index < template.length) truncated = true
    var result = buffer.toString()
    // A non-length cutoff can end inside a URL or secret. Drop the open final
    // token before handing the retained prefix to the existing redactor.
    if (truncated && result.length < RAW_SCAN_LIMIT) {
        var end = result.length
        while (end > 0 && !result[end - 1].isWhitespace()) end--
        result = result.substring(0, end)
    }
    return BoundedMessage(result, truncated)
}

private fun safeArgument(value: Any?): String = when {
    value == null -> "null"
    value.javaClass == String::class.java -> value as String
    value.javaClass == java.lang.Boolean::class.java -> (value as Boolean).toString()
    value.javaClass == java.lang.Byte::class.java -> (value as Byte).toString()
    value.javaClass == java.lang.Short::class.java -> (value as Short).toString()
    value.javaClass == java.lang.Integer::class.java -> (value as Int).toString()
    value.javaClass == java.lang.Long::class.java -> (value as Long).toString()
    value.javaClass == java.lang.Float::class.java -> (value as Float).toString()
    value.javaClass == java.lang.Double::class.java -> (value as Double).toString()
    value.javaClass == java.lang.Character::class.java -> (value as Char).toString()
    else -> "<object>"
}

/** UTF-8 writer reserves room for the marker and final newline in every record. */
private class RecordBuffer {
    private val output = ByteArrayOutputStream(512)
    private var truncated = false
    val full: Boolean get() = output.size() >= RECORD_LIMIT - MARKER_BYTES.size - 1

    fun markTruncated() {
        truncated = true
    }

    fun append(text: String) {
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val value = if (codePoint in 0xD800..0xDFFF) '?'.code else codePoint
            val width = when {
                value <= 0x7f -> 1
                value <= 0x7ff -> 2
                value <= 0xffff -> 3
                else -> 4
            }
            if (output.size() + width > RECORD_LIMIT - MARKER_BYTES.size - 1) {
                truncated = true
                return
            }
            when (width) {
                1 -> output.write(value)
                2 -> {
                    output.write(0xc0 or (value shr 6))
                    output.write(0x80 or (value and 0x3f))
                }
                3 -> {
                    output.write(0xe0 or (value shr 12))
                    output.write(0x80 or ((value shr 6) and 0x3f))
                    output.write(0x80 or (value and 0x3f))
                }
                else -> {
                    output.write(0xf0 or (value shr 18))
                    output.write(0x80 or ((value shr 12) and 0x3f))
                    output.write(0x80 or ((value shr 6) and 0x3f))
                    output.write(0x80 or (value and 0x3f))
                }
            }
            index += Character.charCount(codePoint)
        }
    }

    fun finish(): ByteArray {
        if (truncated) output.write(MARKER_BYTES)
        output.write('\n'.code)
        return output.toByteArray()
    }
}

private const val RAW_SCAN_LIMIT = 16_385
private const val ARGUMENT_LIMIT = 32
private const val RECORD_LIMIT = 65_536
private val MARKER_BYTES = "[truncated]".toByteArray(Charsets.UTF_8)
