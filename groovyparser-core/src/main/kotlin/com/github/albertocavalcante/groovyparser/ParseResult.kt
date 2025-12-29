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
     * Returns true if parsing was successful (no problems and result is present).
     */
    val isSuccessful: Boolean
        get() = problemList.isEmpty() && resultValue != null

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
     * Executes the given consumer if parsing was successful.
     */
    fun ifSuccessful(consumer: Consumer<T>) {
        if (isSuccessful && resultValue != null) {
            consumer.accept(resultValue)
        }
    }

    override fun toString(): String = if (isSuccessful) {
        "ParseResult[successful]"
    } else {
        "ParseResult[failed, ${problems.size} problem(s)]"
    }
}
