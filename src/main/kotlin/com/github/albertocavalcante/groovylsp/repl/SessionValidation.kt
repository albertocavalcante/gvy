package com.github.albertocavalcante.groovylsp.repl

import java.util.concurrent.ConcurrentHashMap

/**
 * Utility object for validating REPL session operations.
 */
object SessionValidation {

    private const val MAX_SESSIONS = 10

    /**
     * Validates that a new session can be created.
     */
    fun validateSessionCreation(sessions: ConcurrentHashMap<String, ReplSession>) {
        if (sessions.size >= MAX_SESSIONS) {
            throw ReplException("Maximum number of sessions ($MAX_SESSIONS) reached")
        }
    }

    /**
     * Validates that a session exists and returns it.
     */
    fun validateSessionExists(sessions: ConcurrentHashMap<String, ReplSession>, sessionId: String): ReplSession =
        sessions[sessionId] ?: throw ReplException("Session not found: $sessionId")

    /**
     * Validates that a context name is available for switching.
     */
    fun validateContextName(contextName: String, availableContexts: List<String>): String {
        if (availableContexts.isNotEmpty() && contextName !in availableContexts) {
            throw ReplException("Context '$contextName' not found. Available: ${availableContexts.joinToString()}")
        }
        return contextName
    }

    /**
     * Validates that a session ID is unique.
     */
    fun validateUniqueSessionId(sessions: ConcurrentHashMap<String, ReplSession>, sessionId: String) {
        if (sessions.containsKey(sessionId)) {
            throw ReplException("Session with ID '$sessionId' already exists")
        }
    }
}
