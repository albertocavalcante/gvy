package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyjenkins.completion.JenkinsContextDetector
import com.github.albertocavalcante.groovyjenkins.metadata.MergedGlobalVariable
import com.github.albertocavalcante.groovyjenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.declarative.DeclarativePipelineSchema
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.dsl.completion.CompletionsBuilder
import com.github.albertocavalcante.groovylsp.dsl.completion.GroovyCompletions
import com.github.albertocavalcante.groovylsp.dsl.completion.completions
import com.github.albertocavalcante.groovylsp.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.groovyspock.SpockDetector
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.SemanticTypeFormatter
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.native.ClassSymbol
import com.github.albertocavalcante.gvy.semantics.native.DeclarationWalker
import com.github.albertocavalcante.gvy.semantics.native.FieldSymbol
import com.github.albertocavalcante.gvy.semantics.native.ImportSymbol
import com.github.albertocavalcante.gvy.semantics.native.MethodSymbol
import com.github.albertocavalcante.gvy.semantics.native.SymbolCompletionContext
import com.github.albertocavalcante.gvy.semantics.native.SymbolExtractor
import com.github.albertocavalcante.gvy.semantics.native.VariableSymbol
import com.github.albertocavalcante.gvy.semantics.workspace.MemberInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.control.CompilationFailedException
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.lang.reflect.Modifier
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
                            semanticResolver = semanticResolver,
                            moduleNode = ast2,
                            workspaceSymbolIndex = compilationService.getWorkspaceSymbolIndex(),
                        ),
                        isSpockSpec = isSpockSpec,
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
                        semanticResolver = semanticResolver,
                        moduleNode = ast1,
                        workspaceSymbolIndex = compilationService.getWorkspaceSymbolIndex(),
                    ),
                    isSpockSpec = isSpockSpec,
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
        ctx.moduleNode?.let { ctx.semanticResolver.semantics.inject(it) }
        val symbolContext = SymbolExtractor.extractCompletionSymbols(
            ctx.ast,
            ctx.line,
            ctx.character,
            ctx.semanticResolver.semantics,
        )
        val isJenkinsFile =
            ctx.compilationService.workspaceManager.getJenkinsCapabilities()?.isJenkinsFile(ctx.uri) ?: false

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
            CompletionContextDetector.detectCompletionContext(
                nodeAtCursor,
                ctx.astModel,
                ctx.semanticResolver,
                ctx.moduleNode,
            )

        val jenkinsContext = resolveJenkinsContext(ctx, isJenkinsFile)
        val metadata = jenkinsContext.metadata

        return completions {
            addSpockBlockLabelsIfApplicable(ctx, completionContext, isSpockSpec)

            // For member access context (e.g., "p."), only add member completions
            // Skip local symbols, keywords, etc. as they are not relevant for member completion
            if (completionContext is ContextType.MemberAccess) {
                handleMemberAccessContext(completionContext, ctx, metadata)
                return@completions
            }

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
        val metadata = ctx.compilationService.workspaceManager.getJenkinsCapabilities()?.getAllMetadata()

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
        context: SymbolCompletionContext,
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
                // TODO(#657): Refactor to use a determined JenkinsCompletionStrategy.
                addJenkinsStepCompletions(metadata)
            }

            addJenkinsGlobalVariables(metadata, ctx.compilationService.workspaceManager.getJenkinsCapabilities())

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
     * Adds workspace members (fields, methods, properties) to completions.
     *
     * @param members List of member information from WorkspaceSymbolIndex
     */
    private fun CompletionsBuilder.addWorkspaceMembers(members: List<MemberInfo>) {
        members.forEach { member ->
            when (member.kind) {
                SymbolKind.FIELD,
                SymbolKind.PROPERTY,
                -> {
                    field(
                        name = member.name,
                        type = member.type?.let { formatType(it) } ?: "def",
                        doc = "Field: ${member.name}",
                    )
                }

                SymbolKind.METHOD -> {
                    method(
                        name = member.name,
                        returnType = member.type?.let { formatType(it) } ?: "def",
                        parameters = parseSignatureToParams(member.signature),
                        doc = "Method: ${member.name}${member.signature ?: "()"}",
                    )
                }

                else -> {
                    /* Skip constructors and other kinds */
                }
            }
        }
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

    private fun CompletionsBuilder.handleMemberAccessContext(
        completionContext: ContextType.MemberAccess,
        ctx: CompletionContext,
        metadata: MergedJenkinsMetadata?,
    ): Boolean {
        val rawType = completionContext.qualifierType.substringBefore('<')
        val qualifierName = completionContext.qualifierName

        // Strategy 0: Map literal keys (check first - most specific)
        // Strategy 0: Map literal keys (check first - most specific)
        if (rawType.endsWith("Map") && qualifierName != null) {
            addMapLiteralKeyCompletions(qualifierName, ctx)
            // Don't return early - also add standard map methods below
        }

        // Strategy 1: Jenkins global variables
        val globalVar = metadata
            ?.let { JenkinsCompletionProvider.findJenkinsGlobalVariable(qualifierName, rawType, it) }

        if (globalVar != null && globalVar.properties.isNotEmpty()) {
            logger.debug { "Adding Jenkins properties for ${qualifierName ?: rawType}" }
            addJenkinsGlobalVariablePropertyCompletions(globalVar)
            return true
        }

        // Strategy 2: Workspace members (cross-file classes)
        ctx.workspaceSymbolIndex?.let { index ->
            val resolvedFqns = resolveWorkspaceClassFqns(rawType, ctx)
            val members = resolvedFqns
                .flatMap { fqn -> index.getAllMembers(fqn.replace('.', '/'), includeInherited = true) }
                .distinctBy { it.symbolId }
            if (members.isNotEmpty()) {
                logger.debug { "Adding workspace members for $rawType (found ${members.size} members)" }
                addWorkspaceMembers(members)
            }
        }

        // Strategy 3: GDK methods
        logger.debug { "Adding GDK methods for $rawType" }
        addGdkMethods(rawType, ctx.compilationService)

        // Strategy 4: Classpath methods
        logger.debug { "Adding Classpath methods for $rawType" }
        addClasspathMethods(rawType, ctx.compilationService)

        return false
    }

    private data class TextImportInfo(
        val packageName: String?,
        val explicitImports: Set<String>,
        val starImports: Set<String>,
    )

    private fun resolveWorkspaceClassFqns(rawType: String, ctx: CompletionContext): List<String> {
        if (rawType.contains('.')) return listOf(rawType)

        val candidates = linkedSetOf<String>()
        val simpleName = rawType
        val importInfo = ctx.moduleNode?.let { moduleNode ->
            TextImportInfo(
                packageName = moduleNode.packageName,
                explicitImports = moduleNode.imports.mapNotNull { it.className }.toSet(),
                starImports = moduleNode.starImports.mapNotNull { it.packageName }.toSet(),
            )
        } ?: parseTextImportInfo(ctx.content)

        importInfo.packageName?.takeIf { it.isNotBlank() }?.let { candidates.add("$it.$simpleName") }
        importInfo.explicitImports
            .filter { it.substringAfterLast('.') == simpleName }
            .forEach { candidates.add(it) }
        importInfo.starImports.forEach { candidates.add("$it.$simpleName") }

        findWorkspaceClassFqnsBySimpleName(simpleName, ctx.compilationService)
            .forEach { candidates.add(it) }

        return candidates.toList()
    }

    private fun findWorkspaceClassFqnsBySimpleName(
        simpleName: String,
        compilationService: GroovyCompilationService,
    ): List<String> {
        val matches = linkedSetOf<String>()
        compilationService.getAllSymbolStorages().forEach { (uri, index) ->
            index.getSymbols(uri)
                .filterIsInstance<Symbol.Class>()
                .filter { it.name == simpleName }
                .forEach { matches.add(it.fullyQualifiedName) }
        }
        return matches.toList()
    }

    private fun findWorkspaceClassSymbols(
        className: String,
        compilationService: GroovyCompilationService,
    ): List<Symbol.Class> {
        val matches = mutableListOf<Symbol.Class>()
        val isFqn = className.contains('.')
        compilationService.getAllSymbolStorages().forEach { (uri, index) ->
            index.getSymbols(uri)
                .filterIsInstance<Symbol.Class>()
                .filter { symbol ->
                    if (isFqn) symbol.fullyQualifiedName == className else symbol.name == className
                }
                .forEach { matches.add(it) }
        }
        return matches
    }

    private fun parseTextImportInfo(content: String): TextImportInfo {
        var packageName: String? = null
        val explicitImports = mutableSetOf<String>()
        val starImports = mutableSetOf<String>()

        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("package ") -> {
                    packageName = trimmed.removePrefix("package ").removeSuffix(";").trim()
                }

                trimmed.startsWith("import static ") -> Unit
                trimmed.startsWith("import ") -> {
                    val value = trimmed.removePrefix("import ").removeSuffix(";").trim()
                    if (value.endsWith(".*")) {
                        starImports.add(value.removeSuffix(".*"))
                    } else {
                        explicitImports.add(value)
                    }
                }

                isCodeDeclarationLine(trimmed) -> break
            }
        }

        return TextImportInfo(packageName, explicitImports, starImports)
    }

    /**
     * Matches Groovy code declarations with optional modifiers, such as:
     *   class Foo
     *   public class Foo
     *   private static final class Foo
     *   def bar()
     *   public def bar()
     */
    private val CODE_DECLARATION_PATTERN =
        Regex("""^(?:(?:public|protected|private|static|final|abstract)\s+)*(class|interface|enum|trait|def)\b""")

    private fun isCodeDeclarationLine(trimmed: String): Boolean =
        (trimmed.startsWith("@") && !trimmed.startsWith("@interface")) ||
            CODE_DECLARATION_PATTERN.containsMatchIn(trimmed)

    /**
     * Finds the enclosing block for the cursor position.
     * Returns the method body block if inside a method, or the script's statement block.
     */
    private fun findEnclosingBlock(ctx: CompletionContext): BlockStatement? {
        val moduleNode = ctx.moduleNode ?: return null

        // Check if cursor is inside any class method
        for (classNode in moduleNode.classes) {
            // Use findLast to prefer the innermost scope if a method has invalid end line (effectively infinite range)
            val method = classNode.methods.findLast { method ->
                method.lineNumber > 0 &&
                    method.lineNumber <= ctx.line + 1 &&
                    (method.lastLineNumber >= ctx.line + 1 || method.lastLineNumber <= 0)
            }
            if (method?.code is BlockStatement) {
                return method.code as BlockStatement
            }
        }

        // Fallback to script-level statement block
        return moduleNode.statementBlock
    }

    /**
     * Adds map literal key completions for a variable with map literal initializer.
     */
    private fun CompletionsBuilder.addMapLiteralKeyCompletions(
        qualifierName: String,
        ctx: CompletionContext,
    ): Boolean {
        val moduleNode = ctx.moduleNode ?: return false
        val nativeContext = ctx.semanticResolver.semantics.getContext(moduleNode)
            ?: return false

        val block = findEnclosingBlock(ctx) ?: return false
        val result = DeclarationWalker.walk(block, nativeContext, captureMapKeys = true)

        // Use findLast to get the innermost scope declaration (handles variable shadowing)
        val mapDecl = result.variables.findLast { it.name == qualifierName }
        val mapKeys = mapDecl?.mapKeys

        if (mapKeys.isNullOrEmpty()) return false

        logger.debug { "Adding map literal keys for '$qualifierName': ${mapKeys.map { it.key }}" }

        mapKeys.forEach { keyInfo ->
            property(
                name = keyInfo.key,
                type = formatType(keyInfo.valueType),
                doc = "Map key '${keyInfo.key}'",
            )
        }
        return true
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

        // Handle static member completion (e.g., "import static java.lang.Math.PI")
        // Only if we can actually find the class on the classpath
        val staticClassName = when {
            ctx.isStaticMemberCompletion -> ctx.staticClassName
            ctx.isStatic && ctx.prefix.contains('.') -> ctx.prefix.substringBeforeLast('.')
            else -> null
        }
        val staticMemberPrefix = when {
            ctx.isStaticMemberCompletion -> ""
            ctx.isStatic && ctx.prefix.contains('.') -> ctx.prefix.substringAfterLast('.')
            else -> null
        }
        if (staticClassName != null) {
            val workspaceFound = addWorkspaceStaticMemberCompletions(
                className = staticClassName,
                compilationService = compilationService,
                ctx = ctx,
                memberPrefix = staticMemberPrefix,
            )
            val classpathFound = classpathService.loadClass(staticClassName) != null
            if (classpathFound) {
                addStaticMethodCompletions(staticClassName, compilationService, ctx, staticMemberPrefix)
                addStaticFieldCompletions(staticClassName, compilationService, ctx, staticMemberPrefix)
            }
            if (workspaceFound || classpathFound) {
                return
            }
        }
        // If className is not found, fall through to normal class completion
        // (it's likely a package path, e.g., "import static org.junit.")

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

    private fun CompletionsBuilder.addWorkspaceStaticMemberCompletions(
        className: String,
        compilationService: GroovyCompilationService,
        ctx: ImportCompletionContext,
        memberPrefix: String?,
    ): Boolean {
        val classSymbols = findWorkspaceClassSymbols(className, compilationService)
        if (classSymbols.isEmpty()) return false

        val range = Range(
            Position(ctx.line, ctx.replaceStartCharacter),
            Position(ctx.line, ctx.replaceEndCharacter),
        )

        var added = false
        val staticFieldNames = mutableSetOf<String>()
        classSymbols.forEach { classSymbol ->
            val qualifier = classSymbol.fullyQualifiedName.ifBlank { className }
            classSymbol.methods
                .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }
                .filter { memberPrefix.isNullOrEmpty() || it.name.startsWith(memberPrefix) }
                .forEach { method ->
                    val returnType = method.returnType?.nameWithoutPackage ?: "def"
                    val params = method.parameters.joinToString(", ") { it.type.nameWithoutPackage }
                    add(
                        CompletionItem().apply {
                            label = method.name
                            kind = CompletionItemKind.Method
                            detail = "$returnType ${method.name}($params)"
                            insertText = method.name
                            textEdit = Either.forLeft(TextEdit(range, "$qualifier.${method.name}"))
                        },
                    )
                    added = true
                }

            classSymbol.fields
                .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }
                .filter { memberPrefix.isNullOrEmpty() || it.name.startsWith(memberPrefix) }
                .forEach { field ->
                    val type = field.type?.nameWithoutPackage ?: "def"
                    add(
                        CompletionItem().apply {
                            label = field.name
                            kind = if (Modifier.isFinal(field.modifiers)) {
                                CompletionItemKind.Constant
                            } else {
                                CompletionItemKind.Field
                            }
                            detail = "$type ${field.name}"
                            insertText = field.name
                            textEdit = Either.forLeft(TextEdit(range, "$qualifier.${field.name}"))
                        },
                    )
                    staticFieldNames.add(field.name)
                    added = true
                }

            classSymbol.properties
                .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }
                .filter { property -> property.name !in staticFieldNames }
                .filter { property -> memberPrefix.isNullOrEmpty() || property.name.startsWith(memberPrefix) }
                .forEach { property ->
                    val type = property.type?.nameWithoutPackage ?: "def"
                    add(
                        CompletionItem().apply {
                            label = property.name
                            kind = CompletionItemKind.Property
                            detail = "$type ${property.name}"
                            insertText = property.name
                            textEdit = Either.forLeft(TextEdit(range, "$qualifier.${property.name}"))
                        },
                    )
                    added = true
                }
        }
        return added
    }

    /**
     * Adds completions for static methods when completing static imports.
     */
    private fun CompletionsBuilder.addStaticMethodCompletions(
        className: String,
        compilationService: GroovyCompilationService,
        ctx: ImportCompletionContext,
        memberPrefix: String? = null,
    ) {
        val methods = compilationService.classpathService.getMethods(className)
            .filter { it.isStatic && it.isPublic }
            .filter { memberPrefix.isNullOrEmpty() || it.name.startsWith(memberPrefix) }

        val range = Range(
            Position(ctx.line, ctx.replaceStartCharacter),
            Position(ctx.line, ctx.replaceEndCharacter),
        )

        methods.forEach { method ->
            add(
                CompletionItem().apply {
                    label = method.name
                    kind = CompletionItemKind.Method
                    detail = "${method.returnType} ${method.name}(${method.parameters.joinToString(", ")})"
                    insertText = method.name
                    textEdit = Either.forLeft(TextEdit(range, "$className.${method.name}"))
                },
            )
        }
    }

    /**
     * Adds completions for static fields and constants when completing static imports.
     */
    private fun CompletionsBuilder.addStaticFieldCompletions(
        className: String,
        compilationService: GroovyCompilationService,
        ctx: ImportCompletionContext,
        memberPrefix: String? = null,
    ) {
        val fields = compilationService.classpathService.getFields(className)
            .filter { it.isStatic && it.isPublic }
            .filter { memberPrefix.isNullOrEmpty() || it.name.startsWith(memberPrefix) }

        val range = Range(
            Position(ctx.line, ctx.replaceStartCharacter),
            Position(ctx.line, ctx.replaceEndCharacter),
        )

        fields.forEach { field ->
            add(
                CompletionItem().apply {
                    label = field.name
                    kind = if (field.isFinal) CompletionItemKind.Constant else CompletionItemKind.Field
                    detail = "${field.type} ${field.name}"
                    textEdit = Either.forLeft(TextEdit(range, "$className.${field.name}"))
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
                parameters = gdkMethod.parameterTypes,
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
