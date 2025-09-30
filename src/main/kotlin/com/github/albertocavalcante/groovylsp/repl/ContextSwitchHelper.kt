package com.github.albertocavalcante.groovylsp.repl

import com.github.albertocavalcante.groovylsp.compilation.WorkspaceCompilationService
import org.slf4j.LoggerFactory

/**
 * Helper class for managing REPL session context switching operations.
 */
@Suppress("TooGenericExceptionCaught") // REPL context switching handles all execution errors
class ContextSwitchHelper(
    @Suppress("UnusedPrivateProperty") private val workspaceService: WorkspaceCompilationService?,
) {

    private val logger = LoggerFactory.getLogger(ContextSwitchHelper::class.java)

    /**
     * Performs a context switch for a REPL session.
     */
    suspend fun switchContext(
        session: ReplSession,
        contextName: String,
        preserveBindings: Boolean,
    ): SwitchContextResult {
        val previousContext = session.contextName

        // Validate context name
        val availableContexts = listOf("default") // Simplified for now
        val validatedContextName = SessionValidation.validateContextName(contextName, availableContexts)

        // Get current bindings if we need to preserve them
        val currentBindings = if (preserveBindings) {
            getCurrentBindingsIfRequested(session)
        } else {
            emptyMap()
        }

        // Create new engine for the context
        val newEngine = createNewEngineForContext(session, validatedContextName)

        // Update session with new engine
        updateSessionWithNewEngine(session, newEngine, validatedContextName)

        // Restore bindings if requested
        val (preservedBindings, lostBindings, warnings) = if (preserveBindings && currentBindings.isNotEmpty()) {
            restoreBindingsIfRequested(newEngine, currentBindings)
        } else {
            Triple(emptyList<String>(), emptyList<String>(), emptyList<String>())
        }

        logger.info("Switched session ${session.id} from '$previousContext' to '$validatedContextName'")

        return SwitchContextResult(
            success = true,
            previousContext = previousContext,
            newContext = validatedContextName,
            preservedBindings = preservedBindings,
            lostBindings = lostBindings,
            warnings = warnings,
        )
    }

    private fun getCurrentBindingsIfRequested(session: ReplSession): Map<String, VariableInfo> = try {
        GroovyInterop.getBindings(session.engine)
    } catch (e: Exception) {
        logger.warn("Failed to get current bindings", e)
        emptyMap()
    }

    private suspend fun createNewEngineForContext(
        session: ReplSession,
        @Suppress("UnusedParameter") contextName: String,
    ): Any {
        val classpath = emptyList<String>() // Simplified for now
        val imports = session.configuration.autoImports

        return GroovyInterop.createReplEngine(
            classpath = classpath,
            imports = imports,
            configuration = mapOf(
                "executionTimeout" to session.configuration.executionTimeout.toMillis(),
                "maxMemory" to session.configuration.maxMemory,
                "sandboxing" to session.configuration.sandboxing,
                "historySize" to session.configuration.historySize,
            ),
        )
    }

    private fun updateSessionWithNewEngine(session: ReplSession, newEngine: Any, contextName: String) {
        // Note: This assumes ReplSession is mutable, which it currently isn't
        // We would need to modify ReplSession to be mutable or return a new instance
        // For now, we'll use reflection to update the engine field
        try {
            val engineField = session.javaClass.getDeclaredField("engine")
            engineField.isAccessible = true
            engineField.set(session, newEngine)

            val contextField = session.javaClass.getDeclaredField("contextName")
            contextField.isAccessible = true
            contextField.set(session, contextName)

            session.updateLastUsed()
        } catch (e: Exception) {
            logger.error("Failed to update session with new engine", e)
            throw ReplException("Failed to update session: ${e.message}", e)
        }
    }

    private fun restoreBindingsIfRequested(
        engine: Any,
        bindings: Map<String, VariableInfo>,
    ): Triple<List<String>, List<String>, List<String>> {
        val preserved = mutableListOf<String>()
        val lost = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        bindings.forEach { (name, info) ->
            try {
                val bindingCode = createBindingCode(name, info)
                GroovyInterop.evaluateCode(engine, bindingCode)
                preserved.add(name)
            } catch (e: Exception) {
                lost.add(name)
                logger.debug("Failed to restore binding '$name': ${e.message}")
            }
        }

        if (lost.isNotEmpty()) {
            warnings.addAll(createWarningsForLostBindings(lost))
        }

        return Triple(preserved, lost, warnings)
    }

    private fun createBindingCode(name: String, info: VariableInfo): String {
        // Simple binding restoration - could be enhanced based on type
        return if (info.isNull) {
            "$name = null"
        } else {
            // For complex objects, we might not be able to restore them perfectly
            when (info.type) {
                "java.lang.String" -> "$name = ${info.value}"
                "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float" -> "$name = ${info.value}"
                "java.lang.Boolean" -> "$name = ${info.value}"
                else -> {
                    // For complex types, create a placeholder
                    "$name = null // Original type: ${info.type}"
                }
            }
        }
    }

    private fun createWarningsForLostBindings(lostBindings: List<String>): List<String> = if (lostBindings.size <=
        MAX_BINDINGS_TO_SHOW
    ) {
        listOf("Could not restore bindings: ${lostBindings.joinToString()}")
    } else {
        listOf(
            "Could not restore ${lostBindings.size} bindings: ${lostBindings.take(
                MAX_BINDINGS_TO_SHOW,
            ).joinToString()}, ...",
        )
    }

    companion object {
        private const val MAX_BINDINGS_TO_SHOW = 3
    }
}
