package com.github.albertocavalcante.groovylsp.repl

import com.github.albertocavalcante.groovylsp.async.future
import kotlinx.coroutines.CoroutineScope
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Handles LSP custom commands for REPL functionality.
 * Note: All methods marked with @Suppress("TooGenericExceptionCaught") are command entry points
 * that must handle all possible exceptions to prevent LSP protocol disruption.
 * This class bridges LSP protocol messages with the REPL session manager.
 */
@Suppress("TooGenericExceptionCaught") // All command handlers are LSP entry points requiring generic exception handling
class ReplCommandHandler(private val sessionManager: ReplSessionManager, private val coroutineScope: CoroutineScope) {

    private val logger = LoggerFactory.getLogger(ReplCommandHandler::class.java)

    companion object {
        // Command identifiers as defined in the protocol specification
        const val CREATE_SESSION = "groovy/repl/create"
        const val EVALUATE_CODE = "groovy/repl/evaluate"
        const val GET_COMPLETIONS = "groovy/repl/complete"
        const val INSPECT_VARIABLE = "groovy/repl/inspect"
        const val GET_HISTORY = "groovy/repl/history"
        const val RESET_SESSION = "groovy/repl/reset"
        const val DESTROY_SESSION = "groovy/repl/destroy"
        const val LIST_SESSIONS = "groovy/repl/list"
        const val SWITCH_CONTEXT = "groovy/repl/switchContext"
    }

    /**
     * Handles the workspace/executeCommand request for REPL commands.
     */
    fun executeCommand(command: String, arguments: List<Any>): CompletableFuture<Any?> = when (command) {
        CREATE_SESSION -> handleCreateSession(arguments) as CompletableFuture<Any?>
        EVALUATE_CODE -> handleEvaluateCode(arguments) as CompletableFuture<Any?>
        GET_COMPLETIONS -> handleGetCompletions(arguments) as CompletableFuture<Any?>
        INSPECT_VARIABLE -> handleInspectVariable(arguments) as CompletableFuture<Any?>
        GET_HISTORY -> handleGetHistory(arguments) as CompletableFuture<Any?>
        RESET_SESSION -> handleResetSession(arguments) as CompletableFuture<Any?>
        DESTROY_SESSION -> handleDestroySession(arguments) as CompletableFuture<Any?>
        LIST_SESSIONS -> handleListSessions(arguments) as CompletableFuture<Any?>
        SWITCH_CONTEXT -> handleSwitchContext(arguments) as CompletableFuture<Any?>
        else -> {
            logger.warn("Unknown REPL command: $command")
            CompletableFuture.completedFuture(null)
        }
    }

    /**
     * Gets the list of supported REPL commands.
     */
    fun getSupportedCommands(): List<String> = listOf(
        CREATE_SESSION,
        EVALUATE_CODE,
        GET_COMPLETIONS,
        INSPECT_VARIABLE,
        GET_HISTORY,
        RESET_SESSION,
        DESTROY_SESSION,
        LIST_SESSIONS,
        SWITCH_CONTEXT,
    )

    // Command handlers

    private fun handleCreateSession(arguments: List<Any>): CompletableFuture<CreateSessionResult> =
        coroutineScope.future {
            try {
                val params = ParameterParser.parseCreateSessionParams(arguments)
                logger.info("Creating REPL session with context: ${params.contextName}")
                sessionManager.createSession(params)
            } catch (e: Exception) {
                logger.error("Failed to create REPL session", e)
                throw e
            }
        }

    private fun handleEvaluateCode(arguments: List<Any>): CompletableFuture<EvaluateResult> = coroutineScope.future {
        try {
            val params = ParameterParser.parseEvaluateParams(arguments)
            logger.debug("Evaluating code in session ${params.sessionId}")

            val result = sessionManager.evaluateCode(params.sessionId, params.code)
            convertToEvaluateResult(result, params)
        } catch (e: Exception) {
            logger.error("Failed to evaluate code", e)
            createErrorEvaluateResult(e)
        }
    }

    private fun handleGetCompletions(arguments: List<Any>): CompletableFuture<ReplCompletionResult> =
        coroutineScope.future {
            try {
                val params = ParameterParser.parseCompletionParams(arguments)
                logger.debug("Getting completions for session ${params.sessionId}")

                val completions = sessionManager.getCompletions(
                    params.sessionId,
                    params.code,
                    params.position,
                )

                createCompletionResult(completions, params)
            } catch (e: Exception) {
                logger.error("Failed to get completions", e)
                ReplCompletionResult(
                    completions = emptyList(),
                    bindingCompletions = emptyList(),
                )
            }
        }

    private fun handleInspectVariable(arguments: List<Any>): CompletableFuture<InspectResult?> = coroutineScope.future {
        try {
            val params = ParameterParser.parseInspectParams(arguments)
            logger.debug("Inspecting variable ${params.variableName} in session ${params.sessionId}")

            val typeInfo = sessionManager.getTypeInfo(params.sessionId, params.variableName)
            createInspectResult(typeInfo, params)
        } catch (e: Exception) {
            logger.error("Failed to inspect variable", e)
            null
        }
    }

