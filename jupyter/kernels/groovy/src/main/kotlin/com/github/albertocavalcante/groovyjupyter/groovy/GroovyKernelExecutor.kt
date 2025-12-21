package com.github.albertocavalcante.groovyjupyter.groovy

import com.github.albertocavalcante.groovyjupyter.handlers.ExecuteResult
import com.github.albertocavalcante.groovyjupyter.kernel.core.GroovyKernelExecutorAdapter
import com.github.albertocavalcante.groovyjupyter.kernel.core.KernelExecutor
import com.github.albertocavalcante.groovyrepl.GroovyExecutor
import org.slf4j.LoggerFactory

class GroovyKernelExecutor : KernelExecutor {
    private val logger = LoggerFactory.getLogger(GroovyKernelExecutor::class.java)
    private val adapter = GroovyKernelExecutorAdapter(GroovyExecutor())

    override fun execute(code: String): ExecuteResult {
        logger.debug("Delegating execution to GroovyExecutor")
        return adapter.execute(code)
    }
}
