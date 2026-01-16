package com.github.albertocavalcante.groovylsp.providers.completion.strategy

import com.github.albertocavalcante.groovyjenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.declarative.DeclarativePipelineSchema
import com.github.albertocavalcante.groovylsp.config.GroovyMode
import com.github.albertocavalcante.groovylsp.dsl.completion.completions
import com.github.albertocavalcante.groovylsp.providers.completion.CursorPositionContext
import com.github.albertocavalcante.groovylsp.providers.completion.JenkinsCompletionProvider
import com.github.albertocavalcante.groovylsp.providers.completion.detectCursorPositionContext
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind

/**
 * Completion strategy for Jenkins Pipeline mode.
 *
 * Provides completions for:
 * - Jenkins steps (echo, sh, readFile, etc.)
 * - Global variables (env, params, currentBuild)
 * - Declarative options (timeout, retry)
 * - Post conditions (always, success, failure)
 * - Agent types (any, none, docker)
 * - Declarative directives (stages, steps, etc.)
 *
 * This strategy only activates when:
 * 1. Mode is explicitly JENKINS, OR
 * 2. Mode is AUTO and file is detected as Jenkins file
 */
internal class JenkinsCompletionStrategy : CompletionStrategy {

    override suspend fun complete(context: CompletionStrategyContext): CompletionResult {
        // Return early if not Jenkins mode
        if (context.mode == GroovyMode.GROOVY) {
            return CompletionStrategy.notApplicable("JenkinsCompletion")
        }
        if (context.mode == GroovyMode.AUTO && !context.isJenkinsFile) {
            return CompletionStrategy.notApplicable("JenkinsCompletion")
        }

        val metadata = context.jenkinsMetadata
            ?: return CompletionStrategy.notApplicable("JenkinsCompletion")

        val items = buildJenkinsCompletions(context, metadata)
        return CompletionStrategy.found(items)
    }

    private fun buildJenkinsCompletions(
        context: CompletionStrategyContext,
        metadata: MergedJenkinsMetadata,
    ): List<CompletionItem> {
        val blockContext = context.jenkinsBlockContext

        return completions {
            with(JenkinsCompletionProvider) {
                // Suggest parameter map keys for method calls
                addJenkinsMapKeyCompletions(
                    ctx = context.baseContext,
                    nodeAtCursor = context.nodeAtCursor,
                    astModel = context.baseContext.astModel,
                    metadata = metadata,
                )

                // Add Jenkins step completions if allowed by block context
                val allowSteps = blockContext == null ||
                    !blockContext.isStrictDeclarative ||
                    blockContext.blockCategories.contains(DeclarativePipelineSchema.CompletionCategory.STEP)

                if (allowSteps) {
                    addJenkinsStepCompletions(metadata)
                }

                // Add Jenkins global variables
                addJenkinsGlobalVariables(
                    metadata = metadata,
                    jenkinsCapabilities = context.baseContext.compilationService
                        .workspaceManager.getJenkinsCapabilities(),
                )

                // Only add block-level completions when cursor is at block level (not inside method call args)
                val cursorContext = detectCursorPositionContext(
                    context.nodeAtCursor,
                    context.baseContext.astModel,
                )
                val isBlockLevel = cursorContext is CursorPositionContext.BlockLevel

                // Add block-specific completions based on declarative context
                blockContext?.blockCategories?.let { categories ->
                    if (categories.contains(DeclarativePipelineSchema.CompletionCategory.AGENT_TYPE) &&
                        isBlockLevel
                    ) {
                        addJenkinsAgentTypeCompletions()
                    }
                    if (categories.contains(DeclarativePipelineSchema.CompletionCategory.DECLARATIVE_OPTION) &&
                        isBlockLevel
                    ) {
                        addJenkinsDeclarativeOptions(metadata)
                    }
                    if (categories.contains(DeclarativePipelineSchema.CompletionCategory.POST_CONDITION) &&
                        isBlockLevel
                    ) {
                        addJenkinsPostConditionCompletions()
                    }
                }

                // Add inner instructions (sub-blocks) from schema
                blockContext?.innerInstructions?.forEach { instruction ->
                    completion {
                        label(instruction)
                        kind(CompletionItemKind.Keyword)
                        detail("Declarative directive")
                        insertText("$instruction {")
                        sortText("0-directive-$instruction")
                    }
                }
            }
        }
    }
}
