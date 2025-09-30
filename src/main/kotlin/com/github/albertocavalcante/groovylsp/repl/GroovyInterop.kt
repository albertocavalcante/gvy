package com.github.albertocavalcante.groovylsp.repl

import org.slf4j.LoggerFactory

/**
 * Kotlin-Groovy interoperability layer for REPL operations.
 * This object provides the main API for interacting with Groovy REPL engines.
 */
@Suppress("TooGenericExceptionCaught") // REPL interop must handle all Groovy engine errors gracefully
object GroovyInterop {

    private val logger = LoggerFactory.getLogger(GroovyInterop::class.java)

    /**
     * Creates a new GroovyReplEngine instance with the specified configuration.
     */
    fun createReplEngine(
        classpath: List<String> = emptyList(),
        imports: List<String> = emptyList(),
        configuration: Map<String, Any> = emptyMap(),
    ): Any = GroovyEngineFactory.createReplEngine(classpath, imports, configuration)

    /**
     * Evaluates code in the given Groovy REPL engine.
     */
    fun evaluateCode(engine: Any, code: String): ReplResult {
        try {
            val evaluateMethod = engine.javaClass.getMethod("evaluate", String::class.java)
            val groovyResult = evaluateMethod.invoke(engine, code)
            return GroovyResultConverter.convertGroovyResult(groovyResult)
        } catch (e: Exception) {
            logger.error("Failed to evaluate code in REPL engine", e)
            throw ReplException("Failed to evaluate code: ${e.message}", e)
        }
    }

    /**
     * Gets current variable bindings from the REPL engine.
     */
    fun getBindings(engine: Any): Map<String, VariableInfo> {
        try {
            val getBindingsMethod = engine.javaClass.getMethod("getCurrentBindings")
            val groovyBindings = getBindingsMethod.invoke(engine) as Map<*, *>
            return GroovyResultConverter.convertBindings(groovyBindings)
        } catch (e: Exception) {
            logger.error("Failed to get bindings from REPL engine", e)
            return emptyMap()
        }
    }

    /**
     * Gets completion candidates from the REPL engine.
     *
     * @param engine The Groovy REPL engine instance
     * @param partial The partial input to complete
     * @return List of completion candidates
     */
    fun getCompletions(engine: Any, partial: String): List<String> {
        try {
            val getCompletionsMethod = engine.javaClass.getMethod("getCompletionCandidates", String::class.java)
            val result = getCompletionsMethod.invoke(engine, partial)
            @Suppress("UNCHECKED_CAST")
            return result as List<String>
        } catch (e: Exception) {
            logger.error("Failed to get completions from REPL engine", e)
            return emptyList()
        }
    }

    /**
     * Gets type information for a variable.
     */
    fun getTypeInfo(engine: Any, variableName: String): TypeInfo? {
        try {
            val getTypeInfoMethod = engine.javaClass.getMethod("getTypeInfo", String::class.java)
            val groovyTypeInfo = getTypeInfoMethod.invoke(engine, variableName)
            return if (groovyTypeInfo != null) GroovyResultConverter.convertTypeInfo(groovyTypeInfo) else null
        } catch (e: Exception) {
            logger.error("Failed to get type info from REPL engine", e)
            return null
        }
    }

    /**
     * Gets command history from the REPL engine.
     *
     * @param engine The Groovy REPL engine instance
     * @param limit Maximum number of history entries to return
     * @return List of command history entries
     */
    fun getHistory(engine: Any, limit: Int = 50): List<String> {
        try {
            val getHistoryMethod = engine.javaClass.getMethod("getHistory", Int::class.java)
            val result = getHistoryMethod.invoke(engine, limit)
            @Suppress("UNCHECKED_CAST")
            return result as List<String>
        } catch (e: Exception) {
            logger.error("Failed to get history from REPL engine", e)
            return emptyList()
        }
    }

    /**
     * Resets the REPL engine state.
     *
     * @param engine The Groovy REPL engine instance
     */
    fun resetEngine(engine: Any) {
        try {
            val resetMethod = engine.javaClass.getMethod("resetState")
            resetMethod.invoke(engine)
        } catch (e: Exception) {
            logger.error("Failed to reset REPL engine", e)
            throw ReplException("Failed to reset engine: ${e.message}", e)
        }
    }

    /**
     * Adds an import to the REPL engine.
     *
     * @param engine The Groovy REPL engine instance
     * @param importStatement The import statement to add
     */
    fun addImport(engine: Any, importStatement: String) {
        try {
            val addImportMethod = engine.javaClass.getMethod("addImport", String::class.java)
            addImportMethod.invoke(engine, importStatement)
        } catch (e: Exception) {
            logger.error("Failed to add import to REPL engine", e)
            // Don't throw - imports can fail silently
        }
    }
}

/**
 * Exception thrown when REPL operations fail.
 */
class ReplException(message: String, cause: Throwable? = null) : Exception(message, cause)
