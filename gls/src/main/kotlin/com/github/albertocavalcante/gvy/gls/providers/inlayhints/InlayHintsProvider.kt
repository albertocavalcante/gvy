package com.github.albertocavalcante.gvy.gls.providers.inlayhints

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.InlayHintsConfiguration
import com.github.albertocavalcante.gvy.gls.services.ReflectedMethod
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.semantics.TypeStringUtils
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Range
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
internal sealed class ResolutionResult {
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
internal data class CallableSignature(val parameterNames: List<String>, val parameterTypes: List<String>)

/**
 * Provides LSP Inlay Hints for Groovy source files.
 *
 * This provider delegates to specialized strategies for different hint types:
 * - [TypeInlayHintStrategy] for type hints on `def` variables
 * - [ParameterInlayHintStrategy] for parameter names on method/constructor calls
 *
 * @param compilationService The service providing AST models for source files.
 * @param semanticResolver The resolver for semantic types.
 * @param config The configuration settings for inlay hints.
 * @param strategies The list of strategies to use (defaults to all available strategies).
 */
class InlayHintsProvider(
    private val compilationService: GroovyCompilationService,
    private val semanticResolver: SemanticTypeResolver,
    private val config: InlayHintsConfiguration = InlayHintsConfiguration(),
    private val strategies: List<InlayHintStrategy> = listOf(
        TypeInlayHintStrategy(),
        ParameterInlayHintStrategy(),
    ),
) {
    private val logger = KotlinLogging.logger {}

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
            logger.debug { "No AST model available for $uri" }
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

        val moduleNode = compilationService.getAst(uri) as? ModuleNode
        val hints = mutableListOf<InlayHint>()
        val context = HintContext(
            astModel = astModel,
            moduleNode = moduleNode,
            symbolTable = symbolTable,
            workspaceSymbols = workspaceSymbols,
            compilationService = compilationService,
            semanticResolver = semanticResolver,
            config = config,
            logger = logger,
        )

        // Traverse all nodes and collect hints within the requested range
        astModel.getAllNodes().forEach { node ->
            processNode(node, params.range, context, hints)
        }

        logger.debug { "Returning ${hints.size} inlay hints for ${params.textDocument.uri}" }
        return hints
    }

    private fun processNode(node: ASTNode, range: Range, context: HintContext, hints: MutableList<InlayHint>) {
        // Filter nodes outside the requested range (1-indexed to 0-indexed conversion)
        val nodeLine = node.lineNumber - 1
        if (nodeLine < range.start.line || nodeLine > range.end.line) {
            return
        }

        // Delegate to strategies
        strategies.forEach { strategy ->
            if (strategy.canHandle(node, context)) {
                hints.addAll(strategy.generateHints(node, context))
            }
        }
    }
}

// TODO(#651): Consolidate InlayHintsCandidates and InlayHintsTypes into single helper.
//   See: https://github.com/albertocavalcante/gvy/issues/651
// TODO(#650): resolveReceiverType() overlaps with SignatureHelpProvider - extract shared utility.
//   See: https://github.com/albertocavalcante/gvy/issues/650
@Suppress("TooManyFunctions") // Internal helper object
internal object InlayHintsCandidates {
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

    fun resolveReceiverType(call: MethodCallExpression, context: HintContext): String? {
        if (call.isImplicitThis) {
            return resolveImplicitThisReceiverType(call, context.astModel)
        }

        val objectExpr = call.objectExpression ?: return null
        val directType = (objectExpr as? ClassExpression)?.type?.name
        val type =
            directType
                ?: resolveExpressionTypeSafely(
                    objectExpr,
                    context,
                    "receiver",
                )

        return refineReceiverTypeWithSymbolTable(
            type,
            objectExpr,
            context,
        )
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
        context: HintContext,
    ): String? {
        if (inferredType != "java.lang.Object" && inferredType != "java.lang.Class") {
            return inferredType
        }
        if (objectExpr !is VariableExpression) {
            return inferredType
        }
        if (context.symbolTable == null) {
            return inferredType
        }

        val resolvedVar = context.symbolTable.resolveSymbol(objectExpr, context.astModel) ?: return inferredType
        if (!resolvedVar.hasInitialExpression()) {
            return inferredType
        }

        val initExpr = resolvedVar.initialExpression ?: return inferredType
        val refined =
            resolveExpressionTypeSafely(
                initExpr,
                context,
                "receiver initializer",
            )
                ?: return inferredType
        return refined.takeUnless { it == "java.lang.Object" } ?: inferredType
    }

