// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SignatureCompletionRequest
import com.github.albertocavalcante.gvy.gls.config.GroovyMode
import com.github.albertocavalcante.gvy.gls.config.ModeResolver
import com.github.albertocavalcante.gvy.gls.dsl.completion.CompletionsBuilder
import com.github.albertocavalcante.gvy.gls.dsl.completion.GroovyCompletions
import com.github.albertocavalcante.gvy.gls.dsl.completion.completions
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.providers.completion.strategy.CompletionStrategy
import com.github.albertocavalcante.gvy.gls.providers.completion.strategy.CompletionStrategyContext
import com.github.albertocavalcante.gvy.gls.providers.completion.strategy.GroovyCompletionStrategy
import com.github.albertocavalcante.gvy.gls.providers.completion.strategy.ImportCompletionStrategy
import com.github.albertocavalcante.gvy.gls.providers.completion.strategy.JenkinsBlockContext
import com.github.albertocavalcante.gvy.gls.providers.completion.strategy.JenkinsCompletionStrategy
import com.github.albertocavalcante.gvy.gls.providers.completion.strategy.MemberAccessCompletionStrategy
import com.github.albertocavalcante.gvy.gls.providers.completion.strategy.SpockCompletionStrategy
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.jenkins.completion.JenkinsContextDetector
import com.github.albertocavalcante.gvy.jenkins.metadata.declarative.DeclarativePipelineSchema
import com.github.albertocavalcante.gvy.semantics.native.SymbolExtractor
import com.github.albertocavalcante.nativeapi.ParseResult
import com.github.albertocavalcante.nativeapi.ParserDiagnostic
import com.github.albertocavalcante.nativeapi.ParserPosition
import com.github.albertocavalcante.nativeapi.ParserSeverity
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationFailedException
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI

private typealias CopyQuery = suspend (ParseResult, SemanticTypeResolver, CompletionInputs?) -> List<CompletionItem>?

/**
 * Context for completion operations.
 */
data class CompletionContext(
    val uri: URI,
    val line: Int,
    val character: Int,
    val ast: ASTNode,
    val astModel: GroovyAstModel,
    val tokenIndex: GroovyTokenIndex?,
    val compilationService: GroovyCompilationService,
    val content: String,
    val semanticResolver: SemanticTypeResolver,
    val moduleNode: ModuleNode?,
    val workspaceSymbolIndex: WorkspaceSymbolIndex? = null,
)

/**
 * Provides completion items for Groovy language constructs using clean DSL.
 */
object CompletionProvider {
    private val logger = KotlinLogging.logger {}

    // Stateless, so one instance of each serves every request and mode. Order is semantics: completions are
    // aggregated in list order.
    private val spockStrategy = SpockCompletionStrategy()
    private val groovyStrategy = GroovyCompletionStrategy()
    private val jenkinsStrategy = JenkinsCompletionStrategy()

    // After member access: Jenkins mode (and AUTO, which only resolves to it for Jenkins files) adds Jenkins steps.
    private val groovyModeStrategies: List<CompletionStrategy> = listOf(spockStrategy, groovyStrategy)
    private val jenkinsAwareStrategies: List<CompletionStrategy> =
        listOf(jenkinsStrategy, spockStrategy, groovyStrategy)

    // Note: IntelliJ uses "IntelliJIdeaRulezzz"
    // Kotlin LSP uses "RWwgUHN5IEtvbmdyb28g" (El Psy Kongroo)
    // See: https://github.com/Kotlin/kotlin-lsp/blob/main/features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/common/kotlin/completion/rekot/completionUtils.kt
    internal const val DUMMY_IDENTIFIER = "BrazilWorldCup2026"
    private const val MAX_TYPE_COMPLETION_RESULTS = 20

    /**
     * Get basic Groovy language completion items using DSL.
     */
    fun getBasicCompletions(): List<CompletionItem> = GroovyCompletions.basic()

