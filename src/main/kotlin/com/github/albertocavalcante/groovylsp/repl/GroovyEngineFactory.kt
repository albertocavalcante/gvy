package com.github.albertocavalcante.groovylsp.repl

import groovy.lang.GroovyClassLoader
import org.slf4j.LoggerFactory

/**
 * Factory for creating Groovy REPL engines with proper configuration.
 */
@Suppress("TooGenericExceptionCaught") // REPL engine creation handles all initialization errors
object GroovyEngineFactory {

    private val logger = LoggerFactory.getLogger(GroovyEngineFactory::class.java)

    /**
     * Creates a new GroovyReplEngine instance with the specified configuration.
     */
    fun createReplEngine(
        classpath: List<String> = emptyList(),
        imports: List<String> = emptyList(),
        configuration: Map<String, Any> = emptyMap(),
    ): Any {
        try {
            // Load the GroovyReplEngine class dynamically
            val classLoader = createGroovyClassLoader(classpath)
            val engineClass = classLoader.loadClass("com.github.albertocavalcante.groovylsp.repl.GroovyReplEngine")

            // Create configuration object
            val configClass = engineClass.getClasses().find { it.simpleName == "ReplConfiguration" }
                ?: error("ReplConfiguration class not found")

            val configInstance = configClass.getDeclaredConstructor().newInstance()

            // Set configuration properties
            setConfigurationProperties(configInstance, configuration, imports)

            // Create the REPL engine
            val constructor = engineClass.getDeclaredConstructor(configClass)
            return constructor.newInstance(configInstance)
        } catch (e: Exception) {
            logger.error("Failed to create Groovy REPL engine", e)
            throw ReplException("Failed to create REPL engine: ${e.message}", e)
        }
    }

    private fun createGroovyClassLoader(classpath: List<String>): GroovyClassLoader {
        val urls = classpath.map { java.io.File(it).toURI().toURL() }.toTypedArray()
        val parentClassLoader = Thread.currentThread().contextClassLoader
        return GroovyClassLoader(parentClassLoader).apply {
            urls.forEach { addURL(it) }
        }
    }

    private fun setConfigurationProperties(
        configInstance: Any,
        configuration: Map<String, Any>,
        imports: List<String>,
    ) {
        val configClass = configInstance.javaClass

        // Set auto-imports
        try {
            val autoImportsField = configClass.getDeclaredField("autoImports")
            autoImportsField.isAccessible = true
            autoImportsField.set(configInstance, imports)
        } catch (e: Exception) {
            logger.warn("Failed to set autoImports", e)
        }

        // Set other configuration properties
        configuration.forEach { (key, value) ->
            try {
                val field = configClass.getDeclaredField(key)
                field.isAccessible = true
                field.set(configInstance, value)
            } catch (e: Exception) {
                logger.warn("Failed to set configuration property: $key", e)
            }
        }
    }
}
