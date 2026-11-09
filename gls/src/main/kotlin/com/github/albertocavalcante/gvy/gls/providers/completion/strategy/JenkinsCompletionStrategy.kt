package com.github.albertocavalcante.gvy.gls.providers.completion.strategy

import com.github.albertocavalcante.gvy.gls.config.GroovyMode
import com.github.albertocavalcante.gvy.gls.dsl.completion.CompletionsBuilder
import com.github.albertocavalcante.gvy.gls.dsl.completion.completions
import com.github.albertocavalcante.gvy.gls.providers.completion.CursorPositionContext
import com.github.albertocavalcante.gvy.gls.providers.completion.JenkinsCompletionProvider
import com.github.albertocavalcante.gvy.gls.providers.completion.detectCursorPositionContext
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.declarative.DeclarativePipelineSchema
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

                // Only add block-level completions when cursor is at block level (not inside method call args)
                val cursorContext = detectCursorPositionContext(
                    context.nodeAtCursor,
                    context.baseContext.astModel,
                )
                val isBlockLevel = cursorContext is CursorPositionContext.BlockLevel

                // Global variables cannot start a statement in structural declarative blocks
                // (pipeline, agent, stage, post, options, ...): offering them there is noise and
                // duplicates agent types such as `docker`. Arguments and Groovy blocks keep them.
                if (!(isBlockLevel && blockContext.isStructuralDeclarativeBlock())) {
                    addJenkinsGlobalVariables(
                        metadata = metadata,
                        jenkinsCapabilities = context.baseContext.compilationService
                            .workspaceManager.getJenkinsCapabilities(),
                    )
                }

                if (isBlockLevel && blockContext != null) {
                    addDeclarativeBlockCompletions(blockContext, metadata)
                }
            }
        }
    }

    /** Agent types, options, post conditions and sub-directives for the declarative block at the cursor. */
    private fun CompletionsBuilder.addDeclarativeBlockCompletions(
        blockContext: JenkinsBlockContext,
        metadata: MergedJenkinsMetadata,
    ) = with(JenkinsCompletionProvider) {
        val categories = blockContext.blockCategories
        if (DeclarativePipelineSchema.CompletionCategory.AGENT_TYPE in categories) {
            addJenkinsAgentTypeCompletions()
        }
        if (DeclarativePipelineSchema.CompletionCategory.DECLARATIVE_OPTION in categories) {
            addJenkinsDeclarativeOptions(metadata)
        }
        if (DeclarativePipelineSchema.CompletionCategory.POST_CONDITION in categories) {
            addJenkinsPostConditionCompletions()
        }
        blockContext.innerInstructions.forEach { instruction ->
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

/**
 * A strict declarative block whose content is fixed by the schema: it has sub-directives or a
 * non-step completion category, and does not accept steps. `environment`, `expression {}` and
 * other free-form Groovy blocks are not structural.
 */
private fun JenkinsBlockContext?.isStructuralDeclarativeBlock(): Boolean {
    if (this == null || !isStrictDeclarative) return false
    if (DeclarativePipelineSchema.CompletionCategory.STEP in blockCategories) return false
    return innerInstructions.isNotEmpty() || blockCategories.isNotEmpty()
}
