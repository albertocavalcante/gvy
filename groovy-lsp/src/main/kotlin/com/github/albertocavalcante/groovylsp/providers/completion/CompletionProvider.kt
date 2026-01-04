package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyjenkins.completion.JenkinsContextDetector
import com.github.albertocavalcante.groovyjenkins.metadata.MergedGlobalVariable
import com.github.albertocavalcante.groovyjenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.declarative.DeclarativePipelineSchema
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.dsl.completion.CompletionsBuilder
import com.github.albertocavalcante.groovylsp.dsl.completion.GroovyCompletions
import com.github.albertocavalcante.groovylsp.dsl.completion.completions
import com.github.albertocavalcante.groovyparser.ast.ClassSymbol
import com.github.albertocavalcante.groovyparser.ast.FieldSymbol
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.ImportSymbol
import com.github.albertocavalcante.groovyparser.ast.MethodSymbol
import com.github.albertocavalcante.groovyparser.ast.SymbolExtractor
import com.github.albertocavalcante.groovyparser.ast.VariableSymbol
import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.groovyspock.SpockDetector
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.CompilationFailedException
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory
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
)

/**
 * Provides completion items for Groovy language constructs using clean DSL.
 */
object CompletionProvider {
    private val logger = LoggerFactory.getLogger(CompletionProvider::class.java)

