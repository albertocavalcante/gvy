package com.github.albertocavalcante.groovygdsl

import com.github.albertocavalcante.groovygdsl.model.GdslParseResult
import groovy.lang.GroovyShell
import org.codehaus.groovy.control.CompilerConfiguration
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
        try {
            val shell = createGroovyShell()
            shell.evaluate(scriptContent, scriptName)
            logger.info("Successfully executed GDSL script: $scriptName")
        } catch (e: Exception) {
            logger.error("Failed to execute GDSL script: $scriptName", e)
            throw e
        }
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
    fun executeAndCapture(scriptContent: String, scriptName: String = "script.gdsl"): GdslParseResult = try {
        val shell = createGroovyShell()
        val script = shell.parse(scriptContent, scriptName) as GdslScript

        // Execute the script to trigger contributor() calls
        script.run()

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
    } catch (e: Exception) {
        logger.error("Failed to parse GDSL script: $scriptName", e)
        GdslParseResult.error(e.message ?: "Unknown error")
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
