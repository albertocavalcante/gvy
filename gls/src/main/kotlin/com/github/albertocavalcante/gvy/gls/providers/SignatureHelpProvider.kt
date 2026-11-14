// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.containsPosition
import com.github.albertocavalcante.groovyparser.ast.isDynamic
import com.github.albertocavalcante.groovyparser.ast.safePosition
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.converters.toGroovyPosition
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.services.GdkExtensionMethod
import com.github.albertocavalcante.gvy.gls.services.ReflectedMethod
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.SemanticTypeFormatter
import com.github.albertocavalcante.gvy.semantics.native.displayName
import com.github.albertocavalcante.gvy.semantics.native.displayType
import com.github.albertocavalcante.nativeapi.ParseResult
import groovy.lang.Script
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URI
import com.github.albertocavalcante.groovyparser.ast.types.Position as GroovyPosition

/** Regex pattern to match synthetic parameter names like arg0, arg1, etc. */
private val SYNTHETIC_PARAM_PATTERN = Regex("arg\\d+")
class SignatureHelpProvider(
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
    private val semanticResolver: SemanticTypeResolver,
) {

    private val logger = KotlinLogging.logger {}
    private val receiverTypeRefiner = ReceiverTypeRefiner(semanticResolver)

    suspend fun provideSignatureHelp(uri: String, position: Position): SignatureHelp {
        val documentUri = URI.create(uri)
        val snapshot = documentProvider.getSnapshot(documentUri)
        return snapshot?.let { provideForSnapshot(documentUri, position, it) }
            ?: provideFromCachedDocument(documentUri, position)
    }

    private suspend fun provideForSnapshot(
        documentUri: URI,
        position: Position,
        snapshot: DocumentSnapshot,
    ): SignatureHelp {
        val cached = compilationService.getCompiledSnapshot(documentUri)
            ?.takeIf { it.sourceText == snapshot.content }
        val currentParse = cached?.parseResult
            ?: parseTransient(documentUri, snapshot.content)
            ?: return emptySignatureHelp()
        requireCurrentSnapshot(documentUri, snapshot)

        resolveSignatureContext(documentUri, position, currentParse)?.let { context ->
            requireCurrentSnapshot(documentUri, snapshot)
            return buildSignatureHelp(context, position)
        }

        return recoverIncompleteCall(documentUri, position, snapshot, currentParse)
            ?.let { buildSignatureHelp(it, position) }
            ?: emptySignatureHelp()
    }

    private suspend fun provideFromCachedDocument(documentUri: URI, position: Position): SignatureHelp {
        ensureAstPrepared(documentUri)
        val cached = compilationService.getParseResult(documentUri) ?: return emptySignatureHelp()
        val context = resolveSignatureContext(documentUri, position, cached) ?: return emptySignatureHelp()
        return buildSignatureHelp(context, position)
    }

    private suspend fun recoverIncompleteCall(
        documentUri: URI,
        position: Position,
        snapshot: DocumentSnapshot,
        currentParse: ParseResult,
    ): SignatureContext? {
        val repairedSource = IncompleteCallRecovery.parsingCopy(snapshot.content, position, currentParse.tokenIndex)
            ?: return null
        val repairedParse = parseTransient(documentUri, repairedSource) ?: return null
        requireCurrentSnapshot(documentUri, snapshot)
        return resolveSignatureContext(documentUri, position, repairedParse)?.copy(activeParameterOverride = 0)
    }

    private data class SignatureContext(
        val methodCall: MethodCallExpression,
        val nodeAtPosition: ASTNode,
        val signatures: List<SignatureInformation>,
        val astVisitor: GroovyAstModel,
        val activeParameterOverride: Int? = null,
    )

    private data class ResolvedSignatureInputs(
        val methodCall: MethodCallExpression,
        val nodeAtPosition: ASTNode,
        val methodName: String,
        val astVisitor: GroovyAstModel,
        val symbolTable: SymbolTable,
        val moduleNode: ModuleNode,
    )

    private fun resolveSignatureContext(
        documentUri: URI,
        position: Position,
        parseResult: ParseResult,
    ): SignatureContext? {
        val inputs = resolveSignatureInputs(documentUri, position, parseResult) ?: return null

        val signatures = collectSignatures(documentUri, inputs).distinctBy { it.label }
        if (signatures.isEmpty()) {
            logger.debug { "No signatures found for ${inputs.methodName}" }
            return null
        }

        return SignatureContext(inputs.methodCall, inputs.nodeAtPosition, signatures, inputs.astVisitor)
    }

    private fun resolveSignatureInputs(
        documentUri: URI,
        position: Position,
        parseResult: ParseResult,
    ): ResolvedSignatureInputs? {
        val moduleNode = parseResult.ast ?: return null
        val astVisitor = parseResult.astModel
        val symbolTable = parseResult.symbolTable

        val groovyPos = position.toGroovyPosition()
        val nodeAtPosition = astVisitor.getNodeAt(documentUri, groovyPos)
        val methodCall = nodeAtPosition?.let { findMethodCall(astVisitor, documentUri, it, groovyPos) }
        val methodName = methodCall?.extractMethodName()
        if (nodeAtPosition == null || methodCall == null || methodName == null) {
            if (methodCall != null && methodName == null) {
                logger.debug { "Could not resolve method name for call at $position in $documentUri" }
            }
            return null
        }

        return ResolvedSignatureInputs(
            methodCall = methodCall,
            nodeAtPosition = nodeAtPosition,
            methodName = methodName,
            astVisitor = astVisitor,
            symbolTable = symbolTable,
            moduleNode = moduleNode,
        )
    }

    private fun collectSignatures(documentUri: URI, inputs: ResolvedSignatureInputs): List<SignatureInformation> =
        buildList {
            // 1. Local Methods (Source)
            val declarations = inputs.symbolTable.registry.findMethodDeclarations(documentUri, inputs.methodName)
            addAll(declarations.map { it.toSignatureInformation() })

            // 2-4. Resolved Methods (Superclass, GDK, Classpath)
            addAll(
                resolveMethodsOnReceiver(
                    inputs.methodCall,
                    inputs.methodName,
                    inputs.symbolTable,
                    inputs.astVisitor,
                    inputs.moduleNode,
                ),
            )
        }

    private fun resolveMethodsOnReceiver(
        methodCall: MethodCallExpression,
        methodName: String,
        symbolTable: SymbolTable,
        astVisitor: GroovyAstModel,
        moduleNode: ModuleNode,
    ): List<SignatureInformation> {
        val signatures = mutableListOf<SignatureInformation>()

        // Determine the type of the object the method is being called on
        val receiverType = resolveReceiverType(methodCall, symbolTable, astVisitor, moduleNode)
        val targetType = receiverType.substringBefore("<")

        // 2. Script Methods (Implicit 'this')
        // Methods from groovy.lang.Script superclass if applicable
        if (methodCall.isImplicitThis) {
            val scriptMethods = findScriptMethods(methodName)
            signatures.addAll(scriptMethods.map { it.toSignatureInformation() })
        }

        // 3. GDK Extension Methods
        val gdkMethods = compilationService.gdkProvider.getMethodsForType(targetType)
            .filter { it.name == methodName }
        signatures.addAll(gdkMethods.map { it.toSignatureInformation() })

        // 4. Classpath Methods (includes java.lang.Object for explicit Object receivers)
        val methods = compilationService.classpathService.getMethods(targetType)
            .filter { it.name == methodName && it.isPublic }
        signatures.addAll(methods.map { it.toSignatureInformation() })

        // Fallback: Check Object methods for implicit this if nothing else found
        if (signatures.isEmpty() && methodCall.isImplicitThis) {
            val objectMethods = compilationService.classpathService.getMethods("java.lang.Object")
                .filter { it.name == methodName }
            signatures.addAll(objectMethods.map { it.toSignatureInformation() })
        }

        return signatures
    }

    // TODO(#650): Consolidate with InlayHintsCandidates.resolveReceiverType() - similar logic.
    //   See: https://github.com/albertocavalcante/gvy/issues/650
    private fun resolveReceiverType(
        methodCall: MethodCallExpression,
        symbolTable: SymbolTable,
        astVisitor: GroovyAstModel,
        moduleNode: ModuleNode,
    ): String {
        if (methodCall.isImplicitThis) {
            return resolveImplicitThisReceiverType(methodCall, astVisitor)
        }

        val objExpr = methodCall.objectExpression
        // Check static type first (like HoverContentGenerator:114-117)
        // For explicitly typed variables (String text, ArrayList list), use static type
        val staticType = objExpr.type
        if (!staticType.isDynamic()) {
            val fqn = staticType.name
            if (fqn != "java.lang.Object") {
                return fqn
            }
        }

        // Only use SemanticTypeResolver for dynamic types that need inference
        val semanticType = semanticResolver.resolveType(objExpr, moduleNode)
        val type = formatSemanticTypeToFqn(semanticType)
        return receiverTypeRefiner.refine(type, objExpr, astVisitor, symbolTable, moduleNode)
    }

    /**
     * Format SemanticType to fully qualified name for classpath lookups.
     * Delegates to SemanticTypeFormatter for consistent formatting.
     */
    private fun formatSemanticTypeToFqn(type: SemanticType): String = SemanticTypeFormatter.formatToFqn(type)

    private fun resolveImplicitThisReceiverType(methodCall: MethodCallExpression, astVisitor: GroovyAstModel): String {
        var current: ASTNode? = methodCall
        while (current != null && current !is ClassNode) {
            current = astVisitor.getParent(current)
        }
        return current?.name ?: "groovy.lang.Script"
    }

    private fun buildSignatureHelp(context: SignatureContext, position: Position): SignatureHelp {
        val signatures = context.signatures
        val activeParameter = context.activeParameterOverride ?: determineActiveParameter(
            context.methodCall,
            context.nodeAtPosition,
            position.toGroovyPosition(),
            context.astVisitor,
        )
        // Normalize active parameter to prevent out-of-bounds
        // We take the max parameter count of available signatures to be safe,
        // or just clamp to the first signature (standard LSP behavior is a bit vague here)
        // Better: checking against each signature's param count is client responsibility.
        // We simply provide the index derived from cursor.
        // But for safety against client crashes:
        // Limit active parameter to valid range [0, maxParams - 1] (or 0 if empty)
        // We use maxParams not maxParams - 1 because we want to allow typing the "next" parameter
        // or varargs, but staying reasonably within bounds prevents client confusion.
        // Actually, LSP spec says: "If the active parameter is out of bounds, the client
        // should decide how to handle it (e.g. highlight nothing or the last parameter)."
        // But to be safe, we clamp to available parameters.
        val maxParams = signatures.maxOfOrNull { it.parameters.size } ?: 0
        // Clamp to [0, maxParams] (allow one past end for "next" param indication, or just maxParams-1?)
        // The user feedback specifically requested strict bounds, likely similar to [0, lastIndex].
        // But if I am at cursor position for arg 2 in a 2-arg method, I might be typing the 3rd arg.
        // Standard practice: activeParameter can be >= parameters.length (meaning too many args).
        // However, user feedback says: "For a method with 2 parameters... this allows indices
        // up to 3... cause LSP clients to receive out-of-bounds".
        // So I will clamp to (parameters.size - 1).coerceAtLeast(0).
        val lastIndex = maxParams.coerceAtLeast(1) - 1
        // If maxParams=0 -> lastIndex=0. If maxParams=2 -> lastIndex=1.
        val normalizedActiveParameter = activeParameter.coerceIn(0, lastIndex)

        return SignatureHelp().apply {
            this.signatures = signatures
            this.activeSignature = 0 // Best match logic could go here
            this.activeParameter = normalizedActiveParameter
        }
    }

    private suspend fun ensureAstPrepared(uri: URI) {
        val hasAst = compilationService.getAst(uri) != null
        val hasVisitor = compilationService.getAstModel(uri) != null
        val hasSymbols = compilationService.getSymbolTable(uri) != null

        if (hasAst && hasVisitor && hasSymbols) {
            return
        }

        val content = documentProvider.get(uri) ?: return
        runSuspendCatching { compilationService.compile(uri, content) }
            .onFailure { error ->
                logger.debug(error) { "Unable to compile $uri before providing signature help" }
            }
    }

    private suspend fun parseTransient(uri: URI, source: String): ParseResult? =
        runSuspendCatching { compilationService.compileTransient(uri, source) }
            .onFailure { error -> logger.debug(error) { "Unable to parse current source for signature help: $uri" } }
            .getOrNull()

    private fun requireCurrentSnapshot(uri: URI, snapshot: DocumentSnapshot) {
        if (documentProvider.getSnapshot(uri) !== snapshot) {
            throw ResponseErrorException(
                ResponseError(ResponseErrorCode.ContentModified, "Document changed during signature help", null),
            )
        }
    }

    // --- Script Method Helpers ---

    private val scriptMethodsByName by lazy {
        try {
            Script::class.java.methods
                .filter { p -> Modifier.isPublic(p.modifiers) }
                .groupBy { it.name }
        } catch (e: SecurityException) {
            logger.warn { "Failed to pre-resolve Script methods: ${e.message}" }
            emptyMap<String, List<Method>>()
        }
    }

    private fun findScriptMethods(methodName: String): List<MethodNode> =
        scriptMethodsByName[methodName]?.map { it.toMethodNode() } ?: emptyList()

    private fun java.lang.reflect.Method.toMethodNode(): MethodNode {
        val params = parameters.map { p ->
            Parameter(ClassHelper.make(p.type), p.name)
        }.toTypedArray()
        return MethodNode(
            name,
            modifiers,
            ClassHelper.make(returnType),
            params,
            ClassNode.EMPTY_ARRAY,
            null,
        )
    }
}

