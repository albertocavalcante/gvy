package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
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
import com.github.albertocavalcante.gvy.jenkins.completion.JenkinsContextDetector
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.declarative.DeclarativePipelineSchema
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.SemanticTypeFormatter
import com.github.albertocavalcante.gvy.semantics.native.SymbolExtractor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationFailedException
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI

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
    ): List<CompletionItem> {
        return try {
            val uriObj = URI.create(uri)

            // Determine if we are inserting into an existing identifier
            val isClean = CompletionContextDetector.isCleanInsertion(content, line, character)

            // Strategy 1: Simple insertion (e.g. "myList.BrazilWorldCup2026")
            val content1 = CompletionContextDetector.insertDummyIdentifier(content, line, character, withDef = false)
            val result1 = compilationService.compileTransient(uriObj, content1)
            val ast1 = result1.ast
            val astModel1 = result1.astModel

            // If simple insertion failed and it was a clean insertion, try adding 'def'.
            // This helps in class bodies: "class Foo { def BrazilWorldCup2026 }" is valid,
            // but "class Foo { BrazilWorldCup2026 }" is not.
            if (isClean && !result1.isSuccessful) {
                val content2 = CompletionContextDetector.insertDummyIdentifier(content, line, character, withDef = true)
                val result2 = compilationService.compileTransient(uriObj, content2)
                val ast2 = result2.ast
                val astModel2 = result2.astModel
                val defStrategyBetter = result2.isSuccessful || result2.diagnostics.size < result1.diagnostics.size

                // Use 'def' strategy if it produced a better result and has a valid AST
                if (defStrategyBetter && ast2 != null) {
                    return buildCompletionsList(
                        CompletionContext(
                            uri = uriObj,
                            line = line,
                            character = character,
                            ast = ast2,
                            astModel = astModel2,
                            tokenIndex = result2.tokenIndex,
                            compilationService = compilationService,
                            content = content,
                            semanticResolver = semanticResolver,
                            moduleNode = ast2,
                            workspaceSymbolIndex = compilationService.getWorkspaceSymbolIndex(),
                        ),
                    )
                }
            }

            // Fallback to result1 (simple insertion)
            if (ast1 == null) {
                return buildFallbackCompletions(
                    content = content,
                    line = line,
                    character = character,
                    tokenIndex = result1.tokenIndex,
                    compilationService = compilationService,
                )
            } else {
                buildCompletionsList(
                    CompletionContext(
                        uri = uriObj,
                        line = line,
                        character = character,
                        ast = ast1,
                        astModel = astModel1,
                        tokenIndex = result1.tokenIndex,
                        compilationService = compilationService,
                        content = content,
                        semanticResolver = semanticResolver,
                        moduleNode = ast1,
                        workspaceSymbolIndex = compilationService.getWorkspaceSymbolIndex(),
                    ),
                )
            }
        } catch (e: CompilationFailedException) {
            // If AST analysis fails, log and return empty list
            logger.debug { "AST analysis failed for completion at $line:$character: ${e.message}" }
            buildFallbackCompletions(
                content = content,
                line = line,
                character = character,
                tokenIndex = null,
                compilationService = compilationService,
            )
        }
    }

    /**
     * Provides best-effort completions when compilation or AST extraction fails.
     * This is intended for broken files (syntax errors, incomplete edits) so users
     * still get import, keyword, and snippet suggestions.
     */
    private fun buildFallbackCompletions(
        content: String,
        line: Int,
        character: Int,
        tokenIndex: GroovyTokenIndex?,
        compilationService: GroovyCompilationService,
    ): List<CompletionItem> {
        val importContext = CompletionContextDetector.detectImportCompletionContext(
            content = content,
            line = line,
            character = character,
            tokenIndex = tokenIndex,
        )
        if (importContext != null) {
            return completions { addImportCompletions(importContext, compilationService) }
        }
        return completions {
            addKeywords()
            GroovyCompletions.basic().forEach(::add)
        }
    }

    private suspend fun buildCompletionsList(ctx: CompletionContext): List<CompletionItem> {
        ctx.moduleNode?.let { ctx.semanticResolver.semantics.inject(it) }

        // Extract symbol context
        val symbolContext = SymbolExtractor.extractCompletionSymbols(
            ctx.ast,
            ctx.line,
            ctx.character,
            ctx.semanticResolver.semantics,
        )

        // Get Jenkins capabilities and create mode resolver
        val jenkinsCapabilities = ctx.compilationService.workspaceManager.getJenkinsCapabilities()
        val modeResolver = ModeResolver(
            configuredMode = GroovyMode.AUTO, // TODO: Get from ServerConfiguration once available
            jenkinsCapabilities = jenkinsCapabilities,
        )
        val mode = modeResolver.resolveMode(ctx.uri)
        val isJenkinsFile = jenkinsCapabilities?.isJenkinsFile(ctx.uri) ?: false

        // Handle import completions (early return)
        val importContext = CompletionContextDetector.detectImportCompletionContext(
            content = ctx.content,
            line = ctx.line,
            character = ctx.character,
            tokenIndex = ctx.tokenIndex,
        )
        if (importContext != null) {
            return completions { addImportCompletions(importContext, ctx.compilationService) }
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
        val jenkinsMetadata = jenkinsCapabilities?.takeIf { isJenkinsFile }?.getAllMetadata()
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
        )

        // Use strategies for general completions (call suspend function outside of completions builder)
        val strategies = buildStrategies(mode, ctx.compilationService, ctx.workspaceSymbolIndex)
        val strategyItems = CompletionStrategy.aggregate(strategies).complete(strategyContext).fold(
            ifLeft = { emptyList() },
            ifRight = { it },
        )

        return completions {
            // Add strategy items (includes member access, Spock, Jenkins, and Groovy completions)
            strategyItems.forEach(::add)

            // Handle contextual completions (TypeParameter - keep existing)
            if (handleContextualCompletions(completionContext, ctx, jenkinsMetadata)) {
                return@completions
            }
        }
    }

    private fun buildStrategies(
        mode: GroovyMode,
        compilationService: GroovyCompilationService,
        workspaceSymbolIndex: WorkspaceSymbolIndex?,
    ): List<CompletionStrategy> {
        val memberAccessStrategy = MemberAccessCompletionStrategy(compilationService, workspaceSymbolIndex)
        return when (mode) {
            GroovyMode.GROOVY -> listOf(
                memberAccessStrategy,
                SpockCompletionStrategy(),
                GroovyCompletionStrategy(),
            )
            GroovyMode.JENKINS -> listOf(
                memberAccessStrategy,
                JenkinsCompletionStrategy(),
                SpockCompletionStrategy(),
                GroovyCompletionStrategy(),
            )
            GroovyMode.AUTO -> listOf(
                memberAccessStrategy,
                JenkinsCompletionStrategy(),
                SpockCompletionStrategy(),
                GroovyCompletionStrategy(),
            )
        }
    }

    private fun buildJenkinsBlockContext(ctx: CompletionContext, isJenkinsFile: Boolean): JenkinsBlockContext? {
        if (!isJenkinsFile) return null

        val detected = JenkinsContextDetector.detectFromDocument(ctx.content.lines(), ctx.line, ctx.character)
        val currentBlock = detected.currentBlock
        val blockCategories = currentBlock?.let(DeclarativePipelineSchema::getCompletionCategories) ?: emptySet()
        val innerInstructions = currentBlock?.let(DeclarativePipelineSchema::getInnerInstructions) ?: emptySet()
        val isStrictDeclarative = detected.isDeclarativePipeline && currentBlock != null && currentBlock != "script"

        return JenkinsBlockContext(
            currentBlock = currentBlock,
            blockCategories = blockCategories,
            innerInstructions = innerInstructions,
            isStrictDeclarative = isStrictDeclarative,
        )
    }

    // TODO(#864): Fix UnusedReceiverParameter warnings in CompletionsBuilder extensions.
    //   Several extension functions below don't use `this` but are defined as extensions for
    //   DSL consistency. Consider suppressing warnings or refactoring.
    //   See: https://github.com/albertocavalcante/gvy/issues/864

    @Suppress("UnusedParameter", "FunctionParameterNaming") // TODO: Use _metadata for Jenkins-specific completions
    private fun CompletionsBuilder.handleContextualCompletions(
        completionContext: ContextType?,
        ctx: CompletionContext,
        _metadata: MergedJenkinsMetadata?,
    ): Boolean = when (completionContext) {
        // MemberAccess is handled via early return in buildCompletionsList
        is ContextType.MemberAccess -> false
        is ContextType.TypeParameter -> {
            logger.debug { "Adding type parameter classes for prefix '${completionContext.prefix}'" }
            addTypeParameterClasses(completionContext.prefix, ctx.compilationService)
            // Also add auto-import completions for unimported types
            addAutoImportCompletions(completionContext.prefix, ctx.uri, ctx.content, ctx.compilationService)
            false
        }

        null -> false
    }

    /**
     * Formats a SemanticType into a human-readable string for display.
     * Delegates to SemanticTypeFormatter for consistent formatting.
     *
     * @param type The semantic type to format
     * @return A formatted type string
     */
    private fun formatType(type: SemanticType): String = SemanticTypeFormatter.formatForCompletion(type)

    /**
     * Parses a method signature into parameter strings for display.
     *
     * For now, this is a simple implementation that extracts parameter types from the signature.
     * Future enhancement: Parse the full signature with parameter names.
     *
     * @param signature The method signature
     *   (e.g., "com/example/MyClass#myMethod(String,int)." or "com/example/MyClass#myMethod(Map<String,String>).")
     * @return List of parameter strings (e.g., ["String", "int"] or ["Map<String,String>"])
     */
    @Suppress("ReturnCount") // Multiple validation checks require early returns
    private fun parseSignatureToParams(signature: String?): List<String> {
        if (signature == null) return emptyList()

        val startIndex = signature.indexOf('(')
        val endIndex = signature.indexOf(')')

        if (startIndex < 0 || endIndex < 0 || startIndex >= endIndex) {
            return emptyList()
        }

        val params = signature.substring(startIndex + 1, endIndex).trim()
        if (params.isEmpty()) return emptyList()

        // Split by comma while respecting angle brackets for generics
        return parseParametersWithGenerics(params)
    }

    /**
     * Parse comma-separated parameters while respecting angle brackets for generics.
     *
     * This helper extracts the complex bracket-tracking logic from parseSignatureToParams.
     */
    private fun parseParametersWithGenerics(params: String): List<String> {
        val result = mutableListOf<String>()
        val currentParam = StringBuilder()
        var bracketDepth = 0
        var invalidBrackets = false

        fun addCurrentParam() {
            result.add(currentParam.toString().trim().substringAfterLast('/').substringAfterLast('.'))
            currentParam.clear()
        }

        for (char in params) {
            when (char) {
                '<' -> {
                    bracketDepth++
                    currentParam.append(char)
                }

                '>' -> {
                    bracketDepth--
                    if (bracketDepth < 0) {
                        invalidBrackets = true
                        break
                    }
                    currentParam.append(char)
                }

                ',' -> {
                    if (bracketDepth == 0) {
                        addCurrentParam()
                    } else {
                        currentParam.append(char)
                    }
                }

                else -> currentParam.append(char)
            }
        }

        // If brackets are unbalanced, fall back to simple comma-based parsing
        if (invalidBrackets || bracketDepth != 0) {
            return params.split(',')
                .map { it.trim().substringAfterLast('/').substringAfterLast('.') }
                .filter { it.isNotEmpty() }
        }

        // Add the last parameter
        if (currentParam.isNotEmpty()) {
            addCurrentParam()
        }

        return result
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
    ) {
        val strategy = ImportCompletionStrategy(compilationService)
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
    ) {
        val classes =
            compilationService.classpathService.findClassesByPrefix(prefix, maxResults = MAX_TYPE_COMPLETION_RESULTS)
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
        uri: URI,
        content: String,
        compilationService: GroovyCompilationService,
    ) {
        val completions = AutoImportCompletionProvider.getTypeCompletions(
            prefix = prefix,
            uri = uri,
            content = content,
            compilationService = compilationService,
            classpathService = compilationService.classpathService,
        )

        // Add pre-built completion items directly (they already have additionalTextEdits)
        addAll(completions)
    }
}
