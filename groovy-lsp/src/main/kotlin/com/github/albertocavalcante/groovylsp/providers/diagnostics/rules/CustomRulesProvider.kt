package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.DiagnosticRuleConfig
import com.github.albertocavalcante.groovylsp.providers.diagnostics.StreamingDiagnosticProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.eclipse.lsp4j.Diagnostic
import org.slf4j.LoggerFactory
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
        private val logger = LoggerFactory.getLogger(CustomRulesProvider::class.java)
    }

    override val id: String = "custom-rules"

    override val enabledByDefault: Boolean = true

    override suspend fun provideDiagnostics(uri: URI, content: String): Flow<Diagnostic> = flow {
        logger.debug("Running ${rules.size} custom rules for: $uri")

        // Create context for rules
        val context = createContext(uri)

        // Execute each enabled rule
        for (rule in rules) {
            if (!ruleConfig.isRuleEnabled(rule)) {
                logger.debug("Skipping disabled rule: ${rule.id}")
                continue
            }

            logger.debug("Executing rule: ${rule.id}")
            val diagnostics =
                runCatching { rule.analyze(uri, content, context) }
                    .onFailure { throwable ->
                        when (throwable) {
                            is CancellationException -> throw throwable
                            is Error -> throw throwable
                            else -> logger.error("Rule ${rule.id} failed for $uri", throwable)
                        }
                    }
                    // Continue with other rules
                    .getOrDefault(emptyList())

            logger.debug("Rule ${rule.id} found ${diagnostics.size} violations")

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
                        else -> logger.debug("Failed to get AST for rule context", throwable)
                    }
                }
                .getOrNull()
        }

        override fun getAst(): Any? = astLazy

        override fun hasErrors(): Boolean = runCatching {
            compilationService.getDiagnostics(uri).any {
                it.severity == org.eclipse.lsp4j.DiagnosticSeverity.Error
            }
        }
            .onFailure { throwable ->
                when (throwable) {
                    is CancellationException -> throw throwable
                    is Error -> throw throwable
                    else -> logger.debug("Failed to check for errors", throwable)
                }
            }
            // Assume errors if we can't check
            .getOrDefault(true)
    }
}
