package com.github.albertocavalcante.groovylsp.providers.rename

import com.github.albertocavalcante.groovylsp.ast.AstVisitor
import com.github.albertocavalcante.groovylsp.ast.SymbolTable
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.LocationConverter
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import com.github.albertocavalcante.groovylsp.providers.references.ReferenceProvider
import kotlinx.coroutines.flow.toList
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Provider for rename/refactor functionality in Groovy code.
 * Handles renaming of variables, methods, classes, and properties with proper
 * Groovy-specific behavior including getter/setter updates.
 */
class RenameProvider(
    private val compilationService: GroovyCompilationService,
    private val referenceProvider: ReferenceProvider,
) {
    private val logger = LoggerFactory.getLogger(RenameProvider::class.java)

    /**
     * Prepare for rename by validating that the symbol at the given position can be renamed.
     * Returns the range of the symbol if it can be renamed, null otherwise.
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - serves as final fallback
    suspend fun prepareRename(uri: String, position: Position): Range? {
        logger.debug("Preparing rename for $uri at ${position.line}:${position.character}")

        var result: Range? = null
        try {
            val documentUri = URI.create(uri)
            val astVisitor = compilationService.getAstVisitor(documentUri)
            val symbolTable = compilationService.getSymbolTable(documentUri)

            when {
                astVisitor == null || symbolTable == null -> {
                    logger.debug("No AST visitor or symbol table available for $uri")
                }
                else -> {
                    val node = astVisitor.getNodeAt(documentUri, position.line, position.character)
                    when {
                        node == null -> {
                            logger.debug("No node found at position")
                        }
                        !isNodeRenameable(node) -> {
                            logger.debug("Node is not renameable: ${node.javaClass.simpleName}")
                        }
                        else -> {
                            val location = LocationConverter.nodeToLocation(node, astVisitor)
                            result = location?.range
                        }
                    }
                }
            }
        } catch (e: GroovyLspException) {
            logger.debug("LSP error preparing rename: ${e.message}")
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid arguments for preparing rename", e)
        } catch (e: Exception) {
            logger.warn("Unexpected error preparing rename", e)
        }
        return result
    }

    /**
     * Perform rename operation by finding all references and creating appropriate edits.
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - serves as final fallback
    suspend fun rename(uri: String, position: Position, newName: String): WorkspaceEdit? {
        logger.debug("Renaming symbol at $uri:${position.line}:${position.character} to '$newName'")

        var result: WorkspaceEdit? = null
        try {
            val documentUri = URI.create(uri)
            result = performRenameOperation(documentUri, position, newName, uri)
        } catch (e: GroovyLspException) {
            logger.error("LSP error during rename: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid arguments for rename", e)
        } catch (e: Exception) {
            logger.error("Unexpected error during rename", e)
        }
        return result
    }

    /**
     * Performs the core rename operation logic.
     */
    private suspend fun performRenameOperation(
        documentUri: URI,
        position: Position,
        newName: String,
        uri: String,
    ): WorkspaceEdit? {
        val astVisitor = compilationService.getAstVisitor(documentUri)
        val symbolTable = compilationService.getSymbolTable(documentUri)

        // Early validation checks
        val validationError = validateRenameOperation(astVisitor, symbolTable, documentUri, position, newName)
        if (validationError != null) {
            logger.debug(validationError)
            return null
        }

        val node = astVisitor!!.getNodeAt(documentUri, position.line, position.character)!!
        val references = referenceProvider.provideReferences(uri, position, includeDeclaration = true).toList()

        val result = createWorkspaceEdit(node, newName, references, astVisitor)
        logger.debug("Created workspace edit with ${result?.changes?.values?.sumOf { it.size } ?: 0} changes")
        return result
    }

    private suspend fun validateRenameOperation(
        astVisitor: AstVisitor?,
        symbolTable: SymbolTable?,
        documentUri: URI,
        position: Position,
        newName: String,
    ): String? {
        if (astVisitor == null || symbolTable == null) {
            return "No AST visitor or symbol table available for $documentUri"
        }

        val node = astVisitor.getNodeAt(documentUri, position.line, position.character)
        if (node == null || !isNodeRenameable(node)) {
            return "No renameable node found at position"
        }

        if (!isValidIdentifier(newName)) {
            return "Invalid identifier name: '$newName'"
        }

        val references = referenceProvider.provideReferences(documentUri.toString(), position, includeDeclaration = true).toList()
        if (references.isEmpty()) {
            return "No references found for symbol"
        }

        return null // All validations passed
    }

    /**
     * Check if a node can be renamed.
     */
    private fun isNodeRenameable(node: ASTNode): Boolean = when (node) {
        is VariableExpression -> true
        is MethodCallExpression -> true
        is PropertyExpression -> true
        is MethodNode -> true
        is FieldNode -> true
        is PropertyNode -> true
        is ClassNode -> !node.isPrimaryClassNode() // Don't rename built-in classes
        else -> false
    }

    /**
     * Validate that a string is a valid Groovy identifier.
     */
    private fun isValidIdentifier(name: String): Boolean {
        if (name.isEmpty()) return false
        if (!name.first().isJavaIdentifierStart()) return false
        return name.all { it.isJavaIdentifierPart() }
    }

    /**
     * Create workspace edit for the rename operation.
     */
    private suspend fun createWorkspaceEdit(
        node: ASTNode,
        newName: String,
        references: List<Location>,
        astVisitor: com.github.albertocavalcante.groovylsp.ast.AstVisitor,
    ): WorkspaceEdit {
        val changes = mutableMapOf<String, MutableList<TextEdit>>()

        // Add basic text edits for all references
        references.forEach { location ->
            val edits = changes.getOrPut(location.uri) { mutableListOf() }
            edits.add(TextEdit(location.range, newName))
        }

        // Handle special cases based on node type
        when (node) {
            is PropertyNode -> {
                // For properties, also rename getters and setters if they exist
                addPropertyGetterSetterEdits(node, newName, changes, astVisitor)
            }
            is FieldNode -> {
                // For fields that might have implicit getters/setters
                addFieldGetterSetterEdits(node, newName, changes, astVisitor)
            }
            is ClassNode -> {
                // For class renames, we might need to update import statements in the future
                // This is a complex operation that would require workspace-wide analysis
                logger.debug("Class rename - import updates not yet implemented")
            }
        }

        return WorkspaceEdit(changes)
    }

    /**
     * Add edits for property getter/setter methods when renaming a property.
     */
    private fun addPropertyGetterSetterEdits(
        propertyNode: PropertyNode,
        newName: String,
        changes: MutableMap<String, MutableList<TextEdit>>,
        astVisitor: com.github.albertocavalcante.groovylsp.ast.AstVisitor,
    ) {
        val capitalizedNewName = newName.replaceFirstChar { it.uppercase() }
        val capitalizedOldName = propertyNode.name.replaceFirstChar { it.uppercase() }

        // Find getter method (getPropertyName)
        val getterName = "get$capitalizedOldName"
        val newGetterName = "get$capitalizedNewName"
        findAndRenameMethod(getterName, newGetterName, changes, astVisitor)

        // Find setter method (setPropertyName)
        val setterName = "set$capitalizedOldName"
        val newSetterName = "set$capitalizedNewName"
        findAndRenameMethod(setterName, newSetterName, changes, astVisitor)

        // Handle boolean properties (isPropertyName)
        if (propertyNode.type.name == "boolean" || propertyNode.type.name == "java.lang.Boolean") {
            val booleanGetterName = "is$capitalizedOldName"
            val newBooleanGetterName = "is$capitalizedNewName"
            findAndRenameMethod(booleanGetterName, newBooleanGetterName, changes, astVisitor)
        }
    }

    /**
     * Add edits for field getter/setter methods when renaming a field.
     */
    private fun addFieldGetterSetterEdits(
        fieldNode: FieldNode,
        newName: String,
        changes: MutableMap<String, MutableList<TextEdit>>,
        astVisitor: com.github.albertocavalcante.groovylsp.ast.AstVisitor,
    ) {
        // Only handle getter/setter for fields that might have them
        // This is a simplified approach - in reality, we'd need to check if they actually exist
        if (fieldNode.isPrivate || fieldNode.isProtected) {
            addPropertyGetterSetterEdits(
                // Create a temporary PropertyNode to reuse the logic
                PropertyNode(fieldNode.name, fieldNode.modifiers, fieldNode.type, fieldNode.owner, null, null, null),
                newName,
                changes,
                astVisitor,
            )
        }
    }

    /**
     * Find and add rename edits for a specific method name.
     */
    private fun findAndRenameMethod(
        oldMethodName: String,
        newMethodName: String,
        changes: MutableMap<String, MutableList<TextEdit>>,
        astVisitor: com.github.albertocavalcante.groovylsp.ast.AstVisitor,
    ) {
        // Find all method nodes with the old name
        astVisitor.getAllNodes()
            .filterIsInstance<MethodNode>()
            .filter { it.name == oldMethodName }
            .forEach { methodNode ->
                val location = LocationConverter.nodeToLocation(methodNode, astVisitor)
                if (location != null) {
                    val edits = changes.getOrPut(location.uri) { mutableListOf() }
                    edits.add(TextEdit(location.range, newMethodName))
                }
            }

        // Find all method call expressions with the old name
        astVisitor.getAllNodes()
            .filterIsInstance<MethodCallExpression>()
            .filter { it.methodAsString == oldMethodName }
            .forEach { callExpr ->
                val location = LocationConverter.nodeToLocation(callExpr.method, astVisitor)
                if (location != null) {
                    val edits = changes.getOrPut(location.uri) { mutableListOf() }
                    edits.add(TextEdit(location.range, newMethodName))
                }
            }
    }
}
