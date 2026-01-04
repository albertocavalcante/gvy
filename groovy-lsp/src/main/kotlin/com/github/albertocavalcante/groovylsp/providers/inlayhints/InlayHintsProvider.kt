package com.github.albertocavalcante.groovylsp.providers.inlayhints

import com.github.albertocavalcante.groovycommon.text.formatTypeName
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.InlayHintsConfiguration
import com.github.albertocavalcante.groovylsp.services.ReflectedMethod
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.TypeInferencer
import com.github.albertocavalcante.groovyparser.ast.isDynamic
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI

private const val MAX_PARENT_SEARCH_DEPTH = 10

private val primitiveTypeAliases = mapOf(
    "boolean" to "Boolean",
    "byte" to "Byte",
    "short" to "Short",
    "char" to "Character",
    "int" to "Integer",
    "long" to "Long",
    "float" to "Float",
    "double" to "Double",
)

private val javaLangTypeAliases = mapOf(
    "Object" to "java.lang.Object",
    "String" to "java.lang.String",
    "Number" to "java.lang.Number",
    "Boolean" to "java.lang.Boolean",
    "Byte" to "java.lang.Byte",
    "Short" to "java.lang.Short",
    "Character" to "java.lang.Character",
    "Integer" to "java.lang.Integer",
    "Long" to "java.lang.Long",
    "Float" to "java.lang.Float",
    "Double" to "java.lang.Double",
    "Void" to "java.lang.Void",
)

/**
 * Represents the outcome of resolving parameter names for a callable.
 */
private sealed class ResolutionResult {
    /**
     * A single best match was found.
     *
     * @property parameterNames Parameter names in declaration order.
     */
    data class Match(val parameterNames: List<String>) : ResolutionResult()

    /**
     * No candidates matched the call site.
     */
    data object NotFound : ResolutionResult()

    /**
     * Multiple candidates matched with no clear winner.
     */
    data object Ambiguous : ResolutionResult()
}

/**
 * Minimal signature needed for overload matching and hint labels.
 */
private data class CallableSignature(val parameterNames: List<String>, val parameterTypes: List<String>)

/**
 * Provides LSP Inlay Hints for Groovy source files.
 * Supports type hints for `def` variables and parameter hints for method/constructor calls.
 *
 * @param compilationService The service providing AST models for source files.
 * @param config The configuration settings for inlay hints.
 */
