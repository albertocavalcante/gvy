package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.converters.toGroovyPosition
import com.github.albertocavalcante.gvy.gls.converters.toLspLocation
import com.github.albertocavalcante.gvy.gls.errors.GroovyLspException
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import io.github.oshai.kotlinlogging.KotlinLogging
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
import java.net.URI

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
            val context = createReferenceContext(uri, position.toGroovyPosition())
            if (context != null) {
                val definition = resolveTargetDefinition(context)
                if (definition != null) {
                    logger.debug { "Found definition via AST: ${definition.javaClass.simpleName}" }
                    findReferences(
                        definition,
                        context.visitor,
                        context.symbolTable,
                        includeDeclaration,
                        emittedLocations,
                    )
                }
            }

            // Second, search workspace-wide using SemanticDB
            workspaceSymbolIndex?.let { index ->
                findWorkspaceReferences(uri, position, includeDeclaration, index, emittedLocations)
            }
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
    ) {
        try {
            // Get SemanticDocument for current file
            val doc = index.getDocument(URI.create(uri))
            if (doc == null) {
                logger.debug { "No semantic document found for $uri" }
                return
            }

            // Find occurrence at cursor position (LSP uses 0-indexed positions)
            val occurrence = doc.occurrences.find { occ ->
                occ.range.contains(position.line, position.character)
            }

            if (occurrence == null) {
                logger.debug { "No occurrence found at ${position.line}:${position.character}" }
                return
            }

            logger.debug { "Found occurrence for symbol ${occurrence.symbol} with role ${occurrence.role}" }

            // Find all references across workspace
            val locations = index.findReferences(occurrence.symbol)
            logger.debug { "Found ${locations.size} workspace references for ${occurrence.symbol}" }

            locations.forEach { location ->
                // Filter out definitions if includeDeclaration is false
                if (!includeDeclaration) {
                    // Check if this location is a definition
                    val targetDoc = index.getDocument(URI.create(location.uri))
                    val targetOccurrence = targetDoc?.occurrences?.find { occ ->
                        occ.symbol == occurrence.symbol &&
                            occ.range.startLine == location.range.start.line &&
                            occ.range.startColumn == location.range.start.character
                    }

                    if (targetOccurrence?.role == OccurrenceRole.DEFINITION) {
                        logger.debug {
                            "Skipping definition occurrence at ${location.uri}:${location.range.start.line}"
                        }
                        return@forEach
                    }
                }

                // Emit with deduplication
                val key = "${location.uri}:${location.range.start.line}:${location.range.start.character}"
                if (emittedLocations.add(key)) {
                    send(location)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error finding workspace references" }
        }
    }

    /**
     * Context for reference resolution.
     */
    private data class ReferenceContext(
        val documentUri: URI,
        val visitor: GroovyAstModel,
        val symbolTable: com.github.albertocavalcante.groovyparser.ast.SymbolTable,
        val targetNode: ASTNode,
    )

    /**
     * Parameters for processing a node reference.
     */
    private data class ProcessNodeParams(
        val node: ASTNode,
        val definition: ASTNode,
        val visitor: GroovyAstModel,
        val symbolTable: com.github.albertocavalcante.groovyparser.ast.SymbolTable,
        val includeDeclaration: Boolean,
        val emittedLocations: MutableSet<String>,
    )

    /**
     * Create reference context from URI and position.
     */
    private fun createReferenceContext(
        uri: String,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
    ): ReferenceContext? =
        createReferenceContextInternal(uri, position) ?: logAndReturnNull("No referenceable node found at position")

    private fun createReferenceContextInternal(
        uri: String,
        position: com.github.albertocavalcante.groovyparser.ast.types.Position,
    ): ReferenceContext? {
        val documentUri = URI.create(uri)
        return compilationService.getAstModel(documentUri)?.let { visitor ->
            compilationService.getSymbolTable(documentUri)?.let { symbolTable ->
                visitor.getNodeAt(documentUri, position)
                    ?.takeIf { it.isReferenceableSymbol() }
                    ?.let { ReferenceContext(documentUri, visitor, symbolTable, it) }
            }
        }
    }

    /**
     * Helper to log debug message and return null.
     */
    private fun logAndReturnNull(message: String): ReferenceContext? {
        logger.debug { message }
        return null
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
        return adjustedDefinition
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
        emittedLocations: MutableSet<String>,
    ) {
        visitor.getAllNodes()
            .filter { it.hasValidPosition() }
            .forEach { node ->
                val params = ProcessNodeParams(
                    node = node,
                    definition = definition,
                    visitor = visitor,
                    symbolTable = symbolTable,
                    includeDeclaration = includeDeclaration,
                    emittedLocations = emittedLocations,
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
            // Robust fallback for Parameters
            is Parameter -> {
                if (params.definition is Parameter) {
                    areParametersEqual(nodeDefinition, params.definition)
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

        val isPartOfDeclaration = params.node.isPartOfDeclaration(params.visitor)
        logger.debug {
            "Node ${params.node.javaClass.simpleName} at ${params.node.lineNumber}:${params.node.columnNumber} - " +
                "isPartOfDeclaration: $isPartOfDeclaration, includeDeclaration: ${params.includeDeclaration}"
        }

        if (params.includeDeclaration || !isPartOfDeclaration) {
            emitUniqueLocation(params.node, params.visitor, params.emittedLocations)
        }
    }

    /**
     * Check if a node is part of a declaration.
     */
    private fun ASTNode.isPartOfDeclaration(visitor: GroovyAstModel): Boolean = when (this) {
        is Parameter -> true
        is MethodNode -> true
        is FieldNode -> true
        is PropertyNode -> true
        is ClassNode -> true
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
        return referenceableTypes.contains(this::class)
    }

    /**
     * Emit a location for this node if it hasn't been seen before.
     */
    private suspend fun ProducerScope<Location>.emitUniqueLocation(
        node: ASTNode,
        visitor: GroovyAstModel,
        seen: MutableSet<String>,
    ) {
        val location = node.toLspLocation(visitor) ?: return
        val key = "${location.uri}:${location.range.start.line}:${location.range.start.character}"
        if (seen.add(key)) {
            send(location)
        }
    }

    /**
     * Compare two Parameters for equality.
     * Needed because Groovy AST nodes don't implement value equality for Parameter.
     */
    private fun areParametersEqual(p1: Parameter, p2: Parameter): Boolean {
        // Simple but effective: name and type name must match
        // We could also check location if available, but generated parameters might not have it
        if (p1.name != p2.name) return false

        val t1 = p1.type?.name
        val t2 = p2.type?.name

        if (t1 == t2) return true

        // Handle fully qualified vs simple name mismatch (e.g. String vs java.lang.String)
        val s1 = t1?.substringAfterLast('.')
        val s2 = t2?.substringAfterLast('.')
        return s1 == s2
    }
}
