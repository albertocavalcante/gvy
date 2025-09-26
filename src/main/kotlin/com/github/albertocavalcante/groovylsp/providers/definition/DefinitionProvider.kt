package com.github.albertocavalcante.groovylsp.providers.definition

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.converters.LocationConverter
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Main provider for go-to-definition functionality.
 * Uses Flow pattern inspired by kotlin-lsp for clean async handling.
 */
class DefinitionProvider(private val compilationService: GroovyCompilationService) {

    private val logger = LoggerFactory.getLogger(DefinitionProvider::class.java)

    /**
     * Provide definitions for the symbol at the given position using Flow pattern.
     * Returns a Flow of Location objects.
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - currently serves as final fallback
    fun provideDefinitions(uri: String, position: Position): Flow<Location> = flow {
        logger.debug("Providing definitions for $uri at ${position.line}:${position.character}")

        val documentUri = try {
            URI.create(uri)
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid URI format: $uri", e)
            return@flow
        }

        // Get the AST for the document
        val ast = compilationService.getAst(documentUri)
        if (ast == null) {
            logger.warn("No AST available for $uri - this might indicate compilation service cache issue")
            return@flow
        }

        // Get the visitor and symbol table from compilation service
        // Note: We need to update GroovyCompilationService to provide these
        val visitor = compilationService.getAstVisitor(documentUri)
        if (visitor == null) {
            logger.warn("No AST visitor available for $uri - this might indicate visitor cache issue")
            return@flow
        }

        val symbolTable = compilationService.getSymbolTable(documentUri)
        if (symbolTable == null) {
            logger.warn("No symbol table available for $uri - this might indicate symbol table cache issue")
            return@flow
        }

        // Create resolver and find definition
        val resolver = DefinitionResolver(visitor, symbolTable)
        try {
            val definitionNode = resolver.findDefinitionAt(documentUri, position)

            if (definitionNode != null) {
                // Convert to Location and emit
                val location = LocationConverter.nodeToLocation(definitionNode, visitor)
                if (location != null) {
                    logger.debug(
                        "Found definition at ${location.uri}:${location.range} " +
                            "(node: ${definitionNode.javaClass.simpleName})",
                    )
                    emit(location)
                } else {
                    logger.debug("Could not convert definition node to location")
                }
            } else {
                logger.debug("No definition found at position")
            }
        } catch (e: GroovyLspException) {
            // Handle expected LSP exceptions by not emitting anything (empty result)
            logger.debug("Definition resolution failed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid arguments during definition resolution", e)
        } catch (e: IllegalStateException) {
            logger.warn("Invalid state during definition resolution", e)
        } catch (e: Exception) {
            logger.warn("Unexpected error during definition resolution", e)
        }
    }

    /**
     * Provide definitions as LocationLink objects for enhanced navigation.
     */
    @Suppress("TooGenericExceptionCaught") // TODO: Review if catch-all is needed - currently serves as final fallback
    fun provideDefinitionLinks(uri: String, position: Position): Flow<LocationLink> = flow {
        logger.debug("Providing definition links for $uri at ${position.line}:${position.character}")

        val documentUri = try {
            URI.create(uri)
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid URI format: $uri", e)
            return@flow
        }

        val ast = compilationService.getAst(documentUri) ?: return@flow
        val visitor = compilationService.getAstVisitor(documentUri) ?: return@flow
        val symbolTable = compilationService.getSymbolTable(documentUri) ?: return@flow

        val resolver = DefinitionResolver(visitor, symbolTable)

        // Find the origin node at the position
        val originNode = visitor.getNodeAt(documentUri, position.line, position.character)
        if (originNode == null) {
            logger.debug("No origin node found at position")
            return@flow
        }

        // Find the definition
        try {
            val definitionNode = resolver.findDefinitionAt(documentUri, position)
            if (definitionNode != null) {
                // Convert to LocationLink and emit
                val locationLink = LocationConverter.nodeToLocationLink(originNode, definitionNode, visitor)
                if (locationLink != null) {
                    logger.debug("Found definition link to ${locationLink.targetUri}:${locationLink.targetRange}")
                    emit(locationLink)
                } else {
                    logger.debug("Could not convert to LocationLink")
                }
            }
        } catch (e: GroovyLspException) {
            // Handle expected LSP exceptions by not emitting anything (empty result)
            logger.debug("Definition link resolution failed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid arguments during definition link resolution", e)
        } catch (e: IllegalStateException) {
            logger.warn("Invalid state during definition link resolution", e)
        } catch (e: Exception) {
            logger.warn("Unexpected error during definition link resolution", e)
        }
    }

    /**
     * Find all targets at position for the given target kinds.
     * Based on kotlin-lsp's pattern.
     */
    fun findTargetsAt(uri: String, position: Position, targetKinds: Set<TargetKind>): Flow<Location> = flow {
        logger.debug("Finding targets at $uri:${position.line}:${position.character} for kinds: $targetKinds")

        val documentUri = try {
            URI.create(uri)
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid URI format: $uri", e)
            return@flow
        }

        val ast = compilationService.getAst(documentUri) ?: return@flow
        val visitor = compilationService.getAstVisitor(documentUri) ?: return@flow
        val symbolTable = compilationService.getSymbolTable(documentUri) ?: return@flow

        val resolver = DefinitionResolver(visitor, symbolTable)
        val targets = resolver.findTargetsAt(documentUri, position, targetKinds)

        // Convert each target to Location and emit
        targets.forEach { targetNode ->
            val location = LocationConverter.nodeToLocation(targetNode, visitor)
            if (location != null) {
                emit(location)
            }
        }
    }

    /**
     * Convenience method for finding definitions only.
     */
    fun findDefinitionsOnly(uri: String, position: Position): Flow<Location> =
        findTargetsAt(uri, position, setOf(TargetKind.DECLARATION))

    /**
     * Convenience method for finding references only.
     */
    fun findReferencesOnly(uri: String, position: Position): Flow<Location> =
        findTargetsAt(uri, position, setOf(TargetKind.REFERENCE))
}