    /**
     * Get contextual completions based on AST analysis.
     */
    suspend fun getContextualCompletions(
        uri: String,
        line: Int,
        character: Int,
        compilationService: GroovyCompilationService,
        semanticResolver: SemanticTypeResolver,
        content: String,
    ): List<CompletionItem> = try {
        val document = URI.create(uri)
        CompletionCopies(document, Position(line, character), content, compilationService) { copy, consume ->
            currentCoroutineContext().ensureActive()
            val parsed = compilationService.compileTransient(document, copy)
            parsed.compilationUnit.classLoader.use { consume(parsed, semanticResolver, null) }
        }.items()
    } catch (_: CompilationFailedException) {
        buildFallbackCompletions(content, Position(line, character), null, compilationService)
    }

    internal suspend fun getContextualCompletions(request: SignatureCompletionRequest): List<CompletionItem> {
        val content = request.source.buffer?.snapshot?.content ?: return getBasicCompletions()
        return try {
            CompletionCopies(request.uri, request.position, content, request.compilation) { copy, consume ->
                request.derived(copy) { native ->
                    consume(native.held.parseResult, native.types, CompletionInputs(request, native))
                }
            }.items()
        } catch (_: CompilationFailedException) {
            request.original({ emptyList() }) { original ->
                buildFallbackCompletions(
                    content,
                    request.position,
                    null,
                    request.compilation,
                    CompletionInputs(request, original),
                )
            }
        }
    }

    /** Keep each candidate inside its own producer while trying a more useful repair. */
    private class CompletionCopies(
        val uri: URI,
        val position: Position,
        val content: String,
        val service: GroovyCompilationService,
        val parse: suspend (String, CopyQuery) -> List<CompletionItem>?,
    ) {
        private val line = position.line
        private val character = position.character

        suspend fun items(): List<CompletionItem> {
            val dummy = CompletionContextDetector.insertDummyIdentifier(content, line, character, withDef = false)
            return parse(dummy) { first, resolver, inputs ->
                val coroutine = currentCoroutineContext()
                val constructor = if (first.ast == null) {
                    CompletionContextDetector.constructorCompletionCopy(TextIndex(content), position) {
                        coroutine.ensureActive()
                    }
                } else {
                    null
                }
                constructor?.let { copy ->
                    parse(copy) { repaired, types, selected ->
                        if (repaired.ast == null) null else withDefRecovery(repaired, types, selected)
                    }
                } ?: withDefRecovery(first, resolver, inputs)
            }.orEmpty()
        }

        private suspend fun withDefRecovery(
            parsed: ParseResult,
            resolver: SemanticTypeResolver,
            inputs: CompletionInputs?,
        ): List<CompletionItem> {
            currentCoroutineContext().ensureActive()
            val retry = !parsed.isSuccessful && CompletionContextDetector.isCleanInsertion(content, line, character) &&
                dummyCausedError(parsed.diagnostics, line, character)
            if (retry) {
                val copy = CompletionContextDetector.insertDummyIdentifier(content, line, character, withDef = true)
                parse(copy) { alternate, types, selected ->
                    val better = alternate.isSuccessful || alternate.diagnostics.size < parsed.diagnostics.size
                    if (alternate.ast != null && better) render(alternate, types, selected) else null
                }?.let { return it }
            }
            return render(parsed, resolver, inputs)
        }

        private suspend fun render(
            parsed: ParseResult,
            resolver: SemanticTypeResolver,
            inputs: CompletionInputs?,
        ): List<CompletionItem> {
            val ast =
                parsed.ast
                    ?: return buildFallbackCompletions(content, position, parsed.tokenIndex, service, inputs)
            val context =
                CompletionContext(
                    uri, line, character, ast, parsed.astModel, parsed.tokenIndex, service, content,
                    resolver, ast, service.getWorkspaceSymbolIndex(),
                )
            return buildCompletionsList(context, inputs)
        }
    }

    /** Whether an error diagnostic overlaps the dummy inserted at [line]:[character] (0-based). */
    private fun dummyCausedError(diagnostics: List<ParserDiagnostic>, line: Int, character: Int): Boolean {
        val start = ParserPosition(line, character)
        val end = ParserPosition(line, character + DUMMY_IDENTIFIER.length)
        return diagnostics.any {
            it.severity == ParserSeverity.ERROR && !it.range.end.isBefore(start) && !end.isBefore(it.range.start)
        }
    }

