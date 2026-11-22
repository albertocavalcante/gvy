// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.fmt.FormatOutcome
import com.github.albertocavalcante.gvy.fmt.OpenRewriteFormatter
import com.github.albertocavalcante.gvy.source.Utf16LineIndex
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.services.LanguageClient
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

fun interface Formatter {
    fun format(text: String): String

    /**
     * Formats [text] and says whether the result was refused as unsafe. Formatters that check
     * their own output override this; the default treats every result as accepted.
     */
    fun formatChecked(text: String): FormatOutcome = format(text).let { formatted ->
        if (formatted == text) FormatOutcome.Unchanged(text) else FormatOutcome.Formatted(formatted)
    }
}

internal enum class FormatterStatus {
    SUCCESS,
    NO_OP,

    /** The formatter produced a result that would change the program, so no edit was sent. */
    REFUSED,
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
    private val logger = KotlinLogging.logger {}
    private val optionsWarningLogged = AtomicBoolean(false)

    suspend fun format(params: DocumentFormattingParams): List<TextEdit> {
        val uriString = params.textDocument.uri
        logger.debug { "Formatting requested for $uriString" }
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

        val formattedResult = runCatching { formatter.formatChecked(currentContent) }
        val durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLISECOND

        val outcome = formattedResult.getOrElse { throwable ->
            if (throwable is CancellationException || throwable !is Exception) throw throwable
            val failureMessage = throwable.message ?: throwable.javaClass.simpleName
            logger.warn { "Formatter failed for $uriString: $failureMessage" }
            if (logger.isDebugEnabled()) {
                logger.debug(throwable) { "Formatter failure details for $uriString" }
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

        val (status, edits) = when {
            outcome is FormatOutcome.Refused -> {
                logger.warn {
                    "Refusing formatting result for $uriString that would change the program: ${outcome.reason}"
                }
                outcome.cause?.let { cause -> logger.debug(cause) { "Token stream check failure for $uriString" } }
                FormatterStatus.REFUSED to emptyList()
            }

            outcome.text == currentContent -> FormatterStatus.NO_OP to emptyList()

            else -> FormatterStatus.SUCCESS to listOf(TextEdit(currentContent.toFullDocumentRange(), outcome.text))
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
            logger.info { "DocumentFormattingOptions are not yet supported; using OpenRewrite defaults." }
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

/** The range from the start to the end of this text; LF, CRLF and a bare CR each end a line, as in LSP. */
internal fun String.toFullDocumentRange(): Range {
    val index = Utf16LineIndex(this)
    val lastLine = index.lineAt(length)
    return Range(Position(0, 0), Position(lastLine, index.lineLength(lastLine) ?: 0))
}

internal class OpenRewriteFormatterAdapter : Formatter {
    private val delegate = OpenRewriteFormatter()
    override fun format(text: String): String = delegate.format(text)
    override fun formatChecked(text: String): FormatOutcome = delegate.formatChecked(text)
}