class InlayHintsProvider(
    private val compilationService: GroovyCompilationService,
    private val config: InlayHintsConfiguration = InlayHintsConfiguration(),
) {
    private val logger = LoggerFactory.getLogger(InlayHintsProvider::class.java)

    private data class NodeProcessingContext(
        val range: org.eclipse.lsp4j.Range,
        val astModel: GroovyAstModel,
        val symbolTable: SymbolTable?,
        val workspaceSymbols: List<Symbol>,
        val hints: MutableList<InlayHint>,
    )

    /**
     * Provides a list of inlay hints for the given document and range.
     *
     * @param params The inlay hint parameters containing the document URI and range.
     * @return A list of [InlayHint] objects for the specified range.
     */
    fun provideInlayHints(params: InlayHintParams): List<InlayHint> {
        val uri = URI.create(params.textDocument.uri)
        val astModel = compilationService.getAstModel(uri)

        if (astModel == null) {
            logger.debug("No AST model available for $uri")
            return emptyList()
        }
        val symbolTable = compilationService.getSymbolTable(uri)
        val workspaceSymbols = if (config.parameterHints) {
            compilationService.getAllSymbolStorages()
                .values
                .flatMap { index -> index.symbols.values.flatten() }
        } else {
            emptyList()
        }

        val hints = mutableListOf<InlayHint>()
        val context = NodeProcessingContext(
            range = params.range,
            astModel = astModel,
            symbolTable = symbolTable,
            workspaceSymbols = workspaceSymbols,
            hints = hints,
        )

        // Traverse all nodes and collect hints within the requested range
        astModel.getAllNodes().forEach { node ->
            processNode(node, context)
        }

        logger.debug("Returning ${hints.size} inlay hints for ${params.textDocument.uri}")
        return hints
    }

    private fun processNode(node: ASTNode, context: NodeProcessingContext) {
        // Filter nodes outside the requested range (1-indexed to 0-indexed conversion)
        val nodeLine = node.lineNumber - 1
        if (nodeLine < context.range.start.line || nodeLine > context.range.end.line) {
            return
        }

        when (node) {
            is DeclarationExpression -> {
                if (config.typeHints) {
                    collectTypeHint(node)?.let { context.hints.add(it) }
                }
            }

            is MethodCallExpression -> {
                if (config.parameterHints) {
                    collectParameterHints(
                        node,
                        context.astModel,
                        context.symbolTable,
                        context.workspaceSymbols,
                        context.hints,
                    )
                }
            }

            is ConstructorCallExpression -> {
                if (config.parameterHints) {
                    collectConstructorParameterHints(node, context.astModel, context.workspaceSymbols, context.hints)
                }
            }
        }
    }

    /**
     * Collect type hint for a declaration expression (e.g., `def name = "hello"`).
     *
     * Only shows hints for `def` declarations where the type is inferred.
     */
    @Suppress("TooGenericExceptionCaught") // TypeInferencer may throw on incomplete/invalid AST nodes.
    private fun collectTypeHint(decl: DeclarationExpression): InlayHint? {
        val varExpr = decl.leftExpression as? VariableExpression ?: return null

        // Only show type hints for dynamic/def declarations
        if (!varExpr.type.isDynamic()) {
            return null
        }

        // Use TypeInferencer to get the inferred type
        val inferredType = try {
            TypeInferencer.inferType(decl)
        } catch (e: Exception) {
            logger.debug("Failed to infer type for ${varExpr.name}", e)
            return null
        }
        if (inferredType == "java.lang.Object") {
            // Don't show hints for Object (no useful information)
            return null
        }

        // Format type as simple name (e.g., "ArrayList<Integer>" instead of "java.util.ArrayList<Integer>")
        val displayType = inferredType.formatTypeName()

        // Position the hint after the variable name
        val position = Position(
            varExpr.lineNumber - 1, // Convert to 0-indexed
            varExpr.columnNumber + varExpr.name.length - 1,
        )

        return InlayHint(position, Either.forLeft(": $displayType")).apply {
            kind = InlayHintKind.Type
            paddingLeft = true
        }
    }

    /**
     * Collect parameter hints for a method call expression.
     *
     * Shows parameter names at call sites for positional arguments.
     */
    private fun collectParameterHints(
        call: MethodCallExpression,
        astModel: GroovyAstModel,
        symbolTable: SymbolTable?,
        workspaceSymbols: List<Symbol>,
        hints: MutableList<InlayHint>,
    ) {
        val arguments = call.arguments as? ArgumentListExpression ?: return

        if (arguments.expressions.isEmpty()) {
            return
        }

        // Try to resolve the method to get parameter names
        val parameterNames = resolveMethodParameterNames(call, astModel, symbolTable, workspaceSymbols)
        if (parameterNames.isEmpty()) {
            return
        }

        // Generate hints for each argument
        arguments.expressions.forEachIndexed { index, arg ->
            if (index >= parameterNames.size) return@forEachIndexed

            val paramName = parameterNames[index]

            // Skip if argument is a closure (they provide their own context)
            if (arg is ClosureExpression) {
                return@forEachIndexed
            }

            val position = Position(
                arg.lineNumber - 1,
                arg.columnNumber - 1,
            )

            hints.add(
                InlayHint(position, Either.forLeft("$paramName:")).apply {
                    kind = InlayHintKind.Parameter
                    paddingRight = true
                },
            )
        }
    }

    /**
     * Collect parameter hints for a constructor call expression.
     */
    private fun collectConstructorParameterHints(
        call: ConstructorCallExpression,
        astModel: GroovyAstModel,
        workspaceSymbols: List<Symbol>,
        hints: MutableList<InlayHint>,
    ) {
        val arguments = call.arguments as? ArgumentListExpression ?: return

        if (arguments.expressions.isEmpty()) {
            return
        }

        // Try to resolve constructor parameter names
        val parameterNames = resolveConstructorParameterNames(call, astModel, workspaceSymbols)
        if (parameterNames.isEmpty()) {
            return
        }

        arguments.expressions.forEachIndexed { index, arg ->
            if (index >= parameterNames.size) return@forEachIndexed

            val paramName = parameterNames[index]

            if (arg is ClosureExpression) {
                return@forEachIndexed
            }

            val position = Position(
                arg.lineNumber - 1,
                arg.columnNumber - 1,
            )

            hints.add(
                InlayHint(position, Either.forLeft("$paramName:")).apply {
                    kind = InlayHintKind.Parameter
                    paddingRight = true
                },
            )
        }
    }

    /**
     * Resolve parameter names for a method call.
     *
     * Resolution stages:
     *  1) same-file AST
     *  2) workspace symbols (requires receiver type)
     *  3) classpath reflection (requires receiver type)
     */
    private fun resolveMethodParameterNames(
        call: MethodCallExpression,
        astModel: GroovyAstModel,
        symbolTable: SymbolTable?,
        workspaceSymbols: List<Symbol>,
    ): List<String> {
        val methodName = call.methodAsString ?: return emptyList()
        val arguments = call.arguments as? ArgumentListExpression ?: return emptyList()
        val argCount = arguments.expressions.size
        val argumentTypes = InlayHintsCandidates.resolveArgumentTypes(arguments.expressions, logger)
        val receiverType = InlayHintsCandidates.resolveReceiverType(call, astModel, symbolTable, logger)
        val isStaticCall = call.objectExpression is ClassExpression

        val result = InlayHintsCandidates.resolveFromCandidates(
            argumentTypes,
            compilationService,
            {
                InlayHintsCandidates.findMethodCandidatesInAst(
                    astModel,
                    methodName,
                    argCount,
                    receiverType,
                    isStaticCall,
                )
            },
            {
                InlayHintsCandidates.findWorkspaceMethodCandidates(
                    methodName,
                    argCount,
                    receiverType,
                    isStaticCall,
                    workspaceSymbols,
                )
            },
            {
                InlayHintsCandidates.findClasspathMethodCandidates(
                    methodName,
                    argCount,
                    receiverType,
                    isStaticCall,
                    compilationService,
                )
            },
        )
        return (result as? ResolutionResult.Match)?.parameterNames.orEmpty()
    }

    /**
     * Resolve parameter names for a constructor call.
     */
    private fun resolveConstructorParameterNames(
        call: ConstructorCallExpression,
        astModel: GroovyAstModel,
        workspaceSymbols: List<Symbol>,
    ): List<String> {
        val arguments = call.arguments as? ArgumentListExpression ?: return emptyList()
        val argCount = arguments.expressions.size
        val argumentTypes = InlayHintsCandidates.resolveArgumentTypes(arguments.expressions, logger)
        val typeName = call.type.name

        val result = InlayHintsCandidates.resolveFromCandidates(
            argumentTypes,
            compilationService,
            { InlayHintsCandidates.findConstructorCandidatesInAst(astModel, typeName, argCount) },
            { InlayHintsCandidates.findWorkspaceConstructorCandidates(typeName, argCount, workspaceSymbols) },
            { InlayHintsCandidates.findClasspathConstructorCandidates(typeName, argCount, compilationService) },
        )
        return (result as? ResolutionResult.Match)?.parameterNames.orEmpty()
    }
}