    private fun ParserPosition.isBefore(other: ParserPosition): Boolean =
        line < other.line || (line == other.line && character < other.character)

    /**
     * Provides best-effort completions when compilation or AST extraction fails.
     * This is intended for broken files (syntax errors, incomplete edits) so users
     * still get import, keyword, and snippet suggestions.
     */
    private fun buildFallbackCompletions(
        content: String,
        position: Position,
        tokenIndex: GroovyTokenIndex?,
        compilationService: GroovyCompilationService,
        inputs: CompletionInputs? = null,
    ): List<CompletionItem> {
        val importContext = CompletionContextDetector.detectImportCompletionContext(
            content = content,
            line = position.line,
            character = position.character,
            tokenIndex = tokenIndex,
        )
        if (importContext != null) {
            return completions { addImportCompletions(importContext, compilationService, inputs) }
        }
        return completions {
            addKeywords()
            GroovyCompletions.basic().forEach(::add)
        }
    }

    private suspend fun buildCompletionsList(ctx: CompletionContext, inputs: CompletionInputs?): List<CompletionItem> {
        ctx.moduleNode?.let { ctx.semanticResolver.semantics.inject(it) }

        // Extract symbol context
        val symbolContext = SymbolExtractor.extractCompletionSymbols(
            ctx.ast,
            ctx.line,
            ctx.character,
            ctx.semanticResolver.semantics,
        )

        // Get Jenkins capabilities and create mode resolver
        val facts = inputs?.jenkins()
        val jenkinsCapabilities = if (facts != null) {
            facts.capability
        } else {
            ctx.compilationService.workspaceManager.getJenkinsCapabilities()
        }
        val modeResolver = facts?.mode ?: ModeResolver(
            configuredMode = ctx.compilationService.serverConfiguration.groovyMode,
            jenkinsCapabilities = jenkinsCapabilities,
        )
        val mode = modeResolver.resolveMode(ctx.uri)
        val isJenkinsFile = mode == GroovyMode.JENKINS

        // Handle import completions (early return)
        val importContext = CompletionContextDetector.detectImportCompletionContext(
            content = ctx.content,
            line = ctx.line,
            character = ctx.character,
            tokenIndex = ctx.tokenIndex,
        )
        if (importContext != null) {
            return completions { addImportCompletions(importContext, ctx.compilationService, inputs) }
        }

        // Detect completion context
        val nodeAtCursor = CompletionContextDetector.findNodeAtOrBefore(
            ctx.astModel,
            ctx.uri,
            ctx.content,
            ctx.line,
            ctx.character,
        )

        val completionContext =
            CompletionContextDetector.detectCompletionContext(
                nodeAtCursor,
                ctx.astModel,
                ctx.semanticResolver,
                ctx.moduleNode,
            )

        // Build strategy context
        val jenkinsMetadata = jenkinsCapabilities?.takeIf { isJenkinsFile }?.getAllMetadata(ctx.uri)
        val jenkinsBlockContext = buildJenkinsBlockContext(ctx, isJenkinsFile)

        val strategyContext = CompletionStrategyContext(
            baseContext = ctx,
            symbolContext = symbolContext,
            nodeAtCursor = nodeAtCursor,
            contextType = completionContext,
            mode = mode,
            isJenkinsFile = isJenkinsFile,
            jenkinsMetadata = jenkinsMetadata,
            jenkinsBlockContext = jenkinsBlockContext,
            jenkinsCapabilities = jenkinsCapabilities,
        )

        return completeStrategies(strategyContext, inputs)
    }

    private suspend fun completeStrategies(
        strategyContext: CompletionStrategyContext,
        inputs: CompletionInputs?,
    ): List<CompletionItem> {
        val ctx = strategyContext.baseContext
        val strategies = buildStrategies(strategyContext.mode, ctx.compilationService, ctx.workspaceSymbolIndex, inputs)
        val strategyItems = CompletionStrategy.aggregate(
            strategies,
            propagateFailures = inputs != null,
        ).complete(strategyContext).fold(
            ifLeft = { emptyList() },
            ifRight = { it },
        )

        return completions {
            // Add strategy items (includes member access, Spock, Jenkins, and Groovy completions)
            strategyItems.forEach(::add)

            // Handle contextual completions (TypeParameter - keep existing)
            if (handleContextualCompletions(strategyContext.contextType, ctx, inputs)) {
                return@completions
            }
        }.filterNot {
            // The parsing copy declares the dummy (`def BrazilWorldCup2026` in a class body is a field);
            // it exists only there and must never reach the user.
            it.label?.contains(DUMMY_IDENTIFIER) == true
        }
    }