    // Note: IntelliJ uses "IntelliJIdeaRulezzz"
    // Kotlin LSP uses "RWwgUHN5IEtvbmdyb28g" (El Psy Kongroo)
    // See: https://github.com/Kotlin/kotlin-lsp/blob/main/features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/common/kotlin/completion/rekot/completionUtils.kt
    internal const val DUMMY_IDENTIFIER = "BrazilWorldCup2026"
    private const val MAX_TYPE_COMPLETION_RESULTS = 20
    private const val MAX_IMPORT_COMPLETION_RESULTS = 50

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
                    val isSpockSpec = SpockDetector.isSpockSpec(uriObj, result2)
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
                        ),
                        isSpockSpec = isSpockSpec,
                    )
                }
            }

            // Fallback to result1 (simple insertion)
            if (ast1 == null) {
                emptyList()
            } else {
                val isSpockSpec = SpockDetector.isSpockSpec(uriObj, result1)
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
                    ),
                    isSpockSpec = isSpockSpec,
                )
            }
        } catch (e: CompilationFailedException) {
            // If AST analysis fails, log and return empty list
            logger.debug("AST analysis failed for completion at {}:{}: {}", line, character, e.message)
            emptyList()
        }
    }

    private fun resolveDataTypes(className: String, service: GroovyCompilationService): List<String> {
        // Try exact name first
        if (service.classpathService.loadClass(className) != null) return listOf(className)

        // If simple name, try default imports
        if (!className.contains('.')) {
            val candidates = listOf(
                "java.lang.$className",
                "java.util.$className",
                "java.io.$className",
                "java.net.$className",
                "groovy.lang.$className",
                "groovy.util.$className",
            )
            return candidates.filter { service.classpathService.loadClass(it) != null }
        }
        return emptyList()
    }

    private fun buildCompletionsList(ctx: CompletionContext, isSpockSpec: Boolean): List<CompletionItem> {
        val symbolContext = SymbolExtractor.extractCompletionSymbols(ctx.ast, ctx.line, ctx.character)
        val isJenkinsFile = ctx.compilationService.workspaceManager.isJenkinsFile(ctx.uri)

        val importContext = CompletionContextDetector.detectImportCompletionContext(
            content = ctx.content,
            line = ctx.line,
            character = ctx.character,
            tokenIndex = ctx.tokenIndex,
        )
        if (importContext != null) {
            return completions { addImportCompletions(importContext, ctx.compilationService) }
        }

        val nodeAtCursor = CompletionContextDetector.findNodeAtOrBefore(
            ctx.astModel,
            ctx.uri,
            ctx.content,
            ctx.line,
            ctx.character,
        )
        val completionContext =
            CompletionContextDetector.detectCompletionContext(nodeAtCursor, ctx.astModel, symbolContext)

        val jenkinsContext = resolveJenkinsContext(ctx, isJenkinsFile)
        val metadata = jenkinsContext.metadata

        return completions {
            addSpockBlockLabelsIfApplicable(ctx, completionContext, isSpockSpec)
            addLocalSymbolsIfApplicable(symbolContext, jenkinsContext.isStrictDeclarative)

            addJenkinsCompletionsIfApplicable(
                jenkinsContext = jenkinsContext,
                ctx = ctx,
                nodeAtCursor = nodeAtCursor,
            )

            if (handleContextualCompletions(completionContext, ctx, metadata)) {
                return@completions
            }
        }
    }

    private data class JenkinsContext(
        val metadata: MergedJenkinsMetadata?,
        val blockCategories: Set<DeclarativePipelineSchema.CompletionCategory>?,
        val innerInstructions: Set<String>?,
        val isStrictDeclarative: Boolean,
    )

    private fun resolveJenkinsContext(ctx: CompletionContext, isJenkinsFile: Boolean): JenkinsContext {
        if (!isJenkinsFile) {
            return JenkinsContext(
                metadata = null,
                blockCategories = null,
                innerInstructions = null,
                isStrictDeclarative = false,
            )
        }

        // Use text-based context detection (more robust during editing) instead of AST traversal.
        val detected = JenkinsContextDetector.detectFromDocument(
            ctx.content.lines(),
            ctx.line,
            ctx.character,
        )
        val metadata = ctx.compilationService.workspaceManager.getAllJenkinsMetadata()

        val currentBlock = detected.currentBlock
        val blockCategories = currentBlock?.let(DeclarativePipelineSchema::getCompletionCategories)
        val innerInstructions = currentBlock?.let(DeclarativePipelineSchema::getInnerInstructions)
        val isStrictDeclarative =
            detected.isDeclarativePipeline &&
                currentBlock != null &&
                currentBlock != "script"

        return JenkinsContext(
            metadata = metadata,
            blockCategories = blockCategories,
            innerInstructions = innerInstructions,
            isStrictDeclarative = isStrictDeclarative,
        )
    }

    private fun CompletionsBuilder.addLocalSymbolsIfApplicable(
        context: com.github.albertocavalcante.groovyparser.ast.SymbolCompletionContext,
        isStrictDeclarative: Boolean,
    ) {
        if (isStrictDeclarative) {
            return
        }

        addClasses(context.classes)
        addMethods(context.methods)
        addFields(context.fields)
        addVariables(context.variables)
        addImports(context.imports)
        addKeywords()

        // Add basic Groovy snippet completions (println, print, etc.)
        GroovyCompletions.basic().forEach(::add)
    }

    private fun CompletionsBuilder.addJenkinsCompletionsIfApplicable(
        jenkinsContext: JenkinsContext,
        ctx: CompletionContext,
        nodeAtCursor: ASTNode?,
    ) {
        val metadata = jenkinsContext.metadata ?: return

        with(JenkinsCompletionProvider) {
            // Suggest parameter map keys so we can complete named parameters
            addJenkinsMapKeyCompletions(ctx, nodeAtCursor, ctx.astModel, metadata)

            // Lenient step allowance: allow steps if not in a strict declarative block,
            // or if the block explicitly allows steps.
            val allowSteps =
                !jenkinsContext.isStrictDeclarative ||
                    jenkinsContext.blockCategories
                        ?.contains(DeclarativePipelineSchema.CompletionCategory.STEP) == true

            if (allowSteps) {
                addJenkinsStepCompletions(metadata)
            }

            addJenkinsGlobalVariables(metadata, ctx.compilationService)

            jenkinsContext.blockCategories?.let { categories ->
                if (categories.contains(DeclarativePipelineSchema.CompletionCategory.AGENT_TYPE)) {
                    addJenkinsAgentTypeCompletions()
                }
                if (categories.contains(DeclarativePipelineSchema.CompletionCategory.DECLARATIVE_OPTION)) {
                    addJenkinsDeclarativeOptions(metadata)
                }
                if (categories.contains(DeclarativePipelineSchema.CompletionCategory.POST_CONDITION)) {
                    addJenkinsPostConditionCompletions()
                }
            }
        }

        // Add inner instructions (sub-blocks) from schema
        jenkinsContext.innerInstructions?.forEach { instruction ->
            completion {
                label(instruction)
                kind(CompletionItemKind.Keyword)
                detail("Declarative directive")
                insertText("$instruction {")
                sortText("0-directive-$instruction")
            }
        }
    }

    private fun CompletionsBuilder.handleContextualCompletions(
        completionContext: ContextType?,
        ctx: CompletionContext,
        metadata: MergedJenkinsMetadata?,
    ): Boolean = when (completionContext) {
        is ContextType.MemberAccess -> handleMemberAccessContext(completionContext, ctx, metadata)
        is ContextType.TypeParameter -> {
            logger.debug("Adding type parameter classes for prefix '{}'", completionContext.prefix)
            addTypeParameterClasses(completionContext.prefix, ctx.compilationService)
            // Also add auto-import completions for unimported types
            addAutoImportCompletions(completionContext.prefix, ctx.uri, ctx.content, ctx.compilationService)
            false
        }

        null -> false
    }

    private fun CompletionsBuilder.handleMemberAccessContext(
        completionContext: ContextType.MemberAccess,
        ctx: CompletionContext,
        metadata: MergedJenkinsMetadata?,
    ): Boolean {
        val rawType = completionContext.qualifierType.substringBefore('<')
        val qualifierName = completionContext.qualifierName

        val globalVar = metadata
            ?.let { JenkinsCompletionProvider.findJenkinsGlobalVariable(qualifierName, rawType, it) }

        if (globalVar != null && globalVar.properties.isNotEmpty()) {
            logger.debug("Adding Jenkins properties for {}", qualifierName ?: rawType)
            addJenkinsGlobalVariablePropertyCompletions(globalVar)
            return true
        }

        logger.debug("Adding GDK/Classpath methods for {}", rawType)
        addGdkMethods(rawType, ctx.compilationService)
        addClasspathMethods(rawType, ctx.compilationService)
        return false
    }

    private fun CompletionsBuilder.addJenkinsGlobalVariablePropertyCompletions(globalVar: MergedGlobalVariable) {
        with(JenkinsCompletionProvider) {
            addJenkinsPropertyCompletions(globalVar)
        }
    }

    private fun CompletionsBuilder.addSpockBlockLabelsIfApplicable(
        ctx: CompletionContext,
        completionContext: ContextType?,
        isSpockSpec: Boolean,
    ) {
        if (!isSpockSpec) return
        if (completionContext != null) return
        if (!isLineIndentOnlyBeforeCursor(ctx.content, ctx.line, ctx.character)) return

        // Deterministic token-based suppression (replaces heuristic isCursorInLikelyCommentOrString)
        val offset = offsetAt(ctx.content, ctx.content.split('\n'), ctx.line, ctx.character)
        if (ctx.tokenIndex?.isInCommentOrString(offset) == true) return

        val labels = listOf(
            "given:" to "Spock setup block",
            "setup:" to "Spock setup block (alias of given)",
            "when:" to "Spock action block",
            "then:" to "Spock assertion block",
            "expect:" to "Spock combined when/then block",
            "where:" to "Spock data-driven block",
            "cleanup:" to "Spock cleanup block",
            "and:" to "Spock block continuation",
        )

        labels.forEach { (label, doc) ->
            completion {
                label(label)
                kind(CompletionItemKind.Keyword)
                detail("Spock block label")
                documentation(doc)
                insertText(label)
                // Sort ahead of general keywords
                sortText("0-$label")
            }
        }
    }

    private fun offsetAt(content: String, lines: List<String>, line: Int, character: Int): Int {
        var offset = 0
        for (i in 0 until line) {
            offset += lines[i].length + 1 // + '\n'
        }
        return (offset + character).coerceIn(0, content.length)
    }

    private fun isLineIndentOnlyBeforeCursor(content: String, line: Int, character: Int): Boolean {
        val lines = content.lines()
        if (line !in lines.indices) return false

        val target = lines[line]
        val safeChar = character.coerceIn(0, target.length)
        val prefix = target.substring(0, safeChar)

        // NOTE: Heuristic / tradeoff:
        // We treat "all whitespace before cursor" as a signal that the user is likely starting a Spock block label.
        // This avoids spamming completions mid-expression, but can still misfire in multiline strings/comments.
        // TODO: Use AST to detect LabeledStatement contexts and suppress inside strings/comments when feasible.
        return prefix.all { it == ' ' || it == '\t' }
    }

    internal data class ImportCompletionContext(
        val prefix: String,
        val isStatic: Boolean,
        val canSuggestStatic: Boolean,
        val line: Int,
        val replaceStartCharacter: Int,
        val replaceEndCharacter: Int,
    )
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

    private fun CompletionsBuilder.addClasses(classes: List<ClassSymbol>) {
        classes.forEach { classSymbol ->
            clazz(
                name = classSymbol.name,
                packageName = classSymbol.packageName,
                doc = "Class: ${classSymbol.name}",
            )
        }
    }

    private fun CompletionsBuilder.addMethods(methods: List<MethodSymbol>) {
        methods.forEach { methodSymbol ->
            val paramSignatures = methodSymbol.parameters.map { "${it.type} ${it.name}" }
            method(
                name = methodSymbol.name,
                returnType = methodSymbol.returnType,
                parameters = paramSignatures,
                doc = "Method: ${methodSymbol.name}",
            )
        }
    }

    private fun CompletionsBuilder.addFields(fields: List<FieldSymbol>) {
        fields.forEach { fieldSymbol ->
            field(
                name = fieldSymbol.name,
                type = fieldSymbol.type,
                doc = "Field: ${fieldSymbol.type} ${fieldSymbol.name}",
            )
        }
    }

    private fun CompletionsBuilder.addVariables(variables: List<VariableSymbol>) {
        variables.forEach { varSymbol ->
            val kind = varSymbol.kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            val docString = "$kind: ${varSymbol.type} ${varSymbol.name}"
            variable(
                name = varSymbol.name,
                type = varSymbol.type,
                doc = docString,
            )
        }
    }

    private fun CompletionsBuilder.addImports(imports: List<ImportSymbol>) {
        imports.forEach { importSymbol ->
            if (!importSymbol.isStarImport) {
                val name = importSymbol.className
                    ?: importSymbol.packageName.substringAfterLast('.')

                clazz(
                    name = name,
                    packageName = importSymbol.packageName,
                    doc = "Imported: ${importSymbol.packageName}.$name",
                )
            }
        }
    }

    private fun CompletionsBuilder.addKeywords() {
        val keywords = listOf(
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
        keywords.forEach { k ->
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
        if (ctx.canSuggestStatic) {
            keyword(
                keyword = "static",
                doc = "Static import",
            )
        }

        val prefix = ctx.prefix.trim()
        if (prefix.isBlank()) {
            // TODO(#575): Provide curated suggestions for empty import prefixes.
            //   See: https://github.com/albertocavalcante/gvy/issues/575
            return
        }

        val classpathService = compilationService.classpathService
        val candidates = if (prefix.contains('.')) {
            classpathService.findClassesByQualifiedPrefix(prefix, maxResults = MAX_IMPORT_COMPLETION_RESULTS)
        } else {
            classpathService.findClassesByPrefix(prefix, maxResults = MAX_IMPORT_COMPLETION_RESULTS)
        }

        val range = Range(
            Position(ctx.line, ctx.replaceStartCharacter),
            Position(ctx.line, ctx.replaceEndCharacter),
        )
        candidates
            .map { it.fullName }
            .distinct()
            .forEach { fullName ->
                add(
                    CompletionItem().apply {
                        label = fullName
                        kind = CompletionItemKind.Class
                        detail = fullName
                        insertText = fullName
                        textEdit = Either.forLeft(TextEdit(range, fullName))
                    },
                )
            }
    }

    /**
     * Add GDK (GroovyDevelopment Kit) method completions for a given type.
     */
    private fun CompletionsBuilder.addGdkMethods(className: String, compilationService: GroovyCompilationService) {
        val resolvedTypes = resolveDataTypes(className, compilationService)
        if (resolvedTypes.isEmpty()) {
            // Try original name as fallback
            addGdkMethodsForSingleType(className, compilationService, this)
        } else {
            resolvedTypes.forEach { type ->
                addGdkMethodsForSingleType(type, compilationService, this)
            }
        }
    }

    private fun addGdkMethodsForSingleType(
        className: String,
        compilationService: GroovyCompilationService,
        builder: CompletionsBuilder,
    ) {
        val gdkMethods = compilationService.gdkProvider.getMethodsForType(className)

        gdkMethods.forEach { gdkMethod ->
            builder.method(
                name = gdkMethod.name,
                returnType = gdkMethod.returnType,
                parameters = gdkMethod.parameters,
                doc = gdkMethod.doc,
            )
        }
    }

    /**
     * Add JDK/classpath method completions for a given type.
     */
    private fun CompletionsBuilder.addClasspathMethods(
        className: String,
        compilationService: GroovyCompilationService,
    ) {
        val resolvedTypes = resolveDataTypes(className, compilationService)
        if (resolvedTypes.isEmpty()) {
            addClasspathMethodsForSingleType(className, compilationService, this)
        } else {
            resolvedTypes.forEach { type ->
                addClasspathMethodsForSingleType(type, compilationService, this)
            }
        }
    }

    private fun addClasspathMethodsForSingleType(
        className: String,
        compilationService: GroovyCompilationService,
        builder: CompletionsBuilder,
    ) {
        val classpathMethods = compilationService.classpathService.getMethods(className)

        classpathMethods.forEach { method ->
            // Only add public instance methods
            if (method.isPublic && !method.isStatic) {
                builder.method(
                    name = method.name,
                    returnType = method.returnType,
                    parameters = method.parameters,
                    doc = method.doc,
                )
            }
        }
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
        logger.debug("Found {} classes for prefix {}", classes.size, prefix)

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
        uri: java.net.URI,
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
