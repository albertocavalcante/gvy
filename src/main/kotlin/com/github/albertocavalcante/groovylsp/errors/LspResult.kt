package com.github.albertocavalcante.groovylsp.errors

/**
 * Type alias for Results containing LSP-specific errors
 */
typealias LspResult<T> = Result<T>

/**
 * Extension functions for working with LspResult types in a functional way
 */

/**
 * Creates a success result
 */
fun <T> T.toLspResult(): LspResult<T> = Result.success(this)

/**
 * Creates a failure result with an LspError
 */
fun <T> LspError.toLspResult(): LspResult<T> = Result.failure(this)

/**
 * Maps an LspResult to another type, preserving errors
 */
inline fun <T, R> LspResult<T>.mapResult(transform: (T) -> R): LspResult<R> = map(transform)

/**
 * Flat maps an LspResult to another LspResult, preserving errors
 */
inline fun <T, R> LspResult<T>.flatMapResult(transform: (T) -> LspResult<R>): LspResult<R> = fold(
    onSuccess = transform,
    onFailure = { Result.failure(it) },
)

/**
 * Converts exceptions to LspError types
 */
inline fun <T> lspResultOf(block: () -> T): LspResult<T> = try {
    Result.success(block())
} catch (e: LspError) {
    Result.failure(e)
} catch (e: IllegalArgumentException) {
    Result.failure(
        LspError.InternalError("validation", e.message ?: "Invalid argument", e),
    )
} catch (e: IllegalStateException) {
    Result.failure(
        LspError.InternalError("state_check", e.message ?: "Invalid state", e),
    )
} catch (e: org.codehaus.groovy.control.CompilationFailedException) {
    Result.failure(
        LspError.InternalError("compilation", e.message ?: "Groovy compilation failed", e),
    )
} catch (e: java.io.IOException) {
    Result.failure(
        LspError.InternalError("io_error", e.message ?: "I/O operation failed", e),
    )
} catch (e: RuntimeException) {
    Result.failure(
        LspError.InternalError("unknown", e.message ?: "Unexpected error", e),
    )
}

/**
 * Combines multiple LspResults into a single result containing a list
 */
fun <T> List<LspResult<T>>.combineResults(): LspResult<List<T>> {
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
inline fun <T> LspResult<T>.recoverFrom(errorType: Class<out LspError>, recovery: (LspError) -> T): LspResult<T> =
    recoverCatching { error ->
        if (errorType.isInstance(error)) {
            recovery(error as LspError)
        } else {
            throw error
        }
    }

/**
 * Recovers from NodeNotFound errors
 */
inline fun <T> LspResult<T>.recoverFromNodeNotFound(recovery: (LspError.NodeNotFound) -> T): LspResult<T> =
    recoverCatching { error ->
        when (error) {
            is LspError.NodeNotFound -> recovery(error)
            else -> throw error
        }
    }

/**
 * Recovers from SymbolNotFound errors
 */
inline fun <T> LspResult<T>.recoverFromSymbolNotFound(recovery: (LspError.SymbolNotFound) -> T): LspResult<T> =
    recoverCatching { error ->
        when (error) {
            is LspError.SymbolNotFound -> recovery(error)
            else -> throw error
        }
    }

/**
 * Logs errors while preserving the result chain
 */
inline fun <T> LspResult<T>.logError(
    logger: org.slf4j.Logger,
    message: (LspError) -> String = { "LSP operation failed: ${it.message}" },
): LspResult<T> = also { result ->
    result.exceptionOrNull()?.let { error ->
        when (error) {
            is LspError -> logger.debug(message(error), error)
            else -> logger.error("Unexpected error: ${error.message}", error)
        }
    }
}

/**
 * Converts LspResult to nullable value, logging errors
 */
fun <T> LspResult<T>.getOrLogNull(logger: org.slf4j.Logger, message: String = "Operation failed"): T? = fold(
    onSuccess = { it },
    onFailure = { error ->
        when (error) {
            is LspError -> logger.debug("$message: ${error.message}")
            else -> logger.error("$message: ${error.message}", error)
        }
        null
    },
)

/**
 * Converts LspResult to a default value, logging errors
 */
inline fun <T> LspResult<T>.getOrDefault(
    default: T,
    logger: org.slf4j.Logger? = null,
    message: String = "Operation failed, using default",
): T = fold(
    onSuccess = { it },
    onFailure = { error ->
        logger?.let {
            when (error) {
                is LspError -> it.debug("$message: ${error.message}")
                else -> it.warn("$message: ${error.message}", error)
            }
        }
        default
    },
)
