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
import org.codehaus.groovy.ast.ClassNode
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
import org.slf4j.LoggerFactory
import java.net.URI

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
        val range = params.range

        // Traverse all nodes and collect hints within the requested range
        astModel.getAllNodes().forEach { node ->
            // Filter nodes outside the requested range (1-indexed to 0-indexed conversion)
            val nodeLine = node.lineNumber - 1
            if (nodeLine < range.start.line || nodeLine > range.end.line) {
                return@forEach
            }

            when (node) {
                is DeclarationExpression -> {
                    if (config.typeHints) {
                        collectTypeHint(node)?.let { hints.add(it) }
                    }
                }

                is MethodCallExpression -> {
                    if (config.parameterHints) {
                        collectParameterHints(node, astModel, symbolTable, workspaceSymbols, hints)
                    }
                }

                is ConstructorCallExpression -> {
                    if (config.parameterHints) {
                        collectConstructorParameterHints(node, astModel, workspaceSymbols, hints)
                    }
                }
            }
        }

        logger.debug("Returning ${hints.size} inlay hints for ${params.textDocument.uri}")
        return hints
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
        val argumentTypes = resolveArgumentTypes(arguments.expressions)
        val receiverType = resolveReceiverType(call, astModel, symbolTable)
        val isStaticCall = call.objectExpression is ClassExpression

        val result = resolveFromCandidates(
            argumentTypes,
            { findMethodCandidatesInAst(astModel, methodName, argCount, receiverType, isStaticCall) },
            { findWorkspaceMethodCandidates(methodName, argCount, receiverType, isStaticCall, workspaceSymbols) },
            { findClasspathMethodCandidates(methodName, argCount, receiverType, isStaticCall) },
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
        val argumentTypes = resolveArgumentTypes(arguments.expressions)
        val typeName = call.type.name

        val result = resolveFromCandidates(
            argumentTypes,
            { findConstructorCandidatesInAst(astModel, typeName, argCount) },
            { findWorkspaceConstructorCandidates(typeName, argCount, workspaceSymbols) },
            { findClasspathConstructorCandidates(typeName, argCount) },
        )
        return (result as? ResolutionResult.Match)?.parameterNames.orEmpty()
    }

    private fun resolveFromCandidates(
        argumentTypes: List<String?>,
        vararg providers: () -> List<CallableSignature>,
    ): ResolutionResult {
        providers.forEach { provider ->
            val result = selectBestCandidate(provider(), argumentTypes)
            when (result) {
                is ResolutionResult.Match -> return result
                ResolutionResult.Ambiguous -> return result
                ResolutionResult.NotFound -> Unit
            }
        }
        return ResolutionResult.NotFound
    }

    private fun resolveReceiverType(
        call: MethodCallExpression,
        astModel: GroovyAstModel,
        symbolTable: SymbolTable?,
    ): String? {
        if (call.isImplicitThis) {
            var current: org.codehaus.groovy.ast.ASTNode? = call
            var depth = 0
            val visited = mutableSetOf<org.codehaus.groovy.ast.ASTNode>()
            while (current != null && current !is ClassNode && depth < MAX_PARENT_SEARCH_DEPTH) {
                if (!visited.add(current)) {
                    break
                }
                val parent = astModel.getParent(current) ?: break
                if (parent === current) break
                current = parent
                depth++
            }
            return (current as? ClassNode)?.name
        }

        val objectExpr = call.objectExpression ?: return null
        val directType = (objectExpr as? ClassExpression)?.type?.name
        var type = directType ?: inferExpressionTypeSafely(objectExpr, "receiver")

        if ((type == "java.lang.Object" || type == "java.lang.Class") &&
            objectExpr is VariableExpression &&
            symbolTable != null
        ) {
            val resolvedVar = symbolTable.resolveSymbol(objectExpr, astModel)
            if (resolvedVar != null && resolvedVar.hasInitialExpression()) {
                resolvedVar.initialExpression?.let { initExpr ->
                    val inferred = inferExpressionTypeSafely(initExpr, "receiver initializer")
                    if (inferred != null && inferred != "java.lang.Object") {
                        type = inferred
                    }
                }
            }
        }

        return type?.takeUnless { isDynamicType(it) || it == "java.lang.Class" }
    }

    private fun resolveArgumentTypes(arguments: List<Expression>): List<String?> =
        arguments.map { arg -> inferExpressionTypeSafely(arg, "argument") }

    private fun inferExpressionTypeSafely(expression: Expression, context: String): String? =
        runCatching { TypeInferencer.inferExpressionType(expression) }
            .onFailure { logger.debug("Type inference failed for $context", it) }
            .getOrNull()

    private fun findMethodCandidatesInAst(
        astModel: GroovyAstModel,
        methodName: String,
        argCount: Int,
        receiverType: String?,
        isStaticCall: Boolean,
    ): List<CallableSignature> {
        val classNodes = astModel.getAllClassNodes()
        val searchScope = if (receiverType != null) {
            val matchingClasses = filterClassesByType(classNodes, receiverType)
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
                    .map { toSignature(it.parameters) }
            }
    }

    private fun findWorkspaceMethodCandidates(
        methodName: String,
        argCount: Int,
        receiverType: String?,
        isStaticCall: Boolean,
        workspaceSymbols: List<Symbol>,
    ): List<CallableSignature> {
        val normalizedReceiverType = receiverType?.let { normalizeTypeName(it) } ?: return emptyList()
        val receiverSimple = normalizedReceiverType.substringAfterLast('.')

        return workspaceSymbols
            .asSequence()
            .filterIsInstance<Symbol.Method>()
            .filter { it.name == methodName && it.parameters.size == argCount }
            .filter { !isStaticCall || it.isStatic }
            .filter { matchesOwner(it.owner, normalizedReceiverType, receiverSimple) }
            .map { toSignature(it.parameters) }
            .toList()
    }

    private fun findClasspathMethodCandidates(
        methodName: String,
        argCount: Int,
        receiverType: String?,
        isStaticCall: Boolean,
    ): List<CallableSignature> {
        val normalizedReceiverType = receiverType?.let { normalizeTypeName(it) } ?: return emptyList()
        // TODO(#581): Resolve synthetic parameter names via JDK source indexing for deterministic hints.
        //   See: https://github.com/albertocavalcante/gvy/issues/581
        return compilationService.classpathService.getMethods(normalizedReceiverType)
            .filter { it.name == methodName && it.parameters.size == argCount }
            .filter { !isStaticCall || it.isStatic }
            .map { toSignature(it) }
    }

    private fun findConstructorCandidatesInAst(
        astModel: GroovyAstModel,
        typeName: String,
        argCount: Int,
    ): List<CallableSignature> {
        val normalizedType = normalizeTypeName(typeName)
        val simpleName = normalizedType.substringAfterLast('.')
        val classNodes = astModel.getAllClassNodes()
        val matchingClasses = classNodes.filter {
            it.name == normalizedType || it.nameWithoutPackage == simpleName
        }

        return matchingClasses.flatMap { classNode ->
            classNode.declaredConstructors
                .filter { it.parameters.size == argCount }
                .map { toSignature(it.parameters) }
        }
    }

    private fun findWorkspaceConstructorCandidates(
        typeName: String,
        argCount: Int,
        workspaceSymbols: List<Symbol>,
    ): List<CallableSignature> {
        val normalizedType = normalizeTypeName(typeName)
        val simpleName = normalizedType.substringAfterLast('.')
        return workspaceSymbols
            .asSequence()
            .filterIsInstance<Symbol.Class>()
            .filter { it.name == simpleName || it.fullyQualifiedName == normalizedType }
            .flatMap { classSymbol ->
                classSymbol.node.declaredConstructors
                    .filter { it.parameters.size == argCount }
                    .map { toSignature(it.parameters) }
            }
            .toList()
    }

    private fun findClasspathConstructorCandidates(typeName: String, argCount: Int): List<CallableSignature> {
        val normalizedType = normalizeTypeName(typeName)
        val clazz = compilationService.classpathService.loadClass(normalizedType) ?: return emptyList()
        return clazz.constructors
            .filter { it.parameterCount == argCount }
            .map { constructor ->
                val types = constructor.parameterTypes.map { it.name }
                val names = constructor.parameters.map { it.name }
                toSignature(types, names)
            }
    }

    private fun filterClassesByType(classNodes: List<ClassNode>, receiverType: String?): List<ClassNode> {
        val normalizedType = receiverType?.let { normalizeTypeName(it) } ?: return emptyList()
        val simpleName = normalizedType.substringAfterLast('.')
        return classNodes.filter { node ->
            node.name == normalizedType || node.nameWithoutPackage == simpleName
        }
    }

    private fun matchesOwner(owner: ClassNode?, receiverType: String, receiverSimple: String): Boolean {
        if (owner == null) return false
        val ownerName = owner.name
        val ownerSimple = owner.nameWithoutPackage
        return ownerName == receiverType || ownerSimple == receiverSimple
    }

    private fun selectBestCandidate(
        candidates: List<CallableSignature>,
        argumentTypes: List<String?>,
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
            val score = scoreCandidate(candidate, argumentTypes)
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

    private fun scoreCandidate(candidate: CallableSignature, argumentTypes: List<String?>): Int {
        var score = 0
        argumentTypes.forEachIndexed { index, argType ->
            if (isUnknownType(argType)) {
                return@forEachIndexed
            }
            val paramType = candidate.parameterTypes.getOrNull(index) ?: return -1
            val matchScore = matchScore(paramType, argType!!)
            if (matchScore < 0) {
                return -1
            }
            score += matchScore
        }
        return score
    }

    private fun matchScore(parameterType: String, argumentType: String): Int {
        val normalizedParam = normalizeTypeName(parameterType)
        val normalizedArg = normalizeTypeName(argumentType)

        if (isDynamicType(normalizedParam)) return 1

        if (typesMatch(normalizedParam, normalizedArg)) {
            return 2
        }

        val paramClass = resolveClass(normalizedParam)
        val argClass = resolveClass(normalizedArg)
        if (paramClass != null && argClass != null) {
            return if (paramClass.isAssignableFrom(argClass)) 1 else -1
        }

        return -1
    }

    private fun typesMatch(parameterType: String, argumentType: String): Boolean {
        val paramKey = normalizePrimitiveAlias(parameterType)
        val argKey = normalizePrimitiveAlias(argumentType)
        return paramKey == argKey
    }

    private fun normalizePrimitiveAlias(typeName: String): String {
        val simple = typeName.substringAfterLast('.')
        return primitiveTypeAliases[simple] ?: simple
    }

    private fun resolveClass(typeName: String): Class<*>? {
        val normalized = normalizeTypeName(typeName)
        val aliased = primitiveTypeAliases[normalized] ?: normalized
        val lookupName = when {
            aliased.contains('.') -> aliased
            javaLangTypeAliases.containsKey(aliased) -> javaLangTypeAliases.getValue(aliased)
            else -> return null
        }
        return compilationService.classpathService.loadClass(lookupName)
    }

    private fun normalizeTypeName(typeName: String): String = typeName.substringBefore('<')

    private fun isUnknownType(typeName: String?): Boolean {
        if (typeName == null) return true
        val normalized = normalizeTypeName(typeName)
        return normalized == "java.lang.Object" || normalized == "Object" || normalized == "def"
    }

    private fun isDynamicType(typeName: String): Boolean =
        typeName == "java.lang.Object" || typeName == "Object" || typeName == "def"

    private fun toSignature(parameters: Array<org.codehaus.groovy.ast.Parameter>): CallableSignature =
        CallableSignature(
            parameterNames = parameters.map { it.name },
            parameterTypes = parameters.map { it.type.name },
        )

    private fun toSignature(parameters: List<org.codehaus.groovy.ast.Parameter>): CallableSignature =
        toSignature(parameters.toTypedArray())

    private fun toSignature(method: ReflectedMethod): CallableSignature =
        toSignature(method.parameters, method.parameterNames)

    private fun toSignature(parameterTypes: List<String>, parameterNames: List<String>): CallableSignature {
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

    companion object {
        // Limit how far up the AST we walk when searching parent nodes to avoid deep or cyclic trees.
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
    }
}
