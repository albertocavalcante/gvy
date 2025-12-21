package com.github.albertocavalcante.groovyjupyter.kernel.core

import com.github.albertocavalcante.groovyjupyter.handlers.ExecuteResult

/**
 * Interface for the kernel's execution engine.
 * Decouples the ZMQ message handling from the actual code execution (Groovy, Mock Jenkins, etc).
 */
interface KernelExecutor {
    /**
     * Executes the provided code and returns a result.
     */
    fun execute(code: String): ExecuteResult
}
