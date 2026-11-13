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
        if (record.full || depth >= MAX_DEPTH || !state.enter(proxy)) {
            record.markTruncated()
            return
        }
        record.append("\n\t")
        record.append(label)
        record.append(safe(proxy.className ?: "", FIELD_BYTES))
        proxy.message?.takeIf { it.isNotEmpty() }?.let {
            record.append(": ")
            record.append(safe(it, FIELD_BYTES))
        }
        renderFrames(record, proxy, state)
        if (record.full) return record.markTruncated()
        renderSuppressed(record, proxy, state, depth)
        if (record.full) return record.markTruncated()
        proxy.cause?.let { renderThrowable(record, it, state, "Caused by: ", depth + 1) }
    }

    private fun renderFrames(record: RecordBuffer, proxy: IThrowableProxy, state: ThrowableState) {
        for (frame in proxy.stackTraceElementProxyArray ?: emptyArray()) {
            if (state.frames >= MAX_FRAMES || record.full) {
                record.markTruncated()
                return
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
    }

    private fun renderSuppressed(record: RecordBuffer, proxy: IThrowableProxy, state: ThrowableState, depth: Int) {
        val suppressed = proxy.suppressed ?: emptyArray()
        for (index in 0 until minOf(suppressed.size, MAX_SUPPRESSED)) {
            if (record.full) {
                record.markTruncated()
                return
            }
            renderThrowable(record, suppressed[index], state, "Suppressed: ", depth + 1)
        }
        if (suppressed.size > MAX_SUPPRESSED) record.markTruncated()
    }

    private fun prefixField(value: String, logger: Boolean): String = buildString {
        for (character in safe(value, METADATA_BYTES)) {
            when {
                character == '[' -> append("\\u005b")
                character == ']' -> append("\\u005d")
                logger && (character.isWhitespace() || Character.isSpaceChar(character)) ->
                    append("\\u").append(character.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
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

        fun enter(proxy: IThrowableProxy): Boolean {
            if (nodes >= MAX_NODES || visited.containsKey(proxy)) return false
            visited[proxy] = true
            nodes++
            return true
        }
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
        const val HEX_RADIX = 16
        const val UNICODE_ESCAPE_DIGITS = 4
    }
}

private data class BoundedMessage(val text: String, val truncated: Boolean)

/** SLF4J-style substitution over a fixed raw UTF-16 prefix; never calls arbitrary toString. */
private fun formatMessage(template: String, arguments: Array<out Any?>?): BoundedMessage =
    MessageFormatter(template, arguments).format()

private class MessageFormatter(private val template: String, private val arguments: Array<out Any?>?) {
    private val buffer = StringBuilder()
    private var truncated = false
    private var index = 0
    private var argument = 0

    fun format(): BoundedMessage {
        while (index < template.length && buffer.length < RAW_SCAN_LIMIT) consume()
        if (index < template.length) truncated = true
        var result = buffer.toString()
        if (truncated && result.length < RAW_SCAN_LIMIT) {
            var end = result.length
            while (end > 0 && !result[end - 1].isWhitespace()) end--
            result = result.substring(0, end)
        }
        return BoundedMessage(result, truncated)
    }

    private fun consume() {
        when {
            !hasRemainingArgument() -> buffer.append(template[index++])
            template.startsWith(DOUBLE_ESCAPED_PLACEHOLDER, index) -> {
                append("\\")
                appendArgument()
                index += DOUBLE_ESCAPED_PLACEHOLDER.length
            }
            template.startsWith(ESCAPED_PLACEHOLDER, index) -> {
                append(PLACEHOLDER)
                index += ESCAPED_PLACEHOLDER.length
            }
            template.startsWith(PLACEHOLDER, index) -> {
                appendArgument()
                index += PLACEHOLDER.length
            }
            else -> buffer.append(template[index++])
        }
    }

    private fun hasRemainingArgument(): Boolean = arguments != null && argument < arguments.size

    private fun appendArgument() {
        if (arguments != null && argument < arguments.size && argument < ARGUMENT_LIMIT) {
            append(safeArgument(arguments[argument++]))
        } else {
            append(PLACEHOLDER)
            if (arguments != null && argument < arguments.size) truncated = true
        }
    }

    private fun append(value: String) {
        val remaining = RAW_SCAN_LIMIT - buffer.length
        if (remaining <= 0) {
            truncated = true
            return
        }
        val count = minOf(value.length, remaining)
        buffer.append(value, 0, count)
        if (count < value.length) truncated = true
    }
}

private const val PLACEHOLDER = "{}"
private const val ESCAPED_PLACEHOLDER = "\\{}"
private const val DOUBLE_ESCAPED_PLACEHOLDER = "\\\\{}"

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
    private val output = ByteArrayOutputStream(INITIAL_BUFFER_SIZE)
    private var truncated = false
    val full: Boolean get() = output.size() >= RECORD_LIMIT - MARKER_BYTES.size - 1

    fun markTruncated() {
        truncated = true
    }

    fun append(text: String) {
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val value = if (codePoint in MIN_SURROGATE..MAX_SURROGATE) '?'.code else codePoint
            val width = when {
                value <= MAX_ASCII -> 1
                value <= MAX_TWO_BYTE -> TWO_BYTES
                value <= MAX_THREE_BYTE -> THREE_BYTES
                else -> FOUR_BYTES
            }
            if (output.size() + width > RECORD_LIMIT - MARKER_BYTES.size - 1) {
                truncated = true
                return
            }
            when (width) {
                1 -> output.write(value)
                TWO_BYTES -> {
                    output.write(TWO_BYTE_PREFIX or (value shr SHIFT_ONE))
                    output.write(CONTINUATION_PREFIX or (value and CONTINUATION_MASK))
                }
                THREE_BYTES -> {
                    output.write(THREE_BYTE_PREFIX or (value shr SHIFT_TWO))
                    output.write(CONTINUATION_PREFIX or ((value shr SHIFT_ONE) and CONTINUATION_MASK))
                    output.write(CONTINUATION_PREFIX or (value and CONTINUATION_MASK))
                }
                else -> {
                    output.write(FOUR_BYTE_PREFIX or (value shr SHIFT_THREE))
                    output.write(CONTINUATION_PREFIX or ((value shr SHIFT_TWO) and CONTINUATION_MASK))
                    output.write(CONTINUATION_PREFIX or ((value shr SHIFT_ONE) and CONTINUATION_MASK))
                    output.write(CONTINUATION_PREFIX or (value and CONTINUATION_MASK))
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

private const val INITIAL_BUFFER_SIZE = 512
private const val MIN_SURROGATE = 0xD800
private const val MAX_SURROGATE = 0xDFFF
private const val MAX_ASCII = 0x7f
private const val MAX_TWO_BYTE = 0x7ff
private const val MAX_THREE_BYTE = 0xffff
private const val TWO_BYTES = 2
private const val THREE_BYTES = 3
private const val FOUR_BYTES = 4
private const val TWO_BYTE_PREFIX = 0xc0
private const val THREE_BYTE_PREFIX = 0xe0
private const val FOUR_BYTE_PREFIX = 0xf0
private const val CONTINUATION_PREFIX = 0x80
private const val CONTINUATION_MASK = 0x3f
private const val SHIFT_ONE = 6
private const val SHIFT_TWO = 12
private const val SHIFT_THREE = 18
private const val RAW_SCAN_LIMIT = 16_385
private const val ARGUMENT_LIMIT = 32
private const val RECORD_LIMIT = 65_536
private val MARKER_BYTES = "\t[truncated]".toByteArray(Charsets.UTF_8)
