// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.codehaus.groovy.ast.ImportNode

/** Resolves the class selected by the document's captured compile context into its exact source. */
class ClasspathResolutionStrategy(
    private val compilationService: GroovyCompilationService,
    private val sourceNavigator: SourceNavigator?,
) : SymbolResolutionStrategy {
    override suspend fun resolve(context: ResolutionContext): ResolutionResult {
        val className = getClassName(context.targetNode) ?: return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)
        val navigator =
            sourceNavigator ?: return SymbolResolutionStrategy.notFound("Source navigation unavailable", STRATEGY_NAME)
        val import = context.targetNode as? ImportNode
        val member = import?.takeIf { it.isStatic }?.fieldName
        val requestContext = currentCoroutineContext()
        val request = compilationService.captureSourceRequest(
            context.documentUri,
            className,
            member,
            checkCancelled = requestContext::ensureActive,
        )
            ?: return SymbolResolutionStrategy.notFound("Current dependency context unavailable", STRATEGY_NAME)
        return sourceResolution(navigator.locate(request.symbol, request.context), className, STRATEGY_NAME)
    }

    companion object {
        private const val STRATEGY_NAME = "Classpath"
    }
}
