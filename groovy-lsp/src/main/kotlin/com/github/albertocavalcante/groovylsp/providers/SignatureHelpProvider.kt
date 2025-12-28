package com.github.albertocavalcante.groovylsp.providers

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.services.GdkExtensionMethod
import com.github.albertocavalcante.groovylsp.services.ReflectedMethod
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.TypeInferencer
import com.github.albertocavalcante.groovyparser.ast.containsPosition
import com.github.albertocavalcante.groovyparser.ast.safePosition
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory
import java.net.URI

class SignatureHelpProvider(
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
) {

    private val logger = LoggerFactory.getLogger(SignatureHelpProvider::class.java)

    suspend fun provideSignatureHelp(uri: String, position: Position): SignatureHelp {
        val documentUri = URI.create(uri)
        ensureAstPrepared(documentUri)

        val context = resolveSignatureContext(documentUri, position) ?: return emptySignatureHelp()

        return buildSignatureHelp(context, position)
    }

    private data class SignatureContext(
        val methodCall: MethodCallExpression,
        val nodeAtPosition: ASTNode,
        val signatures: List<SignatureInformation>,
        val astVisitor: GroovyAstModel,
    )

    @Suppress("ReturnCount", "LongMethod", "ComplexMethod")
    private suspend fun resolveSignatureContext(documentUri: URI, position: Position): SignatureContext? {
        val astVisitor = compilationService.getAstModel(documentUri) ?: run {
            logger.debug("No AST visitor available for {}", documentUri)
            return null
        }
        val symbolTable = compilationService.getSymbolTable(documentUri) ?: run {
            logger.debug("No symbol table available for {}", documentUri)
            return null
        }

        val groovyPos = position.toGroovyPosition()
        val nodeAtPosition = astVisitor.getNodeAt(documentUri, groovyPos) ?: run {
            logger.debug("No AST node found at $position for $documentUri")
            return null
        }

        val methodCall = findMethodCall(astVisitor, documentUri, nodeAtPosition, groovyPos) ?: run {
            logger.debug("No method call expression near $position for $documentUri")
            return null
        }

        val methodName = methodCall.extractMethodName() ?: run {
            logger.debug("Could not resolve method name for call at $position in $documentUri")
            return null
        }

        val allSignatures = mutableListOf<SignatureInformation>()

        // 1. Source (Local Declarations)
        val declarations = symbolTable.registry.findMethodDeclarations(documentUri, methodName)
        allSignatures.addAll(declarations.map { it.toSignatureInformation() })

        // 2 + 3. Receiver Resolution (GDK & Classpath)
        // Determine the type of the object the method is being called on
        var receiverType = if (methodCall.isImplicitThis) {
            // Implicit 'this' call (e.g. println) -> usually Script or enclosing class
            "groovy.lang.Script"
        } else {
            // Explicit receiver (e.g. list.each) -> Infer type
            val objExpr = methodCall.objectExpression
            var type = TypeInferencer.inferExpressionType(objExpr)

            // Refine type if it's Object/Dynamic and likely a variable
            if ((type == "java.lang.Object" || type == "java.lang.Class") && objExpr is VariableExpression) {
                val resolvedVar = symbolTable.resolveSymbol(objExpr, astVisitor)
                if (resolvedVar != null && resolvedVar.hasInitialExpression()) {
                    // Infer from initializer (e.g. def list = [])
                    resolvedVar.initialExpression?.let { init ->
                        val inferred = TypeInferencer.inferExpressionType(init)
                        if (inferred != "java.lang.Object") {
                            type = inferred
                        }
                    }
                }
            }
            type
        }

        // Sanitize type name (remove generics) for reflection/GDK lookup
        val targetType = receiverType.substringBefore("<")

        val gdkProvider = compilationService.gdkProvider
        val classpathService = compilationService.classpathService

        // 1. AST Methods (Local)
        val astMethods: List<MethodNode> = methodCall.methodAsString?.let { name ->
            if (methodCall.isImplicitThis) {
                findScriptMethods(name)
            } else {
                emptyList()
            }
        } ?: emptyList()
        allSignatures.addAll(astMethods.map { node -> node.toSignatureInformation() })

        // 2. GDK Extension Methods (e.g. .each, .collect)
        val gdkMethods = gdkProvider.getMethodsForType(targetType)
            .filter { it.name == methodName }
        allSignatures.addAll(gdkMethods.map { it.toSignatureInformation() })

        // 3. Classpath Methods (e.g. ArrayList.add, String.substring)
        // If the type is standard (not Object/dynamic), check classpath reflection
        if (targetType != "java.lang.Object" && targetType != "java.lang.Class") {
            try {
                // We use ClasspathService to find methods on the inferred type
                val methods = classpathService.getMethods(targetType)
                    .filter { it.name == methodName && it.isPublic }
                allSignatures.addAll(methods.map { it.toSignatureInformation() })
            } catch (e: Exception) {
                logger.debug("Failed to reflect on type $targetType: ${e.message}")
            }
        }

        // If we still have nothing and implicit this, verify Object methods just in case
        if (allSignatures.isEmpty() && methodCall.isImplicitThis) {
            val objectMethods = classpathService.getMethods("java.lang.Object")
                .filter { it.name == methodName }
            allSignatures.addAll(objectMethods.map { it.toSignatureInformation() })
        }

        if (allSignatures.isEmpty()) {
            logger.debug("No signatures found for $methodName on type $receiverType")
            return null
        }

        // Deduplicate signatures based on label
        val distinctSignatures = allSignatures.distinctBy { it.label }

        return SignatureContext(methodCall, nodeAtPosition, distinctSignatures, astVisitor)
    }

    private fun buildSignatureHelp(context: SignatureContext, position: Position): SignatureHelp {
        val signatures = context.signatures
        val activeParameter = determineActiveParameter(
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
        val maxParams = signatures.maxOfOrNull { it.parameters.size } ?: 0
        val normalizedActiveParameter = activeParameter.coerceAtMost(maxParams + 1) // +1 for varargs tolerance

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
        runCatching { compilationService.compile(uri, content) }
            .onFailure { error ->
                logger.debug("Unable to compile $uri before providing signature help", error)
            }
    }

    // --- Converters ---

    private fun MethodNode.toSignatureInformation(): SignatureInformation {
        val paramsInfo = parameters.map { param ->
            val typeName = if (param.isDynamicTyped) "def" else param.type.nameWithoutPackage
            val paramLabel = "$typeName ${param.name}"
            ParameterInformation().apply { label = Either.forLeft(paramLabel) }
        }

        val label = buildString {
            append(returnType.nameWithoutPackage).append(" ")
            append(name).append("(")
            append(paramsInfo.joinToString(", ") { (it.label.left as String) })
            append(")")
        }

        return SignatureInformation(label, null as String?, paramsInfo)
    }

    private fun GdkExtensionMethod.toSignatureInformation(): SignatureInformation {
        val paramsInfo = parameters.map { param ->
            // GDK params are just type names usually (from simpleName)
            // We can improve this if GDK provider gives more info.
            // For now, assume "Type arg" pattern or just "Type"
            ParameterInformation().apply { label = Either.forLeft(param) }
        }

        val label = buildString {
            append(returnType).append(" ")
            append(name).append("(")
            append(parameters.joinToString(", "))
            append(")")
        }

        return SignatureInformation(label, doc.takeIf { it.isNotBlank() } as String?, paramsInfo)
    }

    private fun ReflectedMethod.toSignatureInformation(): SignatureInformation {
        val paramsInfo = parameters.map { paramType ->
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
            append(paramsInfo.joinToString(", ") { (it.label.left as String).substringAfterLast('.') })
            append(")")
        }

        return SignatureInformation(label, doc.takeIf { it.isNotBlank() } as String?, paramsInfo)
    }

    // --- Helpers ---

    private fun determineActiveParameter(
        methodCall: MethodCallExpression,
        nodeAtPosition: ASTNode,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
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

    private fun estimateParameterIndex(
        arguments: List<Expression>,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
    ): Int {
        arguments.forEachIndexed { index, argument ->
            val start = argument.safePosition().getOrNull()?.toParserPosition()
            if (start != null && isBefore(position, start)) {
                return index
            }
        }
        return arguments.size
    }

    private fun isBefore(
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
        other: com.github.albertocavalcante.groovyparser.ast.types.Position,
    ): Boolean {
        if (position.line != other.line) return position.line < other.line
        return position.character < other.character
    }

    private fun findMethodCall(
        astVisitor: GroovyAstModel,
        documentUri: URI,
        nodeAtPosition: ASTNode,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
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
        val methodExpression = method
        return when (methodExpression) {
            is ConstantExpression -> methodExpression.value?.toString()
            is VariableExpression -> methodExpression.name
            is PropertyExpression -> methodExpression.propertyAsString
            else -> null
        }
    }

    private fun emptySignatureHelp(): SignatureHelp = SignatureHelp().apply {
        signatures = mutableListOf()
    }
    // --- Script Method Helpers ---

    private val scriptMethodsByName by lazy {
        try {
            groovy.lang.Script::class.java.methods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                .groupBy { it.name }
        } catch (e: Exception) {
            logger.warn("Failed to pre-resolve Script methods: {}", e.message)
            emptyMap<String, List<java.lang.reflect.Method>>()
        }
    }

    private fun findScriptMethods(methodName: String): List<MethodNode> =
        scriptMethodsByName[methodName]?.map { it.toMethodNode() } ?: emptyList()

    private fun java.lang.reflect.Method.toMethodNode(): MethodNode {
        val params = parameters.map { p ->
            org.codehaus.groovy.ast.Parameter(org.codehaus.groovy.ast.ClassHelper.make(p.type), p.name)
        }.toTypedArray()
        return MethodNode(
            name,
            modifiers,
            org.codehaus.groovy.ast.ClassHelper.make(returnType),
            params,
            org.codehaus.groovy.ast.ClassNode.EMPTY_ARRAY,
            null,
        )
    }
}
