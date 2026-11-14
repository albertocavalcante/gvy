// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion.strategy

import com.github.albertocavalcante.groovyparser.ast.isScriptSafe
import com.github.albertocavalcante.gvy.gls.compilation.ClassNameQuery
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.dsl.completion.CompletionsBuilder
import com.github.albertocavalcante.gvy.gls.dsl.completion.MethodSignatureKey
import com.github.albertocavalcante.gvy.gls.dsl.completion.completions
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionContext
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider.ContextType
import com.github.albertocavalcante.gvy.gls.providers.completion.JenkinsCompletionProvider
import com.github.albertocavalcante.gvy.gls.providers.completion.TextImportInfo
import com.github.albertocavalcante.gvy.gls.providers.completion.TextImportParser
import com.github.albertocavalcante.gvy.gls.providers.completion.followsMemberAccessDot
import com.github.albertocavalcante.gvy.gls.services.reflectSafely
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.SemanticTypeFormatter
import com.github.albertocavalcante.gvy.semantics.db.ImplementationDocument
import com.github.albertocavalcante.gvy.semantics.db.ImplementationFacts
import com.github.albertocavalcante.gvy.semantics.db.ImplementationMethodSignatures
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.native.DeclarationWalker
import com.github.albertocavalcante.gvy.semantics.native.displayName
import com.github.albertocavalcante.gvy.semantics.native.displayParameterTypes
import com.github.albertocavalcante.gvy.semantics.native.displayType
import com.github.albertocavalcante.gvy.semantics.workspace.MemberInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemLabelDetails

/**
 * Completion strategy for member access contexts (e.g., "myList." or "env.").
 *
 * Handles completion after the dot operator by providing:
 * - Map literal keys for variables with map initializers
 * - Jenkins global variable properties (env, currentBuild, etc.)
 * - Workspace members from cross-file classes
 * - GDK (Groovy Development Kit) methods
 * - Classpath methods from JDK/dependencies
 *
 * This strategy delegates to specialized completion builders for each category.
 */
