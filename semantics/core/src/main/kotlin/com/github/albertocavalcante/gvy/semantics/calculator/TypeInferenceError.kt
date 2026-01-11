package com.github.albertocavalcante.gvy.semantics.calculator

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.github.albertocavalcante.groovycommon.functional.DomainError
import com.github.albertocavalcante.gvy.semantics.SemanticType

/**
 * Errors that can occur during type inference.
 *
 * This sealed interface provides a type-safe way to represent all possible
 * failure modes in the type calculation system. By using a sealed hierarchy,
 * we enable exhaustive when() matching and make error handling explicit.
 *
 * ## Design rationale
 *
 * - **Sealed interface**: Allows exhaustive when() matching
 * - **Data classes**: Each error carries specific context
 * - **Railway-oriented programming**: Works with Arrow Either for composable error handling
 * - **Domain-specific**: Errors are semantic, not technical (e.g., "SymbolNotFound" vs "NullPointerException")
 *
 * ## Usage example
 *
 * ```kotlin
 * fun resolveType(symbolName: String): TypeResult =
 *     symbolTable[symbolName]
 *         .ensureFound { SymbolNotFound(symbolName) }
 *
 * resolveType("myVar").fold(
 *     ifLeft = { error -> logger.warn { "Type error: ${error.reason}" } },
 *     ifRight = { type -> useType(type) }
 * )
 * ```
 */
sealed interface TypeInferenceError {

    /**
     * Human-readable description of why the type inference failed.
     */
    val reason: String

    /**
     * Symbol was not found in the current scope or parent scopes.
     *
     * This typically occurs when referencing an undefined variable, class, or import.
     *
     * @property symbolName The name of the symbol that couldn't be resolved
     */
    data class SymbolNotFound(val symbolName: String) : TypeInferenceError {
        override val reason: String
            get() = "Symbol not found: $symbolName"
    }

    /**
     * Method call could not be resolved on the receiver type.
     *
     * This occurs when:
     * - The method name doesn't exist on the receiver
     * - The method exists but the argument types don't match any overload
     * - The method exists but is not accessible (private, protected, etc.)
     *
     * @property receiverType The type of the object the method was called on
     * @property methodName The name of the method that couldn't be found
     * @property argumentTypes The types of the arguments passed to the method
     */
    data class MethodNotFound(
        val receiverType: SemanticType,
        val methodName: String,
        val argumentTypes: List<SemanticType>,
    ) : TypeInferenceError {
        override val reason: String
            get() = "Method not found: $receiverType.$methodName(${argumentTypes.joinToString(", ")})"
    }

    /**
     * Field/property access could not be resolved on the receiver type.
     *
     * This occurs when:
     * - The field name doesn't exist on the receiver
     * - The field exists but is not accessible (private, protected, etc.)
     *
     * @property receiverType The type of the object the field was accessed on
     * @property fieldName The name of the field that couldn't be found
     */
    data class FieldNotFound(val receiverType: SemanticType, val fieldName: String) : TypeInferenceError {
        override val reason: String
            get() = "Field not found: $receiverType.$fieldName"
    }

    /**
     * A type reference (by FQN) could not be resolved.
     *
     * This typically occurs when:
     * - The class doesn't exist or is not on the classpath
     * - The import is missing or incorrect
     * - There's a typo in the type name
     *
     * @property fqn The fully qualified name that couldn't be resolved
     * @property cause Optional additional context about why resolution failed
     */
    data class TypeNotResolved(val fqn: String, val cause: String? = null) : TypeInferenceError {
        override val reason: String
            get() = if (cause != null) {
                "Type not resolved: $fqn (cause: $cause)"
            } else {
                "Type not resolved: $fqn"
            }
    }

    /**
     * The AST node type is not supported by any calculator.
     *
     * This typically indicates:
     * - A new Groovy/Java syntax that isn't handled yet
     * - A malformed or unexpected AST structure
     * - A calculator is missing for this node type
     *
     * @property nodeType The class name of the unsupported AST node
     */
    data class UnsupportedNode(val nodeType: String) : TypeInferenceError {
        override val reason: String
            get() = "Unsupported node type: $nodeType"
    }

    /**
     * No calculator was found that can handle this node type.
     *
     * Similar to [UnsupportedNode], but specifically indicates that the
     * calculator registry returned no matches. This can help distinguish
     * between "calculator exists but returned null" vs "no calculator at all".
     *
     * @property nodeType The class name of the node with no calculator
     */
    data class NoCalculatorFound(val nodeType: String) : TypeInferenceError {
        override val reason: String
            get() = "No calculator found for node type: $nodeType"
    }