/** Refines dynamic receiver types from declarations in the request's own AST and symbol table. */
private class ReceiverTypeRefiner(private val semanticResolver: SemanticTypeResolver) {
    fun refine(
        type: String,
        objExpr: Expression,
        astVisitor: GroovyAstModel,
        symbolTable: SymbolTable,
        moduleNode: ModuleNode,
    ): String {
        val initExpr = if (type == "java.lang.Object" || type == "java.lang.Class") {
            (objExpr as? VariableExpression)?.let { findInitializerExpression(it, astVisitor, symbolTable) }
        } else {
            null
        }
        val refinedType = initExpr?.let { resolveInitializerType(it, moduleNode) }
        return refinedType?.takeIf { it != "java.lang.Object" } ?: type
    }

    private fun resolveInitializerType(initExpr: Expression, moduleNode: ModuleNode): String? =
        if (initExpr is ConstructorCallExpression) {
            initExpr.type.name
        } else {
            runCatching { semanticResolver.resolveType(initExpr, moduleNode) }
                .getOrNull()
                ?.let { SemanticTypeFormatter.formatToFqn(it) }
        }

    private fun findInitializerExpression(
        varExpr: VariableExpression,
        astVisitor: GroovyAstModel,
        symbolTable: SymbolTable,
    ): Expression? {
        val resolvedVar = symbolTable.resolveSymbol(varExpr, astVisitor)
        val declaredInitializer = resolvedVar?.takeIf { it.hasInitialExpression() }?.initialExpression
        return declaredInitializer ?: findLocalInitializer(varExpr, astVisitor)
    }

