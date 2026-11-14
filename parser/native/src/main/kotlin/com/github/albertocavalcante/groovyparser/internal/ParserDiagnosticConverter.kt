// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.nativeapi.ParserDiagnostic
import com.github.albertocavalcante.nativeapi.ParserRange
import com.github.albertocavalcante.nativeapi.ParserSeverity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.Janitor
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.ExceptionMessage
import org.codehaus.groovy.control.messages.LocatedMessage
import org.codehaus.groovy.control.messages.Message
import org.codehaus.groovy.control.messages.SimpleMessage
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.control.messages.WarningMessage
import org.codehaus.groovy.syntax.SyntaxException
import org.codehaus.groovy.syntax.Token
import java.io.PrintWriter
import java.io.StringWriter

internal data class SyntaxSourceContext(
    val source: SourceSnapshot,
    val tokens: GroovyTokenIndex,
    val locators: Set<String>,
)

internal object ParserDiagnosticConverter {
    private val logger = KotlinLogging.logger {}

    /** Diagnostic code of the notice for a project-jar transform that analysis deliberately does not run. */
    const val PROJECT_TRANSFORM_NOT_RUN = "project-transform-not-run"

    /** Groovy's message when `@GroovyASTTransformationClass` names a class the transform loader cannot load. */
    private val MISSING_TRANSFORM =
        Regex("Could not find class for Transformation Processor (\\S+) declared by (\\S+)")

    fun convert(
        errorCollector: ErrorCollector,
        allowedLocators: Set<String>,
        context: SyntaxSourceContext? = null,
        isProjectClass: (String) -> Boolean = { false },
    ): List<ParserDiagnostic> {
        val diagnostics = mutableListOf<ParserDiagnostic>()

        errorCollector.errors?.forEach { message ->
            when (message) {
                is SyntaxErrorMessage -> {
                    val syntaxException = message.cause
                    if (allowedLocators.isNotEmpty() &&
                        !matchesLocator(syntaxException.sourceLocator, allowedLocators)
                    ) {
                        return@forEach
                    }
                    val matchingContext = context?.takeIf {
                        matchesLocator(syntaxException.sourceLocator, it.locators)
                    }
                    diagnostics += convertSyntaxError(syntaxException, matchingContext)
                }

                else -> {
                    val notice = projectTransformNotice(message, isProjectClass)
                    when {
                        // Attributable to one of the requested sources, so it is safe to show against this request
                        notice != null && (allowedLocators.isEmpty() || ownerMatches(message, allowedLocators)) ->
                            diagnostics += notice

                        allowedLocators.isNotEmpty() -> return@forEach
                        else -> diagnostics += convertGenericMessage(message)
                    }
                }
            }
        }

        errorCollector.warnings?.forEach { warning ->
            if (allowedLocators.isNotEmpty()) {
                return@forEach
            }
            diagnostics += convertGenericMessage(warning, ParserSeverity.WARNING)
        }

        return diagnostics
    }

    private fun convertSyntaxError(syntaxException: SyntaxException, context: SyntaxSourceContext?): ParserDiagnostic {
        val line = maxOf(0, syntaxException.line - 1)
        val start = maxOf(0, syntaxException.startColumn - 1)
        val end = maxOf(start, syntaxException.endColumn - 1)
        logger.debug {
            "Syntax diagnostic from '${syntaxException.sourceLocator}': " +
                "${syntaxException.originalMessage} at line ${syntaxException.line}, " +
                "columns ${syntaxException.startColumn}-${syntaxException.endColumn}"
        }
        val refined = context?.let {
            importDiagnostic(syntaxException, it.source, it.tokens)
                ?: expressionDiagnostic(syntaxException, it.source, it.tokens)
                ?: delimiterDiagnostic(syntaxException, it.source, it.tokens)
        }
        if (refined != null) return refined
        val source = context?.source
        val range = if (source == null) {
            ParserRange.singleLine(line, start, end)
        } else {
            val startOffset = source.compilerOffset(syntaxException.line, syntaxException.startColumn)
            val endOffset = source.compilerOffset(syntaxException.endLine, syntaxException.endColumn)
            val cap = (startOffset + MAX_SYNTAX_RANGE).coerceAtMost(source.text.length)
            val safeCap = if (cap < source.text.length && cap > 0 && Character.isLowSurrogate(source.text[cap])) {
                cap - 1
            } else {
                cap
            }
            val boundedEnd = endOffset.coerceIn(startOffset, safeCap)
            source.range(startOffset, boundedEnd)
        }
        return ParserDiagnostic(
            range = range,
            severity = ParserSeverity.ERROR,
            message = syntaxException.originalMessage ?: "Syntax error",
            source = "groovy-parser",
            code = "syntax-error",
        )
    }

