package com.github.albertocavalcante.groovylsp.repl

import com.github.albertocavalcante.groovylsp.compilation.WorkspaceCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages REPL sessions, providing session lifecycle management,
 * workspace integration, and resource cleanup.
 */
class ReplSessionManager(
    private val workspaceService: WorkspaceCompilationService,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val logger = LoggerFactory.getLogger(ReplSessionManager::class.java)
    private val sessions = ConcurrentHashMap<String, ReplSession>()
    private val sessionMutex = Mutex()
    private val lifecycleManager = SessionLifecycleManager(coroutineScope)
    private val contextSwitchHelper = ContextSwitchHelper(workspaceService)

    init {
        lifecycleManager.startSessionCleanup(sessions)
    }

    /**
     * Creates a new REPL session with the specified parameters.
     */
    suspend fun createSession(params: CreateSessionParams): CreateSessionResult = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            SessionValidation.validateSessionCreation(sessions)
            params.sessionId?.let { SessionValidation.validateUniqueSessionId(sessions, it) }

            val result = lifecycleManager.createSession(params, workspaceService)
            sessions[result.sessionId] = ReplSession(
                id = result.sessionId,
                contextName = result.contextName,
                engine = GroovyInterop.createReplEngine(emptyList(), emptyList(), emptyMap()),
                createdAt = java.time.Instant.now(),
                lastUsed = java.time.Instant.now(),
                configuration = result.configuration,
            )
            result
        }
    }

    /**
     * Evaluates code in the specified session.
     */
    suspend fun evaluateCode(sessionId: String, code: String): ReplResult = withContext(Dispatchers.IO) {
        val session = SessionValidation.validateSessionExists(sessions, sessionId)
        session.updateLastUsed()
        GroovyInterop.evaluateCode(session.engine, code)
    }

    /**
     * Gets completions for the specified session.
     */
    suspend fun getCompletions(sessionId: String, code: String, position: Int): List<String> =
        withContext(Dispatchers.IO) {
            val session = SessionValidation.validateSessionExists(sessions, sessionId)
            session.updateLastUsed()

            val partial = extractPartialTextAtPosition(code, position)
            GroovyInterop.getCompletions(session.engine, partial)
        }

    /**
     * Gets type information for a variable in the session.
     */
    suspend fun getTypeInfo(sessionId: String, variableName: String): TypeInfo? = withContext(Dispatchers.IO) {
        val session = SessionValidation.validateSessionExists(sessions, sessionId)
        session.updateLastUsed()
        GroovyInterop.getTypeInfo(session.engine, variableName)
    }

    /**
     * Gets command history for the session.
     */
    suspend fun getHistory(sessionId: String, limit: Int = 50): List<String> = withContext(Dispatchers.IO) {
        val session = SessionValidation.validateSessionExists(sessions, sessionId)
        session.updateLastUsed()
        GroovyInterop.getHistory(session.engine, limit)
    }

    /**
     * Resets the specified session.
     */
    suspend fun resetSession(sessionId: String): Unit = withContext(Dispatchers.IO) {
        val session = SessionValidation.validateSessionExists(sessions, sessionId)
        session.updateLastUsed()
        GroovyInterop.resetEngine(session.engine)
    }

    /**
     * Destroys the specified session and releases resources.
     */
    suspend fun destroySession(sessionId: String): Unit = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            sessions.remove(sessionId)
        }
    }

    /**
     * Lists all active sessions.
     */
    suspend fun listSessions(): ListSessionsResult = withContext(Dispatchers.IO) {
        val sessionInfos = sessions.values.map { session ->
            val bindings = GroovyInterop.getBindings(session.engine)
            SessionInfo(
                sessionId = session.id,
                contextName = session.contextName,
                createdAt = session.createdAt.toString(),
                lastUsed = session.lastUsed.toString(),
                variableCount = bindings.size,
                memoryUsage = lifecycleManager.estimateMemoryUsage(session),
                isActive = lifecycleManager.isSessionActive(session),
            )
        }
        ListSessionsResult(sessionInfos)
    }

    /**
     * Switches the compilation context for a session.
     */
    suspend fun switchContext(
        sessionId: String,
        contextName: String,
        preserveBindings: Boolean = true,
    ): SwitchContextResult = withContext(Dispatchers.IO) {
        val session = SessionValidation.validateSessionExists(sessions, sessionId)
        val result = contextSwitchHelper.switchContext(session, contextName, preserveBindings)

        // Update session in our map if switch was successful
        if (result.success) {
            sessions[sessionId] = session // The helper updates the session internally
        }

        result
    }

    /**
     * Extracts partial text at the given cursor position for completions.
     */
    private fun extractPartialTextAtPosition(code: String, position: Int): String {
        if (position > code.length) return ""

        val beforeCursor = code.substring(0, position)
        val lastSpace = beforeCursor.lastIndexOfAny(charArrayOf(' ', '\t', '\n', '(', '[', '{'))
        return if (lastSpace >= 0) beforeCursor.substring(lastSpace + 1) else beforeCursor
    }

    /**
     * Shuts down the session manager and cleans up resources.
     */
    fun shutdown() {
        sessions.clear()
        logger.info("REPL session manager shut down")
    }
}