private object InlayHintsCandidates {
    fun resolveFromCandidates(
        argumentTypes: List<String?>,
        compilationService: GroovyCompilationService,
        vararg providers: () -> List<CallableSignature>,
    ): ResolutionResult {
        providers.forEach { provider ->
            when (val result = InlayHintsTypes.selectBestCandidate(provider(), argumentTypes, compilationService)) {
                is ResolutionResult.Match -> return result
                ResolutionResult.Ambiguous -> return result
                ResolutionResult.NotFound -> Unit
            }
        }
        return ResolutionResult.NotFound
    }

    fun resolveReceiverType(
        call: MethodCallExpression,
        astModel: GroovyAstModel,
        symbolTable: SymbolTable?,
        logger: Logger,
    ): String? {
        if (call.isImplicitThis) {
            return resolveImplicitThisReceiverType(call, astModel)
        }

        val objectExpr = call.objectExpression ?: return null
        val directType = (objectExpr as? ClassExpression)?.type?.name
        val type = directType ?: inferExpressionTypeSafely(objectExpr, "receiver", logger)

        return refineReceiverTypeWithSymbolTable(type, objectExpr, astModel, symbolTable, logger)
            ?.takeUnless { InlayHintsTypes.isDynamicType(it) || it == "java.lang.Class" }
    }

