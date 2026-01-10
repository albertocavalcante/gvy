@file:Suppress("LongParameterList")

package com.github.albertocavalcante.testing.fixtures

import com.github.albertocavalcante.groovyparser.api.model.Diagnostic
import com.github.albertocavalcante.groovyparser.api.model.Position
import com.github.albertocavalcante.groovyparser.api.model.Range
import com.github.albertocavalcante.groovyparser.api.model.Severity

/**
 * Factory for creating test diagnostics.
 *
 * This fixture provides convenient methods for creating diagnostic objects
 * for testing purposes without requiring verbose setup.
 *
 * Example usage:
 * ```kotlin
 * val diagnostic = DiagnosticFixture.error("Syntax error", line = 5, startCol = 10, endCol = 15)
 * val warning = DiagnosticFixture.warning("Deprecated API", line = 10)
 * ```
 */
object DiagnosticFixture {

    /**
     * Create a diagnostic with custom parameters.
     *
     * @param severity The severity level (ERROR, WARNING, INFO, HINT)
     * @param message The diagnostic message
     * @param line The line number (1-based)
     * @param startCol The start column (1-based)
     * @param endCol The end column (1-based), defaults to line end if not specified
     * @param source The source identifier (default: "test")
     * @param code Optional diagnostic code
     * @return A new Diagnostic instance
     */
    fun create(
        severity: Severity,
        message: String,
        line: Int,
        startCol: Int = 1,
        endCol: Int = Int.MAX_VALUE,
        source: String = "test",
        code: String? = null,
    ): Diagnostic = Diagnostic(
        severity = severity,
        message = message,
        range = Range(
            start = Position(line = line, column = startCol),
            end = Position(line = line, column = endCol),
        ),
        source = source,
        code = code,
    )

    /**
     * Create an error diagnostic.
     *
     * @param message The error message
     * @param line The line number (1-based)
     * @param startCol The start column (1-based)
     * @param endCol The end column (1-based)
     * @param source The source identifier (default: "test")
     * @param code Optional diagnostic code
     * @return A new error Diagnostic
     */
    fun error(
        message: String,
        line: Int,
        startCol: Int = 1,
        endCol: Int = Int.MAX_VALUE,
        source: String = "test",
        code: String? = null,
    ): Diagnostic = create(
        severity = Severity.ERROR,
        message = message,
        line = line,
        startCol = startCol,
        endCol = endCol,
        source = source,
        code = code,
    )

    /**
     * Create a warning diagnostic.
     *
     * @param message The warning message
     * @param line The line number (1-based)
     * @param startCol The start column (1-based)
     * @param endCol The end column (1-based)
     * @param source The source identifier (default: "test")
     * @param code Optional diagnostic code
     * @return A new warning Diagnostic
     */
    fun warning(
        message: String,
        line: Int,
        startCol: Int = 1,
        endCol: Int = Int.MAX_VALUE,
        source: String = "test",
        code: String? = null,
    ): Diagnostic = create(
        severity = Severity.WARNING,
        message = message,
        line = line,
        startCol = startCol,
        endCol = endCol,
        source = source,
        code = code,
    )

    /**
     * Create an info diagnostic.
     *
     * @param message The info message
     * @param line The line number (1-based)
     * @param startCol The start column (1-based)
     * @param endCol The end column (1-based)
     * @param source The source identifier (default: "test")
     * @param code Optional diagnostic code
     * @return A new info Diagnostic
     */
    fun info(
        message: String,
        line: Int,
        startCol: Int = 1,
        endCol: Int = Int.MAX_VALUE,
        source: String = "test",
        code: String? = null,
    ): Diagnostic = create(
        severity = Severity.INFO,
        message = message,
        line = line,
        startCol = startCol,
        endCol = endCol,
        source = source,
        code = code,
    )

    /**
     * Create a hint diagnostic.
     *
     * @param message The hint message
     * @param line The line number (1-based)
     * @param startCol The start column (1-based)
     * @param endCol The end column (1-based)
     * @param source The source identifier (default: "test")
     * @param code Optional diagnostic code
     * @return A new hint Diagnostic
     */
    fun hint(
        message: String,
        line: Int,
        startCol: Int = 1,
        endCol: Int = Int.MAX_VALUE,
        source: String = "test",
        code: String? = null,
    ): Diagnostic = create(
        severity = Severity.HINT,
        message = message,
        line = line,
        startCol = startCol,
        endCol = endCol,
        source = source,
        code = code,
    )

    /**
     * Create a syntax error diagnostic.
     *
     * @param message The error message
     * @param line The line number (1-based)
     * @param startCol The start column (1-based)
     * @param endCol The end column (1-based)
     * @return A new syntax error Diagnostic
     */
    fun syntaxError(message: String, line: Int, startCol: Int = 1, endCol: Int = Int.MAX_VALUE): Diagnostic = error(
        message = message,
        line = line,
        startCol = startCol,
        endCol = endCol,
        source = "groovy-parser",
        code = "syntax-error",
    )

    /**
     * Create a CodeNarc violation diagnostic.
     *
     * @param ruleName The CodeNarc rule name
     * @param message The violation message
     * @param line The line number (1-based)
     * @param startCol The start column (1-based)
     * @param endCol The end column (1-based)
     * @param severity The severity level (default: WARNING)
     * @return A new CodeNarc violation Diagnostic
     */
    fun codeNarcViolation(
        ruleName: String,
        message: String,
        line: Int,
        startCol: Int = 1,
        endCol: Int = Int.MAX_VALUE,
        severity: Severity = Severity.WARNING,
    ): Diagnostic = create(
        severity = severity,
        message = message,
        line = line,
        startCol = startCol,
        endCol = endCol,
        source = "codenarc",
        code = ruleName,
    )

    /**
     * Create a multi-line diagnostic.
     *
     * @param severity The severity level
     * @param message The diagnostic message
     * @param startLine The start line number (1-based)
     * @param startCol The start column (1-based)
     * @param endLine The end line number (1-based)
     * @param endCol The end column (1-based)
     * @param source The source identifier (default: "test")
     * @param code Optional diagnostic code
     * @return A new multi-line Diagnostic
     */
    fun multiLine(
        severity: Severity,
        message: String,
        startLine: Int,
        startCol: Int,
        endLine: Int,
        endCol: Int,
        source: String = "test",
        code: String? = null,
    ): Diagnostic = Diagnostic(
        severity = severity,
        message = message,
        range = Range(
            start = Position(line = startLine, column = startCol),
            end = Position(line = endLine, column = endCol),
        ),
        source = source,
        code = code,
    )
}
