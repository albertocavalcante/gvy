package com.github.albertocavalcante.groovyjupyter.kernel.core

import com.github.albertocavalcante.groovyjupyter.handlers.ExecuteResult
import com.github.albertocavalcante.groovyjupyter.handlers.ExecuteStatus
import com.github.albertocavalcante.groovyrepl.GroovyExecutor

/**
 * Adapter that adapts [GroovyExecutor] to [KernelExecutor].
 */
class GroovyKernelExecutorAdapter(private val groovyExecutor: GroovyExecutor) : KernelExecutor {
    override fun execute(code: String): ExecuteResult {
        val result = groovyExecutor.execute(code)

        return ExecuteResult(
            status = if (result.isSuccess) ExecuteStatus.OK else ExecuteStatus.ERROR,
            result = result.value,
            stdout = result.stdout,
            stderr = result.stderr,
            errorName = result.errorType,
            errorValue = result.errorMessage,
            traceback = result.stackTrace,
        )
    }
}
