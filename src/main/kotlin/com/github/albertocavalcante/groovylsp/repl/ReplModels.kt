package com.github.albertocavalcante.groovylsp.repl

import java.time.Duration
import java.time.Instant

/**
 * Data models for REPL operations on the Kotlin side.
 * These models are used for type-safe communication between Kotlin and Groovy components.
 */

/**
 * Result of evaluating code in the REPL.
 */
data class ReplResult(
    val success: Boolean,
    val value: Any?,
    val type: String?,
    val output: String,
    val duration: Long,
    val bindings: Map<String, VariableInfo>,
    val diagnostics: List<String>,
    val sideEffects: SideEffects,
    val error: Throwable? = null,
) {
    companion object {
        fun success(
            value: Any?,
            duration: Long,
            output: String = "",
            bindings: Map<String, VariableInfo> = emptyMap(),
            sideEffects: SideEffects = SideEffects(),
        ): ReplResult = ReplResult(
            success = true,
            value = value,
            type = value?.javaClass?.name ?: "null",
            output = output,
            duration = duration,
            bindings = bindings,
            diagnostics = emptyList(),
            sideEffects = sideEffects,
        )

        fun compilationError(errors: List<String>, duration: Long = 0): ReplResult = ReplResult(
            success = false,
            value = null,
            type = null,
            output = "",
            duration = duration,
            bindings = emptyMap(),
            diagnostics = errors,
            sideEffects = SideEffects(),
        )

        fun runtimeError(error: Throwable, duration: Long, output: String = ""): ReplResult = ReplResult(
            success = false,
            value = null,
            type = null,
            output = output,
            duration = duration,
            bindings = emptyMap(),
            diagnostics = listOf(error.message ?: error.javaClass.simpleName),
            sideEffects = SideEffects(),
            error = error,
        )

        fun timeout(duration: Long): ReplResult = ReplResult(
            success = false,
            value = null,
            type = null,
            output = "",
            duration = duration,
            bindings = emptyMap(),
            diagnostics = listOf("Execution timed out after ${duration}ms"),
            sideEffects = SideEffects(),
        )
    }
}

/**
 * Information about a variable in the REPL session.
 */
data class VariableInfo(
    val name: String,
    val value: String,
    val type: String,
    val isNull: Boolean,
    val size: Int? = null,
)

/**
 * Side effects captured during code evaluation.
 */
data class SideEffects(
    val printOutput: String = "",
    val errorOutput: String = "",
    val imports: List<String> = emptyList(),
    val classesLoaded: List<String> = emptyList(),
    val systemPropertyChanges: Map<String, String> = emptyMap(),
)

/**
 * Type information for introspection.
 */
data class TypeInfo(
    val typeName: String,
    val hierarchy: List<String>,
    val methods: List<MethodInfo>,
    val properties: List<PropertyInfo>,
    val documentation: String? = null,
)

/**
 * Method information for type introspection.
 */
data class MethodInfo(
    val name: String,
    val signature: String,
    val returnType: String,
    val isStatic: Boolean,
    val visibility: String,
)

/**
 * Property information for type introspection.
 */
data class PropertyInfo(
    val name: String,
    val type: String,
    val hasGetter: Boolean,
    val hasSetter: Boolean,
    val isStatic: Boolean,
)

/**
 * Configuration for a REPL session.
 */
data class SessionConfiguration(
    val autoImports: List<String> = emptyList(),
    val executionTimeout: Duration = Duration.ofSeconds(DEFAULT_EXECUTION_TIMEOUT_SECONDS),
    val maxMemory: String = "512m",
    val sandboxing: Boolean = false,
    val historySize: Int = DEFAULT_HISTORY_SIZE,
    val enableMetaClassModifications: Boolean = true,
    val allowedPackages: Set<String> = emptySet(),
    val disallowedMethods: Set<String> = emptySet(),
) {
    companion object {
        private const val DEFAULT_EXECUTION_TIMEOUT_SECONDS = 30L
        private const val DEFAULT_HISTORY_SIZE = 1000
    }
}

/**
 * A REPL session managing state and configuration.
 */
data class ReplSession(
    val id: String,
    val contextName: String,
    val engine: Any, // Groovy engine instance
    val createdAt: Instant,
    var lastUsed: Instant,
    val configuration: SessionConfiguration,
) {
    fun updateLastUsed() {
        lastUsed = Instant.now()
    }
}

/**
 * Parameters for creating a new REPL session.
 */
