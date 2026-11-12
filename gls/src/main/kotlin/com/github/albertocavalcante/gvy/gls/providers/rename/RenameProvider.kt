// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.converters.toGroovyPosition
import com.github.albertocavalcante.gvy.gls.converters.toLspLocation
import com.github.albertocavalcante.gvy.gls.providers.references.ReferenceProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.utils.ParameterOrigins
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.gls.utils.declaredName
import com.github.albertocavalcante.gvy.gls.utils.firstNameOccurrence
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameResult
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
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Provider for rename refactoring operations.
 * Renames variables, methods, properties, and classes from a captured document compilation.
 * Workspace-wide rename stays disabled until indexed symbol identities distinguish ambiguous bindings.
 */
class RenameProvider private constructor(
    private val compilationService: GroovyCompilationService,
    private val documentSource: RenameDocumentSource,
    private val openBuffers: () -> Map<URI, String> = { emptyMap() },
    private val caches: RenameCaches = RenameCaches(),
    private val scanLimits: ScanLimits = ScanLimits(),
    private val extraRoots: () -> List<Path> = { emptyList() },
) {
    /** Current text of a document (open buffer, else file on disk); null if unavailable. */
    constructor(
        compilationService: GroovyCompilationService,
        documentText: (URI) -> String? = ::readFromDisk,
    ) : this(
        compilationService,
        RenameDocumentSource {
            documentText(it)?.let { text -> DocumentSnapshot(text, null) }
        },
    )

    /**
     * [diskText] should outlive a request (providers are built per request) so unchanged files are not re-read;
     * [scanLimits] bounds the cross-file check.
     */
    internal constructor(
        compilationService: GroovyCompilationService,
        documents: DocumentProvider,
        diskText: WorkspaceTextCache = WorkspaceTextCache(),
        scanLimits: ScanLimits = ScanLimits(),
        extraRoots: () -> List<Path> = { emptyList() },
        walks: WorkspaceWalkCache = WorkspaceWalkCache(),
    ) : this(
        compilationService,
        RenameDocumentSource { uri ->
            documents.getSnapshot(uri) ?: readFromDisk(uri)?.let { DocumentSnapshot(it, null) }
        },
        documents::snapshot,
        RenameCaches(diskText, walks),
        scanLimits,
        extraRoots,
    )

    private val logger = KotlinLogging.logger {}
    private val referenceProvider = ReferenceProvider(compilationService)
    private val crossFileUses = CrossFileUseGuard(
        checkNotNull(compilationService.getWorkspaceSymbolIndex()),
        openBuffers,
        WorkspaceLayout(
            sources = compilationService.workspaceManager::getWorkspaceSources,
            roots = {
                (listOfNotNull(compilationService.workspaceManager.getWorkspaceRoot()) + extraRoots()).distinct()
            },
            sourceRoots = compilationService.workspaceManager::getSourceRoots,
        ),
        caches.walks,
        caches.diskText,
        scanLimits,
    )

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
    suspend fun provideRename(uri: String, position: Position, newName: String): WorkspaceEdit {
        logger.debug { "Rename requested for $uri at ${position.line}:${position.character} to '$newName'" }

        // Validate the new name
        validateNewName(newName)

        val sources = RenameSourceSnapshots(documentSource)
        val target = captureTarget(uri, position, sources)
        if (target.oldName == newName) {
            throw createError(ResponseErrorCode.InvalidRequest, "New name is the same as the current name")
        }
        val nameLocations = collectNameLocations(target, sources)
        refuseIfUsedElsewhere(target)
        sources.validate()
        return buildWorkspaceEdit(target.definition, newName, nameLocations, target.definitionLocation, sources)
    }

    /** Preparation proves eligibility against held source without constructing or applying edits. */
    suspend fun providePrepareRename(uri: String, position: Position): PrepareRenameResult? = try {
        val sources = RenameSourceSnapshots(documentSource)
        val target = captureTarget(uri, position, sources)
        val location = collectNameLocations(target, sources).firstOrNull {
            URI.create(it.uri) == target.documentUri && it.range.start <= position && position < it.range.end
        }
        if (location == null) {
            null
        } else {
            refuseIfUsedElsewhere(target)
            sources.validate()
            PrepareRenameResult(location.range, target.oldName)
        }
    } catch (e: RenameRefusedException) {
        throw e // Unlike other ineligible targets, the client should show why before asking for a name.
    } catch (e: ResponseErrorException) {
        when (e.responseError.code) {
            ResponseErrorCode.InvalidParams.value,
            ResponseErrorCode.InvalidRequest.value,
            ResponseErrorCode.ContentModified.value,
            -> null
            else -> throw e
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Only this file is edited, so a target other files use would be silently broken. */
    private suspend fun refuseIfUsedElsewhere(target: RenameTarget) {
        val uses = crossFileUses.usesOutside(target.documentUri, target.definition, target.oldName)
        if (!uses.isEmpty) {
            val error = ResponseError(ResponseErrorCode.RequestFailed, uses.message(target.oldName), null)
            throw RenameRefusedException(error)
        }
    }

    private data class RenameTarget(
        val documentUri: URI,
        val compiled: CompiledDocumentSnapshot,
        val parameterOrigins: ParameterOrigins,
        val definition: ASTNode,
        val definitionLocation: Location,
        val oldName: String,
    )

    @Suppress("ThrowsCount") // Each invalid protocol target reports a specific validation error.
    private suspend fun captureTarget(
        uri: String,
        position: Position,
        sources: RenameSourceSnapshots,
    ): RenameTarget {
        val documentUri = URI.create(uri)
        val original = sources.capture(documentUri)
        val groovyPosition = TextIndex(original.content).codePointPosition(position)?.toGroovyPosition()
            ?: throw createError(ResponseErrorCode.InvalidParams, "Rename position is outside the document")

        val compiled = requireSemanticCompilation(documentUri, original, sources)

        // Get the node at the position (now guaranteed to be in cache)
        val visitor = compiled.parseResult.astModel

        val targetNode = visitor.getNodeAt(documentUri, groovyPosition)
            ?: throw createError(ResponseErrorCode.InvalidRequest, "No symbol found at position")

        // Resolve to definition
        val parameterOrigins = ParameterOrigins(visitor, documentUri)
        val definition = resolveDefinition(targetNode, compiled, parameterOrigins)

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
        val oldName = definition.declaredName()
            ?: throw createError(ResponseErrorCode.InvalidRequest, "Could not determine symbol name")

        return RenameTarget(documentUri, compiled, parameterOrigins, definition, definitionLocation, oldName)
    }

    private suspend fun collectNameLocations(target: RenameTarget, sources: RenameSourceSnapshots): List<Location> {
        // Collect all references including declaration
        val locations = collectReferences(
            target.documentUri,
            target.definition,
            target.compiled,
            sources,
            target.parameterOrigins,
        )

        if (locations.isEmpty()) {
            throw createError(ResponseErrorCode.InvalidRequest, "No references found for symbol")
        }

        // Check if any references are in dependencies
        val workspaceLocations = locations.filter { isInWorkspace(it.uri) }
        val dependencyLocations = locations.filter { !isInWorkspace(it.uri) }

        if (dependencyLocations.isNotEmpty()) {
            logger.warn { "Found ${dependencyLocations.size} references in dependencies - these will not be renamed" }
        }

        // References carry whole AST node ranges (a declaration's range is the whole declaration,
        // and enclosing nodes can be reported too). Narrow each one to the old name so a rename can
        // never replace anything but the identifier. Unverifiable occurrences reject the whole edit.
        val nameLocations = narrowToName(workspaceLocations, target.oldName, target.definitionLocation, sources)
        if (nameLocations.isEmpty()) {
            throw createError(ResponseErrorCode.InvalidRequest, "No occurrences of '${target.oldName}' found to rename")
        }
        return nameLocations
    }

    private suspend fun requireSemanticCompilation(
        uri: URI,
        original: DocumentSnapshot,
        sources: RenameSourceSnapshots,
    ): CompiledDocumentSnapshot {
        // Await active compilation, then hold the exact source/result pair for this request.
        compilationService.ensureCompiled(uri)
            ?: throw createError(ResponseErrorCode.InvalidRequest, "Document not compiled: $uri")
        val compiled = compilationService.getCompiledSnapshot(uri) ?: sources.changed(uri)
        if (compiled.sourceText != original.content) sources.changed(uri)
        if (compiled.parseResult.outcome.achievedPhase < Phases.SEMANTIC_ANALYSIS) {
            throw createError(ResponseErrorCode.InvalidRequest, "Rename requires completed semantic analysis")
        }
        return compiled
    }

    private fun resolveDefinition(
        target: ASTNode,
        compiled: CompiledDocumentSnapshot,
        parameterOrigins: ParameterOrigins,
    ): ASTNode {
        val parsed = compiled.parseResult
        val resolved = target.resolveToDefinition(parsed.astModel, parsed.symbolTable, strict = false)
            ?: throw createError(ResponseErrorCode.InvalidRequest, "Could not resolve symbol to definition")
        return if (resolved is Parameter) {
            try {
                parameterOrigins.source(resolved)
            } catch (e: IllegalStateException) {
                logger.warn(e) { "Cannot prove rename parameter ownership" }
                throw createError(ResponseErrorCode.InvalidRequest, "Cannot prove parameter ownership")
            }
        } else {
            resolved
        }
    }

    private suspend fun collectReferences(
        uri: URI,
        definition: ASTNode,
        compiled: CompiledDocumentSnapshot,
        sources: RenameSourceSnapshots,
        parameterOrigins: ParameterOrigins,
    ): List<Location> = try {
        referenceProvider.provideRenameReferences(uri, definition, compiled, parameterOrigins).toList()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalStateException) {
        logger.warn(e) { "Cannot finish rename reference scan for $uri" }
        sources.changed(uri)
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
            logger.warn(e) { "Could not parse URI: $uriString" }
            false
        }
    }

    /**
     * Narrows [locations] to occurrences of [name]. The definition is narrowed to its name (the
     * first occurrence in the declaration). Any other location that contains another reported
     * location is an enclosing node, not a reference, and is dropped: narrowing it could land on a
     * different symbol with the same name. The rest are narrowed to their whole-word occurrence.
     */
    private fun narrowToName(
        locations: List<Location>,
        name: String,
        definition: Location,
        sources: RenameSourceSnapshots,
    ): List<Location> = locations.groupBy { it.uri }.flatMap { (uri, locs) ->
        val documentUri = URI.create(uri)
        val text = sources.capture(documentUri).content
        val index = TextIndex(text)
        locs.filter { loc ->
            loc.isSameAs(definition) ||
                locs.none { other -> other !== loc && loc.range.strictlyContains(other.range) }
        }.map { loc ->
            val range = firstNameOccurrence(index, loc.range, name) ?: sources.changed(documentUri)
            Location(uri, range)
        }
            .distinctBy { it.range.start.line to it.range.start.character }
    }

    private fun Location.isSameAs(other: Location) = uri == other.uri && range == other.range

    /**
     * Build a WorkspaceEdit from the collected references.
     */
    private fun buildWorkspaceEdit(
        definition: ASTNode,
        newName: String,
        locations: List<Location>,
        definitionLocation: Location,
        sources: RenameSourceSnapshots,
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
                VersionedTextDocumentIdentifier(uri, sources.capture(URI.create(uri)).version),
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
            logger.debug { "Class ${classNode.name} is an inner class, not a top-level class" }
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
                logger.debug {
                    "File name '$fileName' does not match class name '${classNode.nameWithoutPackage}', " +
                        "not treating as top-level"
                }
            }
            matches
        } catch (e: Exception) {
            logger.warn(e) { "Could not check file name for class" }
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

        logger.debug { "Creating file rename operation: $oldUri -> $newUri" }
        RenameFile(oldUri, newUri)
    } catch (e: Exception) {
        logger.error(e) { "Could not create file rename operation" }
        null
    }

    /**
     * Create and throw a ResponseErrorException for error handling.
     */
    private fun createError(code: ResponseErrorCode, message: String): Nothing =
        throw ResponseErrorException(ResponseError(code, message, null))
}

private fun readFromDisk(uri: URI): String? =
    if (uri.scheme == "file") runCatching { java.nio.file.Files.readString(Paths.get(uri)) }.getOrNull() else null

private operator fun Position.compareTo(other: Position): Int =
    compareValuesBy(this, other, Position::getLine, Position::getCharacter)

/** True if [inner] lies within this range and is not the same range. */
private fun org.eclipse.lsp4j.Range.strictlyContains(inner: org.eclipse.lsp4j.Range): Boolean =
    start <= inner.start && inner.end <= end && this != inner

/** State that outlives a request: providers are built per request, these are not. */
internal class RenameCaches(
    val diskText: WorkspaceTextCache = WorkspaceTextCache(),
    val walks: WorkspaceWalkCache = WorkspaceWalkCache(),
)

/** A rename refused because other files would break; surfaced even by prepareRename. */
internal class RenameRefusedException(error: ResponseError) : ResponseErrorException(error)
