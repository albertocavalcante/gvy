package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.converters.toGroovyPosition
import com.github.albertocavalcante.gvy.gls.converters.toLspLocation
import com.github.albertocavalcante.gvy.gls.converters.toLspRange
import com.github.albertocavalcante.gvy.gls.errors.GroovyLspException
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.utils.ParameterOrigins
import com.github.albertocavalcante.gvy.gls.utils.ParameterSourceRanges
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.gls.utils.declaredName
import com.github.albertocavalcante.gvy.gls.utils.firstNameOccurrence
import com.github.albertocavalcante.gvy.gls.utils.rethrowIfCancellationOrError
import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Provider for finding references to symbols in Groovy code.
 *
 * Supports both same-file (AST-based) and workspace-wide (SemanticDB-based) reference finding.
 * When a [WorkspaceSymbolIndex] is provided, searches across all files in the workspace.
 * Falls back to same-file AST analysis when workspace index is unavailable.
 *
 * @property compilationService Service for accessing compiled AST and symbol tables
 * @property workspaceSymbolIndex Optional workspace-wide symbol index for cross-file references
 */
class ReferenceProvider(
    private val compilationService: GroovyCompilationService,
    private val workspaceSymbolIndex: WorkspaceSymbolIndex? = null,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Find all references to the symbol at the given position.
     *
     * Searches both same-file (using AST) and workspace-wide (using SemanticDB) if available.
     * Results are deduplicated to avoid returning the same location twice.
     *
     * @param uri The URI of the document
     * @param position The position in the document
     * @param includeDeclaration Whether to include the declaration in the results
     * @return Flow of locations where the symbol is referenced
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - currently serves as final fallback
    fun provideReferences(uri: String, position: Position, includeDeclaration: Boolean): Flow<Location> = channelFlow {
        logger.debug { "Finding references for $uri at ${position.line}:${position.character}" }

        val emittedLocations = mutableSetOf<String>()

        try {
            // First, try same-file AST-based reference finding
            val context = createReferenceContext(uri, position)
            var symbolName: String? = null
            var isLocal = false
            if (context != null) {
                val definition = resolveTargetDefinition(context)
                symbolName = definition?.declaredName()
                // Locals and parameters never cross files, and the index gives every local of a
                // class one symbol id (shared with a same-named field), so it must not be searched.
                isLocal = definition is VariableExpression || definition is Parameter
                if (definition != null) {
                    logger.debug { "Found definition via AST: ${definition.javaClass.simpleName}" }
                    findReferences(
                        definition,
                        context.visitor,
                        context.symbolTable,
                        includeDeclaration,
                        ReferenceScanState(
                            emittedLocations,
                            capturedSources = mapOf(context.documentUri to context.source),
                            parameterOrigins = context.parameterOrigins,
                            typeAliases = context.compiled.parseResult.ast?.imports.orEmpty()
                                .filter {
                                    definition is ClassNode &&
                                        it.type?.redirect()?.name == definition.redirect().name
                                }
                                .mapNotNullTo(mutableSetOf()) { it.alias },
                        ),
                    )
                }
            }

            // Second, search workspace-wide using SemanticDB
            workspaceSymbolIndex?.takeUnless { isLocal }?.let { index ->
                findWorkspaceReferences(uri, position, includeDeclaration, index, emittedLocations, symbolName)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GroovyLspException) {
            logger.error(e) { "LSP error finding references" }
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Invalid arguments for finding references" }
        } catch (e: IllegalStateException) {
            logger.error(e) { "Invalid state while finding references" }
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error finding references" }
        }
    }

    /** Rename must reuse its definition's parse result and propagate scan failures without partial edits. */
    internal fun provideRenameReferences(
        uri: URI,
        definition: ASTNode,
        compiled: CompiledDocumentSnapshot,
        parameterOrigins: ParameterOrigins,
    ): Flow<Location> = channelFlow {
        val source = TextIndex(compiled.sourceText)
        if (definition is ClassNode) {
            val module = checkNotNull(compiled.parseResult.ast) { "No captured type import bindings for rename" }
            check(!module.hasExplicitTypeAlias(definition, source)) {
                "Cannot safely rename through an explicit import alias"
            }
        }
        findReferences(
            definition,
            compiled.parseResult.astModel,
            compiled.parseResult.symbolTable,
            includeDeclaration = true,
            state = ReferenceScanState(
                mutableSetOf(),
                capturedSources = mapOf(uri to source),
                parameterOrigins = parameterOrigins,
                strict = true,
            ),
        )
    }

    /**
     * Find references across the workspace using the SemanticDB index.
     *
     * @param uri The document URI
     * @param position The cursor position
     * @param includeDeclaration Whether to include declaration occurrences
     * @param index The workspace symbol index
     * @param emittedLocations Set of already emitted location keys for deduplication
     */
    private suspend fun ProducerScope<Location>.findWorkspaceReferences(
        uri: String,
        position: Position,
        includeDeclaration: Boolean,
        index: WorkspaceSymbolIndex,
        emittedLocations: MutableSet<String>,
        symbolName: String?,
    ) {
        try {
            // Get SemanticDocument for current file
            val origin = index.getDocumentSnapshot(URI.create(uri))
            val originText = origin?.sourceText?.let(::TextIndex)
            val cursor = originText?.codePointPosition(position)
            if (origin == null || cursor == null) {
                logger.debug { "No semantic document found for $uri" }
                return
            }

            // The innermost occurrence at the cursor: a method's occurrence lies inside its class's,
            // and taking the first match returned the class (LSP uses 0-indexed positions).
            val occurrence = origin.document.occurrences
                .filter { occ -> occ.range.contains(cursor.line, cursor.character) }
                .minWithOrNull(
                    compareBy(
                        { it.range.endLine - it.range.startLine },
                        { it.range.span() },
                        { if (it.role == OccurrenceRole.IMPORT) 1 else 0 },
                    ),
                )

            if (occurrence == null) {
                logger.debug { "No occurrence found at ${position.line}:${position.character}" }
                return
            }

            logger.debug { "Found occurrence for symbol ${occurrence.symbol} with role ${occurrence.role}" }

            val symbol = index.findSymbol(occurrence.symbol)
            val name = symbolName ?: symbol?.name
            if (symbol?.kind == SymbolKind.VARIABLE || symbol?.kind == SymbolKind.PARAMETER || name == null) {
                logger.debug { "Not searching the workspace for local or unnamed ${occurrence.symbol}" }
                return
            }
            index.findReferenceDocuments(occurrence.symbol).forEach { (referenceUri, snapshot) ->
                snapshot.referenceLocations(
                    referenceUri,
                    occurrence.symbol,
                    name,
                    includeDeclaration,
                ).forEach { named ->
                    val key = "${named.uri}:${named.range.start.line}:${named.range.start.character}"
                    if (emittedLocations.add(key)) send(named)
                }
            }
        } catch (e: Exception) {
            rethrowIfCancellationOrError(e)
            logger.error(e) { "Error finding workspace references" }
        }
    }

    /**
     * Context for reference resolution.
     */
    private data class ReferenceContext(
        val documentUri: URI,
        val compiled: CompiledDocumentSnapshot,
        val source: TextIndex,
        val targetNode: ASTNode,
    ) {
        val visitor get() = compiled.parseResult.astModel
        val symbolTable get() = compiled.parseResult.symbolTable
        val parameterOrigins = ParameterOrigins(visitor, documentUri)
    }

    /**
     * Parameters for processing a node reference.
     */
    private data class ProcessNodeParams(
        val node: ASTNode,
        val definition: ASTNode,
        val visitor: GroovyAstModel,
        val symbolTable: com.github.albertocavalcante.groovyparser.ast.SymbolTable,
        val includeDeclaration: Boolean,
        val state: ReferenceScanState,
    )

    private class ReferenceScanState(
        val emittedLocations: MutableSet<String>,
        val capturedSources: Map<URI, TextIndex>,
        val parameterOrigins: ParameterOrigins,
        val strict: Boolean = false,
        val typeAliases: Set<String> = emptySet(),
    ) {
        val parameterRanges = HashMap<URI, ParameterSourceRanges>()
        val classDeclarations: MutableSet<ClassNode> = Collections.newSetFromMap(IdentityHashMap())

        fun typeReferenceNames(node: ASTNode, name: String, visitor: GroovyAstModel): Set<String>? {
            val typeUse = node is ConstructorCallExpression || node is ClassExpression ||
                node is ClassNode && node !in classDeclarations && visitor.getParent(node) !is ImportNode
            if (!typeUse) return null
            return if (strict) setOf(name) else typeAliases + name
        }
    }

    /**
     * Create reference context from URI and position.
     */
    private fun createReferenceContext(uri: String, position: Position): ReferenceContext? {
        val documentUri = URI.create(uri)
        val compiled = compilationService.getCompiledSnapshot(documentUri) ?: return null
        val source = TextIndex(compiled.sourceText)
        val groovyPosition = source.codePointPosition(position)?.toGroovyPosition() ?: return null
        return compiled.parseResult.astModel.getNodeAt(documentUri, groovyPosition)
            ?.takeIf { it.isReferenceableSymbol() }
            ?.let { ReferenceContext(documentUri, compiled, source, it) }
    }

    /**
     * Resolve the target node to its definition.
     */
    private fun resolveTargetDefinition(context: ReferenceContext): ASTNode? {
        // INSIGHT from fork-groovy-language-server: Get definition directly from the target node
        // For VariableExpression, this should return accessedVariable which unifies all references
        val definition = context.targetNode.resolveToDefinition(context.visitor, context.symbolTable, strict = false)

        // CRITICAL FIX: If position finder returned ClassNode but we have a VariableExpression nearby,
        // we might need to find the actual VariableExpression we're looking for
        val adjustedDefinition = when {
            // If we get a ClassNode but the context suggests we're looking for a variable
            context.targetNode is ClassNode &&
                definition is ClassNode -> {
                // Try to find a VariableExpression at the same position that might be the real target
                val targetLineNumber = context.targetNode.lineNumber
                val targetColumnNumber = context.targetNode.columnNumber

                if (targetLineNumber > 0 && targetColumnNumber > 0) {
                    // Look for VariableExpression nodes at or near this position
                    val allNodes = context.visitor.getAllNodes()
                    val variableAtPosition = allNodes
                        .filterIsInstance<VariableExpression>()
                        .find { varNode ->
                            varNode.lineNumber > 0 && varNode.columnNumber > 0 &&
                                varNode.lineNumber == targetLineNumber &&
                                kotlin.math.abs(varNode.columnNumber - targetColumnNumber) <= 2
                        }

                    // If we found a VariableExpression, use its definition instead
                    variableAtPosition?.resolveToDefinition(
                        context.visitor,
                        context.symbolTable,
                        strict = false,
                    ) ?: definition
                } else {
                    definition
                }
            }

            else -> definition
        }

        if (adjustedDefinition == null) {
            logger.debug { "Could not resolve definition for node" }
            return null
        }
        return if (adjustedDefinition is Parameter) {
            context.sourceParameterOrIdentity(adjustedDefinition)
        } else {
            adjustedDefinition
        }
    }

    /** Public reads can follow a compiler-owned binding even when no source declaration can be proved. */
    private fun ReferenceContext.sourceParameterOrIdentity(parameter: Parameter): Parameter = try {
        parameterOrigins.source(parameter)
    } catch (e: IllegalStateException) {
        rethrowIfCancellationOrError(e)
        parameter
    }

    /**
     * Find all references to the given definition node using AST analysis.
     *
     * @param definition The definition node to find references for
     * @param visitor The AST model
     * @param symbolTable The symbol table
     * @param includeDeclaration Whether to include the declaration
     * @param emittedLocations Set of already emitted location keys for deduplication
     */
    private suspend fun ProducerScope<Location>.findReferences(
        definition: ASTNode,
        visitor: GroovyAstModel,
        symbolTable: com.github.albertocavalcante.groovyparser.ast.SymbolTable,
        includeDeclaration: Boolean,
        state: ReferenceScanState,
    ) {
        state.classDeclarations.addAll(visitor.getAllClassNodes())
        visitor.getAllNodes()
            .filter { it.hasValidPosition() }
            .forEach { node ->
                val params = ProcessNodeParams(
                    node = node,
                    definition = definition,
                    visitor = visitor,
                    symbolTable = symbolTable,
                    includeDeclaration = includeDeclaration,
                    state = state,
                )
                processNode(params)
            }
    }

    /**
     * Process a single node to check if it references our target definition.
     *
     * CRITICAL GROOVY AST INSIGHT: Groovy creates different VariableExpression objects for each
     * reference to the same variable, but they all share the same `accessedVariable` property
     * that points to the original declaration. This means we cannot use simple object identity
     * comparison (===) for VariableExpressions - we must compare their accessedVariable instead.
     *
     * Example: For code "def x = 1; x + 2; x * 3"
     * - Declaration VariableExpression: accessedVariable points to itself
     * - First reference: accessedVariable points to declaration
     * - Second reference: accessedVariable points to declaration
     * All three are different objects but share the same accessedVariable reference!
     */
    private suspend fun ProducerScope<Location>.processNode(params: ProcessNodeParams) {
        val nodeDefinition = params.node.resolveToDefinition(params.visitor, params.symbolTable, strict = false)

        // Simple and robust matching using .equals() like fork-groovy-language-server
        // INSIGHT: By returning accessedVariable as definition for VariableExpression,
        // all references naturally resolve to the same definition, making comparison simple

        val isMatchingDefinition = when (nodeDefinition) {
            null -> false
            params.definition -> true
            // A held parse preserves declaration identity; name/type equality cannot establish lexical ownership.
            is Parameter -> {
                if (params.definition is Parameter) {
                    params.state.parametersMatch(nodeDefinition, params.definition)
                } else {
                    false
                }
            }

            else -> false
        }

        if (!isMatchingDefinition) return

        // CRITICAL FIX: Don't emit DeclarationExpressions as references - only their inner VariableExpression
        // This prevents double-counting when we have both "def localVar" (DeclarationExpression) and
        // "localVar" (VariableExpression) matching the same definition
        if (params.node is DeclarationExpression) {
            return // Skip DeclarationExpression - we'll emit its VariableExpression separately
        }

        val isPartOfDeclaration = params.node.isPartOfDeclaration(params.visitor, params.state)
        logger.debug {
            "Node ${params.node.javaClass.simpleName} at ${params.node.lineNumber}:${params.node.columnNumber} - " +
                "isPartOfDeclaration: $isPartOfDeclaration, includeDeclaration: ${params.includeDeclaration}"
        }

        if (params.includeDeclaration || !isPartOfDeclaration) {
            emitUniqueLocation(
                params.node,
                params.definition.declaredName(),
                params.visitor,
                params.state,
            )
        }
    }

    private fun ReferenceScanState.parametersMatch(candidate: Parameter, target: Parameter): Boolean = try {
        parameterOrigins.matches(candidate, target)
    } catch (e: IllegalStateException) {
        rethrowIfCancellationOrError(e)
        if (strict) throw e
        false
    }

    /**
     * Check if a node is part of a declaration.
     */
    private fun ASTNode.isPartOfDeclaration(visitor: GroovyAstModel, state: ReferenceScanState): Boolean = when (this) {
        is Parameter -> true
        is MethodNode -> true
        is FieldNode -> true
        is PropertyNode -> true
        is ClassNode -> this in state.classDeclarations
        is VariableExpression -> {
            val parent = visitor.getParent(this)
            val isDecl = parent is DeclarationExpression &&
                parent.leftExpression == this
            if (isDecl) {
                logger.debug { "Found variable ${this.name} as part of declaration" }
            }
            isDecl
        }

        else -> false
    }

    /**
     * Check if this node has valid position information for LSP.
     */
    private fun ASTNode.hasValidPosition(): Boolean = lineNumber > 0 && columnNumber > 0

    /**
     * Check if this node represents a referenceable symbol.
     * Groups related node types to reduce cyclomatic complexity.
     */
    private fun ASTNode.isReferenceableSymbol(): Boolean {
        val referenceableTypes = setOf(
            // Variable-related nodes
            VariableExpression::class,
            DeclarationExpression::class,
            Parameter::class,
            Variable::class,
            // Method and field nodes
            MethodCallExpression::class,
            MethodNode::class,
            FieldNode::class,
            PropertyNode::class,
            PropertyExpression::class,
            // Class-related nodes
            ClassNode::class,
            ClassExpression::class,
            ConstructorCallExpression::class,
            // Import nodes
            ImportNode::class,
        )
        // Nested classes are InnerClassNode, a ClassNode subclass the exact-class set would miss.
        return referenceableTypes.contains(this::class) || this is ClassNode
    }

    /**
     * Emit a location for this node if it hasn't been seen before.
     */
    private suspend fun ProducerScope<Location>.emitUniqueLocation(
        node: ASTNode,
        name: String?,
        visitor: GroovyAstModel,
        state: ReferenceScanState,
    ) {
        val location = node.nameLocation(name, visitor, state) ?: return
        val key = "${location.uri}:${location.range.start.line}:${location.range.start.character}"
        if (state.emittedLocations.add(key)) {
            send(location)
        }
    }

    /**
     * The location of the identifier [name] in this node, not the whole node: a method declaration
     * would otherwise cover its body, and a call its receiver and arguments. Call and property names
     * have their own AST nodes; elsewhere the name is found in the compiled source text. Null when
     * the node does not contain the name, so such a node is never reported.
     */
    private fun ASTNode.nameLocation(name: String?, visitor: GroovyAstModel, state: ReferenceScanState): Location? {
        val nodeLocation = toLspLocation(visitor)
        check(nodeLocation != null || !state.strict) { "Missing rename reference location" }
        if (nodeLocation == null) return null
        if (name == null) return nodeLocation
        val searchIn = referenceRange(nodeLocation, visitor, state)
        val index = state.capturedSources[URI.create(nodeLocation.uri)]
        check(index != null || !state.strict) { "No captured rename source for ${nodeLocation.uri}" }
        return index?.let { captured ->
            state.sourceRange(searchIn, captured)?.let { sourceRange ->
                val typeNames = state.typeReferenceNames(this, name, visitor)
                val range = if (typeNames != null) {
                    typeReferenceRange(captured, sourceRange, typeNames)
                } else {
                    identifierRange(name, captured, sourceRange, state, URI.create(nodeLocation.uri))
                }
                check(range != null || !state.strict) { "Missing rename identifier in captured source" }
                range?.let { Location(nodeLocation.uri, it) }
            }
        }
    }

    private fun ASTNode.referenceRange(
        nodeLocation: Location,
        visitor: GroovyAstModel,
        state: ReferenceScanState,
    ): Range {
        val nameNode = when (this) {
            is ConstructorCallExpression -> return type.toLspRange() ?: nodeLocation.range
            is ClassExpression -> return type.toLspRange() ?: nodeLocation.range
            is MethodCallExpression -> method
            is PropertyExpression -> property
            else -> null
        }
        val namedLocation = nameNode?.toLspLocation(visitor)
        check(nameNode == null || namedLocation != null || !state.strict) { "Missing rename member location" }
        return namedLocation?.range ?: nodeLocation.range
    }

    private fun ReferenceScanState.sourceRange(range: Range, index: TextIndex): Range? {
        val converted = index.utf16Range(range)
        check(converted != null || !strict) { "Invalid rename source range" }
        return converted
    }

    private fun ASTNode.identifierRange(
        name: String,
        index: TextIndex,
        searchIn: Range,
        state: ReferenceScanState,
        uri: URI,
    ): Range? {
        if (this !is Parameter) return firstNameOccurrence(index, searchIn, name)
        val ranges = state.parameterRanges.getOrPut(uri) { ParameterSourceRanges(index.text.lines()) }
        val span = ranges.name(this) ?: return null
        return Range(Position(span.line, span.start), Position(span.line, span.start + span.length))
    }
}

/** Column span on a single line; a large value for multi-line ranges, which sort after it. */
private fun com.github.albertocavalcante.gvy.semantics.db.Range.span(): Int =
    if (startLine == endLine) endColumn - startColumn else Int.MAX_VALUE
