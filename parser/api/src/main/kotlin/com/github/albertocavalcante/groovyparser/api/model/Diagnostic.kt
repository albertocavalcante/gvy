package com.github.albertocavalcante.groovyparser.api.model

/**
 * A diagnostic message from parsing (error, warning, info).
 */
data class Diagnostic(
    val severity: Severity,
    val message: String,
    val range: Range,
    val source: String = "groovy-parser",
    val code: String? = null,
)

/**
 * Severity level for diagnostics.
 */
enum class Severity {
    ERROR,
    WARNING,
    INFO,
    HINT,
}