    private fun convertGenericMessage(
        message: Message,
        severity: ParserSeverity = ParserSeverity.ERROR,
    ): ParserDiagnostic = ParserDiagnostic(
        range = positionOf(message),
        severity = severity,
        message = textOf(message),
        source = "groovy-parser",
        code = "compilation-error",
    )

    /**
     * Not an error in the user's code: the transform lives in a project jar the server deliberately never runs. The
     * unit also stops after SEMANTIC_ANALYSIS because of this message, so the notice says that later checks are
     * skipped; an Information notice alone would make the file look fully checked.
     */
    private fun projectTransformNotice(message: Message, isProjectClass: (String) -> Boolean): ParserDiagnostic? {
        val missing = (message as? SimpleMessage)?.let { MISSING_TRANSFORM.matchEntire(textOf(it)) }
        if (missing == null || !isProjectClass(missing.groupValues[1])) return null
        return ParserDiagnostic(
            range = positionOf(message),
            severity = ParserSeverity.INFORMATION,
            message = "Transform ${missing.groupValues[1]} (declared by ${missing.groupValues[2]}) comes from the " +
                "project classpath and is not run during analysis; later checks for this file were skipped",
            source = "groovy-parser",
            code = PROJECT_TRANSFORM_NOT_RUN,
        )
    }

    /** Whether a `SimpleMessage` was reported on one of [locators]; its owner field has no public accessor. */
    private fun ownerMatches(message: Message, locators: Set<String>): Boolean {
        val owner = runCatching {
            SimpleMessage::class.java.getDeclaredField("owner").apply { isAccessible = true }.get(message)
        }.getOrNull()
        return matchesLocator((owner as? SourceUnit)?.name, locators)
    }

    /** The user-visible text of a compiler message; `Message` subclasses do not override `toString`. */
    internal fun textOf(message: Message): String = when (message) {
        is LocatedMessage -> message.message
        is SimpleMessage -> message.message
        is ExceptionMessage -> message.cause?.let {
            oneLine("${it.javaClass.simpleName}: ${it.message ?: "no message"}")
        }
            .orEmpty()
        is WarningMessage -> message.message
        else -> written(message)
    }.ifBlank { written(message) }

    /** `LocatedMessage` carries the token it was reported on; other messages have no position. */
    private fun positionOf(message: Message): ParserRange {
        val token = (message as? LocatedMessage)?.context
        return if (token == null || token.startLine < 1) {
            ParserRange.point(0, 0)
        } else {
            val column = maxOf(0, token.startColumn - 1)
            ParserRange.singleLine(token.startLine - 1, column, column + ((token as? Token)?.text.orEmpty().length))
        }
    }

    /** Last resort: let the message print itself, which avoids falling back to an identity hash. */
    private fun written(message: Message): String =
        runCatching { StringWriter().also { message.write(PrintWriter(it), Janitor()) }.toString().trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::oneLine)
            ?: message.javaClass.simpleName

    /**
     * First line only, capped. Exception messages can span lines (and `ExceptionMessage.write` prints a whole stack
     * trace), and they may contain absolute paths, so a diagnostic must not carry all of that.
     */
    private fun oneLine(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        .trim().take(MAX_MESSAGE_CHARS)

    private const val MAX_MESSAGE_CHARS = 300
    private const val MAX_SYNTAX_RANGE = 80

    private fun matchesLocator(sourceLocator: String?, allowedLocators: Set<String>): Boolean {
        if (sourceLocator == null) return false
        return allowedLocators.any { candidate -> candidate == sourceLocator }
    }
}
