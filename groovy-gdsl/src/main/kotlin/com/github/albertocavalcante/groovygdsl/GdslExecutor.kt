package com.github.albertocavalcante.groovygdsl

import groovy.lang.GroovyShell
import org.codehaus.groovy.control.CompilerConfiguration
import org.slf4j.LoggerFactory

/**
 * Executes GDSL scripts.
 */
class GdslExecutor {
    private val logger = LoggerFactory.getLogger(GdslExecutor::class.java)

    fun execute(scriptContent: String, scriptName: String = "script.gdsl") {
        try {
            val config = CompilerConfiguration()
            config.scriptBaseClass = GdslScript::class.java.name

            val shell = GroovyShell(this.javaClass.classLoader, config)
            shell.evaluate(scriptContent, scriptName)
            logger.info("Successfully executed GDSL script: $scriptName")
        } catch (e: Exception) {
            logger.error("Failed to execute GDSL script: $scriptName", e)
            throw e
        }
    }
}
