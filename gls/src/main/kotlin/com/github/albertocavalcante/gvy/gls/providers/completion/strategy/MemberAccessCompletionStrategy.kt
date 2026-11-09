package com.github.albertocavalcante.gvy.gls.providers.completion.strategy

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.dsl.completion.CompletionsBuilder
import com.github.albertocavalcante.gvy.gls.dsl.completion.completions
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider.ContextType
import com.github.albertocavalcante.gvy.gls.providers.completion.JenkinsCompletionProvider
import com.github.albertocavalcante.gvy.gls.providers.completion.TextImportInfo
import com.github.albertocavalcante.gvy.gls.providers.completion.TextImportParser
import com.github.albertocavalcante.gvy.gls.services.ReflectedMethod
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedGlobalVariable
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.SemanticTypeFormatter
import com.github.albertocavalcante.gvy.semantics.db.ImplementationMethodSignatures
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.native.DeclarationWalker
import com.github.albertocavalcante.gvy.semantics.workspace.MemberInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.stmt.BlockStatement

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

        val items = completions {
            handleMemberAccessContext(
                memberAccessContext,
                context,
                context.jenkinsMetadata,
            )
        }

        return CompletionStrategy.found(items)
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
        val rawType = completionContext.qualifierType.substringBefore('<')
        val qualifierName = completionContext.qualifierName

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
            return
        }

        // Held declarations are authoritative for this buffer, including unsaved changes.
        val local = LocalCompletionMembers(ctx.baseContext.moduleNode).lookup(rawType)
        local?.let { addWorkspaceMembers(it.members, qualifiedParameters = true) }

        // Query shared metadata only for types not represented by the held local module.
        workspaceSymbolIndex?.let { index ->
            val resolvedFqns = local?.externalTypes ?: resolveWorkspaceClassFqns(rawType, ctx)
            addIndexedMembers(resolvedFqns, index, qualifiedParameters = local != null)
        }

        // Strategy 3: GDK methods
        logger.debug { "Adding GDK methods for $rawType" }
        addGdkMethods(rawType, compilationService, qualifiedParameters = local != null)

        // Strategy 4: Classpath methods
        logger.debug { "Adding Classpath methods for $rawType" }
        if (local == null) addClasspathMethods(rawType, compilationService)
        local?.externalTypes?.forEach { addClasspathMethods(it, compilationService, qualifiedParameters = true) }
    }

    private fun CompletionsBuilder.addIndexedMembers(
        classNames: List<String>,
        index: WorkspaceSymbolIndex,
        qualifiedParameters: Boolean,
    ) {
        // Symbols and qualified facts must come from the same captured publication.
        val documents = if (qualifiedParameters) index.implementationSnapshot.values else emptyList()
        classNames.forEach { name ->
            val owner = "${name.replace('.', '/')}#"
            val document = documents.singleOrNull { it.document.findSymbol(owner) != null }
            val members = document?.document?.symbols?.filter {
                it.owner == owner && it.kind in setOf(SymbolKind.METHOD, SymbolKind.FIELD, SymbolKind.PROPERTY)
            }?.map { MemberInfo(it.name, it.kind, it.type, it.symbol, it.symbol) }
                ?: index.getAllMembers(name.replace('.', '/'), includeInherited = true)
            val exact = document?.let { ImplementationMethodSignatures.parameterTypesBySymbol(it, owner) }.orEmpty()
            addWorkspaceMembers(members.distinctBy { it.symbolId }, exactParameters = exact)
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
        } ?: parseTextImportInfo(content)

        importInfo.packageName?.takeIf { it.isNotBlank() }?.let { candidates.add("$it.$simpleName") }
        importInfo.explicitImports
            .filter { it.substringAfterLast('.') == simpleName }
            .forEach { candidates.add(it) }
        importInfo.starImports.forEach { candidates.add("$it.$simpleName") }

        findWorkspaceClassFqnsBySimpleName(simpleName, compilationService)
            .forEach { candidates.add(it) }

        return candidates.toList()
    }

    /**
     * Scans workspace symbols for class matches by simple name.
     * This is a fallback for completion and may be costly without caching.
     */
    private fun findWorkspaceClassFqnsBySimpleName(
        simpleName: String,
        compilationService: GroovyCompilationService,
    ): List<String> {
        // TODO(#861): Cache workspace symbol lookups for completion.
        //   See: https://github.com/albertocavalcante/gvy/issues/861
        val matches = linkedSetOf<String>()
        compilationService.getAllSymbolStorages().forEach { (uri, index) ->
            index.getSymbols(uri)
                .filterIsInstance<Symbol.Class>()
                .filter { it.name == simpleName }
                .forEach { matches.add(it.fullyQualifiedName) }
        }
        return matches.toList()
    }

    /**
     * Best-effort, line-based import parsing for fallback scenarios.
     * Supports simple multi-line imports but does not handle full Groovy syntax.
     */
    private fun parseTextImportInfo(content: String): TextImportInfo = TextImportParser.parse(content)

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
     * Adds Jenkins global variable property completions.
     */
    private fun CompletionsBuilder.addJenkinsGlobalVariablePropertyCompletions(globalVar: MergedGlobalVariable) {
        with(JenkinsCompletionProvider) {
            addJenkinsPropertyCompletions(globalVar)
        }
    }

    /**
     * Adds workspace members (fields, methods, properties) to completions.
     *
     * @param members List of member information from WorkspaceSymbolIndex
     */
    private fun CompletionsBuilder.addWorkspaceMembers(
        members: List<MemberInfo>,
        qualifiedParameters: Boolean = false,
        exactParameters: Map<String, List<List<String>>> = emptyMap(),
    ) {
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

                SymbolKind.METHOD -> addWorkspaceMethod(member, qualifiedParameters, exactParameters[member.symbolId])

                else -> {
                    /* Skip constructors and other kinds */
                }
            }
        }
    }

    private fun CompletionsBuilder.addWorkspaceMethod(
        member: MemberInfo,
        qualifiedParameters: Boolean,
        exactParameters: List<List<String>>?,
    ) {
        val alternatives = exactParameters ?: listOf(member.signature.completionParameters(qualifiedParameters))
        alternatives.forEach { parameters ->
            method(
                name = member.name,
                returnType = member.type?.let { formatType(it) } ?: "def",
                parameters = parameters,
                doc = "Method: ${member.name}${member.signature ?: "()"}",
            )
        }
    }

    /**
     * Add GDK (Groovy Development Kit) method completions for a given type.
     */
    private fun CompletionsBuilder.addGdkMethods(
        className: String,
        compilationService: GroovyCompilationService,
        qualifiedParameters: Boolean,
    ) {
        val resolvedTypes = resolveDataTypes(className, compilationService)
        if (resolvedTypes.isEmpty()) {
            // Try original name as fallback
            addGdkMethodsForSingleType(className, compilationService, this, qualifiedParameters)
        } else {
            resolvedTypes.forEach { type ->
                addGdkMethodsForSingleType(type, compilationService, this, qualifiedParameters)
            }
        }
    }

    private fun addGdkMethodsForSingleType(
        className: String,
        compilationService: GroovyCompilationService,
        builder: CompletionsBuilder,
        qualifiedParameters: Boolean,
    ) {
        val gdkMethods = if (qualifiedParameters) {
            compilationService.gdkProvider.getQualifiedMethodsForType(className)
        } else {
            compilationService.gdkProvider.getMethodsForType(className)
        }

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
        qualifiedParameters: Boolean = false,
    ) {
        val resolvedTypes = resolveDataTypes(className, compilationService)
        if (resolvedTypes.isEmpty()) {
            addClasspathMethodsForSingleType(className, compilationService, this, qualifiedParameters)
        } else {
            resolvedTypes.forEach { type ->
                addClasspathMethodsForSingleType(type, compilationService, this, qualifiedParameters)
            }
        }
    }

    private fun addClasspathMethodsForSingleType(
        className: String,
        compilationService: GroovyCompilationService,
        builder: CompletionsBuilder,
        qualifiedParameters: Boolean,
    ) {
        val classpathMethods = if (qualifiedParameters) {
            qualifiedClasspathMethods(className, compilationService)
        } else {
            compilationService.classpathService.getMethods(className)
        }

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

    private fun qualifiedClasspathMethods(
        className: String,
        compilationService: GroovyCompilationService,
    ): List<ReflectedMethod> {
        val type = compilationService.classpathService.loadClass(className) ?: return emptyList()
        return try {
            type.methods.map { method ->
                ReflectedMethod(
                    method.name,
                    method.returnType.simpleName,
                    method.parameterTypes.map { it.typeName },
                    method.parameters.map { it.name },
                    java.lang.reflect.Modifier.isStatic(method.modifiers),
                    java.lang.reflect.Modifier.isPublic(method.modifiers),
                    "JDK/Classpath method from ${type.simpleName}",
                    method.declaringClass.name,
                )
            }
        } catch (error: NoClassDefFoundError) {
            logger.debug(error) { "Incomplete classpath methods for $className" }
            emptyList()
        } catch (error: SecurityException) {
            logger.debug(error) { "Cannot reflect completion methods for $className" }
            emptyList()
        } catch (error: java.lang.reflect.MalformedParametersException) {
            logger.debug(error) { "Cannot read completion parameter metadata for $className" }
            emptyList()
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
private fun String?.completionParameters(qualifiedParameters: Boolean): List<String> {
    val signature = this ?: return emptyList()

    val startIndex = signature.indexOf('(')
    val endIndex = signature.indexOf(')')

    if (startIndex < 0 || endIndex < 0 || startIndex >= endIndex) {
        return emptyList()
    }

    val params = signature.substring(startIndex + 1, endIndex).trim()

    // Split by comma while respecting angle brackets for generics
    return parseParametersWithGenerics(params, qualifiedParameters)
}

/**
 * Parse comma-separated parameters while respecting angle brackets for generics.
 *
 * This helper extracts the complex bracket-tracking logic from completionParameters.
 */
private fun parseParametersWithGenerics(params: String, qualifiedParameters: Boolean): List<String> {
    val result = mutableListOf<String>()
    val currentParam = StringBuilder()
    var bracketDepth = 0
    var invalidBrackets = false

    fun addCurrentParam() {
        result.add(completionParameterType(currentParam.toString(), qualifiedParameters))
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
            .map { completionParameterType(it, qualifiedParameters) }
            .filter { it.isNotEmpty() }
    }

    // Add the last parameter
    if (currentParam.isNotEmpty()) {
        addCurrentParam()
    }

    return result
}

private fun completionParameterType(type: String, qualified: Boolean): String {
    val trimmed = type.trim()
    return if (qualified) trimmed else trimmed.substringAfterLast('/').substringAfterLast('.')
}

/** A request-local member view; it never writes shared semantic metadata. */
private class LocalCompletionMembers(module: ModuleNode?) {
    private val classes = module?.classes.orEmpty().associateBy { it.name }

    data class Result(val members: List<MemberInfo>, val externalTypes: List<String>)

    fun lookup(type: String): Result? {
        val first = heldClass(type) ?: return null
        val pending = ArrayDeque<ClassNode>().apply { add(first) }
        val visited = mutableSetOf<String>()
        val members = linkedMapOf<String, MemberInfo>()
        val external = linkedSetOf<String>()
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            val held = heldClass(next.name)
            if (!visited.add(held?.name ?: next.name)) continue
            if (held == null) {
                external.add(next.name)
            } else {
                ownMembers(held, held !== first).forEach { (identity, member) ->
                    members.putIfAbsent(identity, member)
                }
                // The class chain precedes interface declarations, even at a greater depth.
                held.interfaces.forEach(pending::addLast)
                held.superClass?.let(pending::addFirst)
            }
        }
        return Result(members.values.toList(), external.toList())
    }

    private fun ownMembers(node: ClassNode, inherited: Boolean): List<Pair<String, MemberInfo>> {
        val owner = node.name.replace('.', '/')
        val methods = node.methods.filter { hasSource(it) && (!inherited || !it.isPrivate) }.map { method ->
            val identity = methodIdentity(method)
            identity to MemberInfo(
                method.name,
                SymbolKind.METHOD,
                SemanticType.Known(typeName(method.returnType)),
                "$owner#$identity.",
                "$owner#$identity.",
            )
        }
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
        return methods + fields + properties
    }

    private fun methodIdentity(method: MethodNode): String =
        "${method.name}(${method.parameters.joinToString(",") { typeName(it.type) }})"

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