    /**
     * The receiver type of a member access could not be resolved.
     *
     * This occurs when trying to access a method or field on an expression
     * whose type couldn't be determined (e.g., `unknownVar.someMethod()`).
     *
     * @property hint Optional context about what was being accessed
     */
    data class ReceiverTypeUnresolved(val hint: String? = null) : TypeInferenceError {
        override val reason: String
            get() = if (hint != null) {
                "Receiver type unresolved: $hint"
            } else {
                "Receiver type unresolved"
            }
    }

    /**
     * An internal error occurred during type inference.
     *
     * This represents unexpected failures that shouldn't happen in normal operation,
     * such as:
     * - Reflection failures
     * - Unexpected null values
     * - Logic errors in calculators
     *
     * @property reason Human-readable description of the internal error
     * @property cause The underlying exception, if any
     */
    data class InternalError(override val reason: String, val cause: Throwable? = null) : TypeInferenceError
}

/**
 * Type alias for type inference results using Railway-Oriented Programming.
 *
 * This represents the result of a type calculation operation:
 * - **Left**: A [TypeInferenceError] describing what went wrong
 * - **Right**: A successfully calculated [SemanticType]
 *
 * ## Why Either?
 *
 * 1. **Explicit error handling**: Callers must handle errors at compile time
 * 2. **Composable**: Chain operations with `map`, `flatMap`, `fold`
 * 3. **No exceptions**: Errors are values, not control flow
 * 4. **Testable**: Easy to assert on Left/Right without try-catch
 *
 * ## Example usage
 *
 * ```kotlin
 * fun calculateType(node: ASTNode): TypeResult =
 *     resolveReceiver(node)
 *         .flatMap { receiver -> resolveMethod(receiver, node.methodName) }
 *         .map { method -> method.returnType }
 * ```
 */
typealias TypeResult = Either<TypeInferenceError, SemanticType>

/**
 * Convert this [TypeInferenceError] to a general [DomainError].
 *
 * This allows type inference errors to be propagated through layers
 * that use the general [DomainError] type (e.g., LSP handlers).
 *
 * @return A [DomainError] with source set to "TypeInference"
 */
fun TypeInferenceError.toDomainError(): DomainError = DomainError(
    reason = this.reason,
    source = "TypeInference",
    cause = (this as? TypeInferenceError.InternalError)?.cause,
)

/**
 * Wrap this [SemanticType] in a successful [TypeResult].
 *
 * Convenience method for creating Right values.
 *
 * ```kotlin
 * val result: TypeResult = TypeConstants.STRING.asRight()
 * ```
 */
fun SemanticType.asRight(): TypeResult = this.right()

/**
 * Wrap this [TypeInferenceError] in a failed [TypeResult].
 *
 * Convenience method for creating Left values.
 *
 * ```kotlin
 * val result: TypeResult = SymbolNotFound("myVar").asLeft()
 * ```
 */
fun TypeInferenceError.asLeft(): TypeResult = this.left()

/**
 * Ensure a nullable value is non-null, or return an error.
 *
 * This is useful for converting nullable results into Either:
 *
 * ```kotlin
 * fun resolveSymbol(name: String): TypeResult =
 *     symbolTable[name].ensureFound { SymbolNotFound(name) }
 * ```
 *
 * @param error Lazy error provider, called only if this value is null
 * @return Either.Right(value) if non-null, or Either.Left(error) if null
 */
fun <T : Any> T?.ensureFound(error: () -> TypeInferenceError): Either<TypeInferenceError, T> =
    this?.right() ?: error().left()

/**
 * Catch exceptions and convert them to [TypeInferenceError.InternalError].
 *
 * This is useful for wrapping risky operations (reflection, I/O) that
 * might throw unexpected exceptions:
 *
 * ```kotlin
 * fun reflectiveCall(obj: Any): TypeResult = catchingTypeError {
 *     val result = obj.javaClass.getMethod("getValue").invoke(obj)
 *     inferTypeOf(result)
 * }
 * ```
 *
 * @param block The operation that might throw
 * @return Either.Right(result) if successful, or Either.Left(InternalError) if an exception occurred
 */
inline fun <T> catchingTypeError(block: () -> T): Either<TypeInferenceError, T> = runCatching { block() }.fold(
    onSuccess = { it.right() },
    onFailure = { ex ->
        TypeInferenceError.InternalError(
            reason = ex.message ?: "Unknown error: ${ex.javaClass.simpleName}",
            cause = ex,
        ).left()
    },
)
