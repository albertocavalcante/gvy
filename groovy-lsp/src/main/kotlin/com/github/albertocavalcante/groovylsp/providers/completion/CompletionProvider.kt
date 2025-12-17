package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.dsl.completion.CompletionsBuilder
import com.github.albertocavalcante.groovylsp.dsl.completion.GroovyCompletions
import com.github.albertocavalcante.groovylsp.dsl.completion.completions
import com.github.albertocavalcante.groovyparser.ast.ClassSymbol
import com.github.albertocavalcante.groovyparser.ast.FieldSymbol
import com.github.albertocavalcante.groovyparser.ast.ImportSymbol
import com.github.albertocavalcante.groovyparser.ast.MethodSymbol
import com.github.albertocavalcante.groovyparser.ast.SymbolCompletionContext
import com.github.albertocavalcante.groovyparser.ast.SymbolExtractor
import com.github.albertocavalcante.groovyparser.ast.VariableSymbol
import org.codehaus.groovy.control.CompilationFailedException
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.MarkupContent
import org.slf4j.LoggerFactory

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

            // If simple insertion failed and it was a clean insertion, try adding 'def'
            // This helps in class bodies: "class Foo { def BrazilWorldCup2026 }" is valid, but "class Foo { BrazilWorldCup2026 }" is not.
            if (isClean && !result1.isSuccessful) {
                val content2 = insertDummyIdentifier(content, line, character, withDef = true)
                val result2 = compilationService.compileTransient(uriObj, content2)

                // If 'def' strategy produced a better result (successful or fewer errors), use it
                if (result2.isSuccessful || result2.diagnostics.size < result1.diagnostics.size) {
                    val ast = result2.ast
                    val astModel = result2.astModel
                    if (ast != null) {
                        return buildCompletionsList(ast, astModel, line, character, compilationService, uriObj)
                    }
                }
            }

            // Fallback to result1 (simple insertion)
            val ast = result1.ast
            val astModel = result1.astModel

            if (ast == null) {
                emptyList()
            } else {
                buildCompletionsList(ast, astModel, line, character, compilationService, uriObj)
            }
        } catch (e: CompilationFailedException) {
            // If AST analysis fails, log and return empty list
            logger.debug("AST analysis failed for completion at {}:{}: {}", line, character, e.message)
            emptyList()
        }
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

        val targetLine = lines[line]
        // Ensure character is within bounds
        val safeChar = character.coerceIn(0, targetLine.length)

        // Insert dummy identifier
        val insertion = if (withDef) "def $DUMMY_IDENTIFIER" else DUMMY_IDENTIFIER
        val modifiedLine = targetLine.substring(0, safeChar) + insertion + targetLine.substring(safeChar)
        lines[line] = modifiedLine

        return lines.joinToString("\n")
    }

    private fun buildCompletionsList(
        ast: org.codehaus.groovy.ast.ASTNode,
        astModel: com.github.albertocavalcante.groovyparser.ast.GroovyAstModel,
        line: Int,
        character: Int,
        compilationService: GroovyCompilationService,
        uri: java.net.URI,
    ): List<CompletionItem> {
        // Extract completion context
        val context = SymbolExtractor.extractCompletionSymbols(ast, line, character)
        val isJenkinsFile = compilationService.workspaceManager.isJenkinsFile(uri)

        // Try to detect member access (e.g., "myList.")
        val nodeAtCursor = astModel.getNodeAt(uri, line, character)
        val completionContext = detectCompletionContext(nodeAtCursor, astModel, context)

        return completions {
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
                    logger.debug("Adding GDK/Classpath methods for {}", completionContext.qualifierType)
                    // Strip generics for method lookup (e.g. "ArrayList<String>" -> "ArrayList")
                    // Note: substringBefore('<') is safe even for nested generics (e.g. Map<String, List<Integer>>)
                    // because we only need the raw outer type to look up methods on the class itself.
                    val rawType = completionContext.qualifierType.substringBefore('<')
                    addGdkMethods(rawType, compilationService)
                    addClasspathMethods(rawType, compilationService)
                }

                is ContextType.TypeParameter -> {
                    logger.debug("Adding type parameter classes for prefix '{}'", completionContext.prefix)
                    addTypeParameterClasses(completionContext.prefix, compilationService)
                }

                null -> {
                    /* No special context */
                    if (isJenkinsFile) {
                        val metadata = compilationService.workspaceManager.getAllJenkinsMetadata()
                        if (metadata != null) {
                            // Best-effort: if inside a method call on root (e.g., sh(...)), suggest map keys.
                            addJenkinsMapKeyCompletions(nodeAtCursor, astModel, metadata)

                            // Suggest global variables from vars/ directory and plugins
                            addJenkinsGlobalVariables(metadata, compilationService)
                        }
                    }
                }
            }
        }
    }

    private fun CompletionsBuilder.addJenkinsGlobalVariables(
        metadata: com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadata,
        compilationService: GroovyCompilationService,
    ) {
        // 1. Add global variables from bundled plugin metadata
        val bundledCompletions = JenkinsStepCompletionProvider.getGlobalVariableCompletions(metadata)
        bundledCompletions.forEach { item ->
            val docString = when {
                item.documentation?.isRight == true -> (item.documentation.right as? MarkupContent)?.value
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
            variable(
                name = globalVar.name,
                type = "Closure",
                doc = globalVar.documentation.ifEmpty {
                    "Shared library global variable from vars/${globalVar.name}.groovy"
                },
            )
        }
    }

    private fun CompletionsBuilder.addJenkinsMapKeyCompletions(
        nodeAtCursor: org.codehaus.groovy.ast.ASTNode?,
        astModel: com.github.albertocavalcante.groovyparser.ast.GroovyAstModel,
        metadata: com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadata,
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
            // Case 1: Direct PropertyExpression (e.g., "myList.ea|")
            is org.codehaus.groovy.ast.expr.PropertyExpression -> {
                val objectExpr = nodeAtCursor.objectExpression
                var qualifierType = objectExpr.type?.name

                // If object is a variable, try to get inferred type from context
                if (objectExpr is org.codehaus.groovy.ast.expr.VariableExpression) {
                    val inferredType = resolveVariableType(objectExpr.name, context)
                    if (inferredType != null) {
                        qualifierType = inferredType
                    }
                }

                qualifierType?.let { ContextType.MemberAccess(it) }
            }

            // Case 2: VariableExpression that's part of a PropertyExpression or BinaryExpression
            // (e.g., cursor is on "myList" in "myList.")
            is org.codehaus.groovy.ast.expr.VariableExpression -> {
                if (parent is org.codehaus.groovy.ast.expr.PropertyExpression) {
                    var qualifierType = nodeAtCursor.type?.name

                    // Try to get inferred type from context
                    val inferredType = resolveVariableType(nodeAtCursor.name, context)
                    if (inferredType != null) {
                        qualifierType = inferredType
                    }

                    qualifierType?.let { ContextType.MemberAccess(it) }
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
            // (e.g., cursor lands on the dummy identifier "IntelliJIdeaRulezzz" in "myList.IntelliJIde aRulezzz")
            is org.codehaus.groovy.ast.expr.ConstantExpression -> {
                if (parent is org.codehaus.groovy.ast.expr.PropertyExpression) {
                    val objectExpr = parent.objectExpression
                    var qualifierType = objectExpr.type?.name

                    // If object is a variable, try to get inferred type from context
                    if (objectExpr is org.codehaus.groovy.ast.expr.VariableExpression) {
                        val inferredType = resolveVariableType(objectExpr.name, context)
                        if (inferredType != null) {
                            qualifierType = inferredType
                        }
                    }

                    qualifierType?.let { ContextType.MemberAccess(it) }
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

    private fun resolveVariableType(variableName: String, context: SymbolCompletionContext): String? {
        val inferredVar = context.variables.find { it.name == variableName }
        return inferredVar?.type
    }

    private sealed interface ContextType {
        data class MemberAccess(val qualifierType: String) : ContextType
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
     * Add GDK (GroovyDevelopment Kit) method completions for a given type.
     */
    private fun CompletionsBuilder.addGdkMethods(className: String, compilationService: GroovyCompilationService) {
        val gdkMethods = compilationService.gdkProvider.getMethodsForType(className)
        logger.debug("Found {} GDK methods for type {}", gdkMethods.size, className)

        gdkMethods.forEach { gdkMethod ->
            method(
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
        val classpathMethods = compilationService.classpathService.getMethods(className)
        logger.debug("Found {} classpath methods for type {}", classpathMethods.size, className)

        classpathMethods.forEach { method ->
            // Only add public instance methods
            if (method.isPublic && !method.isStatic) {
                method(
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
}
