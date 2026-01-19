package com.github.albertocavalcante.gvy.jupyter.groovy

import com.github.albertocavalcante.gvy.jupyter.core.handlers.ExecuteResult
import com.github.albertocavalcante.gvy.jupyter.core.kernel.core.GroovyKernelExecutorAdapter
import com.github.albertocavalcante.gvy.jupyter.core.kernel.core.KernelExecutor
import com.github.albertocavalcante.gvy.repl.GroovyExecutor
import io.github.oshai.kotlinlogging.KotlinLogging

class GroovyKernelExecutor : KernelExecutor {
    private val logger = KotlinLogging.logger {}
    private val adapter = GroovyKernelExecutorAdapter(GroovyExecutor())

    override fun execute(code: String): ExecuteResult {
        logger.debug { "Delegating execution to GroovyExecutor" }
        return adapter.execute(code)
    }
}
