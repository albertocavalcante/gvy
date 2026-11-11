package com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.DiagnosticRuleConfig
import com.github.albertocavalcante.gvy.gls.config.GroovyMode
import com.github.albertocavalcante.gvy.gls.config.ModeResolver
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.StreamingDiagnosticProvider
import com.github.albertocavalcante.gvy.jenkins.JenkinsFileDetector
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.net.URI

/**
 * Provider that runs custom diagnostic rules on source code.
 *
 * This provider executes a set of configured diagnostic rules and streams
 * their results. Rules are executed sequentially to maintain diagnostic ordering
 * and simplify error handling.
 *
 * NOTE: Rules are stateless and should be safe to reuse across invocations.
 *
 * @param configuration Captured mode and file patterns for this provider cohort. Omitted by legacy callers that
 * intentionally resolve the live workspace configuration on each analysis.
 */
class CustomRulesProvider(
    private val rules: List<DiagnosticRule>,
    private val compilationService: GroovyCompilationService,
    private val ruleConfig: DiagnosticRuleConfig = DiagnosticRuleConfig(),
    configuration: ServerConfiguration? = null,
) : StreamingDiagnosticProvider {

    private val configuredMode = configuration?.groovyMode
    private val configuredJenkinsFiles = configuration?.let { JenkinsFileDetector(it.jenkinsConfig.filePatterns) }

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val id: String = "custom-rules"

    override val enabledByDefault: Boolean = true

    override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> =
        analyzeRules(uri, content) { createContext(uri) }

    override suspend fun provideDiagnostics(
        uri: URI,
        content: String,
        compilation: CompilationResult,
    ): Flow<Diagnostic> = analyzeRules(uri, content) { createContext(uri, compilation) }

    private fun analyzeRules(uri: URI, content: String, contextFactory: () -> RuleContext): Flow<Diagnostic> = flow {
        logger.debug { "Running ${rules.size} custom rules for: $uri" }
        val context = contextFactory()

        val failures = mutableListOf<CustomRuleFailure>()
        // Execute each enabled rule; ordinary failures do not discard healthy later emissions.
        for (rule in rules) {
            if (!ruleConfig.isRuleEnabled(rule)) {
                logger.debug { "Skipping disabled rule: ${rule.id}" }
                continue
            }

            logger.debug { "Executing rule: ${rule.id}" }
            val diagnostics =
                runCatching { rule.analyze(uri, content, context) }
                    .getOrElse { throwable ->
                        when (throwable) {
                            is CancellationException -> throw throwable
                            is Error -> throw throwable
                            is Exception -> {
                                failures.add(CustomRuleFailure(rule.id, throwable))
                                logger.error(throwable) { "Rule ${rule.id} failed for $uri" }
                                emptyList()
                            }
                            else -> throw throwable
                        }
                    }

            logger.debug { "Rule ${rule.id} found ${diagnostics.size} violations" }

            // Emit each diagnostic
            diagnostics.forEach { diagnostic ->
                emit(diagnostic)
            }
        }
        if (failures.isNotEmpty()) throw CustomRuleFailuresException(failures)
    }

    private fun createContext(uri: URI, compilation: CompilationResult? = null): RuleContext = object : RuleContext {
        // Lazy AST retrieval - only fetch if rules need it
        private val astLazy: Any? by lazy {
            if (compilation == null) {
                compilationService.getAst(uri)
            } else {
                check(compilation.ast != null || !compilation.isSuccess) {
                    "Completed compilation has no AST for custom rules at $uri"
                }
                compilation.ast
            }
        }

        override fun getAst(): Any? = astLazy

        override fun hasErrors(): Boolean = if (compilation == null) {
            compilationService.getDiagnostics(uri).any { it.severity == DiagnosticSeverity.Error }
        } else {
            !compilation.isSuccess || compilation.diagnostics.any { it.severity == DiagnosticSeverity.Error }
        }

        override fun isJenkinsFile(uri: URI): Boolean {
            configuredMode?.let { mode ->
                return when (mode) {
                    GroovyMode.GROOVY -> false
                    GroovyMode.JENKINS -> true
                    GroovyMode.AUTO -> checkNotNull(configuredJenkinsFiles).isJenkinsFile(uri)
                }
            }
            // Legacy callers without captured configuration retain their live workspace strategy.
            val configuration = compilationService.serverConfiguration
            val jenkinsCapabilities = compilationService.workspaceManager.getJenkinsCapabilities()
            // Without a Jenkins strategy (e.g. no workspace) AUTO mode still honours the configured patterns.
            if (jenkinsCapabilities == null && configuration.groovyMode == GroovyMode.AUTO) {
                return JenkinsFileDetector(configuration.jenkinsConfig.filePatterns).isJenkinsFile(uri)
            }
            return ModeResolver.fromConfig(configuration, jenkinsCapabilities).isJenkinsModeEnabled(uri)
        }
    }
}

/** An ordinary failed rule, retained in execution order with its original exception. */
internal data class CustomRuleFailure(val ruleId: String, val cause: Exception)

/** The healthy rule emissions precede this failure; the collector retains them with failed-provider metadata. */
internal class CustomRuleFailuresException(failures: List<CustomRuleFailure>) :
    Exception(
        "Custom diagnostic rules failed: " + failures.joinToString("; ") {
            "${it.ruleId}: ${it.cause.javaClass.name}: ${it.cause.message}"
        },
        failures.first().cause,
    ) {
    val failures: List<CustomRuleFailure> = failures.toList()

    init {
        failures.drop(1).forEach { addSuppressed(it.cause) }
    }
}