    private fun findLocalInitializer(varExpr: VariableExpression, astVisitor: GroovyAstModel): Expression? =
        astVisitor.getUri(varExpr)?.let { uri ->
            astVisitor.getNodes(uri).filterIsInstance<DeclarationExpression>()
                .firstOrNull { declaration ->
                    val left = declaration.leftExpression
                    left is VariableExpression && left.name == varExpr.name
                }?.rightExpression
        }
}

// --- Converters ---

private fun MethodNode.toSignatureInformation(): SignatureInformation {
    // A parameter written `Type...` reads as written: the parse context Groovy keeps on the parameter
    // records the `...` (Parameter.isVarargs). Explicit `Type[]` parameters, and parameters without that
    // context, display as arrays (#1405).
    val paramsInfo = parameters.map { param ->
        val typeName = when {
            param.isDynamicTyped -> "def"
            else -> param.displayType()
        }
        val defaultValue = param.initialExpression?.let { expr ->
            val text = expr.text ?: return@let ""
            if (expr is ConstantExpression && expr.type.name == "java.lang.String") {
                " = \"$text\""
            } else {
                " = $text"
            }
        } ?: ""
        val paramLabel = "$typeName ${param.name}$defaultValue"
        ParameterInformation().apply { label = Either.forLeft(paramLabel) }
    }

    val label = buildString {
        append(returnType.displayName()).append(" ")
        append(name).append("(")
        append(paramsInfo.joinToString(", ") { it.label.left as String })
        append(")")
    }

    return SignatureInformation(label, null as String?, paramsInfo)
}