internal class MemberAccessCompletionStrategy(
    private val compilationService: GroovyCompilationService,
    private val workspaceSymbolIndex: WorkspaceSymbolIndex?,
) : CompletionStrategy {

    private val logger = KotlinLogging.logger {}

    override suspend fun complete(context: CompletionStrategyContext): CompletionResult {
        // Only handle MemberAccess contexts
        val memberAccessContext = context.contextType as? ContextType.MemberAccess
            ?: return CompletionStrategy.notApplicable("MemberAccessCompletionStrategy")

        // `sh 'ls' |` parses (with the dummy identifier) as `sh('ls').<dummy>` but has no dot to complete after.
        val base = context.baseContext
        if (!followsMemberAccessDot(base.content, base.line, base.character)) {
            return CompletionStrategy.notApplicable("MemberAccessCompletionStrategy")
        }

        val items = completions {
            handleMemberAccessContext(
                memberAccessContext,
                context,
                context.jenkinsMetadata,
            )
        }

        return CompletionStrategy.found(items.withPackageHints())
    }

    /**
     * Handles member access completion context.
     *
     * Strategy order:
     * 1. Map literal keys (most specific)
     * 2. Jenkins global variable properties
     * 3. Workspace members (cross-file classes)
     * 4. GDK methods
     * 5. Classpath methods
     */
    private fun CompletionsBuilder.handleMemberAccessContext(
        completionContext: ContextType.MemberAccess,
        ctx: CompletionStrategyContext,
        metadata: MergedJenkinsMetadata?,
    ) {
        val qualifierName = completionContext.qualifierName
        val module = ctx.baseContext.moduleNode
        val staticType = staticClasspathReceiver(ctx, compilationService)
        if (staticType != null) {
            addStaticClasspathMembers(staticType, compilationService)
            return
        }
        val receiverType = module?.let { explicitReceiverType(qualifierName, it, ctx.baseContext) }
            ?: completionContext.qualifierType
        val rawType = receiverType.substringBefore('<')

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
            with(JenkinsCompletionProvider) { addJenkinsPropertyCompletions(globalVar) }
            return
        }

        // Held declarations are authoritative for this buffer, including unsaved changes.
        val held = LocalCompletionMembers(module)
        val local = held.lookup(rawType)
        if (local == null) {
            addSharedMembers(rawType, ctx)
            return
        }

        // Every method below carries an exact qualified signature, so overrides collapse across
        // sources while overloads differing only by package stay separate under simple display names.
        local.methods.forEach { addCandidate(it) }
        addWorkspaceMembers(local.fields)
        val walk = addInheritedMembers(local, held, workspaceSymbolIndex)
        addQualifiedGdkMethods(rawType)
        // Facts are built only when a generic binary ancestor could need them (never for Object alone).
        val genericOverrides by lazy { walk.genericOverrides.flatMapTo(mutableSetOf()) { it.value } }
        walk.binaryTypes.forEach { type -> addQualifiedClasspathMethods(type) { genericOverrides } }
    }

    /** Receivers resolved only from shared metadata keep their display-based de-duplication. */
    private fun CompletionsBuilder.addSharedMembers(rawType: String, ctx: CompletionStrategyContext) {
        workspaceSymbolIndex?.let { index ->
            val members = resolveWorkspaceClassFqns(rawType, ctx)
                .flatMap { fqn -> index.getAllMembers(fqn.replace('.', '/'), includeInherited = true) }
                .distinctBy { it.symbolId }
            addWorkspaceMembers(members)
        }

        logger.debug { "Adding GDK methods for $rawType" }
        addGdkMethods(rawType, compilationService)

        logger.debug { "Adding Classpath methods for $rawType" }
        addClasspathMethods(rawType, compilationService)
    }

    /**
     * Adds members that [local]'s class inherits from outside the held chain: indexed workspace
     * ancestors, across files, in the same superclass-first order as the held walk. A held class reached
     * through the index uses its buffer declarations, never the published copy. Returns the binary
     * ancestors left for classpath reflection and the reflected methods source overrides generically.
     */
    private fun CompletionsBuilder.addInheritedMembers(
        local: LocalCompletionMembers.Result,
        held: LocalCompletionMembers,
        index: WorkspaceSymbolIndex?,
    ): InheritedWalk {
        val overrides = mutableListOf(local.genericOverrides)
        if (index == null) return InheritedWalk(local.externalTypes, overrides)
        val pending = ArrayDeque(local.externalTypes)
        val visited = local.visited.toMutableSet()
        val binary = mutableListOf<String>()
        val methods = mutableListOf<MethodCandidate>()
        val members = mutableListOf<MemberInfo>()
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!visited.add(name)) continue
            val step = inheritedStep(name, held, index)
            methods += step.methods
            members += step.members
            step.genericOverrides?.let(overrides::add)
            visited += step.covered
            step.parents.interfaces.forEach(pending::addLast)
            step.parents.superclass?.let(pending::addFirst)
            if (step.binary) binary += name
        }
        methods.forEach { addCandidate(it) }
        addWorkspaceMembers(members)
        return InheritedWalk(binary, overrides)
    }

    private fun CompletionsBuilder.addQualifiedGdkMethods(className: String) {
        val types = resolveDataTypes(className, compilationService).ifEmpty { listOf(className) }
        types.flatMap { compilationService.gdkProvider.getQualifiedMethodsForType(it) }.forEach { gdk ->
            val method = gdk.display
            addCandidate(
                MethodCandidate(
                    name = method.name,
                    returnType = method.returnType,
                    parameters = gdk.qualifiedParameters.zip(method.parameterTypes, ::CandidateParameter),
                    doc = method.doc,
                ),
            )
        }
    }

    /** Reflected methods that a source method already overrides through generics are left out. */
    private fun CompletionsBuilder.addQualifiedClasspathMethods(
        className: String,
        genericOverrides: () -> Set<Pair<String, List<String>>>,
    ) {
        val types = resolveDataTypes(className, compilationService).ifEmpty { listOf(className) }
        types.forEach { type ->
            val reflected = compilationService.classpathService.loadClass(type) ?: return@forEach
            // Generic signatures may need types the (possibly closed) loader never loaded: keep what was added.
            reflectSafely({ "completion methods of $type" }, Unit) {
                reflectedInstanceMethods(reflected).filterNot {
                    it.isGenericInstantiation() &&
                        (it.name to it.parameterTypes.map { parameter -> parameter.typeName }) in genericOverrides()
                }.forEach { method ->
                    addCandidate(
                        MethodCandidate(
                            name = method.name,
                            returnType = method.returnType.simpleName,
                            // Identity keys on the JVM type; the display reads `Object...` when declared varargs.
                            parameters = method.parameterTypes.zip(method.displayParameterTypes()) { type, shown ->
                                CandidateParameter(type.typeName, shown)
                            },
                            doc = "JDK/Classpath method from ${method.declaringClass.simpleName}",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Resolves workspace class candidates for a simple name using package/import
     * context first, then falls back to a workspace-wide symbol scan.
     *
     * Order of candidates: same-package, explicit imports, star imports, workspace scan.
     * Candidates from imports are not validated for existence; consumers must disambiguate.
     */
    private fun resolveWorkspaceClassFqns(rawType: String, ctx: CompletionStrategyContext): List<String> {
        if (rawType.contains('.')) return listOf(rawType)

        val candidates = linkedSetOf<String>()
        val simpleName = rawType
        val moduleNode = ctx.baseContext.moduleNode
        val content = ctx.baseContext.content

        val importInfo = moduleNode?.let { node ->
            TextImportInfo(
                packageName = node.packageName,
                explicitImports = node.imports.mapNotNull { it.className }.toSet(),
                starImports = node.starImports.mapNotNull { it.packageName }.toSet(),
            )
        } ?: TextImportParser.parse(content) // Best-effort, line-based fallback import parsing.

        importInfo.packageName?.takeIf { it.isNotBlank() }?.let { candidates.add("$it.$simpleName") }
        importInfo.explicitImports
            .filter { it.substringAfterLast('.') == simpleName }
            .forEach { candidates.add(it) }
        importInfo.starImports.forEach { candidates.add("$it.$simpleName") }

        findWorkspaceClassFqnsBySimpleName(simpleName, compilationService)
            .forEach { candidates.add(it) }

        return candidates.toList()
    }

    /** Workspace classes with this simple name, from the indexed name lookup rather than a full scan (#861). */
    private fun findWorkspaceClassFqnsBySimpleName(
        simpleName: String,
        compilationService: GroovyCompilationService,
    ): List<String> = compilationService.symbolIndexingService.findClasses(ClassNameQuery.Exact(simpleName))
        .mapTo(linkedSetOf()) { it.fullyQualifiedName }
        .toList()

    /**
     * Finds the enclosing block for the cursor position.
     * Returns the method body block if inside a method, or the script's statement block.
     */
    private fun findEnclosingBlock(ctx: CompletionStrategyContext): BlockStatement? {
        val moduleNode = ctx.baseContext.moduleNode ?: return null
        val line = ctx.baseContext.line

        // Check if cursor is inside any class method
        for (classNode in moduleNode.classes) {
            // Use findLast to prefer the innermost scope if a method has invalid end line (effectively infinite range)
            val method = classNode.methods.findLast { method ->
                method.lineNumber > 0 &&
                    method.lineNumber <= line + 1 &&
                    (method.lastLineNumber >= line + 1 || method.lastLineNumber <= 0)
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
        ctx: CompletionStrategyContext,
    ): Boolean {
        val moduleNode = ctx.baseContext.moduleNode ?: return false
        val semanticResolver = ctx.baseContext.semanticResolver
        val nativeContext = semanticResolver.semantics.getContext(moduleNode)
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
                        parameters = member.signature.completionParameters().map(::simpleTypeName),
                        doc = "Method: ${member.name}${member.signature ?: "()"}",
                    )
                }

                else -> {
                    /* Skip constructors and other kinds */
                }
            }
        }
    }

    private fun CompletionsBuilder.addCandidate(candidate: MethodCandidate) {
        method(
            name = candidate.name,
            returnType = candidate.returnType,
            parameters = candidate.parameters.map { it.display },
            doc = candidate.doc,
            signature = candidate.parameters.joinToString(",") { it.identity },
        )
    }

    /**
     * Add GDK (Groovy Development Kit) method completions for a given type.
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
                    parameters = method.displayParameters,
                    doc = method.doc,
                )
            }
        }
    }

    /**
     * Resolves data types by looking up on classpath and trying common packages.
     */
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
}

private fun CompletionsBuilder.addStaticClasspathMembers(type: String, compilationService: GroovyCompilationService) {
    val classpath = compilationService.classpathService
    classpath.getMethods(type).filter { it.isPublic && it.isStatic }.forEach { reflected ->
        val parameters = reflected.qualifiedParameters.zip(reflected.displayParameters, ::CandidateParameter)
        method(
            name = reflected.name,
            returnType = reflected.returnType,
            parameters = parameters.map { it.display },
            doc = reflected.doc,
            signature = parameters.joinToString(",") { it.identity },
        )
    }
    classpath.getFields(type).filter { it.isPublic && it.isStatic }.forEach { member ->
        field(name = member.name, type = member.type)
    }
}

/** A class expression names the owner; its semantic type may instead be java.lang.Class. */
private fun staticClasspathReceiver(
    ctx: CompletionStrategyContext,
    compilationService: GroovyCompilationService,
): String? {
    val module = ctx.baseContext.moduleNode ?: return null
    val receiver = receiverExpression(ctx) ?: return null
    val type = when (receiver) {
        is ClassExpression -> receiver.type.name
        is VariableExpression -> importedClassReceiver(receiver, module, ctx)
        else -> null
    } ?: return null
    // Source declarations remain authoritative, even if a classpath contains the same name.
    return type.takeIf { name ->
        module.classes.none { it.name == name } && compilationService.classpathService.loadClass(name) != null
    }
}

private fun importedClassReceiver(
    receiver: VariableExpression,
    module: ModuleNode,
    ctx: CompletionStrategyContext,
): String? {
    val variable = receiver.accessedVariable
    if (variable != null && variable !is DynamicVariable) return null
    val name = receiver.name
    if (ctx.symbolContext.variables.any { it.name == name } || ctx.symbolContext.fields.any { it.name == name }) {
        return null
    }
    return module.imports.singleOrNull { it.alias == name }?.className
}

private fun receiverExpression(ctx: CompletionStrategyContext): Expression? {
    val node = ctx.nodeAtCursor
    val parent = node?.let { ctx.baseContext.astModel.getParent(it) }
    return when {
        node is PropertyExpression -> node.objectExpression
        parent is PropertyExpression -> parent.objectExpression
        else -> null
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
 * Extracts the raw parameter types of a symbol signature, respecting generic brackets.
 *
 * @param signature The method signature
 *   (e.g., "com/example/MyClass#myMethod(String,int)." or "com/example/MyClass#myMethod(Map<String,String>).")
 * @return List of parameter types as written (e.g., ["String", "int"] or ["Map<String,String>"])
 */
private fun String?.completionParameters(): List<String> {
    val signature = this ?: return emptyList()

    val startIndex = signature.indexOf('(')
    val endIndex = signature.indexOf(')')

    if (startIndex < 0 || endIndex < 0 || startIndex >= endIndex) {
        return emptyList()
    }

    val params = signature.substring(startIndex + 1, endIndex).trim()

    // Split by comma while respecting angle brackets for generics
    return parseParametersWithGenerics(params)
}

/**
 * Parse comma-separated parameters while respecting angle brackets for generics.
 *
 * This helper extracts the complex bracket-tracking logic from completionParameters.
 */
private fun parseParametersWithGenerics(params: String): List<String> {
    val result = mutableListOf<String>()
    val currentParam = StringBuilder()
    var bracketDepth = 0
    var invalidBrackets = false

    fun addCurrentParam() {
        result.add(currentParam.toString().trim())
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
        return params.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    // Add the last parameter
    if (currentParam.isNotEmpty()) {
        addCurrentParam()
    }

    return result
}

private val QUALIFIED_NAME = Regex("""(?:[\w$]+[./])+([\w$]+)""")

/** `java.util.Map<java.lang.String,int[]>` becomes `Map<String,int[]>`; `java.util.Map$Entry` becomes `Entry`. */
private fun simpleTypeName(type: String): String =
    QUALIFIED_NAME.replace(type.trim()) { it.groupValues[1].substringAfterLast('$') }

private val reflectionLogger = KotlinLogging.logger {}

/** A typed sibling the [bridge] forwards to: same name and arity, each parameter assignable to the bridge's. */
private fun java.lang.reflect.Method.bridgedBy(bridge: java.lang.reflect.Method): Boolean =
    name == bridge.name && parameterCount == bridge.parameterCount &&
        parameterTypes.indices.all { bridge.parameterTypes[it].isAssignableFrom(parameterTypes[it]) }

internal fun reflectedInstanceMethods(type: Class<*>): List<java.lang.reflect.Method> = try {
    val instance = type.methods.filter {
        java.lang.reflect.Modifier.isPublic(it.modifiers) && !java.lang.reflect.Modifier.isStatic(it.modifiers)
    }
    // A generic bridge (`compareTo(Object)` beside `compareTo(Date)`) is the same member under its erasure.
    // A visibility bridge (StringBuilder.length() over its package-private base) is the only copy: keep it.
    val typed = instance.filterNot { it.isBridge }
    instance.filterNot { bridge -> bridge.isBridge && typed.any { it.bridgedBy(bridge) } }
} catch (error: LinkageError) {
    reflectionLogger.debug(error) { "Incomplete classpath methods for ${type.name}" }
    emptyList()
} catch (error: SecurityException) {
    reflectionLogger.debug(error) { "Cannot reflect completion methods for ${type.name}" }
    emptyList()
}

/** Fields and exact method candidates that [owner] declares itself in its indexed [document]. */
private fun indexedDocumentMembers(
    document: ImplementationDocument,
    owner: String,
): Pair<List<MemberInfo>, List<MethodCandidate>> {
    // Every indexed member here is inherited, so private declarations are not reachable.
    val exact = ImplementationMethodSignatures.parameterTypesBySymbol(document, owner)
    val privateAlternatives = ImplementationMethodSignatures.privateParameterTypesBySymbol(document, owner)
    val members = document.document.symbols.filter {
        it.owner == owner && it.kind in INDEXED_MEMBER_KINDS
    }.map { MemberInfo(it.name, it.kind, it.type, it.symbol, it.symbol) }.distinctBy { it.symbolId }
    val methods = members.filter { it.kind == SymbolKind.METHOD }.flatMap { member ->
        val alternatives = exact[member.symbolId]?.filterNot { it in privateAlternatives[member.symbolId].orEmpty() }
            ?: listOf(member.signature.completionParameters())
        alternatives.map { types ->
            MethodCandidate(
                name = member.name,
                returnType = member.type?.let { formatType(it) } ?: "def",
                parameters = types.map { CandidateParameter(it, simpleTypeName(it)) },
                doc = "Method: ${member.name}(${types.joinToString(", ")})",
            )
        }
    }
    return members.filter { it.kind != SymbolKind.METHOD } to methods
}

/** What one ancestor contributes. Its superclass is walked before its interfaces, as in the held walk. */
private class InheritedStep(
    val methods: List<MethodCandidate> = emptyList(),
    val members: List<MemberInfo> = emptyList(),
    val genericOverrides: Lazy<Set<Pair<String, List<String>>>>? = null,
    val covered: Set<String> = emptySet(),
    val parents: Parents = Parents(),
    val binary: Boolean = false,
)

/** The superclass, walked next, and the interfaces, walked after everything already queued. */
private data class Parents(val superclass: String? = null, val interfaces: List<String> = emptyList())

/**
 * A held class reached through the index uses its buffer declarations, never the published copy.
 * Symbols and qualified facts of an indexed class come from the same captured document.
 */
private fun inheritedStep(name: String, held: LocalCompletionMembers, index: WorkspaceSymbolIndex): InheritedStep {
    held.lookup(name, inherited = true)?.let { buffer ->
        return InheritedStep(
            methods = buffer.methods,
            members = buffer.fields,
            genericOverrides = buffer.genericOverrides,
            covered = buffer.visited,
            parents = Parents(
                buffer.externalSuperclass,
                buffer.externalTypes - setOfNotNull(buffer.externalSuperclass),
            ),
        )
    }
    val owner = "${name.replace('.', '/')}#"
    val document = index.findImplementationDocument(owner)
        ?: return InheritedStep(
            members = index.getAllMembers(name.replace('.', '/'), includeInherited = true),
            binary = true,
        )
    val (fields, methods) = indexedDocumentMembers(document, owner)
    val parents = ImplementationMethodSignatures.supertypes(document, owner)
    return InheritedStep(
        methods = methods,
        members = fields,
        genericOverrides = lazy { ImplementationMethodSignatures.genericOverrides(document, owner) },
        parents = Parents(
            parents?.superclass?.toClassName(),
            parents?.interfaces.orEmpty().map { it.toClassName() },
        ),
    )
}

/** Binary ancestors for classpath reflection, and the reflected methods source overrides generically. */
private class InheritedWalk(
    val binaryTypes: List<String>,
    val genericOverrides: List<Lazy<Set<Pair<String, List<String>>>>>,
)

/** Only a method whose declaring type is generic can be a generic override's erased twin. */
private fun java.lang.reflect.Method.isGenericInstantiation(): Boolean =
    genericParameterTypes.any { it is java.lang.reflect.TypeVariable<*> }

private fun String.toClassName(): String = removeSuffix("#").replace('/', '.')

/**
 * Methods whose simple display collides (for example `choose(Date)` from `java.util` and `java.sql`)
 * get the qualified parameter list as a label hint so the user can tell them apart.
 */
private fun List<CompletionItem>.withPackageHints(): List<CompletionItem> {
    val collisions = filter { it.data is MethodSignatureKey }
        .groupBy { it.label to it.detail }
        .values.filter { group -> group.map { it.data }.distinct().size > 1 }
    collisions.flatten().forEach { item ->
        val signature = (item.data as MethodSignatureKey).signature
        item.labelDetails = CompletionItemLabelDetails().apply { description = signature.replace(",", ", ") }
    }
    return this
}

private val INDEXED_MEMBER_KINDS = setOf(SymbolKind.METHOD, SymbolKind.FIELD, SymbolKind.PROPERTY)

/** A method suggestion with display parameters and an exact, qualified de-duplication identity. */
private data class MethodCandidate(
    val name: String,
    val returnType: String,
    val parameters: List<CandidateParameter>,
    val doc: String?,
)

/** [identity] is the qualified erased type; [display] is what the user sees and inserts. */
private data class CandidateParameter(val identity: String, val display: String)

/**
 * `this` and `super` name the class enclosing the cursor, or the script class at top level.
 * Returns null for any other qualifier or when the receiver cannot be determined.
 */
private fun explicitReceiverType(qualifierName: String?, module: ModuleNode, context: CompletionContext): String? {
    if (qualifierName != THIS && qualifierName != SUPER) return null
    val line = context.line + 1
    val column = context.character + 1
    val enclosing = module.classes.filter { it.contains(line, column) }
        .maxWithOrNull(compareBy<ClassNode>({ it.lineNumber }, { it.columnNumber }))
        ?: module.classes.firstOrNull { it.isScriptSafe() }
        ?: return null
    return if (qualifierName == THIS) enclosing.name else enclosing.superClass?.name
}

private const val THIS = "this"
private const val SUPER = "super"

private fun ASTNode.contains(line: Int, column: Int): Boolean {
    if (lineNumber <= 0 || lastLineNumber <= 0) return false
    val afterStart = line > lineNumber || (line == lineNumber && column >= columnNumber)
    val beforeEnd = line < lastLineNumber || (line == lastLineNumber && column <= lastColumnNumber)
    return afterStart && beforeEnd
}

/** A request-local member view; it never writes shared semantic metadata. */
private class LocalCompletionMembers(private val module: ModuleNode?) {
    private val classes = module?.classes.orEmpty().associateBy { it.name }

    // Request-local hierarchy facts of the held buffer, built only when a lookup needs them.
    private val facts by lazy { module?.let(ImplementationFacts::build) }

    data class Result(
        val methods: List<MethodCandidate>,
        val fields: List<MemberInfo>,
        val externalTypes: List<String>,
        /** The first class-chain ancestor outside the buffer; walked before external interfaces. */
        val externalSuperclass: String?,
        /** Held class names this walk covered. */
        val visited: Set<String>,
        /** Reflected `name` to erased parameters that the held chain overrides through generics. */
        val genericOverrides: Lazy<Set<Pair<String, List<String>>>>,
    )

    /** Members of held [type] and its held ancestors; [inherited] also hides [type]'s own private members. */
    fun lookup(type: String, inherited: Boolean = false): Result? {
        val first = heldClass(type) ?: return null
        val pending = ArrayDeque<ClassNode>().apply { add(first) }
        val visited = mutableSetOf<String>()
        val methods = linkedMapOf<String, MethodCandidate>()
        val fields = linkedMapOf<String, MemberInfo>()
        val external = linkedSetOf<String>()
        val heldNames = mutableSetOf<String>()
        var externalSuperclass: String? = null
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            val held = heldClass(next.name)
            if (!visited.add(held?.name ?: next.name)) continue
            if (held == null) {
                external.add(next.name)
            } else {
                heldNames += held.name
                val hidePrivate = inherited || held !== first
                ownMethods(held, hidePrivate).forEach { (identity, method) -> methods.putIfAbsent(identity, method) }
                ownFields(held, hidePrivate).forEach { (identity, field) -> fields.putIfAbsent(identity, field) }
                // The class chain precedes interface declarations, even at a greater depth.
                held.interfaces.forEach(pending::addLast)
                held.superClass?.let(pending::addFirst)
                val parent = held.superClass?.takeIf { !held.isInterface && heldClass(it.name) == null }
                if (externalSuperclass == null) externalSuperclass = parent?.name
            }
        }
        val owner = "${first.name.replace('.', '/')}#"
        return Result(
            methods.values.toList(),
            fields.values.toList(),
            external.toList(),
            externalSuperclass,
            heldNames,
            lazy { facts?.let { ImplementationMethodSignatures.genericOverrides(it, owner) }.orEmpty() },
        )
    }

    private fun ownMethods(node: ClassNode, inherited: Boolean): List<Pair<String, MethodCandidate>> =
        node.methods.filter { hasSource(it) && (!inherited || !it.isPrivate) }.map { method ->
            val parameters = method.parameters.map {
                CandidateParameter(typeName(it.type), "${it.displayType()} ${it.name}")
            }
            val identity = "${method.name}(${parameters.joinToString(",") { it.identity }})"
            identity to MethodCandidate(
                name = method.name,
                returnType = method.returnType.displayName(),
                parameters = parameters,
                doc = "Method: ${node.nameWithoutPackage}.$identity",
            )
        }

    private fun ownFields(node: ClassNode, inherited: Boolean): List<Pair<String, MemberInfo>> {
        val owner = node.name.replace('.', '/')
        val fields = node.fields.filter {
            hasSource(it) && (!inherited || !it.isPrivate)
        }.map {
            "field:${it.name}" to MemberInfo(
                it.name,
                SymbolKind.FIELD,
                SemanticType.Known(typeName(it.type)),
                null,
                "$owner#${it.name}.",
            )
        }
        val properties = node.properties.filter {
            hasSource(it) && (!inherited || !java.lang.reflect.Modifier.isPrivate(it.modifiers))
        }.map {
            "property:${it.name}" to MemberInfo(
                it.name,
                SymbolKind.PROPERTY,
                SemanticType.Known(typeName(it.type)),
                null,
                "$owner#${it.name}.",
            )
        }
        return fields + properties
    }

    private fun heldClass(type: String): ClassNode? =
        classes[type] ?: classes.values.singleOrNull { it.nameWithoutPackage == type }

    private fun typeName(type: ClassNode): String {
        var component = type
        var dimensions = 0
        while (component.isArray) {
            dimensions++
            component = component.componentType
        }
        return component.name + "[]".repeat(dimensions)
    }

    private fun hasSource(node: ASTNode): Boolean =
        node.lineNumber > 0 && node.columnNumber > 0 && node.lastLineNumber > 0 && node.lastColumnNumber > 0
}
