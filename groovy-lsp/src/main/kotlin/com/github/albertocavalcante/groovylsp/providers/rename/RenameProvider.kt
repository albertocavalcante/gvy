package com.github.albertocavalcante.groovylsp.providers.rename

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovylsp.converters.toLspLocation
import com.github.albertocavalcante.groovylsp.providers.references.ReferenceProvider
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import kotlinx.coroutines.flow.toList
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Paths

/**
 * Provider for rename refactoring operations.
 * Supports renaming variables, methods, properties, and classes across the workspace.
 */
class RenameProvider(private val compilationService: GroovyCompilationService) {
    private val logger = LoggerFactory.getLogger(RenameProvider::class.java)
    private val referenceProvider = ReferenceProvider(compilationService)

    // Groovy keywords that cannot be used as identifiers
    private val groovyKeywords = setOf(
        "abstract", "as", "assert", "boolean", "break", "byte", "case", "catch",
        "char", "class", "const", "continue", "def", "default", "do", "double",
        "else", "enum", "extends", "final", "finally", "float", "for", "goto",
        "if", "implements", "import", "in", "instanceof", "int", "interface",
        "long", "native", "new", "null", "package", "private", "protected",
        "public", "return", "short", "static", "strictfp", "super", "switch",
        "synchronized", "this", "throw", "throws", "trait", "transient", "try",
        "void", "volatile", "while",
    )

    /**
     * Perform a rename operation at the given position.
     *
     * @param uri The URI of the document
     * @param position The position in the document
     * @param newName The new name for the symbol
     * @return WorkspaceEdit containing all required changes, or throws ResponseError
     */
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    suspend fun provideRename(uri: String, position: Position, newName: String): WorkspaceEdit {
        logger.debug("Rename requested for $uri at ${position.line}:${position.character} to '$newName'")

        // Validate the new name
        validateNewName(newName)

        val documentUri = URI.create(uri)
        val groovyPosition = position.toGroovyPosition()

        // Get the node at the position
        val visitor = compilationService.getAstVisitor(documentUri)
            ?: throw createError(ResponseErrorCode.InvalidRequest, "No AST visitor available for $uri")

        val symbolTable = compilationService.getSymbolTable(documentUri)
            ?: throw createError(ResponseErrorCode.InvalidRequest, "No symbol table available for $uri")

        val targetNode = visitor.getNodeAt(documentUri, groovyPosition)
            ?: throw createError(ResponseErrorCode.InvalidRequest, "No symbol found at position")

        // Resolve to definition
        val definition = targetNode.resolveToDefinition(visitor, symbolTable, strict = false)
            ?: throw createError(ResponseErrorCode.InvalidRequest, "Could not resolve symbol to definition")

        // Check if definition is in workspace or dependency
        val definitionLocation = definition.toLspLocation(visitor)
            ?: throw createError(ResponseErrorCode.InvalidRequest, "Could not determine definition location")

        if (!isInWorkspace(definitionLocation.uri)) {
            throw createError(
                ResponseErrorCode.InvalidRequest,
                "Cannot rename symbols defined in dependencies (read-only): ${definitionLocation.uri}",
            )
        }

        // Get symbol name
        val oldName = getSymbolName(definition)
            ?: throw createError(ResponseErrorCode.InvalidRequest, "Could not determine symbol name")

        if (oldName == newName) {
            throw createError(ResponseErrorCode.InvalidRequest, "New name is the same as the current name")
        }

        // Collect all references including declaration
        val locations = referenceProvider.provideReferences(uri, position, includeDeclaration = true).toList()

        if (locations.isEmpty()) {
            throw createError(ResponseErrorCode.InvalidRequest, "No references found for symbol")
        }

        // Check if any references are in dependencies
        val workspaceLocations = locations.filter { isInWorkspace(it.uri) }
        val dependencyLocations = locations.filter { !isInWorkspace(it.uri) }

        if (dependencyLocations.isNotEmpty()) {
            logger.warn("Found ${dependencyLocations.size} references in dependencies - these will not be renamed")
        }

        // Build workspace edit
        return buildWorkspaceEdit(
            definition,
            newName,
            workspaceLocations,
            definitionLocation,
        )
    }

