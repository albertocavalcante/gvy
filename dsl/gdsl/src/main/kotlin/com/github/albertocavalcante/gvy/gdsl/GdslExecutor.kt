package com.github.albertocavalcante.gvy.gdsl

import com.github.albertocavalcante.gvy.gdsl.model.GdslParseResult
import groovy.lang.GroovyRuntimeException
import groovy.lang.GroovyShell
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.runtime.InvokerInvocationException

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
    private val logger = KotlinLogging.logger {}

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
                is CompilationFailedException -> logger.error(failure) { "Failed to compile GDSL script: $scriptName" }
                is InvokerInvocationException -> logger.error(failure) { "GDSL script threw an exception: $scriptName" }
                is GroovyRuntimeException -> logger.error(failure) { "Failed to execute GDSL script: $scriptName" }
                else -> logger.error(failure) { "Failed to execute GDSL script: $scriptName" }
            }

            throw failure
        }

        logger.info { "Successfully executed GDSL script: $scriptName" }
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
                logger.error { "GDSL script threw an exception: $scriptName" }
                return GdslParseResult.error(runFailure.message ?: "Script execution failed")
            }

            // Collect all contributions
            val methods = script.allMethods
            val properties = script.allProperties

            logger.info {
                "Successfully parsed GDSL script: $scriptName " +
                    "(${methods.size} methods, ${properties.size} properties)"
            }

            GdslParseResult(
                methods = methods,
                properties = properties,
                success = true,
            )
        } catch (e: CompilationFailedException) {
            logger.error(e) { "Failed to compile GDSL script: $scriptName" }
            GdslParseResult.error(e.message ?: "Compilation failed")
        } catch (e: InvokerInvocationException) {
            logger.error(e) { "GDSL script threw an exception: $scriptName" }
            GdslParseResult.error(e.message ?: "Script execution failed")
        } catch (e: GroovyRuntimeException) {
            logger.error(e) { "Failed to execute GDSL script: $scriptName" }
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
