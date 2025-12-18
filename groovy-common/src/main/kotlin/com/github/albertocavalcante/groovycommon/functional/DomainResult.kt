package com.github.albertocavalcante.groovycommon.functional

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * General purpose domain error with context.
 *
 * This provides a consistent error type across all modules, enabling
 * Railway-Oriented Programming with Arrow's Either type.
 *
 * @property reason Human-readable description of why the operation failed
 * @property source Component/module that produced this error (for debugging)
 * @property cause Optional underlying exception
 */
data class DomainError(val reason: String, val source: String = "unknown", val cause: Throwable? = null)

/**
 * Type alias for common result pattern using Arrow Either.
 *
 * ## Why Either over exceptions?
 *
 * 1. **Explicit error handling**: Callers must handle errors at compile time
 * 2. **Composable**: Chain operations with `map`, `flatMap`, `fold`
 * 3. **Testable**: Easy to assert on Left/Right without try-catch
 * 4. **No stack traces**: Faster for expected failures (not exceptional)
 *
 * ## Example usage
 * ```kotlin
 * fun parseConfig(input: String): DomainResult<Config> =
 *     if (input.isBlank()) "Empty input".failure("ConfigParser")
 *     else Config(input).success()
 *
 * val result = parseConfig(userInput)
 *     .map { it.validate() }
 *     .fold(
 *         ifLeft = { error -> logger.warn("Parse failed: ${error.reason}") },
 *         ifRight = { config -> applyConfig(config) }
 *     )
 * ```
 */
typealias DomainResult<T> = Either<DomainError, T>

/**
 * Wrap a successful value in Either.Right.
 *
 * ```kotlin
 * val result: DomainResult<Int> = 42.success()
 * ```
 */
fun <T> T.success(): DomainResult<T> = this.right()

/**
 * Create a failure result from an error message.
 *
 * ```kotlin
 * val result: DomainResult<Nothing> = "Not found".failure("UserService")
 * ```
 */
fun String.failure(source: String = "unknown"): DomainResult<Nothing> = DomainError(this, source).left()

/**
 * Create a failure result from an error message with a cause.
 *
 * ```kotlin
 * val result = "Failed to parse".failure("Parser", exception)
 * ```
 */
fun String.failure(source: String, cause: Throwable): DomainResult<Nothing> = DomainError(this, source, cause).left()
