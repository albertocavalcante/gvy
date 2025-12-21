package com.github.albertocavalcante.groovyjupyter.execution

import groovy.lang.Binding
import groovy.lang.GroovyShell
import org.codehaus.groovy.control.CompilerConfiguration
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Result of executing Groovy code.
 *
 * @property isSuccess Whether execution completed without errors
 * @property value The return value of the last expression (null for void)
 * @property stdout Captured standard output
 * @property stderr Captured standard error
 * @property errorMessage Error message if execution failed
 * @property errorType Error type (e.g., class name) if execution failed
 * @property stackTrace Stack trace lines if execution failed
 */
data class ExecutionResult(
    val isSuccess: Boolean,
    val value: Any? = null,
    val stdout: String = "",
    val stderr: String = "",
    val errorMessage: String? = null,
    val errorType: String? = null,
    val stackTrace: List<String> = emptyList(),
)

/**
 * Groovy code executor for Jupyter kernel.
 *
 * Maintains state across cell executions like a REPL, capturing stdout/stderr
 * and returning structured results. Built on GroovyShell with shared bindings.
 *
 * Inspired by lappsgrid-incubator/jupyter-groovy-kernel (Apache 2.0).
 */
class GroovyExecutor {
    private val logger = LoggerFactory.getLogger(GroovyExecutor::class.java)
    private val binding = Binding()
    private val config = createCompilerConfiguration()
    private var shell = GroovyShell(javaClass.classLoader, binding, config)

    /**
     * Execute Groovy code and return the result.
     *
     * Variables defined without `def` or types are added to the binding
     * and persist across executions.
     *
     * @param code The Groovy code to execute
     * @return ExecutionResult with value, stdout, stderr, or error info
     */
    fun execute(code: String): ExecutionResult {
        val stdoutCapture = ByteArrayOutputStream()
        val stderrCapture = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err

        return try {
            // Redirect stdout/stderr
            System.setOut(PrintStream(stdoutCapture, true, "UTF-8"))
            System.setErr(PrintStream(stderrCapture, true, "UTF-8"))

            // Execute code
            val result = shell.evaluate(code)

            ExecutionResult(
                isSuccess = true,
                value = result,
                stdout = stdoutCapture.toString(),
                stderr = stderrCapture.toString(),
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.debug("Execution failed: ${e.message}")
            ExecutionResult(
                isSuccess = false,
                stdout = stdoutCapture.toString(),
                stderr = stderrCapture.toString(),
                errorMessage = e.message ?: e.toString(),
                errorType = e.javaClass.simpleName,
                stackTrace = e.stackTrace.take(MAX_STACK_FRAMES).map { it.toString() },
            )
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    /**
     * Reset the executor state, clearing all bindings.
     */
    fun reset() {
        binding.variables.clear()
        shell = GroovyShell(javaClass.classLoader, binding, config)
    }

    /**
     * Get current binding variables.
     */
    @Suppress("UNCHECKED_CAST")
    fun getBindings(): Map<String, Any?> = (binding.variables as Map<String, Any?>).toMap()

    private fun createCompilerConfiguration(): CompilerConfiguration = CompilerConfiguration().apply {
        sourceEncoding = "UTF-8"
    }

    companion object {
        private const val MAX_STACK_FRAMES = 20
    }
}
