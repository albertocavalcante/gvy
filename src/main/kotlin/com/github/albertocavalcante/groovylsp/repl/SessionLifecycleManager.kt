package com.github.albertocavalcante.groovylsp.repl

import com.github.albertocavalcante.groovylsp.compilation.WorkspaceCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages REPL session lifecycle operations including creation, cleanup, and timeout management.
 */
@Suppress("TooGenericExceptionCaught") // REPL session management handles all execution errors
class SessionLifecycleManager(private val coroutineScope: CoroutineScope) {

    private val logger = LoggerFactory.getLogger(SessionLifecycleManager::class.java)

    companion object {
        private const val SESSION_TIMEOUT_MINUTES = 60L
        private const val CLEANUP_INTERVAL_MINUTES = 15L
        private const val ACTIVE_SESSION_THRESHOLD_MINUTES = 5L
        private const val ESTIMATED_MEMORY_PER_BINDING_BYTES = 1024L
        private const val SECONDS_PER_MINUTE = 60
        private const val MILLISECONDS_PER_SECOND = 1000
        private const val RANDOM_RANGE_MAX = 999
    }

    /**
     * Starts automatic cleanup of inactive sessions.
     */
    fun startSessionCleanup(sessions: ConcurrentHashMap<String, ReplSession>) {
        coroutineScope.launch {
            while (isActive) {
                try {
                    cleanupInactiveSessions(sessions)
                    delay(CLEANUP_INTERVAL_MINUTES * SECONDS_PER_MINUTE * MILLISECONDS_PER_SECOND)
                } catch (e: Exception) {
                    logger.warn("Error during session cleanup", e)
                }
            }
        }
    }

    /**
     * Generates a unique session ID.
     */
    fun generateSessionId(): String = "repl-${System.currentTimeMillis()}-${(0..RANDOM_RANGE_MAX).random()}"

    /**
     * Creates a new REPL session with the given parameters.
     */
    suspend fun createSession(
        params: CreateSessionParams,
        @Suppress("UnusedParameter") workspaceService: WorkspaceCompilationService?,
    ): CreateSessionResult {
        val sessionId = params.sessionId ?: generateSessionId()
        val contextName = params.contextName ?: "default"
        val configuration = params.configuration ?: SessionConfiguration()

        logger.info("Creating REPL session: $sessionId with context: $contextName")

        val classpath = emptyList<String>() // Simplified for now
        val allImports = configuration.autoImports + params.imports

        val engine = GroovyInterop.createReplEngine(
            classpath = classpath,
            imports = allImports,
            configuration = mapOf(
                "executionTimeout" to configuration.executionTimeout.toMillis(),
                "maxMemory" to configuration.maxMemory,
                "sandboxing" to configuration.sandboxing,
                "historySize" to configuration.historySize,
            ),
        )

        val session = ReplSession(
            id = sessionId,
            contextName = contextName,
            engine = engine,
            createdAt = Instant.now(),
            lastUsed = Instant.now(),
            configuration = configuration,
        )

        val availableContexts = listOf("default") // Simplified for now
        val initialBindings = GroovyInterop.getBindings(engine).values.toList()

        return CreateSessionResult(
            sessionId = sessionId,
            contextName = contextName,
            availableContexts = availableContexts,
            configuration = configuration,
            initialBindings = initialBindings,
        )
    }

    /**
     * Removes expired sessions that haven't been used recently.
     */
    private fun cleanupInactiveSessions(sessions: ConcurrentHashMap<String, ReplSession>) {
        val now = Instant.now()
        val expiredSessions = sessions.values.filter {
            java.time.Duration.between(it.lastUsed, now).toMinutes() > SESSION_TIMEOUT_MINUTES
        }

        expiredSessions.forEach { session ->
            logger.info("Removing expired session: ${session.id}")
            sessions.remove(session.id)
        }

        if (expiredSessions.isNotEmpty()) {
            logger.info("Cleaned up ${expiredSessions.size} expired sessions")
        }
    }

    /**
     * Checks if a session is considered active (used recently).
     */
    fun isSessionActive(session: ReplSession): Boolean {
        val now = Instant.now()
        return java.time.Duration.between(session.lastUsed, now).toMinutes() <= ACTIVE_SESSION_THRESHOLD_MINUTES
    }

    /**
     * Estimates memory usage for a session based on its bindings.
     */
    fun estimateMemoryUsage(session: ReplSession): Long {
        val bindings = GroovyInterop.getBindings(session.engine)
        return bindings.size * ESTIMATED_MEMORY_PER_BINDING_BYTES
    }
}