    /**
     * Validate the new name for the symbol.
     */
    @Suppress("ThrowsCount")
    private fun validateNewName(newName: String) {
        when {
            newName.isBlank() -> throw createError(
                ResponseErrorCode.InvalidParams,
                "New name cannot be empty or blank",
            )
            newName in groovyKeywords -> throw createError(
                ResponseErrorCode.InvalidParams,
                "'$newName' is a Groovy keyword and cannot be used as an identifier",
            )
            !newName.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")) -> throw createError(
                ResponseErrorCode.InvalidParams,
                "'$newName' is not a valid identifier",
            )
        }
    }

    /**
     * Check if a URI is within the workspace (not a dependency).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun isInWorkspace(uriString: String): Boolean {
        val workspaceRoot = compilationService.workspaceManager.getWorkspaceRoot()
            ?: return false // No workspace, treat as not in workspace

        return try {
            val uri = URI.create(uriString)
            val path = Paths.get(uri)
            path.startsWith(workspaceRoot)
        } catch (e: Exception) {
            logger.warn("Could not parse URI: $uriString", e)
            false
        }
    }

    /**
     * Get the symbol name from an AST node.
     */
    private fun getSymbolName(node: ASTNode): String? = when (node) {
        is VariableExpression -> node.name
        is Parameter -> node.name
        is MethodNode -> node.name
        is FieldNode -> node.name
        is PropertyNode -> node.name
        is ClassNode -> node.nameWithoutPackage
        else -> null
    }

    /**
     * Build a WorkspaceEdit from the collected references.
     */
    private fun buildWorkspaceEdit(
        definition: ASTNode,
        newName: String,
        locations: List<Location>,
        definitionLocation: Location,
    ): WorkspaceEdit {
        val documentChanges = mutableListOf<Either<TextDocumentEdit, ResourceOperation>>()

        // Group locations by document URI
        val locationsByUri = locations.groupBy { it.uri }

        // Create text edits for each document
        locationsByUri.forEach { (uri, locs) ->
            val textEdits = locs.map { location ->
                TextEdit(location.range, newName)
            }

            val textDocumentEdit = TextDocumentEdit(
                VersionedTextDocumentIdentifier(uri, null),
                textEdits,
            )
            documentChanges.add(Either.forLeft(textDocumentEdit))
        }

        // If renaming a top-level class, add file rename operation
        if (definition is ClassNode && isTopLevelClass(definition, definitionLocation)) {
            val fileRename = createFileRenameOperation(definitionLocation.uri, newName)
            if (fileRename != null) {
                documentChanges.add(Either.forRight(fileRename))
            }
        }

        return WorkspaceEdit(documentChanges)
    }

    /**
     * Check if a ClassNode is a top-level class (not an inner class).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun isTopLevelClass(classNode: ClassNode, location: Location): Boolean {
        // A class is top-level if it's not an inner class
        val isInner = classNode.outerClass != null
        if (isInner) {
            logger.debug("Class ${classNode.name} is an inner class, not a top-level class")
            return false
        }

        // Also check if the file name matches the class name
        return try {
            val uri = URI.create(location.uri)
            val path = Paths.get(uri)
            val fileName = path.fileName.toString()
            val expectedFileName = "${classNode.nameWithoutPackage}.groovy"

            val matches = fileName.equals(expectedFileName, ignoreCase = true)
            if (!matches) {
                logger.debug(
                    "File name '$fileName' does not match class name '${classNode.nameWithoutPackage}', " +
                        "not treating as top-level",
                )
            }
            matches
        } catch (e: Exception) {
            logger.warn("Could not check file name for class", e)
            false
        }
    }

    /**
     * Create a file rename operation for a top-level class.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun createFileRenameOperation(oldUri: String, newName: String): RenameFile? = try {
        val uri = URI.create(oldUri)
        val path = Paths.get(uri)
        val parent = path.parent
        val newPath = parent.resolve("$newName.groovy")
        val newUri = newPath.toUri().toString()

        logger.debug("Creating file rename operation: $oldUri -> $newUri")
        RenameFile(oldUri, newUri)
    } catch (e: Exception) {
        logger.error("Could not create file rename operation", e)
        null
    }

    /**
     * Create and throw a ResponseErrorException for error handling.
     */
    private fun createError(code: ResponseErrorCode, message: String): Nothing =
        throw ResponseErrorException(ResponseError(code, message, null))
}