    /**
     * The strategies for one request in [mode]: member access first, then the shared per-mode list.
     *
     * Member access holds the request's [compilationService] (this provider is a process-wide object that
     * receives the service per call), so it is the one strategy built per request.
     */
    internal fun buildStrategies(
        mode: GroovyMode,
        compilationService: GroovyCompilationService,
        workspaceSymbolIndex: WorkspaceSymbolIndex?,
        inputs: CompletionInputs? = null,
    ): List<CompletionStrategy> {
        val memberAccessStrategy = MemberAccessCompletionStrategy(compilationService, workspaceSymbolIndex, inputs)
        val modeStrategies = when (mode) {
            GroovyMode.GROOVY -> groovyModeStrategies
            GroovyMode.JENKINS, GroovyMode.AUTO -> jenkinsAwareStrategies
        }
        return listOf(memberAccessStrategy) + modeStrategies
    }

    private fun buildJenkinsBlockContext(ctx: CompletionContext, isJenkinsFile: Boolean): JenkinsBlockContext? {
        if (!isJenkinsFile) return null

        val detected = JenkinsContextDetector.detectFromDocument(ctx.content.lines(), ctx.line, ctx.character)
        val currentBlock = detected.currentBlock
        val blockCategories = currentBlock?.let(DeclarativePipelineSchema::getCompletionCategories) ?: emptySet()
        val contextual = DeclarativePipelineSchema.getContextualStatementInstructions(
            currentBlock,
            detected.enclosingBlocks,
        )
        val innerInstructions =
            (currentBlock?.let(DeclarativePipelineSchema::getInnerInstructions) ?: emptySet()) + contextual
        val statementInstructions =
            (currentBlock?.let(DeclarativePipelineSchema::getStatementInstructions) ?: emptySet()) + contextual
        val isStrictDeclarative = detected.isDeclarativePipeline && currentBlock != null && currentBlock != "script"

        return JenkinsBlockContext(
            currentBlock = currentBlock,
            blockCategories = blockCategories,
            innerInstructions = innerInstructions,
            isStrictDeclarative = isStrictDeclarative,
            enclosingBlocks = detected.enclosingBlocks,
            statementInstructions = statementInstructions,
        )
    }

    // TODO(#864): Fix UnusedReceiverParameter warnings in CompletionsBuilder extensions.
    //   Several extension functions below don't use `this` but are defined as extensions for
    //   DSL consistency. Consider suppressing warnings or refactoring.
    //   See: https://github.com/albertocavalcante/gvy/issues/864

    private fun CompletionsBuilder.handleContextualCompletions(
        completionContext: ContextType?,
        ctx: CompletionContext,
        inputs: CompletionInputs?,
    ): Boolean = when (completionContext) {
        // MemberAccess is handled via early return in buildCompletionsList
        is ContextType.MemberAccess -> false
        is ContextType.TypeParameter -> {
            logger.debug { "Adding type parameter classes for prefix '${completionContext.prefix}'" }
            addTypeParameterClasses(completionContext.prefix, ctx.compilationService, inputs)
            // Also add auto-import completions for unimported types
            addAutoImportCompletions(completionContext.prefix, ctx, inputs)
            false
        }

        null -> false
    }

    internal data class ImportCompletionContext(
        val prefix: String,
        val isStatic: Boolean,
        val canSuggestStatic: Boolean,
        val line: Int,
        val replaceStartCharacter: Int,
        val replaceEndCharacter: Int,
    ) {
        // Static member completion is when we have a fully qualified class name followed by a dot
        // e.g., "import static java.lang.Math." (cursor after dot)
        // NOT "import static java.lang.Math" (cursor at end of class name)
        val isStaticMemberCompletion: Boolean
            get() = isStatic && prefix.endsWith('.') && prefix.substringBeforeLast('.').contains('.')

        val staticClassName: String?
            get() = if (isStaticMemberCompletion) prefix.substringBeforeLast('.') else null
    }

