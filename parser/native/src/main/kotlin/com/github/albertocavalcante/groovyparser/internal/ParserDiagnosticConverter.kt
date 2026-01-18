package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.nativeapi.ParserDiagnostic
import com.github.albertocavalcante.nativeapi.ParserRange
import com.github.albertocavalcante.nativeapi.ParserSeverity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.messages.Message
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException

internal object ParserDiagnosticConverter {
    private val logger = KotlinLogging.logger {}

    fun convert(errorCollector: ErrorCollector, allowedLocators: Set<String>): List<ParserDiagnostic> {
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
                    diagnostics += convertSyntaxError(syntaxException)
                }

                else -> {
                    if (allowedLocators.isNotEmpty()) {
                        return@forEach
                    }
                    diagnostics += convertGenericMessage(message)
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

    private fun convertSyntaxError(syntaxException: SyntaxException): ParserDiagnostic {
        val line = maxOf(0, syntaxException.line - 1)
        val start = maxOf(0, syntaxException.startColumn - 1)
        val end = maxOf(start, syntaxException.endColumn - 1)
        logger.debug {
            "Syntax diagnostic from '${syntaxException.sourceLocator}': " +
                "${syntaxException.originalMessage} at line ${syntaxException.line}, " +
                "columns ${syntaxException.startColumn}-${syntaxException.endColumn}"
        }
        return ParserDiagnostic(
            range = ParserRange.singleLine(line, start, end),
            severity = ParserSeverity.ERROR,
            message = syntaxException.message ?: "Syntax error",
            source = "groovy-parser",
            code = "syntax-error",
        )
    }

    private fun convertGenericMessage(
        message: Message,
        severity: ParserSeverity = ParserSeverity.ERROR,
    ): ParserDiagnostic = ParserDiagnostic(
        range = ParserRange.point(0, 0),
        severity = severity,
        message = message.toString(),
        source = "groovy-parser",
        code = "compilation-error",
    )

    private fun matchesLocator(sourceLocator: String?, allowedLocators: Set<String>): Boolean {
        if (sourceLocator == null) return false
        return allowedLocators.any { candidate -> candidate == sourceLocator }
    }
}
