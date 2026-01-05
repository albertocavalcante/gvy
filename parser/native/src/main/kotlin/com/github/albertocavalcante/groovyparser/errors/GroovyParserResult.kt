package com.github.albertocavalcante.groovyparser.errors

/**
 * Type alias for Results containing LSP-specific errors
 */
typealias GroovyParserResult<T> = Result<T>

/**
 * Extension functions for working with GroovyParserResult types in a functional way
 */

/**
 * Creates a success result
 */
fun <T> T.toGroovyParserResult(): GroovyParserResult<T> = Result.success(this)

/**
 * Creates a failure result with an GroovyParserError
 */
fun <T> GroovyParserError.toGroovyParserResult(): GroovyParserResult<T> = Result.failure(this)

/**
 * Maps an GroovyParserResult to another type, preserving errors
 */
inline fun <T, R> GroovyParserResult<T>.mapResult(transform: (T) -> R): GroovyParserResult<R> = map(transform)

/**
 * Flat maps an GroovyParserResult to another GroovyParserResult, preserving errors
 */
inline fun <T, R> GroovyParserResult<T>.flatMapResult(transform: (T) -> GroovyParserResult<R>): GroovyParserResult<R> =
    fold(
        onSuccess = transform,
        onFailure = { Result.failure(it) },
    )

/**
 * Converts exceptions to GroovyParserError types
 */
@Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - utility function's final error wrapper
inline fun <T> groovyParserResultOf(block: () -> T): GroovyParserResult<T> = try {
    Result.success(block())
} catch (e: GroovyParserError) {
    Result.failure(e)
} catch (e: IllegalArgumentException) {
    Result.failure(
        GroovyParserError.InternalError("validation", e.message ?: "Invalid argument", e),
    )
} catch (e: IllegalStateException) {
    Result.failure(
        GroovyParserError.InternalError("state_check", e.message ?: "Invalid state", e),
    )
} catch (e: org.codehaus.groovy.control.CompilationFailedException) {
    Result.failure(
        GroovyParserError.InternalError("compilation", e.message ?: "Groovy compilation failed", e),
    )
} catch (e: java.io.IOException) {
    Result.failure(
        GroovyParserError.InternalError("io_error", e.message ?: "I/O operation failed", e),
    )
} catch (e: Exception) {
    Result.failure(
        GroovyParserError.InternalError("unknown", e.message ?: "Unexpected error", e),
    )
}

/**
 * Combines multiple GroovyParserResults into a single result containing a list
 */
fun <T> List<GroovyParserResult<T>>.combineResults(): GroovyParserResult<List<T>> {
    val successes = mutableListOf<T>()
    val firstFailure = this.firstOrNull { it.isFailure }

    return if (firstFailure != null) {
        firstFailure.map { emptyList<T>() } // This will preserve the failure
    } else {
        this.forEach { result ->
            result.getOrNull()?.let { successes.add(it) }
        }
        Result.success(successes)
    }
}

/**
 * Recovers from specific error types
 */
inline fun <T> GroovyParserResult<T>.recoverFrom(
    errorType: Class<out GroovyParserError>,
    recovery: (GroovyParserError) -> T,
): GroovyParserResult<T> = recoverCatching { error ->
    if (errorType.isInstance(error)) {
        recovery(error as GroovyParserError)
    } else {
        throw error
    }
}

/**
 * Logs errors while preserving the result chain
 */
inline fun <T> GroovyParserResult<T>.logError(
    logger: org.slf4j.Logger,
    message: (GroovyParserError) -> String = { "LSP operation failed: ${it.message}" },
): GroovyParserResult<T> = also { result ->
    result.exceptionOrNull()?.let { error ->
        when (error) {
            is GroovyParserError -> logger.debug(message(error), error)
            else -> logger.error("Unexpected error: ${error.message}", error)
        }
    }
}

/**
 * Converts GroovyParserResult to nullable value, logging errors
 */
fun <T> GroovyParserResult<T>.getOrLogNull(logger: org.slf4j.Logger, message: String = "Operation failed"): T? = fold(
    onSuccess = { it },
    onFailure = { error ->
        when (error) {
            is GroovyParserError -> logger.debug("$message: ${error.message}")
            else -> logger.error("$message: ${error.message}", error)
        }
        null
    },
)

/**
 * Converts GroovyParserResult to a default value, logging errors
 */
fun <T> GroovyParserResult<T>.getOrDefault(
    default: T,
    logger: org.slf4j.Logger? = null,
    message: String = "Operation failed, using default",
): T = fold(
    onSuccess = { it },
    onFailure = { error ->
        logger?.let {
            when (error) {
                is GroovyParserError -> it.debug("$message: ${error.message}")
                else -> it.warn("$message: ${error.message}", error)
            }
        }
        default
    },
)
