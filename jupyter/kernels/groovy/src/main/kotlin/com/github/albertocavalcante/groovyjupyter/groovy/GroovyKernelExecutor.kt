package com.github.albertocavalcante.groovyjupyter.groovy

import com.github.albertocavalcante.groovyjupyter.handlers.ExecuteResult
import com.github.albertocavalcante.groovyjupyter.kernel.core.GroovyKernelExecutorAdapter
import com.github.albertocavalcante.groovyjupyter.kernel.core.KernelExecutor
import com.github.albertocavalcante.groovyrepl.GroovyExecutor
import io.github.oshai.kotlinlogging.KotlinLogging

class GroovyKernelExecutor : KernelExecutor {
    private val logger = KotlinLogging.logger {}
    private val adapter = GroovyKernelExecutorAdapter(GroovyExecutor())

    override fun execute(code: String): ExecuteResult {
        logger.debug { "Delegating execution to GroovyExecutor" }
        return adapter.execute(code)
    }
}
