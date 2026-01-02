package com.github.albertocavalcante.groovyparser

import java.util.Optional
import java.util.function.Consumer

/**
 * The result of a parse operation, containing either a successful parse result
 * or a list of problems encountered during parsing.
 *
 * @param T the type of the parsed result (e.g., CompilationUnit, Expression)
 */
class ParseResult<T>(private val resultValue: T?, private val problemList: List<Problem>) {
    /**
     * Returns true if parsing was successful (no errors and result is present).
     * Note: warnings don't affect success status.
     */
    val isSuccessful: Boolean
        get() = !hasErrors && resultValue != null

    /**
     * Returns true if there are any errors (not warnings).
     */
    val hasErrors: Boolean
        get() = problemList.any { it.severity == ProblemSeverity.ERROR }

    /**
     * Returns true if there are any warnings.
     */
    val hasWarnings: Boolean
        get() = problemList.any { it.severity == ProblemSeverity.WARNING }

    /**
     * Returns the parsed result wrapped in an Optional.
     */
    val result: Optional<T & Any>
        get() = Optional.ofNullable(resultValue)

    /**
     * Returns the list of problems encountered during parsing.
     */
    val problems: List<Problem>
        get() = problemList

    /**
     * Returns only the errors (not warnings or info).
     */
    val errors: List<Problem>
        get() = problemList.filter { it.severity == ProblemSeverity.ERROR }

    /**
     * Returns only the warnings.
     */
    val warnings: List<Problem>
        get() = problemList.filter { it.severity == ProblemSeverity.WARNING }

    /**
     * Returns problems filtered by the given severity.
     */
    fun problemsBySeverity(severity: ProblemSeverity): List<Problem> = problemList.filter { it.severity == severity }

    /**
     * Returns problems at least as severe as the given threshold.
     */
    fun problemsAtLeast(threshold: ProblemSeverity): List<Problem> =
        problemList.filter { it.severity.isAtLeast(threshold) }

    /**
     * Executes the given consumer if parsing was successful.
     */
    fun ifSuccessful(consumer: Consumer<T>) {
        if (isSuccessful && resultValue != null) {
            consumer.accept(resultValue)
        }
    }

    /**
     * Returns the result if present, otherwise throws an exception with problems.
     */
    fun getOrThrow(): T {
        if (resultValue != null) return resultValue
        throw ParseProblemException(problemList)
    }

    override fun toString(): String {
        val errorCount = errors.size
        val warningCount = warnings.size
        return when {
            isSuccessful && warningCount == 0 -> "ParseResult[successful]"
            isSuccessful -> "ParseResult[successful, $warningCount warning(s)]"
            resultValue != null -> "ParseResult[partial, $errorCount error(s), $warningCount warning(s)]"
            else -> "ParseResult[failed, $errorCount error(s)]"
        }
    }
}
