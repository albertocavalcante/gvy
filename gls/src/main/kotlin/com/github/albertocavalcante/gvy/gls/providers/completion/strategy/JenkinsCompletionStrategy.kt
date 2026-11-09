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
                    blockContext.blockCategories.contains(DeclarativePipelineSchema.CompletionCategory.STEP) ||
                    blockContext.isInsideGroovyBody()

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

                // Directives belong to the innermost declarative block only: `when { expression { | } }`
                // and a scripted `stage('x') {}` inside `script {}` are Groovy, not places for
                // `branch` or the `parallel` directive (which would duplicate the `parallel` step).
                blockContext?.takeIf { isBlockLevel && it.acceptsDeclarativeDirectives() }?.let {
                    addDeclarativeBlockCompletions(it, metadata)
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
 * Declarative blocks whose statements are fixed directives, sections or conditions, never
 * expressions. Global variables are not offered at their block level.
 *
 * Deliberately not listed: `steps`, post-condition bodies and `script` (steps), `environment`
 * (values reference `env`/`params`), `expression {}` (arbitrary Groovy), and `parameters`,
 * `triggers`, `tools`, `libraries`, `matrix`, whose contents are not modeled yet (#1246).
 */
private val STRUCTURAL_DECLARATIVE_BLOCKS = setOf(
    "pipeline", "agent", "stages", "stage", "parallel", "axes", "post", "options", "when",
)

/** True unless a tracked non-schema block (such as `expression {}`) sits inside [JenkinsBlockContext.currentBlock]. */
private fun JenkinsBlockContext.isInnermostTrackedBlock(): Boolean =
    enclosingBlocks.isEmpty() || enclosingBlocks.last() == currentBlock

/**
 * True when the cursor sits directly in a structural declarative block: the innermost tracked
 * block is that block (not, say, `expression {}` inside `when`), and no Groovy body encloses it
 * (a scripted `stage('x') {}` inside `script {}` takes steps and globals).
 */
private fun JenkinsBlockContext?.isStructuralDeclarativeBlock(): Boolean {
    if (this == null || !isStrictDeclarative) return false
    val block = currentBlock ?: return false
    if (block !in STRUCTURAL_DECLARATIVE_BLOCKS) return false
    if (!isInnermostTrackedBlock()) return false
    return !isInsideGroovyBody()
}

/** The cursor is directly in a declarative block (not in Groovy nested inside one). */
private fun JenkinsBlockContext.acceptsDeclarativeDirectives(): Boolean =
    isInnermostTrackedBlock() && !isInsideGroovyBody()

/**
 * A `script {}`, or a scripted `node {}`, encloses the cursor, so scripted-pipeline statements
 * apply beneath it. `agent { node { label 'x' } }` is the declarative node agent, not Groovy.
 */
private fun JenkinsBlockContext.isInsideGroovyBody(): Boolean =
    "script" in enclosingBlocks || ("node" in enclosingBlocks && "agent" !in enclosingBlocks)
