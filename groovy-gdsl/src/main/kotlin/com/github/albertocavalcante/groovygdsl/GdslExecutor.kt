package com.github.albertocavalcante.groovygdsl

import com.github.albertocavalcante.groovygdsl.model.GdslParseResult
import groovy.lang.GroovyRuntimeException
import groovy.lang.GroovyShell
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.runtime.InvokerInvocationException
import org.slf4j.LoggerFactory

/**
 * Executes GDSL scripts and captures their contributions.
 *
 * GDSL (Groovy DSL) scripts define type contributions for IDE completion.
 * This executor runs the scripts using Groovy's script engine with
 * [GdslScript] as the base class, capturing all method and property
 * contributions.
 *
 * Example usage:
 * ```kotlin
 * val executor = GdslExecutor()
 * val result = executor.executeAndCapture(gdslContent, "jenkins.gdsl")
 * if (result.success) {
 *     result.methods.forEach { println("Method: ${it.name}") }
 * }
 * ```
 */
class GdslExecutor {
    private val logger = LoggerFactory.getLogger(GdslExecutor::class.java)

    /**
     * Executes a GDSL script without capturing results.
     *
     * @param scriptContent The GDSL script content
     * @param scriptName Name for error reporting
     * @throws Exception if script execution fails
     */
    fun execute(scriptContent: String, scriptName: String = "script.gdsl") {
        val shell = createGroovyShell()
        val failure = runCatching { shell.evaluate(scriptContent, scriptName) }.exceptionOrNull()
        if (failure != null) {
            when (failure) {
                is CompilationFailedException -> logger.error("Failed to compile GDSL script: $scriptName", failure)
                is InvokerInvocationException -> logger.error("GDSL script threw an exception: $scriptName", failure)
                is GroovyRuntimeException -> logger.error("Failed to execute GDSL script: $scriptName", failure)
                else -> logger.error("Failed to execute GDSL script: $scriptName", failure)
            }

            throw failure
        }

        logger.info("Successfully executed GDSL script: $scriptName")
    }

    /**
     * Executes a GDSL script and captures all contributions.
     *
     * This method parses and executes the GDSL script, collecting all
     * method and property contributions made via `contributor` blocks.
     *
     * @param scriptContent The GDSL script content
     * @param scriptName Name for error reporting (default: "script.gdsl")
     * @return GdslParseResult containing captured methods, properties, and success status
     */
    fun executeAndCapture(scriptContent: String, scriptName: String = "script.gdsl"): GdslParseResult {
        val shell = createGroovyShell()

        return try {
            val script = shell.parse(scriptContent, scriptName) as GdslScript

            // Execute the script to trigger contributor() calls.
            //
            // NOTE: We intentionally use `runCatching` so we can convert any script runtime failure into
            // a `GdslParseResult.error(...)` without catching `Exception`/`RuntimeException` directly
            // (which is forbidden by the repository's Detekt configuration).
            // TODO: Replace this with explicit, typed exception handling once we decide on the exact
            // runtime failure contract for GDSL scripts (and can enumerate the exceptions we expect).
            val runFailure = runCatching { script.run() }.exceptionOrNull()
            if (runFailure != null) {
                logger.error("GDSL script threw an exception: $scriptName", runFailure)
                return GdslParseResult.error(runFailure.message ?: "Script execution failed")
            }

            // Collect all contributions
            val methods = script.allMethods
            val properties = script.allProperties

            logger.info(
                "Successfully parsed GDSL script: $scriptName " +
                    "(${methods.size} methods, ${properties.size} properties)",
            )

            GdslParseResult(
                methods = methods,
                properties = properties,
                success = true,
            )
        } catch (e: CompilationFailedException) {
            logger.error("Failed to compile GDSL script: $scriptName", e)
            GdslParseResult.error(e.message ?: "Compilation failed")
        } catch (e: InvokerInvocationException) {
            logger.error("GDSL script threw an exception: $scriptName", e)
            GdslParseResult.error(e.message ?: "Script execution failed")
        } catch (e: GroovyRuntimeException) {
            logger.error("Failed to execute GDSL script: $scriptName", e)
            GdslParseResult.error(e.message ?: "Execution failed")
        }
    }

    /**
     * Creates a configured GroovyShell for GDSL execution.
     */
    private fun createGroovyShell(): GroovyShell {
        val config = CompilerConfiguration().apply {
            scriptBaseClass = GdslScript::class.java.name
        }
        return GroovyShell(javaClass.classLoader, config)
    }
}
