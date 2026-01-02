package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyjenkins.metadata.MergedGlobalVariable
import com.github.albertocavalcante.groovyjenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.dsl.completion.CompletionsBuilder
import com.github.albertocavalcante.groovylsp.dsl.completion.GroovyCompletions
import com.github.albertocavalcante.groovylsp.dsl.completion.completions
import com.github.albertocavalcante.groovyparser.ast.ClassSymbol
import com.github.albertocavalcante.groovyparser.ast.FieldSymbol
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.ImportSymbol
import com.github.albertocavalcante.groovyparser.ast.MethodSymbol
import com.github.albertocavalcante.groovyparser.ast.SymbolCompletionContext
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
    private const val DUMMY_IDENTIFIER = "BrazilWorldCup2026"
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
            val uriObj = java.net.URI.create(uri)

            // Determine if we are inserting into an existing identifier
            val isClean = isCleanInsertion(content, line, character)

            // Strategy 1: Simple insertion (e.g. "myList.BrazilWorldCup2026")
            val content1 = insertDummyIdentifier(content, line, character, withDef = false)
            val result1 = compilationService.compileTransient(uriObj, content1)
            val ast1 = result1.ast
            val astModel1 = result1.astModel

            // If simple insertion failed and it was a clean insertion, try adding 'def'
            // This helps in class bodies: "class Foo { def BrazilWorldCup2026 }" is valid, but "class Foo { BrazilWorldCup2026 }" is not.
            if (isClean && !result1.isSuccessful) {
                val content2 = insertDummyIdentifier(content, line, character, withDef = true)
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

    private fun isCleanInsertion(content: String, line: Int, character: Int): Boolean {
        val lines = content.lines()
        if (line < 0 || line >= lines.size) return true

        val targetLine = lines[line]
        // Ensure character is within bounds
        val safeChar = character.coerceIn(0, targetLine.length)

        val charBefore = if (safeChar > 0) targetLine[safeChar - 1] else ' '
        val charAfter = if (safeChar < targetLine.length) targetLine[safeChar] else ' '

        // If surrounded by identifier parts, it's NOT a clean insertion (we are inside a word)
        return !Character.isJavaIdentifierPart(charBefore) && !Character.isJavaIdentifierPart(charAfter)
    }

    private fun insertDummyIdentifier(content: String, line: Int, character: Int, withDef: Boolean): String {
        val lines = content.lines().toMutableList()
        if (line < 0 || line >= lines.size) return content

        // Fix hanging assignments on previous line to ensure parser continues to current line
        if (line > 0) {
            val prevLineIdx = line - 1
            if (lines[prevLineIdx].trim().endsWith("=")) {
                lines[prevLineIdx] = lines[prevLineIdx] + " null;"
            }
        }

        val targetLine = lines[line]
        // Ensure character is within bounds
        val safeChar = character.coerceIn(0, targetLine.length)

        // Insert dummy identifier
        val insertion = if (withDef) "def $DUMMY_IDENTIFIER" else DUMMY_IDENTIFIER
        val modifiedLine = targetLine.substring(0, safeChar) + insertion + targetLine.substring(safeChar)
        lines[line] = modifiedLine

        return lines.joinToString("\n")
    }

    private fun buildCompletionsList(ctx: CompletionContext, isSpockSpec: Boolean): List<CompletionItem> {
        // Extract completion context
        val context = SymbolExtractor.extractCompletionSymbols(ctx.ast, ctx.line, ctx.character)
        val isJenkinsFile = ctx.compilationService.workspaceManager.isJenkinsFile(ctx.uri)
        val importContext = detectImportCompletionContext(ctx)

        if (importContext != null) {
            return completions {
                addImportCompletions(importContext, ctx.compilationService)
            }
        }

        // Try to detect member access (e.g., "myList.")
        val nodeAtCursor = ctx.astModel.getNodeAt(ctx.uri, ctx.line, ctx.character)
        val completionContext = detectCompletionContext(nodeAtCursor, ctx.astModel, context)

        return completions {
            addSpockBlockLabelsIfApplicable(ctx, completionContext, isSpockSpec)

            // Add local symbol completions
            addClasses(context.classes)
            addMethods(context.methods)
            addFields(context.fields)
            addVariables(context.variables)
            addImports(context.imports)
            addKeywords()

            // Handle contextual completions
            when (completionContext) {
                is ContextType.MemberAccess -> {
                    val rawType = completionContext.qualifierType.substringBefore('<')
                    val qualifierName = completionContext.qualifierName

                    // Check if this is a Jenkins global variable with properties (env, currentBuild)
                    if (isJenkinsFile) {
                        val metadata = ctx.compilationService.workspaceManager.getAllJenkinsMetadata()
                        if (metadata != null) {
                            val globalVar = findJenkinsGlobalVariable(qualifierName, rawType, metadata)

                            if (globalVar != null && globalVar.properties.isNotEmpty()) {
                                // Add Jenkins-specific property completions
                                logger.debug("Adding Jenkins properties for {}", qualifierName ?: rawType)
                                addJenkinsPropertyCompletions(globalVar)
                                return@completions
                            }
                        }
                    }

                    // Standard GDK/classpath methods (fallback)
                    logger.debug("Adding GDK/Classpath methods for {}", rawType)
                    addGdkMethods(rawType, ctx.compilationService)
                    addClasspathMethods(rawType, ctx.compilationService)
                }

                is ContextType.TypeParameter -> {
                    logger.debug("Adding type parameter classes for prefix '{}'", completionContext.prefix)
                    addTypeParameterClasses(completionContext.prefix, ctx.compilationService)
                    // Also add auto-import completions for unimported types
                    addAutoImportCompletions(completionContext.prefix, ctx.uri, ctx.content, ctx.compilationService)
                }

                null -> {
                    /* No special context */
                    if (isJenkinsFile) {
                        val metadata = ctx.compilationService.workspaceManager.getAllJenkinsMetadata()
                        if (metadata != null) {
                            // Best-effort: if inside a method call on root (e.g., sh(...)), suggest map keys.
                            addJenkinsMapKeyCompletions(nodeAtCursor, ctx.astModel, metadata)

                            // Suggest global variables from vars/ directory and plugins
                            addJenkinsGlobalVariables(metadata, ctx.compilationService)
                        }
                    }
                }
            }
        }
    }

    /**
     * Resolves a Jenkins global variable by name or type, with shadowing protection.
     */
    private fun findJenkinsGlobalVariable(
        name: String?,
        type: String,
        metadata: MergedJenkinsMetadata,
    ): MergedGlobalVariable? {
        // 1. Try to find by name (e.g. "env")
        if (name != null) {
            val byName = metadata.getGlobalVariable(name)
            if (byName != null) {
                // VERIFICATION: Check if the inferred type matches the global variable type
                // If it doesn't match (and isn't Object/dynamic), it's likely a shadowed variable.
                // We assume "java.lang.Object" might be an untyped 'def', so we allow it to fall back to the global.
                if (type != "java.lang.Object" && type != byName.type) {
                    return null
                }
                return byName
            }
        }

        // 2. Fallback: Find by type (e.g. "org.jenkinsci.plugins.workflow.cps.EnvActionImpl")
        // Note: metadata.globalVariables is keyed by variable name, so we scan values for matching type.
        return metadata.globalVariables.values.find { it.type == type }
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

    private fun CompletionsBuilder.addJenkinsGlobalVariables(
        metadata: MergedJenkinsMetadata,
        compilationService: GroovyCompilationService,
    ) {
        // 1. Add global variables from bundled plugin metadata
        val bundledCompletions = JenkinsStepCompletionProvider.getGlobalVariableCompletions(metadata)
        bundledCompletions.forEach { item ->
            val docString = when {
                item.documentation?.isRight == true -> item.documentation.right.value
                item.documentation?.isLeft == true -> item.documentation.left
                else -> null
            } ?: item.detail

            variable(
                name = item.label,
                type = item.detail?.substringAfterLast('.') ?: "Object",
                doc = docString ?: "Jenkins global variable",
            )
        }

        // 2. Add global variables from workspace vars/ directory
        // TODO: Consider caching these completions if performance becomes an issue
        val varsGlobals = compilationService.workspaceManager.getJenkinsGlobalVariables()
        varsGlobals.forEach { globalVar ->
            // Use function() to insert as method call with parens: buildPlugin($1)
            function(
                name = globalVar.name,
                returnType = "Object",
                doc = globalVar.documentation.ifEmpty {
                    "Shared library global variable from vars/${globalVar.name}.groovy"
                },
            )
        }
    }

    /**
     * Add property completions for Jenkins global variables (env, currentBuild).
     */
    private fun CompletionsBuilder.addJenkinsPropertyCompletions(globalVar: MergedGlobalVariable) {
        globalVar.properties.forEach { (name, prop) ->
            property(
                name = name,
                type = prop.type,
                doc = prop.description,
            )
        }
    }

    private fun CompletionsBuilder.addJenkinsMapKeyCompletions(
        nodeAtCursor: org.codehaus.groovy.ast.ASTNode?,
        astModel: com.github.albertocavalcante.groovyparser.ast.GroovyAstModel,
        metadata: MergedJenkinsMetadata,
    ) {
        val methodCall = findEnclosingMethodCall(nodeAtCursor, astModel)
        val callName = methodCall?.methodAsString ?: return

        // Collect already specified argument map keys if present
        val existingKeys = mutableSetOf<String>()
        val args = methodCall.arguments
        if (args is org.codehaus.groovy.ast.expr.ArgumentListExpression) {
            args.expressions.filterIsInstance<org.codehaus.groovy.ast.expr.MapExpression>().forEach { mapExpr ->
                mapExpr.mapEntryExpressions.forEach { entry ->
                    val key = entry.keyExpression.text.removeSuffix(":")
                    existingKeys.add(key)
                }
            }
        }

        val bundledParamCompletions = JenkinsStepCompletionProvider.getParameterCompletions(
            callName,
            existingKeys,
            metadata,
        )
        bundledParamCompletions.forEach(::add)
    }

    private fun findEnclosingMethodCall(
        node: org.codehaus.groovy.ast.ASTNode?,
        astModel: com.github.albertocavalcante.groovyparser.ast.GroovyAstModel,
    ): org.codehaus.groovy.ast.expr.MethodCallExpression? {
        var current: org.codehaus.groovy.ast.ASTNode? = node
        while (current != null) {
            if (current is org.codehaus.groovy.ast.expr.MethodCallExpression) {
                return current
            }
            current = astModel.getParent(current)
        }
        return null
    }

    /**
     * Detect completion context (member access or type parameter).
     */
    private fun detectCompletionContext(
        nodeAtCursor: org.codehaus.groovy.ast.ASTNode?,
        astModel: com.github.albertocavalcante.groovyparser.ast.GroovyAstModel,
        context: SymbolCompletionContext,
    ): ContextType? {
        if (nodeAtCursor == null) {
            return null
        }

        // Check parent as well
        val parent = astModel.getParent(nodeAtCursor)

        return when (nodeAtCursor) {
            // Case 1: Direct PropertyExpression (e.g., "myList.ea|" or "env.BUILD|")
            is org.codehaus.groovy.ast.expr.PropertyExpression -> {
                val objectExpr = nodeAtCursor.objectExpression
                resolveQualifier(objectExpr, context)?.let { (type, name) ->
                    ContextType.MemberAccess(type, name)
                }
            }

            // Case 2: VariableExpression that's part of a PropertyExpression or BinaryExpression
            // (e.g., cursor is on "myList" in "myList.")
            is org.codehaus.groovy.ast.expr.VariableExpression -> {
                if (parent is org.codehaus.groovy.ast.expr.PropertyExpression) {
                    var qualifierType = nodeAtCursor.type?.name
                    val qualifierName = nodeAtCursor.name

                    // Try to get inferred type from context
                    val inferredType = resolveVariableType(nodeAtCursor.name, context)
                    if (inferredType != null) {
                        qualifierType = inferredType
                    }

                    qualifierType?.let { ContextType.MemberAccess(it, qualifierName) }
                } else if (parent is org.codehaus.groovy.ast.expr.BinaryExpression &&
                    parent.operation.text == "<" &&
                    nodeAtCursor.name.contains(DUMMY_IDENTIFIER)
                ) {
                    val prefix = nodeAtCursor.name.substringBefore(DUMMY_IDENTIFIER)
                    ContextType.TypeParameter(prefix)
                } else {
                    null
                }
            }

            // Case 3: ConstantExpression that's the property in a PropertyExpression
            // (e.g., cursor lands on the dummy identifier in "myList.IntelliJIdeaRulezzz")
            is org.codehaus.groovy.ast.expr.ConstantExpression -> {
                if (parent is org.codehaus.groovy.ast.expr.PropertyExpression) {
                    val objectExpr = parent.objectExpression
                    resolveQualifier(objectExpr, context)?.let { (type, name) ->
                        ContextType.MemberAccess(type, name)
                    }
                } else {
                    null
                }
            }

            // Case 4: ClassExpression (e.g. "List<String>")
            is org.codehaus.groovy.ast.expr.ClassExpression -> {
                val generics = nodeAtCursor.type.genericsTypes
                if (generics != null && generics.isNotEmpty()) {
                    // Check if any generic type matches our dummy identifier
                    val dummyGeneric = generics.find { it.name.contains(DUMMY_IDENTIFIER) }
                    if (dummyGeneric != null) {
                        // Extract prefix (everything before the dummy identifier)
                        val prefix = dummyGeneric.name.substringBefore(DUMMY_IDENTIFIER)
                        ContextType.TypeParameter(prefix)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            // Case 5: Method call expression (might be incomplete)
            is org.codehaus.groovy.ast.expr.MethodCallExpression -> {
                null // For now, don't handle method calls
            }

            else -> {
                null
            }
        }
    }

    private data class ImportCompletionContext(
        val prefix: String,
        val isStatic: Boolean,
        val canSuggestStatic: Boolean,
        val line: Int,
        val replaceStartCharacter: Int,
        val replaceEndCharacter: Int,
    )

    private fun detectImportCompletionContext(ctx: CompletionContext): ImportCompletionContext? {
        val lines = ctx.content.split('\n')
        if (ctx.line !in lines.indices) return null

        val lineText = lines[ctx.line]
        val safeChar = ctx.character.coerceIn(0, lineText.length)
        val beforeCursor = lineText.substring(0, safeChar)
        val trimmed = beforeCursor.trimStart()
        val importKeyword = "import"
        if (!trimmed.startsWith(importKeyword)) return null
        if (trimmed.length > importKeyword.length &&
            Character.isJavaIdentifierPart(trimmed[importKeyword.length])
        ) {
            return null
        }

        val offset = offsetAt(ctx.content, lines, ctx.line, ctx.character)
        if (ctx.tokenIndex?.isInCommentOrString(offset) == true) return null

        val staticKeyword = "static"
        val importColumn = lineText.indexOf(importKeyword)
        if (importColumn == -1 || importColumn + importKeyword.length > safeChar) return null

        val afterImportSlice = lineText.substring(importColumn + importKeyword.length, safeChar)
        val afterImportTrimStart = afterImportSlice.indexOfFirst { !it.isWhitespace() }
        if (afterImportTrimStart == -1) {
            return ImportCompletionContext(
                prefix = "",
                isStatic = false,
                canSuggestStatic = true,
                line = ctx.line,
                replaceStartCharacter = safeChar,
                replaceEndCharacter = safeChar,
            )
        }

        val afterImportTrimmed = afterImportSlice.substring(afterImportTrimStart)
        val afterImportStart = importColumn + importKeyword.length + afterImportTrimStart
        val hasStaticKeyword = afterImportTrimmed.startsWith(staticKeyword) &&
            (
                afterImportTrimmed.length == staticKeyword.length ||
                    afterImportTrimmed[staticKeyword.length].isWhitespace()
                )
        if (hasStaticKeyword) {
            val afterStaticIndex = afterImportStart + staticKeyword.length
            val afterStaticSlice = lineText.substring(afterStaticIndex, safeChar)
            val afterStaticTrimStart = afterStaticSlice.indexOfFirst { !it.isWhitespace() }
            if (afterStaticTrimStart == -1) {
                return ImportCompletionContext(
                    prefix = "",
                    isStatic = true,
                    canSuggestStatic = false,
                    line = ctx.line,
                    replaceStartCharacter = safeChar,
                    replaceEndCharacter = safeChar,
                )
            }

            val prefix = afterStaticSlice.substring(afterStaticTrimStart)
            val prefixStart = afterStaticIndex + afterStaticTrimStart
            return ImportCompletionContext(
                prefix = prefix,
                isStatic = true,
                canSuggestStatic = false,
                line = ctx.line,
                replaceStartCharacter = prefixStart,
                replaceEndCharacter = safeChar,
            )
        }

        val isTypingStatic =
            !afterImportTrimmed.any { it.isWhitespace() } && staticKeyword.startsWith(afterImportTrimmed)
        // TODO(#576): Consider suggesting both "static" and matching class prefixes when overlapping.
        //   See: https://github.com/albertocavalcante/gvy/issues/576
        val prefix = if (isTypingStatic) "" else afterImportTrimmed
        val replaceStart = if (isTypingStatic) safeChar else afterImportStart
        return ImportCompletionContext(
            prefix = prefix,
            isStatic = false,
            canSuggestStatic = true,
            line = ctx.line,
            replaceStartCharacter = replaceStart,
            replaceEndCharacter = safeChar,
        )
    }

    private fun resolveVariableType(variableName: String, context: SymbolCompletionContext): String? {
        val inferredVar = context.variables.find { it.name == variableName }
        return inferredVar?.type
    }

    private fun resolveQualifier(
        objectExpr: org.codehaus.groovy.ast.expr.Expression,
        context: SymbolCompletionContext,
    ): Pair<String, String?>? {
        var qualifierType = objectExpr.type?.name
        var qualifierName: String? = null

        // If object is a variable, capture its name for Jenkins global matching
        if (objectExpr is org.codehaus.groovy.ast.expr.VariableExpression) {
            qualifierName = objectExpr.name
            val inferredType = resolveVariableType(objectExpr.name, context)
            if (inferredType != null) {
                qualifierType = inferredType
            }
        }

        return qualifierType?.let { it to qualifierName }
    }

    private sealed interface ContextType {
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
        if (prefix.isBlank()) return

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
