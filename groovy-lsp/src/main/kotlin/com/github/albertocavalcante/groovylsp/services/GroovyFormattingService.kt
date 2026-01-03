package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovyformatter.OpenRewriteFormatter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.services.LanguageClient
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

fun interface Formatter {
    fun format(text: String): String
}

internal enum class FormatterStatus {
    SUCCESS,
    NO_OP,
    ERROR,
    NOT_FOUND,
}

internal data class FormatterTelemetryEvent(
    val uri: String,
    val status: FormatterStatus,
    val durationMs: Long,
    val ignoredOptions: Boolean,
    val errorMessage: String? = null,
)

private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val DEFAULT_TAB_SIZE = 4
private val KNOWN_FORMATTING_OPTION_KEYS = setOf("tabSize", "insertSpaces")

/**
 * Service responsible for formatting Groovy documents.
 *
 * This service handles the actual formatting logic using a provided [Formatter],
 * manages formatting options (currently supporting basic options and OpenRewrite defaults),
 * and reports telemetry events regarding the formatting process (success, failure, no-op).
 */
class GroovyFormattingService(
    private val formatter: Formatter,
    private val documentProvider: DocumentProvider,
    private val client: () -> LanguageClient?,
) {
    private val logger = LoggerFactory.getLogger(GroovyFormattingService::class.java)
    private val optionsWarningLogged = AtomicBoolean(false)

    suspend fun format(params: DocumentFormattingParams): List<TextEdit> {
        val uriString = params.textDocument.uri
        logger.debug("Formatting requested for {}", uriString)
        val startNanos = System.nanoTime()
        val uri = URI.create(uriString)

        val options = params.options
        val ignoredOptions = shouldMarkOptionsIgnored(options)
        maybeLogIgnoredOptions(ignoredOptions)

        val currentContent = documentProvider.get(uri)
        if (currentContent == null) {
            publishTelemetry(uriString, FormatterStatus.NOT_FOUND, durationMs = 0, ignoredOptions = ignoredOptions)
            return emptyList()
        }

        currentCoroutineContext().ensureActive()

        val formattedResult = runCatching { formatter.format(currentContent) }
        val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLISECOND

        val formattedContent = formattedResult.getOrElse { throwable ->
            val failureMessage = throwable.message ?: throwable.javaClass.simpleName
            logger.warn("Formatter failed for {}: {}", uriString, failureMessage)
            if (logger.isDebugEnabled) {
                logger.debug("Formatter failure details for {}", uriString, throwable)
            }
            publishTelemetry(
                uriString,
                FormatterStatus.ERROR,
                durationMs = durationMs,
                ignoredOptions = ignoredOptions,
                errorMessage = failureMessage,
            )
            return emptyList()
        }

        currentCoroutineContext().ensureActive()

        val (status, edits) = if (formattedContent == currentContent) {
            FormatterStatus.NO_OP to emptyList()
        } else {
            FormatterStatus.SUCCESS to listOf(TextEdit(currentContent.toFullDocumentRange(), formattedContent))
        }

        publishTelemetry(
            uriString,
            status,
            durationMs = durationMs,
            ignoredOptions = ignoredOptions,
        )
        return edits
    }

    private fun maybeLogIgnoredOptions(ignoredOptions: Boolean) {
        if (ignoredOptions && optionsWarningLogged.compareAndSet(false, true)) {
            logger.info("DocumentFormattingOptions are not yet supported; using OpenRewrite defaults.")
        }
    }

    private fun publishTelemetry(
        uri: String,
        status: FormatterStatus,
        durationMs: Long,
        ignoredOptions: Boolean,
        errorMessage: String? = null,
    ) {
        client()?.telemetryEvent(
            FormatterTelemetryEvent(
                uri = uri,
                status = status,
                durationMs = durationMs,
                ignoredOptions = ignoredOptions,
                errorMessage = errorMessage,
            ),
        )
    }
}

private fun shouldMarkOptionsIgnored(options: FormattingOptions?): Boolean {
    if (options == null) {
        return false
    }
    if (options.tabSize != DEFAULT_TAB_SIZE || !options.isInsertSpaces) {
        return true
    }
    if (options.isTrimTrailingWhitespace || options.isInsertFinalNewline || options.isTrimFinalNewlines) {
        return true
    }
    return options.keys.any { it !in KNOWN_FORMATTING_OPTION_KEYS }
}

private fun String.toFullDocumentRange(): Range {
    var line = 0
    var lastLineStart = 0
    this.indices.forEach { index ->
        if (this[index] == '\n') {
            line++
            lastLineStart = index + 1
        }
    }

    var column = length - lastLineStart
    if (column > 0 && this[length - 1] == '\r') {
        column--
    }
    column = max(column, 0)

    return Range(
        Position(0, 0),
        Position(line, column),
    )
}

internal class OpenRewriteFormatterAdapter : Formatter {
    private val delegate = OpenRewriteFormatter()
    override fun format(text: String): String = delegate.format(text)
}
