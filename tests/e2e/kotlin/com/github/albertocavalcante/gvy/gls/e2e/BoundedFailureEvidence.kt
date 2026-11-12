package com.github.albertocavalcante.gvy.gls.e2e

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** Bounded retained failure text. Throwable getters can themselves allocate before returning. */
internal data class FailureEvidence(val text: String, val truncated: Boolean, val unavailable: Boolean)

/** Captures cause and suppressed origins without retaining throwable graphs or invoking Throwable.toString(). */
internal object BoundedFailureEvidence {
    const val MAX_UTF8_BYTES = 16_384
    private const val MAX_TEXT_CHARS = 8_192
    private const val MAX_NODES = 32
    private const val MAX_DEPTH = 8
    private const val MAX_FRAMES = 32
    private const val MAX_SUPPRESSED = 8
    private const val MARKER_RESERVE = 32

    fun capture(failure: Throwable): FailureEvidence = captureAll(listOf(failure))

    fun captureAll(failures: List<Throwable>): FailureEvidence {
        val writer = Writer()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var nodes = 0

        fun visit(failure: Throwable, label: String, depth: Int) {
            if (depth > MAX_DEPTH || nodes >= MAX_NODES || !seen.add(failure)) {
                writer.truncated = true
                return
            }
            nodes++
            writer.append(label)
            writer.append(failure.javaClass.name)
            val message = writer.read { failure.message }
            if (message != null) {
                writer.append(": ")
                writer.append(message)
            }
            writer.append("\n")
            val frames = writer.read { failure.stackTrace }
            frames?.take(MAX_FRAMES)?.forEach { frame ->
                writer.append("  at ")
                writer.append(frame.className)
                writer.append(".")
                writer.append(frame.methodName)
                writer.append("(")
                writer.append(frame.fileName ?: "Unknown Source")
                if (frame.lineNumber >= 0) {
                    writer.append(":")
                    writer.append(frame.lineNumber.toString())
                }
                writer.append(")\n")
            }
            if (frames != null && frames.size > MAX_FRAMES) writer.truncated = true
            val suppressed = writer.read { failure.suppressed }
            suppressed?.take(MAX_SUPPRESSED)?.forEach { visit(it, "Suppressed: ", depth + 1) }
            if (suppressed != null && suppressed.size > MAX_SUPPRESSED) writer.truncated = true
            writer.read { failure.cause }?.let { visit(it, "Caused by: ", depth + 1) }
        }

        failures.take(MAX_NODES).forEach { visit(it, "", 0) }
        if (failures.size > MAX_NODES) writer.truncated = true
        return writer.finish()
    }

    private class Writer {
        private val text = StringBuilder()
        private var bytes = 0
        var truncated = false
        private var unavailable = false

        fun append(value: String) {
            var index = 0
            while (index < value.length) {
                val codePoint = Character.codePointAt(value, index)
                val chars = Character.charCount(codePoint)
                val byteCount = when {
                    codePoint <= 0x7f -> 1
                    codePoint <= 0x7ff -> 2
                    codePoint <= 0xffff -> 3
                    else -> 4
                }
                if (text.length + chars > MAX_TEXT_CHARS - MARKER_RESERVE ||
                    bytes + byteCount > MAX_UTF8_BYTES - MARKER_RESERVE
                ) {
                    truncated = true
                    return
                }
                text.appendCodePoint(codePoint)
                bytes += byteCount
                index += chars
            }
        }

        fun <T> read(block: () -> T): T? = try {
            block()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            unavailable = true
            null
        }

        fun finish(): FailureEvidence {
            if (truncated) text.append("[truncated]")
            if (unavailable) text.append("[unavailable]")
            return FailureEvidence(text.toString(), truncated, unavailable)
        }
    }
}