data class CreateSessionParams(
    val sessionId: String? = null,
    val contextName: String? = null,
    val imports: List<String> = emptyList(),
    val configuration: SessionConfiguration? = null,
)

/**
 * Result of creating a REPL session.
 */
data class CreateSessionResult(
    val sessionId: String,
    val contextName: String,
    val availableContexts: List<String>,
    val configuration: SessionConfiguration,
    val initialBindings: List<VariableInfo>,
)

/**
 * Parameters for evaluating code in a REPL session.
 */
data class EvaluateParams(
    val sessionId: String,
    val code: String,
    val async: Boolean = false,
    val includeBindings: Boolean = false,
    val captureOutput: Boolean = true,
)

/**
 * Result of evaluating code in a REPL session.
 */
data class EvaluateResult(
    val success: Boolean,
    val value: Any?,
    val type: String?,
    val output: String?,
    val duration: Long,
    val bindings: List<VariableInfo>?,
    val diagnostics: List<org.eclipse.lsp4j.Diagnostic>?,
    val sideEffects: SideEffects?,
)

/**
 * Parameters for getting completions in a REPL session.
 */
data class ReplCompletionParams(
    val sessionId: String,
    val code: String,
    val position: Int,
    val includeBindings: Boolean = true,
    val includeWorkspace: Boolean = true,
)

/**
 * Result of getting completions in a REPL session.
 */
data class ReplCompletionResult(
    val completions: List<org.eclipse.lsp4j.CompletionItem>,
    val bindingCompletions: List<BindingCompletion>,
)

/**
 * Completion item for REPL bindings.
 */
data class BindingCompletion(
    val name: String,
    val type: String,
    val kind: String, // "variable", "method", "property"
    val documentation: String? = null,
    val signature: String? = null, // For methods
)

/**
 * Parameters for inspecting a variable.
 */
data class InspectParams(
    val sessionId: String,
    val variableName: String,
    val includeMetaClass: Boolean = false,
    val includeMethods: Boolean = true,
    val includeProperties: Boolean = true,
)

/**
 * Result of inspecting a variable.
 */
data class InspectResult(
    val variable: DetailedVariableInfo,
    val methods: List<MethodInfo>? = null,
    val properties: List<PropertyInfo>? = null,
    val metaClass: MetaClassInfo? = null,
)

/**
 * Detailed variable information with additional metadata.
 */
data class DetailedVariableInfo(
    val name: String,
    val value: String,
    val type: String,
    val isNull: Boolean,
    val size: Int? = null,
    val documentation: String? = null,
    val sourceLocation: org.eclipse.lsp4j.Location? = null,
    val hierarchy: List<String> = emptyList(),
)

/**
 * MetaClass information for Groovy objects.
 */
data class MetaClassInfo(
    val className: String,
    val methods: List<String>,
    val properties: List<String>,
    val categories: List<String>, // Applied categories
)

/**
 * Parameters for getting command history.
 */
data class HistoryParams(
    val sessionId: String,
    val limit: Int = 50,
    val offset: Int = 0,
    val includeResults: Boolean = false,
)

/**
 * Result of getting command history.
 */
data class HistoryResult(val entries: List<HistoryEntry>, val totalCount: Int)

/**
 * A single entry in the command history.
 */
data class HistoryEntry(
    val id: Int,
    val timestamp: String, // ISO timestamp
    val code: String,
    val success: Boolean,
    val result: Any? = null,
    val duration: Long,
)

/**
 * Parameters for resetting a REPL session.
 */
data class ResetParams(
    val sessionId: String,
    val preserveImports: Boolean = false,
    val preserveHistory: Boolean = true,
)

/**
 * Parameters for destroying a REPL session.
 */
data class DestroyParams(val sessionId: String, val saveHistory: Boolean = false)

/**
 * Parameters for switching compilation context.
 */
data class SwitchContextParams(val sessionId: String, val contextName: String, val preserveBindings: Boolean = true)

/**
 * Result of switching compilation context.
 */
data class SwitchContextResult(
    val success: Boolean,
    val previousContext: String,
    val newContext: String,
    val preservedBindings: List<String>,
    val lostBindings: List<String>,
    val warnings: List<String>,
)

/**
 * Result of listing active sessions.
 */
data class ListSessionsResult(val sessions: List<SessionInfo>)

/**
 * Information about a REPL session.
 */
data class SessionInfo(
    val sessionId: String,
    val contextName: String,
    val createdAt: String, // ISO timestamp
    val lastUsed: String, // ISO timestamp
    val variableCount: Int,
    val memoryUsage: Long, // In bytes
    val isActive: Boolean,
)
