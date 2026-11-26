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
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Provider for rename refactoring operations.
 * Renames variables, methods, properties, and classes from a captured document compilation.
 * Workspace-wide rename stays disabled until indexed symbol identities distinguish ambiguous bindings.
 */
class RenameProvider private constructor(
    private val compilationService: GroovyCompilationService,
    documentSource: RenameDocumentSource,
    caches: RenameCaches = RenameCaches(),
    scanLimits: ScanLimits = ScanLimits(),
    extraRoots: () -> List<Path> = { emptyList() },
    documents: DocumentProvider? = null,
) {
    /** Current text of a document (open buffer, else file on disk); null if unavailable. */
    constructor(
        compilationService: GroovyCompilationService,
        documentText: (URI) -> String? = ::readFromDisk,
    ) : this(
        compilationService,
        RenameDocumentSource { uri ->
            try {
                documentText(uri)?.let { text -> DocumentSnapshot(text, null) }
            } catch (_: IOException) {
                renameChanged(uri)
            } catch (_: SecurityException) {
                renameChanged(uri)
            }
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
        RenameCaches(diskText, walks),
        scanLimits,
        extraRoots,
        documents,
    )

    private val logger = KotlinLogging.logger {}
    private val referenceProvider = ReferenceProvider(compilationService)
    private val queries =
        RenameRequestQuery(compilationService, documentSource, caches, scanLimits, extraRoots, documents)

    internal fun capture(uri: String, position: Position): RenameRequest = queries.capture(uri, position)

    /**
     * Perform a rename operation at the given position.
     *
     * @param uri The URI of the document
     * @param position The position in the document
     * @param newName The new name for the symbol
     * @return WorkspaceEdit containing all required changes, or throws ResponseError
     */
    suspend fun provideRename(uri: String, position: Position, newName: String): WorkspaceEdit =
        provideRename(capture(uri, position), newName)

    internal suspend fun provideRename(request: RenameRequest, newName: String): WorkspaceEdit {
        if (!queries.inputsCurrent(request)) renameChanged(request.source.uri)
        validateNewName(newName)
        return queries.query(request) { compiled, sources ->
            requireSemanticPhase(request.source.uri, compiled)
            rename(captureTarget(request, compiled, sources), request.position, newName, sources)
        }
    }

    private suspend fun rename(
        target: RenameTarget,
        position: Position,
        newName: String,
        sources: RenameSourceSnapshots,
    ): WorkspaceEdit {
        if (!target.declaredHere) {
            refuseIfUsedElsewhere(target.documentUri, target.definition, target.oldName, false, sources)
        }
        if (target.oldName == newName) {
            throw createError(ResponseErrorCode.InvalidRequest, "New name is the same as the current name")
        }
        val nameLocations = collectNameLocations(target, sources)
        // From a constructor or a `new` call, rename starts only where preparation offers a name, so the two agree.
        if (ClassRenameConstructors.startsOnName(target.node) && target.nameAt(position, nameLocations) == null) {
            throw createError(ResponseErrorCode.InvalidRequest, "No name to rename at the cursor")
        }
        refuseIfUsedElsewhere(target.documentUri, target.definition, target.oldName, target.declaredHere, sources)
        return buildWorkspaceEdit(target.definition, newName, nameLocations, target.definitionLocation, sources)
    }

    /** Preparation proves eligibility against held source without constructing or applying edits. */
    suspend fun providePrepareRename(uri: String, position: Position): PrepareRenameResult? = try {
        providePrepareRename(capture(uri, position))
    } catch (refused: RenameRefusedException) {
        throw refused
    } catch (e: ResponseErrorException) {
        // The legacy text-supplier API represents a changed caller-owned source as an unavailable offer.
        when (e.responseError.code) {
            ResponseErrorCode.InvalidRequest.value,
            ResponseErrorCode.ContentModified.value,
            -> null
            else -> throw e
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    internal suspend fun providePrepareRename(request: RenameRequest): PrepareRenameResult? =
        queries.query(request) { compiled, sources ->
            requireSemanticPhase(request.source.uri, compiled)
            try {
                prepare(captureTarget(request, compiled, sources), request.position, sources)
            } catch (refused: RenameRefusedException) {
                throw refused
            } catch (e: ResponseErrorException) {
                when (e.responseError.code) {
                    ResponseErrorCode.InvalidParams.value,
                    ResponseErrorCode.InvalidRequest.value,
                    -> null
                    else -> throw e
                }
            }
        }

    private suspend fun prepare(
        target: RenameTarget,
        position: Position,
        sources: RenameSourceSnapshots,
    ): PrepareRenameResult? {
        if (!target.declaredHere) {
            refuseIfUsedElsewhere(target.documentUri, target.definition, target.oldName, false, sources)
        }
        val location = target.nameAt(position, collectNameLocations(target, sources))
        if (location == null) return null
        refuseIfUsedElsewhere(target.documentUri, target.definition, target.oldName, target.declaredHere, sources)
        return PrepareRenameResult(location.range, target.oldName)
    }

    /**
     * Only this file is edited, so a target other files use would be silently broken. A class this file does not
     * declare is declared in a file that spells its name, or in a dependency: the guard names the other file, and a
     * class it lets through is refused all the same. Such a class is checked before its uses here are collected.
     */
    private suspend fun refuseIfUsedElsewhere(
        uri: URI,
        definition: ASTNode,
        name: String,
        declaredHere: Boolean,
        sources: RenameSourceSnapshots,
    ) {
        val uses = sources.guard.usesOutside(uri, definition, name)
        val message = when {
            !uses.isEmpty -> uses.message(name)
            !declaredHere -> ClassRenameUses.declaredElsewhere(name)
            else -> return
        }
        throw RenameRefusedException(ResponseError(ResponseErrorCode.RequestFailed, message, null))
    }

    private data class RenameTarget(
        val documentUri: URI,
        val compiled: CompiledDocumentSnapshot,
        val parameterOrigins: ParameterOrigins,
        val node: ASTNode,
        val definition: ASTNode,
        val definitionLocation: Location,
        val oldName: String,
    ) {
        /** False for a class declared in another file or a dependency, whose rename this file cannot complete. */
        val declaredHere: Boolean
            get() = (definition as? ClassNode)?.let { ClassRenameUses.declaredIn(it, compiled) } ?: true

        fun nameAt(cursor: Position, locations: List<Location>): Location? =
            ClassRenameConstructors.nameAtCursor(node, documentUri, compiled.sourceText, locations, cursor)
    }

    @Suppress("ThrowsCount") // Each invalid protocol target reports a specific validation error.
    private suspend fun captureTarget(
        request: RenameRequest,
        compiled: CompiledDocumentSnapshot,
        sources: RenameSourceSnapshots,
    ): RenameTarget {
        val documentUri = request.source.uri
        val position = request.position
        val original = sources.capture(documentUri)
        val groovyPosition = TextIndex(original.content).codePointPosition(position)?.toGroovyPosition()
            ?: throw createError(ResponseErrorCode.InvalidParams, "Rename position is outside the document")

        val visitor = compiled.parseResult.astModel

        // On a class name a type use spells (a parameter type, a type argument), the rename is the class's.
        val atCursor = ClassRenameUses.classAt(compiled, TextIndex(original.content).offsetOf(position))
        when (atCursor) {
            is ClassAtCursor.Refused ->
                throw RenameRefusedException(ResponseError(ResponseErrorCode.RequestFailed, atCursor.reason, null))
            // Always refused: as a dependency when no source declares it, else by the guard when another file
            // spells the name, else as declared elsewhere.
            is ClassAtCursor.Foreign -> {
                ClassRenameUses.dependencyName(atCursor.type)?.let(ClassRenameUses::refuseDependency)
                val name = atCursor.type.declaredName().orEmpty()
                refuseIfUsedElsewhere(documentUri, atCursor.type, name, false, sources)
            }
            is ClassAtCursor.Declared, null -> Unit
        }
        val targetNode = (atCursor as? ClassAtCursor.Declared)?.type
            ?: visitor.getNodeAt(documentUri, groovyPosition)
            ?: throw createError(ResponseErrorCode.InvalidRequest, "No symbol found at position")

        // Resolve to definition
        val parameterOrigins = ParameterOrigins(visitor, documentUri)
        val definition = resolveDefinition(targetNode, compiled, parameterOrigins)
        if (definition is ClassNode && definition.isGenericsPlaceHolder) {
            // A type parameter redirects to its bound; renaming it must never rename the bound.
            val reason = "Cannot rename '${definition.unresolvedName}': renaming a type parameter is not supported yet"
            throw RenameRefusedException(ResponseError(ResponseErrorCode.RequestFailed, reason, null))
        }

        // Check if definition is in workspace or dependency
        val definitionLocation = definition.toLspLocation(visitor)
            ?: ClassRenameUses.dependencyName(definition)?.let(ClassRenameUses::refuseDependency)
            ?: throw createError(ResponseErrorCode.InvalidRequest, "Could not determine definition location")

        if (!isInWorkspace(definitionLocation.uri, sources)) {
            ClassRenameUses.refuseDependency(definitionLocation.uri)
        }

        // Get symbol name
        val oldName = definition.declaredName()
            ?: throw createError(ResponseErrorCode.InvalidRequest, "Could not determine symbol name")

        return RenameTarget(
            documentUri,
            compiled,
            parameterOrigins,
            node = targetNode,
            definition = definition,
            definitionLocation = definitionLocation,
            oldName = oldName,
        )
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
        val workspaceLocations = locations.filter { isInWorkspace(it.uri, sources) }
        val dependencyLocations = locations.filter { !isInWorkspace(it.uri, sources) }

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
        val constructorNames = ClassRenameConstructors.nameLocations(
            target.definition,
            target.compiled,
            target.definitionLocation,
            target.documentUri,
            target.oldName,
        )
        val located = (nameLocations + constructorNames).distinctBy {
            Triple(it.uri, it.range.start.line, it.range.start.character)
        }
        val type = (target.definition as? ClassNode)?.redirect()
        return if (type != null && target.declaredHere) {
            ClassRenameUses.covering(type, target.compiled, target.documentUri, located, target.oldName)
        } else {
            located
        }
    }

    private fun resolveDefinition(
        target: ASTNode,
        compiled: CompiledDocumentSnapshot,
        parameterOrigins: ParameterOrigins,
    ): ASTNode {
        val parsed = compiled.parseResult
        val resolved = target.resolveToDefinition(parsed.astModel, parsed.symbolTable, strict = false)
            ?.let(ClassRenameConstructors::renamedDeclaration)
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
     * Check if a URI is editable: inside any workspace folder (not a dependency). Without any folder
     * (single-file mode) only the document being renamed and other open buffers qualify.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun isInWorkspace(uriString: String, sources: RenameSourceSnapshots): Boolean {
        return try {
            val uri = URI.create(uriString)
            sources.request.authority.contains(
                uri,
                uri == sources.request.source.uri || sources.request.buffers?.documents?.containsKey(uri) == true,
            )
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
                VersionedTextDocumentIdentifier(uri, sources.capture(URI.create(uri)).version?.value),
                textEdits,
            )
            documentChanges.add(Either.forLeft(textDocumentEdit))
        }

        // If renaming a top-level class, add file rename operation
        if (definition is ClassNode && isTopLevelClass(definition, definitionLocation)) {
            val fileRename = createFileRenameOperation(definitionLocation.uri, newName)
            if (fileRename != null &&
                !sources.request.authority.contains(URI.create(fileRename.newUri), sources.roots.isEmpty())
            ) {
                sources.changed(URI.create(definitionLocation.uri))
            }
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
}

// Groovy keywords that cannot be used as identifiers
private val groovyKeywords = setOf(
    "abstract", "as", "assert", "boolean", "break", "byte", "case", "catch",
    "char", "class", "const", "continue", "def", "default", "do", "double",
    "else", "enum", "extends", "final", "finally", "float", "for", "goto",
    "if", "implements", "import", "in", "instanceof", "int", "interface",
    "long", "native", "new", "null", "package", "private", "protected",
    "public", "return", "short", "static", "strictfp", "super", "switch",
    "synchronized", "this", "throw", "throws", "trait", "transient", "try",
    "void", "volatile", "while", "true", "false",
)

private fun validateNewName(newName: String) {
    val error = when {
        newName.isBlank() -> "New name cannot be empty or blank"
        newName in groovyKeywords -> "'$newName' is a Groovy keyword and cannot be used as an identifier"
        !isJavaIdentifier(newName) -> "'$newName' is not a valid identifier"
        else -> return
    }
    createError(ResponseErrorCode.InvalidParams, error)
}

private fun isJavaIdentifier(name: String): Boolean {
    val first = name.codePointAt(0)
    return Character.isJavaIdentifierStart(first) &&
        name.codePoints().skip(1).allMatch {
            Character.isJavaIdentifierPart(it) && !Character.isIdentifierIgnorable(it)
        }
}

private fun Location.isSameAs(other: Location) = uri == other.uri && range == other.range

private fun requireSemanticPhase(uri: URI, compiled: CompiledDocumentSnapshot) {
    if (compiled.parseResult.ast == null) {
        createError(ResponseErrorCode.InvalidRequest, "Document not compiled: $uri")
    }
    if (compiled.parseResult.outcome.achievedPhase < Phases.SEMANTIC_ANALYSIS) {
        createError(ResponseErrorCode.InvalidRequest, "Rename requires completed semantic analysis")
    }
}

private fun createError(code: ResponseErrorCode, message: String): Nothing =
    throw ResponseErrorException(ResponseError(code, message, null))

private fun readFromDisk(uri: URI): String? = try {
    if (uri.scheme == "file") Files.readString(Paths.get(uri)) else null
} catch (_: NoSuchFileException) {
    null
}

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
