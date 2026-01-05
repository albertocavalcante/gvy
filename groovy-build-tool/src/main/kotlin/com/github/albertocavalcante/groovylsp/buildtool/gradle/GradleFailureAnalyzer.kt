package com.github.albertocavalcante.groovylsp.buildtool.gradle

class GradleFailureAnalyzer {

    /**
     * Specifically detects the "Unsupported class file major version" error which indicates
     * a JDK/Gradle version mismatch.
     */
    fun isJdkMismatch(t: Throwable): Boolean = searchExceptionChain(t) { message ->
        message.contains("Unsupported class file major version", ignoreCase = true)
    }

    /**
     * Detects errors related to init scripts (init.d, cp_init, etc).
     */
    fun isInitScriptError(t: Throwable): Boolean {
        // IMPORTANT: We MUST NOT classify JDK mismatch as init script error,
        // because we don't want to retry with isolated Gradle User Home for JDK issues.
        if (isJdkMismatch(t)) return false

        return searchExceptionChain(t) { message ->
            message.contains("init.d", ignoreCase = true) ||
                message.contains("init script", ignoreCase = true) ||
                message.contains("cp_init", ignoreCase = true)
        }
    }

    /**
     * Detects transient errors that might benefit from a simple retry (e.g. file locks).
     */
    fun isTransient(t: Throwable): Boolean = searchExceptionChain(t) { message ->
        message.contains("waiting to lock", ignoreCase = true) ||
            message.contains("waiting for lock", ignoreCase = true) ||
            message.contains("Could not open build receipt cache", ignoreCase = true) ||
            message.contains("Connection refused", ignoreCase = true)
    }

    private fun searchExceptionChain(t: Throwable, predicate: (String) -> Boolean): Boolean {
        var current: Throwable? = t
        while (current != null) {
            val message = current.message
            if (message != null && predicate(message)) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