    fun resolveArgumentTypes(
        arguments: List<Expression>,
        semanticResolver: SemanticTypeResolver,
        moduleNode: ModuleNode?,
    ): List<String?> = arguments.map { arg ->
        runCatching {
            val semanticType = semanticResolver.resolveType(arg, moduleNode)
            semanticResolver.formatSemanticType(semanticType)
        }.getOrNull()
    }

    fun resolveExpressionTypeSafely(
        expression: Expression,
        context: HintContext,
        contextDescription: String,
    ): String? = runCatching {
        val type = context.semanticResolver.resolveType(expression, context.moduleNode)
        context.semanticResolver.formatSemanticType(type)
    }
        .onFailure { context.logger.debug(it) { "Type resolution failed for $contextDescription" } }
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

        // Guard: Skip classpath lookup for invalid type names (e.g., "unresolved variable: binding")
        if (!TypeStringUtils.isValidClasspathTypeName(normalizedReceiverType)) {
            return emptyList()
        }

        // Expand simple names (e.g., "String" -> "java.lang.String") for Class.forName()
        val lookupType = expandToFqn(normalizedReceiverType) ?: return emptyList()

        // TODO(#581): Resolve synthetic parameter names via JDK source indexing for deterministic hints.
        //   See: https://github.com/albertocavalcante/gvy/issues/581
        return compilationService.classpathService.getMethods(lookupType)
            .filter { it.name == methodName && it.parameters.size == argCount }
            .filter { !isStaticCall || it.isStatic }
            .map { InlayHintsTypes.toSignature(it) }
    }

    fun findGdkMethodCandidates(
        methodName: String,
        argCount: Int,
        receiverType: String?,
        compilationService: GroovyCompilationService,
    ): List<CallableSignature> {
        val normalizedType = receiverType?.let { InlayHintsTypes.normalizeTypeName(it) }
            ?: return emptyList()

        return compilationService.gdkProvider.getMethodsForType(normalizedType)
            .filter { it.name == methodName && it.parameterTypes.size == argCount }
            .map {
                CallableSignature(
                    parameterNames = it.parameterNames,
                    parameterTypes = it.parameterTypes.map { t -> InlayHintsTypes.normalizeTypeName(t) },
                )
            }
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

    @Suppress("ReturnCount") // Multiple validation checks require early returns
    fun findClasspathConstructorCandidates(
        typeName: String,
        argCount: Int,
        compilationService: GroovyCompilationService,
    ): List<CallableSignature> {
        val normalizedType = InlayHintsTypes.normalizeTypeName(typeName)

        // Guard: Skip classpath lookup for invalid type names
        if (!TypeStringUtils.isValidClasspathTypeName(normalizedType)) {
            return emptyList()
        }

        // Expand simple names (e.g., "String" -> "java.lang.String") for Class.forName()
        val lookupType = expandToFqn(normalizedType) ?: return emptyList()

        val clazz = compilationService.classpathService.loadClass(lookupType) ?: return emptyList()
        return clazz.constructors
            .filter { it.parameterCount == argCount }
            .map { constructor ->
                val types = constructor.parameterTypes.map { it.name }
                val names = constructor.parameters.map { it.name }
                InlayHintsTypes.toSignature(types, names)
            }
    }

    /**
     * Expand a simple class name to its fully qualified name for classpath lookup.
     * Returns the FQN if the type already contains a package, expands java.lang aliases,
     * or returns null if the simple name cannot be resolved.
     */
    fun expandToFqn(typeName: String): String? = when {
        typeName.contains('.') -> typeName // Already FQN
        javaLangTypeAliases.containsKey(typeName) -> javaLangTypeAliases.getValue(typeName)
        else -> null // Unknown simple name - skip classpath lookup
    }
}

internal object InlayHintsTypes {
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

    private fun isUnknownType(typeName: String?): Boolean = TypeStringUtils.isUnknownType(typeName)

    fun isDynamicType(typeName: String): Boolean = TypeStringUtils.isDynamicType(typeName)

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