private fun GdkExtensionMethod.toSignatureInformation(): SignatureInformation {
    val paramsInfo = parameterTypes.mapIndexed { i, type ->
        val paramName = parameterNames.getOrNull(i) ?: "arg$i"
        val paramLabel = if (paramName.matches(SYNTHETIC_PARAM_PATTERN)) {
            type
        } else {
            "$type $paramName"
        }
        ParameterInformation().apply { label = Either.forLeft(paramLabel) }
    }

    val label = buildString {
        append(returnType).append(" ")
        append(name).append("(")
        // Use mapIndexed to avoid truncation when parameterNames.size < parameterTypes.size
        append(
            parameterTypes.mapIndexed { i, type ->
                val paramName = parameterNames.getOrNull(i) ?: "arg$i"
                if (paramName.matches(SYNTHETIC_PARAM_PATTERN)) {
                    type
                } else {
                    "$type $paramName"
                }
            }.joinToString(", "),
        )
        append(")")
    }

    return SignatureInformation(label, doc.takeIf { it.isNotBlank() }, paramsInfo)
}

private fun ReflectedMethod.toSignatureInformation(): SignatureInformation {
    val paramsInfo = displayParameters.map { paramType ->
        // ReflectedMethod params are currently just type strings?
        // Checking ReflectedMethod definition: val parameters: List<String>
        // These are FQCNs or SimpleNames.
        // We ideally want "Type argName", but reflection only gives names if -parameters is on.
        // Usually just type.
        ParameterInformation().apply { label = Either.forLeft(paramType) }
    }

    // Simplistic label generation
    val retTypeSimple = returnType.substringAfterLast('.')
    val label = buildString {
        append(retTypeSimple).append(" ")
        append(name).append("(")
        append(paramsInfo.joinToString(", ") { withoutPackage(it.label.left as String) })
        append(")")
    }

    return SignatureInformation(label, doc.takeIf { it.isNotBlank() }, paramsInfo)
}

