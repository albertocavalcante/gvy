package com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.DiagnosticRuleConfig
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.StreamingDiagnosticProvider
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
 */
class CustomRulesProvider(
    private val rules: List<DiagnosticRule>,
    private val compilationService: GroovyCompilationService,
    private val ruleConfig: DiagnosticRuleConfig = DiagnosticRuleConfig(),
) : StreamingDiagnosticProvider {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val id: String = "custom-rules"

    override val enabledByDefault: Boolean = true

    override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> = flow {
        logger.debug { "Running ${rules.size} custom rules for: $uri" }

        // Create context for rules
        val context = createContext(uri)

        // Execute each enabled rule
        for (rule in rules) {
            if (!ruleConfig.isRuleEnabled(rule)) {
                logger.debug { "Skipping disabled rule: ${rule.id}" }
                continue
            }

            logger.debug { "Executing rule: ${rule.id}" }
            val diagnostics =
                runCatching { rule.analyze(uri, content, context) }
                    .onFailure { throwable ->
                        when (throwable) {
                            is CancellationException -> throw throwable
                            is Error -> throw throwable
                            else -> logger.error(throwable) { "Rule ${rule.id} failed for $uri" }
                        }
                    }
                    // Continue with other rules
                    .getOrDefault(emptyList())

            logger.debug { "Rule ${rule.id} found ${diagnostics.size} violations" }

            // Emit each diagnostic
            diagnostics.forEach { diagnostic ->
                emit(diagnostic)
            }
        }
    }

    private fun createContext(uri: URI): RuleContext = object : RuleContext {
        // Lazy AST retrieval - only fetch if rules need it
        private val astLazy: Any? by lazy {
            runCatching { compilationService.getAst(uri) }
                .onFailure { throwable ->
                    when (throwable) {
                        is CancellationException -> throw throwable
                        is Error -> throw throwable
                        else -> logger.debug(throwable) { "Failed to get AST for rule context" }
                    }
                }
                .getOrNull()
        }

        override fun getAst(): Any? = astLazy

        override fun hasErrors(): Boolean = runCatching {
            compilationService.getDiagnostics(uri).any {
                it.severity == DiagnosticSeverity.Error
            }
        }
            .onFailure { throwable ->
                when (throwable) {
                    is CancellationException -> throw throwable
                    is Error -> throw throwable
                    else -> logger.debug(throwable) { "Failed to check for errors" }
                }
            }
            // Assume errors if we can't check
            .getOrDefault(true)
    }
}