    fun resolveImplicitThisReceiverType(call: MethodCallExpression, astModel: GroovyAstModel): String? {
        var current: ASTNode? = call
        var depth = 0
        val visited = mutableSetOf<ASTNode>()

        var keepGoing = true
        while (keepGoing) {
            if (current == null || current is ClassNode || depth >= MAX_PARENT_SEARCH_DEPTH) {
                keepGoing = false
            } else if (!visited.add(current)) {
                keepGoing = false
            } else {
                val parent = astModel.getParent(current)
                if (parent == null || parent === current) {
                    keepGoing = false
                } else {
                    current = parent
                    depth += 1
                }
            }
        }
        return (current as? ClassNode)?.name
    }

    fun refineReceiverTypeWithSymbolTable(
        inferredType: String?,
        objectExpr: Expression,
        astModel: GroovyAstModel,
        symbolTable: SymbolTable?,
        logger: Logger,
    ): String? {
        if (inferredType != "java.lang.Object" && inferredType != "java.lang.Class") {
            return inferredType
        }
        if (objectExpr !is VariableExpression) {
            return inferredType
        }
        if (symbolTable == null) {
            return inferredType
        }

        val resolvedVar = symbolTable.resolveSymbol(objectExpr, astModel) ?: return inferredType
        if (!resolvedVar.hasInitialExpression()) {
            return inferredType
        }

        val initExpr = resolvedVar.initialExpression ?: return inferredType
        val refined = inferExpressionTypeSafely(initExpr, "receiver initializer", logger) ?: return inferredType
        return refined.takeUnless { it == "java.lang.Object" } ?: inferredType
    }

    fun resolveArgumentTypes(arguments: List<Expression>, logger: Logger): List<String?> =
        arguments.map { arg -> inferExpressionTypeSafely(arg, "argument", logger) }

    fun inferExpressionTypeSafely(expression: Expression, context: String, logger: Logger): String? =
        runCatching { TypeInferencer.inferExpressionType(expression) }
            .onFailure { logger.debug("Type inference failed for $context", it) }
            .getOrNull()

    fun findMethodCandidatesInAst(
        astModel: GroovyAstModel,
        methodName: String,
        argCount: Int,
        receiverType: String?,
        isStaticCall: Boolean,
    ): List<CallableSignature> {
        val classNodes = astModel.getAllClassNodes()
        val searchScope = if (receiverType != null) {
            val normalizedType = InlayHintsTypes.normalizeTypeName(receiverType)
            val simpleName = normalizedType.substringAfterLast('.')
            val matchingClasses = classNodes.filter { node ->
                node.name == normalizedType || node.nameWithoutPackage == simpleName
            }
            if (matchingClasses.isEmpty()) {
                return emptyList()
            }
            matchingClasses
        } else {
            classNodes
        }

        return searchScope
            .flatMap { classNode ->
                classNode.methods
                    .filter { it.name == methodName && it.parameters.size == argCount }
                    .filter { !isStaticCall || it.isStatic }
                    .map { InlayHintsTypes.toSignature(it.parameters.asList()) }
            }
    }