// --- Helpers ---

/** `java.lang.Object...` reads `Object...`: only the package goes, never the varargs dots. */
private fun withoutPackage(type: String): String {
    val base = type.removeSuffix(VARARGS)
    return base.substringAfterLast('.') + type.substring(base.length)
}

private const val VARARGS = "..."

private fun determineActiveParameter(
    methodCall: MethodCallExpression,
    nodeAtPosition: ASTNode,
    position: GroovyPosition,
    astVisitor: GroovyAstModel,
): Int {
    val arguments = methodCall.argumentExpressions()
    arguments.forEachIndexed { index, argument ->
        if (argument == nodeAtPosition || astVisitor.contains(argument, nodeAtPosition)) {
            return index
        }
        if (argument.containsPosition(position.line, position.character)) {
            return index
        }
    }
    return estimateParameterIndex(arguments, position)
}

private fun estimateParameterIndex(arguments: List<Expression>, position: GroovyPosition): Int {
    arguments.forEachIndexed { index, argument ->
        val start = argument.safePosition().getOrNull()?.toParserPosition()
        if (start != null && isBefore(position, start)) {
            return index
        }
    }
    return arguments.size
}

private fun isBefore(position: GroovyPosition, other: GroovyPosition): Boolean {
    if (position.line != other.line) return position.line < other.line
    return position.character < other.character
}

private fun findMethodCall(
    astVisitor: GroovyAstModel,
    documentUri: URI,
    nodeAtPosition: ASTNode,
    position: GroovyPosition,
): MethodCallExpression? {
    var current: ASTNode? = nodeAtPosition
    while (current != null && current !is MethodCallExpression) {
        current = astVisitor.getParent(current)
    }
    if (current is MethodCallExpression) return current

    return astVisitor.getNodes(documentUri)
        .asSequence()
        .filterIsInstance<MethodCallExpression>()
        .firstOrNull { it.containsPosition(position.line, position.character) }
}

private fun MethodCallExpression.argumentExpressions(): List<Expression> = when (val args = arguments) {
    is ArgumentListExpression -> args.expressions
    is TupleExpression -> args.expressions
    else -> emptyList()
}

private fun MethodCallExpression.extractMethodName(): String? {
    methodAsString?.let { return it }
    return when (val methodExpression = method) {
        is ConstantExpression -> methodExpression.value?.toString()
        is VariableExpression -> methodExpression.name
        is PropertyExpression -> methodExpression.propertyAsString
        else -> null
    }
}

private fun emptySignatureHelp(): SignatureHelp = SignatureHelp().apply {
    signatures = mutableListOf()
}