    private fun handleGetHistory(arguments: List<Any>): CompletableFuture<HistoryResult> = coroutineScope.future {
        try {
            val params = ParameterParser.parseHistoryParams(arguments)
            logger.debug("Getting history for session ${params.sessionId}")

            val history = sessionManager.getHistory(params.sessionId, params.limit)
            createHistoryResult(history, params)
        } catch (e: Exception) {
            logger.error("Failed to get history", e)
            HistoryResult(emptyList(), 0)
        }
    }

    private fun handleResetSession(arguments: List<Any>): CompletableFuture<Void?> = coroutineScope.future {
        try {
            val params = ParameterParser.parseResetParams(arguments)
            logger.info("Resetting session ${params.sessionId}")
            sessionManager.resetSession(params.sessionId)
            null
        } catch (e: Exception) {
            logger.error("Failed to reset session", e)
            throw e
        }
    }

    private fun handleDestroySession(arguments: List<Any>): CompletableFuture<Void?> = coroutineScope.future {
        try {
            val params = ParameterParser.parseDestroyParams(arguments)
            logger.info("Destroying session ${params.sessionId}")
            sessionManager.destroySession(params.sessionId)
            null
        } catch (e: Exception) {
            logger.error("Failed to destroy session", e)
            throw e
        }
    }

    private fun handleListSessions(
        @Suppress("UNUSED_PARAMETER") arguments: List<Any>,
    ): CompletableFuture<ListSessionsResult> = coroutineScope.future {
        try {
            logger.debug("Listing REPL sessions")
            sessionManager.listSessions()
        } catch (e: Exception) {
            logger.error("Failed to list sessions", e)
            ListSessionsResult(emptyList())
        }
    }

    private fun handleSwitchContext(arguments: List<Any>): CompletableFuture<SwitchContextResult> =
        coroutineScope.future {
            try {
                val params = ParameterParser.parseSwitchContextParams(arguments)
                logger.info("Switching context for session ${params.sessionId} to ${params.contextName}")
                sessionManager.switchContext(
                    params.sessionId,
                    params.contextName,
                    params.preserveBindings,
                )
            } catch (e: Exception) {
                logger.error("Failed to switch context", e)
                createErrorSwitchContextResult(e)
            }
        }

    // Helper methods for creating results

    private fun convertToEvaluateResult(result: ReplResult, params: EvaluateParams): EvaluateResult = EvaluateResult(
        success = result.success,
        value = result.value,
        type = result.type,
        output = if (params.captureOutput) result.output else null,
        duration = result.duration,
        bindings = if (params.includeBindings) {
            result.bindings.values.toList()
        } else {
            null
        },
        diagnostics = if (result.diagnostics.isNotEmpty()) {
            result.diagnostics.map { createDiagnostic(it) }
        } else {
            null
        },
        sideEffects = result.sideEffects,
    )

    private fun createErrorEvaluateResult(error: Exception): EvaluateResult = EvaluateResult(
        success = false,
        value = null,
        type = null,
        output = null,
        duration = 0,
        bindings = null,
        diagnostics = listOf(createDiagnostic(error.message ?: "Evaluation failed")),
        sideEffects = null,
    )

    private suspend fun createCompletionResult(
        completions: List<String>,
        params: ReplCompletionParams,
    ): ReplCompletionResult {
        val completionItems = completions.map { completion ->
            CompletionItem().apply {
                label = completion
                kind = CompletionItemKind.Text
                detail = "REPL completion"
            }
        }

        val bindingCompletions = if (params.includeBindings) {
            emptyList<BindingCompletion>() // Simplified for now
        } else {
            emptyList()
        }

        return ReplCompletionResult(
            completions = completionItems,
            bindingCompletions = bindingCompletions,
        )
    }

    private fun createInspectResult(typeInfo: TypeInfo?, params: InspectParams): InspectResult? =
        if (typeInfo == null) {
            null
        } else {
            InspectResult(
                variable = DetailedVariableInfo(
                    name = params.variableName,
                    value = "N/A",
                    type = typeInfo.typeName,
                    isNull = false,
                    hierarchy = typeInfo.hierarchy,
                ),
                methods = if (params.includeMethods) typeInfo.methods else null,
                properties = if (params.includeProperties) typeInfo.properties else null,
                metaClass = null,
            )
        }

    private fun createHistoryResult(history: List<String>, params: HistoryParams): HistoryResult {
        val entries = history.mapIndexed { index, code ->
            HistoryEntry(
                id = index + params.offset,
                timestamp = java.time.Instant.now().toString(),
                code = code,
                success = true,
                duration = 0,
            )
        }

        return HistoryResult(
            entries = entries,
            totalCount = history.size,
        )
    }

    private fun createErrorSwitchContextResult(error: Exception): SwitchContextResult = SwitchContextResult(
        success = false,
        previousContext = "unknown",
        newContext = "unknown",
        preservedBindings = emptyList(),
        lostBindings = emptyList(),
        warnings = listOf("Context switch failed: ${error.message}"),
    )

    // LSP diagnostic creation

    private fun createDiagnostic(message: String): Diagnostic = Diagnostic().apply {
        range = Range(Position(0, 0), Position(0, 0))
        severity = DiagnosticSeverity.Error
        this.message = message
        source = "groovy-repl"
    }
}