    fun findWorkspaceMethodCandidates(
        methodName: String,
        argCount: Int,
        receiverType: String?,
        isStaticCall: Boolean,
        workspaceSymbols: List<Symbol>,
    ): List<CallableSignature> {
        val normalizedReceiverType = receiverType?.let { InlayHintsTypes.normalizeTypeName(it) } ?: return emptyList()
        val receiverSimple = normalizedReceiverType.substringAfterLast('.')

        return workspaceSymbols
            .asSequence()
            .filterIsInstance<Symbol.Method>()
            .filter { it.name == methodName && it.parameters.size == argCount }
            .filter { !isStaticCall || it.isStatic }
            .filter { methodSymbol ->
                val classOwner = methodSymbol.owner ?: return@filter false
                val ownerName = classOwner.name
                val ownerSimple = classOwner.nameWithoutPackage
                ownerName == normalizedReceiverType || ownerSimple == receiverSimple
            }
            .map { InlayHintsTypes.toSignature(it.parameters) }
            .toList()
    }

    fun findClasspathMethodCandidates(
        methodName: String,
        argCount: Int,
        receiverType: String?,
        isStaticCall: Boolean,
        compilationService: GroovyCompilationService,
    ): List<CallableSignature> {
        val normalizedReceiverType = receiverType?.let { InlayHintsTypes.normalizeTypeName(it) } ?: return emptyList()
        // TODO(#581): Resolve synthetic parameter names via JDK source indexing for deterministic hints.
        //   See: https://github.com/albertocavalcante/gvy/issues/581
        return compilationService.classpathService.getMethods(normalizedReceiverType)
            .filter { it.name == methodName && it.parameters.size == argCount }
            .filter { !isStaticCall || it.isStatic }
            .map { InlayHintsTypes.toSignature(it) }
    }

    fun findConstructorCandidatesInAst(
        astModel: GroovyAstModel,
        typeName: String,
        argCount: Int,
    ): List<CallableSignature> {
        val normalizedType = InlayHintsTypes.normalizeTypeName(typeName)
        val simpleName = normalizedType.substringAfterLast('.')
        val classNodes = astModel.getAllClassNodes()
        val matchingClasses = classNodes.filter {
            it.name == normalizedType || it.nameWithoutPackage == simpleName
        }

        return matchingClasses.flatMap { classNode ->
            classNode.declaredConstructors
                .filter { it.parameters.size == argCount }
                .map { InlayHintsTypes.toSignature(it.parameters.asList()) }
        }
    }

    fun findWorkspaceConstructorCandidates(
        typeName: String,
        argCount: Int,
        workspaceSymbols: List<Symbol>,
    ): List<CallableSignature> {
        val normalizedType = InlayHintsTypes.normalizeTypeName(typeName)
        val simpleName = normalizedType.substringAfterLast('.')
        return workspaceSymbols
            .asSequence()
            .filterIsInstance<Symbol.Class>()
            .filter { it.name == simpleName || it.fullyQualifiedName == normalizedType }
            .flatMap { classSymbol ->
                classSymbol.node.declaredConstructors
                    .filter { it.parameters.size == argCount }
                    .map { InlayHintsTypes.toSignature(it.parameters.asList()) }
            }
            .toList()
    }

    fun findClasspathConstructorCandidates(
        typeName: String,
        argCount: Int,
        compilationService: GroovyCompilationService,
    ): List<CallableSignature> {
        val normalizedType = InlayHintsTypes.normalizeTypeName(typeName)
        val clazz = compilationService.classpathService.loadClass(normalizedType) ?: return emptyList()
        return clazz.constructors
            .filter { it.parameterCount == argCount }
            .map { constructor ->
                val types = constructor.parameterTypes.map { it.name }
                val names = constructor.parameters.map { it.name }
                InlayHintsTypes.toSignature(types, names)
            }
    }
}