    internal sealed interface ContextType {
        /**
         * Member access context (e.g., "myList." or "env.").
         * @param qualifierType The type of the qualifier (e.g., "ArrayList" or "Object")
         * @param qualifierName The name of the qualifier variable (e.g., "myList" or "env").
         *                      Used to match Jenkins global variables by name.
         */
        data class MemberAccess(val qualifierType: String, val qualifierName: String? = null) : ContextType
        data class TypeParameter(val prefix: String) : ContextType
    }

    /**
     * Keywords that have snippet versions in GroovyCompletions.basic().
     * These are excluded from addKeywords() to prevent duplicate completions (#857).
     */
    private val SNIPPET_KEYWORDS = setOf("def", "class", "interface", "enum", "if", "for", "while")

    private fun CompletionsBuilder.addKeywords() {
        val allKeywords = listOf(
            // Types
            "def", "void", "int", "boolean", "char", "byte",
            "short", "long", "float", "double", "String", "Object",
            // Control flow
            "if", "else", "for", "while", "do", "switch", "case", "default",
            "break", "continue", "return", "try", "catch", "finally", "throw",
            // Structure
            "class", "interface", "trait", "enum", "package", "import",
            // Modifiers
            "public", "protected", "private", "static", "final", "abstract",
            "synchronized", "transient", "volatile", "native",
            // Values/Other
            "true", "false", "null", "this", "super", "new", "in", "as", "assert",
        )
        // Filter out keywords that have snippet versions in GroovyCompletions.basic()
        allKeywords.filterNot { it in SNIPPET_KEYWORDS }.forEach { k ->
            keyword(
                keyword = k,
                doc = "Keyword/Type: $k",
            )
        }
    }

    /**
     * Adds import-specific completions (static keyword and matching class names).
     */
    private fun CompletionsBuilder.addImportCompletions(
        ctx: ImportCompletionContext,
        compilationService: GroovyCompilationService,
        inputs: CompletionInputs?,
    ) {
        val strategy = ImportCompletionStrategy(compilationService, inputs)
        strategy.addImportCompletions(
            ImportCompletionStrategy.ImportContext(
                prefix = ctx.prefix,
                isStatic = ctx.isStatic,
                canSuggestStatic = ctx.canSuggestStatic,
                line = ctx.line,
                replaceRange = Range(
                    Position(ctx.line, ctx.replaceStartCharacter),
                    Position(ctx.line, ctx.replaceEndCharacter),
                ),
            ),
            this,
        )
    }

    /**
     * Add class completions for type parameters (e.g., List<I...> → Integer).
     */
    private fun CompletionsBuilder.addTypeParameterClasses(
        prefix: String,
        compilationService: GroovyCompilationService,
        inputs: CompletionInputs?,
    ) {
        val classes = inputs?.classNames(prefix, MAX_TYPE_COMPLETION_RESULTS)
            ?: compilationService.classpathService.findClassesByPrefix(prefix, maxResults = MAX_TYPE_COMPLETION_RESULTS)
        logger.debug { "Found ${classes.size} classes for prefix $prefix" }

        classes.forEach { classInfo ->
            clazz(
                name = classInfo.simpleName,
                packageName = classInfo.packageName,
                doc = "Class: ${classInfo.fullName}",
            )
        }
    }

    /**
     * Add auto-import completions for types not yet imported.
     * Searches both workspace and classpath for matching types.
     */
    private fun CompletionsBuilder.addAutoImportCompletions(
        prefix: String,
        ctx: CompletionContext,
        inputs: CompletionInputs?,
    ) {
        val completions = if (inputs == null) {
            AutoImportCompletionProvider.getTypeCompletions(
                prefix,
                ctx.uri,
                ctx.content,
                ctx.compilationService,
                ctx.compilationService.classpathService,
            )
        } else {
            AutoImportCompletionProvider.getTypeCompletions(prefix, ctx.content, inputs)
        }

        // Add pre-built completion items directly (they already have additionalTextEdits)
        addAll(completions)
    }
}
