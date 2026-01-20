package com.github.albertocavalcante.gvy.gls.providers.completion.strategy

import com.github.albertocavalcante.gvy.gls.config.GroovyMode
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionContext
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider.ContextType
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.declarative.DeclarativePipelineSchema
import com.github.albertocavalcante.gvy.semantics.native.SymbolCompletionContext
import org.codehaus.groovy.ast.ASTNode

/**
 * Context for completion strategies containing all data needed for completions.
 *
 * @property baseContext The underlying CompletionContext with AST, position, etc.
 * @property symbolContext Extracted symbols (classes, methods, fields, variables)
 * @property nodeAtCursor The AST node at the cursor position
 * @property contextType Detected completion context type (MemberAccess, TypeParameter, etc.)
 * @property mode The effective language mode for this file
 * @property isJenkinsFile Whether this is detected as a Jenkins file
 * @property jenkinsMetadata Jenkins metadata (steps, global vars) if available
 * @property jenkinsBlockContext Jenkins declarative block context if applicable
 */
internal data class CompletionStrategyContext(
    val baseContext: CompletionContext,
    val symbolContext: SymbolCompletionContext,
    val nodeAtCursor: ASTNode?,
    val contextType: ContextType?,
    val mode: GroovyMode,
    val isJenkinsFile: Boolean,
    val jenkinsMetadata: MergedJenkinsMetadata? = null,
    val jenkinsBlockContext: JenkinsBlockContext? = null,
)

/**
 * Jenkins declarative pipeline block context.
 *
 * @property currentBlock The current declarative block name (e.g., "steps", "post", "options")
 * @property blockCategories Completion categories allowed in this block
 * @property innerInstructions Sub-directives allowed in this block
 * @property isStrictDeclarative Whether we're in strict declarative mode (no arbitrary Groovy)
 */
internal data class JenkinsBlockContext(
    val currentBlock: String?,
    val blockCategories: Set<DeclarativePipelineSchema.CompletionCategory>,
    val innerInstructions: Set<String>,
    val isStrictDeclarative: Boolean,
)