private object InlayHintsTypes {
    fun selectBestCandidate(
        candidates: List<CallableSignature>,
        argumentTypes: List<String?>,
        compilationService: GroovyCompilationService,
    ): ResolutionResult {
        if (candidates.isEmpty()) return ResolutionResult.NotFound

        val hasTypeInfo = argumentTypes.any { !isUnknownType(it) }
        if (!hasTypeInfo) {
            return if (candidates.size == 1) {
                ResolutionResult.Match(candidates.first().parameterNames)
            } else {
                ResolutionResult.Ambiguous
            }
        }

        val scored = candidates.mapNotNull { candidate ->
            val score = scoreCandidate(candidate, argumentTypes, compilationService)
            if (score < 0) null else candidate to score
        }
        if (scored.isEmpty()) return ResolutionResult.Ambiguous

        val maxScore = scored.maxOf { it.second }
        val best = scored.filter { it.second == maxScore }

        return if (best.size == 1) {
            ResolutionResult.Match(best.first().first.parameterNames)
        } else {
            ResolutionResult.Ambiguous
        }
    }

    private fun scoreCandidate(
        candidate: CallableSignature,
        argumentTypes: List<String?>,
        compilationService: GroovyCompilationService,
    ): Int {
        var score = 0
        argumentTypes.forEachIndexed { index, argType ->
            if (isUnknownType(argType)) {
                return@forEachIndexed
            }
            val paramType = candidate.parameterTypes.getOrNull(index) ?: return -1
            val matchScore = matchScore(paramType, argType!!, compilationService)
            if (matchScore < 0) {
                return -1
            }
            score += matchScore
        }
        return score
    }

    private fun matchScore(
        parameterType: String,
        argumentType: String,
        compilationService: GroovyCompilationService,
    ): Int {
        val normalizedParam = normalizeTypeName(parameterType)
        val normalizedArg = normalizeTypeName(argumentType)
        val paramKey = normalizePrimitiveAlias(normalizedParam)
        val argKey = normalizePrimitiveAlias(normalizedArg)

        return when {
            isDynamicType(normalizedParam) -> 1
            paramKey == argKey -> 2
            else -> {
                val paramClass = resolveClass(normalizedParam, compilationService)
                val argClass = resolveClass(normalizedArg, compilationService)
                if (paramClass != null && argClass != null && paramClass.isAssignableFrom(argClass)) 1 else -1
            }
        }
    }

    private fun normalizePrimitiveAlias(typeName: String): String {
        val simple = typeName.substringAfterLast('.')
        return primitiveTypeAliases[simple] ?: simple
    }

    private fun resolveClass(typeName: String, compilationService: GroovyCompilationService): Class<*>? {
        val normalized = normalizeTypeName(typeName)
        val aliased = primitiveTypeAliases[normalized] ?: normalized
        val lookupName = when {
            aliased.contains('.') -> aliased
            javaLangTypeAliases.containsKey(aliased) -> javaLangTypeAliases.getValue(aliased)
            else -> return null
        }
        return compilationService.classpathService.loadClass(lookupName)
    }

    fun normalizeTypeName(typeName: String): String = typeName.substringBefore('<')

    private fun isUnknownType(typeName: String?): Boolean {
        if (typeName == null) return true
        val normalized = normalizeTypeName(typeName)
        return normalized == "java.lang.Object" || normalized == "Object" || normalized == "def"
    }

    fun isDynamicType(typeName: String): Boolean =
        typeName == "java.lang.Object" || typeName == "Object" || typeName == "def"

    fun toSignature(parameters: Iterable<Parameter>): CallableSignature = CallableSignature(
        parameterNames = parameters.map { it.name },
        parameterTypes = parameters.map { it.type.name },
    )

    fun toSignature(method: ReflectedMethod): CallableSignature = toSignature(method.parameters, method.parameterNames)

    fun toSignature(parameterTypes: List<String>, parameterNames: List<String>): CallableSignature {
        val normalizedNames = normalizeParameterNames(parameterNames, parameterTypes.size)
        return CallableSignature(
            parameterNames = normalizedNames,
            parameterTypes = parameterTypes.map { normalizeTypeName(it) },
        )
    }

    private fun normalizeParameterNames(names: List<String>, size: Int): List<String> {
        if (names.size == size) {
            return names.mapIndexed { index, name -> name.ifBlank { "arg$index" } }
        }
        return List(size) { index -> "arg$index" }
    }
}
