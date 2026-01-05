package com.github.albertocavalcante.groovylsp.config

import com.github.albertocavalcante.groovyjenkins.JenkinsConfiguration
import com.github.albertocavalcante.groovylsp.buildtool.GradleBuildStrategy
import com.github.albertocavalcante.groovylsp.engine.config.EngineType
import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.DiagnosticAnalysisType
import org.eclipse.lsp4j.DiagnosticSeverity
import org.slf4j.LoggerFactory

/**
 * Configuration for the Groovy Language Server.
 */
data class ServerConfiguration(
    val compilationMode: CompilationMode = CompilationMode.WORKSPACE,
    val parserEngine: EngineType = EngineType.Core,
    val incrementalThreshold: Int = 50,
    val maxWorkspaceFiles: Int = 500,
    val maxNumberOfProblems: Int = 100,
    val javaHome: String? = null,
    val groovyLanguageVersion: String? = null,
    val logLevel: LogLevel = LogLevel.INFO,
    val traceServer: TraceLevel = TraceLevel.OFF,
    val replEnabled: Boolean = true,
    val maxReplSessions: Int = 10,
    val replSessionTimeoutMinutes: Int = 60,
    val workerDescriptors: List<WorkerDescriptorConfig> = emptyList(),

    // CodeNarc configuration
    val codeNarcEnabled: Boolean = true,
    val codeNarcPropertiesFile: String? = null,
    val codeNarcAutoDetect: Boolean = true,

    // Diagnostics configuration
    val diagnosticConfig: DiagnosticConfig = DiagnosticConfig(),
    val diagnosticRuleConfig: DiagnosticRuleConfig = DiagnosticRuleConfig(),

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

    // Gradle Build Strategy - controls how Gradle projects resolve dependencies
    // See: https://devblogs.microsoft.com/java/new-build-server-for-gradle/
    val gradleBuildStrategy: GradleBuildStrategy = GradleBuildStrategy.AUTO,
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

    /**
     * Log level for server output.
     */
    enum class LogLevel {
        ERROR,
        WARN,
        INFO,
        DEBUG,
        TRACE,
        ;

        companion object {
            fun fromString(value: String?): LogLevel = when (value?.lowercase()) {
                "error" -> ERROR
                "warn" -> WARN
                "debug" -> DEBUG
                "trace" -> TRACE
                else -> INFO
            }
        }
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
                    parserEngine = EngineType.fromString(map["groovy.languageServer.engine"] as? String),
                    incrementalThreshold = (map["groovy.compilation.incrementalThreshold"] as? Number)?.toInt() ?: 50,
                    maxWorkspaceFiles = (map["groovy.compilation.maxWorkspaceFiles"] as? Number)?.toInt() ?: 500,
                    maxNumberOfProblems = (map["groovy.server.maxNumberOfProblems"] as? Number)?.toInt() ?: 100,
                    javaHome = map["groovy.java.home"] as? String,
                    groovyLanguageVersion = map["groovy.language.version"] as? String,
                    logLevel = parseLogLevel(map),
                    traceServer = parseTraceLevel(map),
                    replEnabled = (map["groovy.repl.enabled"] as? Boolean) ?: true,
                    maxReplSessions = (map["groovy.repl.maxSessions"] as? Number)?.toInt() ?: 10,
                    replSessionTimeoutMinutes = (map["groovy.repl.sessionTimeoutMinutes"] as? Number)?.toInt() ?: 60,
                    workerDescriptors = parseWorkerDescriptors(map),

                    // CodeNarc configuration
                    codeNarcEnabled = (map["groovy.codenarc.enabled"] as? Boolean) ?: true,
                    codeNarcPropertiesFile = map["groovy.codenarc.propertiesFile"] as? String,
                    codeNarcAutoDetect = (map["groovy.codenarc.autoDetect"] as? Boolean) ?: true,

                    // Diagnostics configuration
                    diagnosticConfig = DiagnosticConfig(
                        enabledProviders = parseStringSet(map["groovy.diagnostics.providers.enabled"]),
                        disabledProviders = parseStringSet(map["groovy.diagnostics.providers.disabled"]),
                    ),
                    diagnosticRuleConfig = DiagnosticRuleConfig(
                        enabledRuleIds = parseStringSet(map["groovy.diagnostics.rules.enabled"]),
                        disabledRuleIds = parseStringSet(map["groovy.diagnostics.rules.disabled"]),
                        enabledAnalysisTypes = parseAnalysisTypes(
                            map["groovy.diagnostics.rules.analysisTypes.enabled"],
                        ),
                        disabledAnalysisTypes = parseAnalysisTypes(
                            map["groovy.diagnostics.rules.analysisTypes.disabled"],
                        ),
                    ),

                    // Jenkins configuration
                    jenkinsConfig = JenkinsConfiguration.fromMap(map),

                    // Gradle build strategy
                    gradleBuildStrategy = GradleBuildStrategy.fromString(
                        map["groovy.gradle.buildStrategy"] as? String,
                    ),
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

        private fun parseLogLevel(map: Map<String, Any>): LogLevel {
            val rawValue = map["groovy.server.logLevel"]
            val logLevelString = rawValue as? String
            // Log at INFO level so this is ALWAYS visible before level is changed
            logger.info(
                "Parsing logLevel from initOptions: raw='{}', type={}, parsed={}",
                rawValue,
                rawValue?.javaClass?.simpleName ?: "null",
                logLevelString,
            )
            val parsed = LogLevel.fromString(logLevelString)
            logger.info("LogLevel parsed as: {}", parsed.name)
            return parsed
        }

        private fun parseWorkerDescriptors(map: Map<String, Any>): List<WorkerDescriptorConfig> {
            val rawWorkers = map["groovy.workers"] as? List<*> ?: return emptyList()
            return rawWorkers.mapNotNull { entry ->
                val entryMap = entry as? Map<*, *> ?: run {
                    logger.warn("Invalid worker descriptor entry: expected map, got {}", entry?.javaClass?.simpleName)
                    return@mapNotNull null
                }
                val id = entryMap["id"] as? String
                val minVersion = entryMap["minVersion"] as? String
                if (id.isNullOrBlank() || minVersion.isNullOrBlank()) {
                    logger.warn("Invalid worker descriptor entry: missing id or minVersion")
                    return@mapNotNull null
                }
                val maxVersion = entryMap["maxVersion"] as? String
                val features = parseWorkerFeatures(entryMap["features"])
                val connector = entryMap["connector"] as? String ?: "in-process"
                WorkerDescriptorConfig(
                    id = id,
                    minVersion = minVersion,
                    maxVersion = maxVersion,
                    features = features,
                    connector = connector,
                )
            }
        }

        private fun parseWorkerFeatures(raw: Any?): Set<String> = when (raw) {
            is List<*> -> raw.filterIsInstance<String>().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            is String -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            else -> emptySet()
        }

        private fun parseStringSet(raw: Any?): Set<String> = when (raw) {
            is List<*> -> raw.filterIsInstance<String>().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            is String -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            else -> emptySet()
        }

        private fun parseAnalysisTypes(raw: Any?): Set<DiagnosticAnalysisType> =
            parseStringSet(raw).mapNotNull { value ->
                DiagnosticAnalysisType.values().firstOrNull { it.name.equals(value, ignoreCase = true) }
            }.toSet()

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
        "maxFiles=$maxWorkspaceFiles, maxProblems=$maxNumberOfProblems, " +
        "languageVersion=$groovyLanguageVersion, repl=$replEnabled)"
}
