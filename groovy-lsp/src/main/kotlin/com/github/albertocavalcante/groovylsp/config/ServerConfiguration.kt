package com.github.albertocavalcante.groovylsp.config

import com.github.albertocavalcante.groovyjenkins.JenkinsConfiguration
import org.eclipse.lsp4j.DiagnosticSeverity
import org.slf4j.LoggerFactory

/**
 * Configuration for the Groovy Language Server.
 */
data class ServerConfiguration(
    val compilationMode: CompilationMode = CompilationMode.WORKSPACE,
    val incrementalThreshold: Int = 50,
    val maxWorkspaceFiles: Int = 500,
    val maxNumberOfProblems: Int = 100,
    val javaHome: String? = null,
    val traceServer: TraceLevel = TraceLevel.OFF,
    val replEnabled: Boolean = true,
    val maxReplSessions: Int = 10,
    val replSessionTimeoutMinutes: Int = 60,

    // CodeNarc configuration
    val codeNarcEnabled: Boolean = true,
    val codeNarcPropertiesFile: String? = null,
    val codeNarcAutoDetect: Boolean = true,

    // TODO comment configuration
    val todoScanEnabled: Boolean = true,
    val todoPatterns: Map<String, DiagnosticSeverity> = mapOf(
        "TODO" to DiagnosticSeverity.Information,
        "FIXME" to DiagnosticSeverity.Warning,
        "XXX" to DiagnosticSeverity.Warning,
        "HACK" to DiagnosticSeverity.Hint,
        "NOTE" to DiagnosticSeverity.Information,
        "BUG" to DiagnosticSeverity.Error,
        "OPTIMIZE" to DiagnosticSeverity.Hint,
    ),
    val todoSemanticTokensEnabled: Boolean = true,

    // Jenkins configuration
    val jenkinsConfig: JenkinsConfiguration = JenkinsConfiguration(),
) {

    enum class CompilationMode {
        /**
         * Compile all workspace files together, enabling cross-file resolution.
         * Slower for large workspaces but provides better language features.
         */
        WORKSPACE,

        /**
         * Compile each file separately. Faster but no cross-file resolution.
         * Suitable for very large codebases or when performance is critical.
         */
        SINGLE_FILE,
    }

    enum class TraceLevel {
        OFF,
        MESSAGES,
        VERBOSE,
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ServerConfiguration::class.java)

        /**
         * Creates configuration from initialization options or configuration settings.
         */
        @Suppress("TooGenericExceptionCaught") // Config parsing handles all JSON conversion errors
        fun fromMap(map: Map<String, Any>?): ServerConfiguration {
            if (map == null) {
                logger.debug("No configuration provided, using defaults")
                return ServerConfiguration()
            }

            return try {
                ServerConfiguration(
                    compilationMode = parseCompilationMode(map),
                    incrementalThreshold = (map["groovy.compilation.incrementalThreshold"] as? Number)?.toInt() ?: 50,
                    maxWorkspaceFiles = (map["groovy.compilation.maxWorkspaceFiles"] as? Number)?.toInt() ?: 500,
                    maxNumberOfProblems = (map["groovy.server.maxNumberOfProblems"] as? Number)?.toInt() ?: 100,
                    javaHome = map["groovy.java.home"] as? String,
                    traceServer = parseTraceLevel(map),
                    replEnabled = (map["groovy.repl.enabled"] as? Boolean) ?: true,
                    maxReplSessions = (map["groovy.repl.maxSessions"] as? Number)?.toInt() ?: 10,
                    replSessionTimeoutMinutes = (map["groovy.repl.sessionTimeoutMinutes"] as? Number)?.toInt() ?: 60,

                    // CodeNarc configuration
                    codeNarcEnabled = (map["groovy.codenarc.enabled"] as? Boolean) ?: true,
                    codeNarcPropertiesFile = map["groovy.codenarc.propertiesFile"] as? String,
                    codeNarcAutoDetect = (map["groovy.codenarc.autoDetect"] as? Boolean) ?: true,

                    // Jenkins configuration
                    jenkinsConfig = JenkinsConfiguration.fromMap(map),
                )
            } catch (e: Exception) {
                logger.warn("Error parsing configuration, using defaults", e)
                ServerConfiguration()
            }
        }

        private fun parseCompilationMode(map: Map<String, Any>): CompilationMode {
            val modeString = map["groovy.compilation.mode"] as? String
            return when (modeString?.lowercase()) {
                "workspace" -> CompilationMode.WORKSPACE
                "single-file", "singlefile" -> CompilationMode.SINGLE_FILE
                null -> CompilationMode.WORKSPACE
                else -> {
                    logger.warn("Unknown compilation mode '$modeString', using workspace mode")
                    CompilationMode.WORKSPACE
                }
            }
        }

        private fun parseTraceLevel(map: Map<String, Any>): TraceLevel {
            val traceString = map["groovy.trace.server"] as? String
            return when (traceString?.lowercase()) {
                "off" -> TraceLevel.OFF
                "messages" -> TraceLevel.MESSAGES
                "verbose" -> TraceLevel.VERBOSE
                null -> TraceLevel.OFF
                else -> {
                    logger.warn("Unknown trace level '$traceString', using off")
                    TraceLevel.OFF
                }
            }
        }
    }

    /**
     * Returns true if workspace compilation should be enabled.
     */
    fun shouldUseWorkspaceCompilation(): Boolean = compilationMode == CompilationMode.WORKSPACE

    /**
     * Returns true if incremental compilation should be used for the given file count.
     */
    fun shouldUseIncrementalCompilation(fileCount: Int): Boolean = fileCount >= incrementalThreshold

    /**
     * Returns true if the workspace is too large for workspace compilation.
     */
    fun isWorkspaceTooLarge(fileCount: Int): Boolean = fileCount > maxWorkspaceFiles

    override fun toString(): String = "ServerConfiguration(mode=$compilationMode, incremental=$incrementalThreshold, " +
        "maxFiles=$maxWorkspaceFiles, maxProblems=$maxNumberOfProblems, repl=$replEnabled)"
}
