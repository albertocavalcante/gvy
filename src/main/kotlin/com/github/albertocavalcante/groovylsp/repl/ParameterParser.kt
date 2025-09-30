package com.github.albertocavalcante.groovylsp.repl

import java.time.Duration

/**
 * Utility class for parsing LSP command parameters into REPL data structures.
 */
object ParameterParser {

    /**
     * Parses create session parameters from LSP command arguments.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseCreateSessionParams(arguments: List<Any>): CreateSessionParams {
        if (arguments.isEmpty()) return CreateSessionParams()

        val args = arguments[0] as Map<String, Any>
        return CreateSessionParams(
            sessionId = args["sessionId"] as String?,
            contextName = args["contextName"] as String?,
            imports = args["imports"] as List<String>? ?: emptyList(),
            configuration = args["configuration"]?.let { parseSessionConfiguration(it as Map<String, Any>) },
        )
    }

    /**
     * Parses evaluate parameters from LSP command arguments.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseEvaluateParams(arguments: List<Any>): EvaluateParams {
        require(arguments.isNotEmpty()) { "EvaluateParams required" }

        val args = arguments[0] as Map<String, Any>
        return EvaluateParams(
            sessionId = args["sessionId"] as String,
            code = args["code"] as String,
            async = args["async"] as Boolean? ?: false,
            includeBindings = args["includeBindings"] as Boolean? ?: false,
            captureOutput = args["captureOutput"] as Boolean? ?: true,
        )
    }

    /**
     * Parses completion parameters from LSP command arguments.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseCompletionParams(arguments: List<Any>): ReplCompletionParams {
        require(arguments.isNotEmpty()) { "ReplCompletionParams required" }

        val args = arguments[0] as Map<String, Any>
        return ReplCompletionParams(
            sessionId = args["sessionId"] as String,
            code = args["code"] as String,
            position = (args["position"] as Number).toInt(),
            includeBindings = args["includeBindings"] as Boolean? ?: true,
            includeWorkspace = args["includeWorkspace"] as Boolean? ?: true,
        )
    }

    /**
     * Parses inspect parameters from LSP command arguments.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseInspectParams(arguments: List<Any>): InspectParams {
        require(arguments.isNotEmpty()) { "InspectParams required" }

        val args = arguments[0] as Map<String, Any>
        return InspectParams(
            sessionId = args["sessionId"] as String,
            variableName = args["variableName"] as String,
            includeMetaClass = args["includeMetaClass"] as Boolean? ?: false,
            includeMethods = args["includeMethods"] as Boolean? ?: true,
            includeProperties = args["includeProperties"] as Boolean? ?: true,
        )
    }

    /**
     * Parses history parameters from LSP command arguments.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseHistoryParams(arguments: List<Any>): HistoryParams {
        if (arguments.isEmpty()) return HistoryParams("")

        val args = arguments[0] as Map<String, Any>
        return HistoryParams(
            sessionId = args["sessionId"] as String,
            limit = (args["limit"] as Number?)?.toInt() ?: 50,
            offset = (args["offset"] as Number?)?.toInt() ?: 0,
            includeResults = args["includeResults"] as Boolean? ?: false,
        )
    }

    /**
     * Parses reset parameters from LSP command arguments.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseResetParams(arguments: List<Any>): ResetParams {
        require(arguments.isNotEmpty()) { "ResetParams required" }

        val args = arguments[0] as Map<String, Any>
        return ResetParams(
            sessionId = args["sessionId"] as String,
            preserveImports = args["preserveImports"] as Boolean? ?: false,
            preserveHistory = args["preserveHistory"] as Boolean? ?: true,
        )
    }

    /**
     * Parses destroy parameters from LSP command arguments.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseDestroyParams(arguments: List<Any>): DestroyParams {
        require(arguments.isNotEmpty()) { "DestroyParams required" }

        val args = arguments[0] as Map<String, Any>
        return DestroyParams(
            sessionId = args["sessionId"] as String,
            saveHistory = args["saveHistory"] as Boolean? ?: false,
        )
    }

    /**
     * Parses switch context parameters from LSP command arguments.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseSwitchContextParams(arguments: List<Any>): SwitchContextParams {
        require(arguments.isNotEmpty()) { "SwitchContextParams required" }

        val args = arguments[0] as Map<String, Any>
        return SwitchContextParams(
            sessionId = args["sessionId"] as String,
            contextName = args["contextName"] as String,
            preserveBindings = args["preserveBindings"] as Boolean? ?: true,
        )
    }

    /**
     * Parses session configuration from a map.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseSessionConfiguration(configMap: Map<String, Any>): SessionConfiguration = SessionConfiguration(
        autoImports = configMap["autoImports"] as List<String>? ?: emptyList(),
        executionTimeout = Duration.ofMillis(
            (configMap["executionTimeout"] as Number?)?.toLong() ?: DEFAULT_EXECUTION_TIMEOUT_MS,
        ),
        maxMemory = configMap["maxMemory"] as String? ?: "512m",
        sandboxing = configMap["sandboxing"] as Boolean? ?: false,
        historySize = (configMap["historySize"] as Number?)?.toInt() ?: 1000,
    )

    private const val DEFAULT_EXECUTION_TIMEOUT_MS = 30_000L
}
