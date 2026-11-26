// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.services.Formatter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Main provider for code actions (quick fixes) in Groovy files.
 * Supports:
 * - Missing import resolution (unambiguous only)
 * - Formatting actions
 * - Removal of unused imports reported by gvy, and Organize Imports
 * - Deterministic lint fixes from CodeNarc
 */
class CodeActionProvider(
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
    formatter: Formatter,
    private val supportsAnalysisRemedies: () -> Boolean = { false },
) {
    private val logger = KotlinLogging.logger {}

    // Reuse action provider instances to reduce object allocation
    private val formattingAction = FormattingAction(formatter)
    private val importAction = ImportAction(compilationService)
    private val unusedImportFixAction = UnusedImportFixAction(compilationService)
    private val organizeImportsAction = OrganizeImportsAction(compilationService)
    private val verdictMemory = SpockVerdictMemory()

    /** Drops the remembered Spock verdicts of a closed document (#1387). */
    fun forgetDocument(uri: URI) = verdictMemory.forget(uri.toString())

    /**
     * The Groovy and Java files of [packageName] across every workspace source root the compile uses, plus the
     * source's own directory (its file need not sit where its `package` says), on disk or open, as one sorted
     * string; null when a directory cannot be listed. The package comes from the AST, not the directory, so
     * nested source roots cannot misplace it.
     */
    private fun packageFileSet(path: String, packageName: String): String? {
        val directory = try {
            Path.of(path).parent?.let(::real)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val packagePath = packageName.split('.').filter(String::isNotEmpty)
        val roots = compilationService.workspaceManager.getSourceRoots().map(::real)
        val directories = (
            roots.map { root -> packagePath.fold(root) { dir, part -> dir.resolve(part) } } +
                listOf(directory)
            )
            .map { it.normalize() }
            .distinct()
        val onDisk = directories.flatMap(::listSources)
        val open = documentProvider.getAllUris()
            .mapNotNull { uri ->
                try {
                    real(Path.of(uri))
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            .filter { it.parent in directories && isSourceFile(it) }
        return (onDisk + open).map(Path::toString).toSortedSet().joinToString(",")
    }

    /** A missing package directory is empty; unexpected filesystem errors reach the query boundary. */
    private fun listSources(directory: Path): List<Path> = try {
        Files.list(directory).use { entries ->
            entries.filter {
                isSourceFile(it) && Files.readAttributes(it, BasicFileAttributes::class.java).isRegularFile
            }
                .map(::real).toList()
        }
    } catch (_: NoSuchFileException) {
        emptyList()
    } catch (_: NotDirectoryException) {
        emptyList()
    }

    private fun real(path: Path): Path = try {
        path.toRealPath()
    } catch (_: NoSuchFileException) {
        path.toAbsolutePath().normalize()
    }

    private fun isSourceFile(path: Path): Boolean =
        path.fileName.toString().let { it.endsWith(".groovy") || it.endsWith(".java") }

    /** The open text or a bounded disk read; missing and oversized files cannot prove a verdict. */
    private fun currentSourceText(path: String): String? {
        val file = try {
            Path.of(path)
        } catch (_: IllegalArgumentException) {
            return null
        }
        documentProvider.getSnapshot(file.toUri())?.let { return it.content }
        return try {
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            if (!attributes.isRegularFile || attributes.size() > MAX_SOURCE_BYTES) return null
            val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
            if (bytes.size >
                MAX_SOURCE_BYTES
            ) {
                null
            } else {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            }
        } catch (_: NoSuchFileException) {
            null
        }
    }
    private val lintFixAction = LintFixAction()

    /**
     * Provides code actions for the given context.
     * Only returns deterministic, safe actions; ambiguous or risky fixes are declined.
     */
    suspend fun provideCodeActions(params: CodeActionParams): List<CodeAction> {
        val uri = URI.create(params.textDocument.uri)
        logger.debug { "Providing code actions for ${params.textDocument.uri}" }

        // Get document content
        val snapshot = documentProvider.getSnapshot(uri) ?: run {
            logger.debug { "Document not found in provider: $uri" }
            return emptyList()
        }

        val compiled = compilationService.getCompiledSnapshot(uri)
        return generateActions(params, snapshot, compiled, supportsAnalysisRemedies())
    }

    internal fun capture(params: CodeActionParams) =
        CodeActionRequest(params, compilationService, documentProvider, supportsAnalysisRemedies)

    internal suspend fun provideCodeActions(request: CodeActionRequest): List<CodeAction> =
        request.deliver { document, held ->
            generateActions(request.params, document, held, request.analysisRemedies, request)
        }

    private fun generateActions(
        params: CodeActionParams,
        snapshot: DocumentSnapshot,
        compiled: CompiledDocumentSnapshot?,
        analysisRemedies: Boolean,
        request: CodeActionRequest? = null,
    ): List<CodeAction> {
        val uri = URI.create(params.textDocument.uri)
        val content = snapshot.content
        val matching = compiled?.takeIf { it.sourceText == content }
        val actions = buildList {
            addAll(spockActions(params, snapshot, matching, request))

            if (analysisRemedies) {
                addAll(
                    AnalysisRemedyAction.create(
                        params.textDocument.uri,
                        params.context.diagnostics,
                        params.context.only,
                    ),
                )
            }

            // Add formatting action if available
            formattingAction.createFormattingAction(params.textDocument.uri, content)?.let(::add)

            // Add import actions for missing symbols
            addAll(
                if (request == null) {
                    importAction.createImportActions(params.textDocument.uri, params.context.diagnostics, content)
                } else {
                    importAction.createImportActions(
                        params.textDocument.uri,
                        params.context.diagnostics,
                        content,
                        request::findCandidates,
                    )
                },
            )

            // Import hygiene: quick fixes for gvy's own unused-import diagnostic, and Organize Imports
            val importRequest = ImportActionRequest(uri, snapshot.version, content, params.context.diagnostics)
            if (allowsKind(CodeActionKind.QuickFix, params.context.only)) {
                matching?.let { addAll(unusedImportFixAction.createActions(importRequest, it.parseResult)) }
            }
            if (allowsKind(CodeActionKind.SourceOrganizeImports, params.context.only)) {
                matching?.let { organizeImportsAction.createAction(importRequest, it.parseResult) }?.let(::add)
            }

            // Add lint fix actions for deterministic CodeNarc fixes
            addAll(
                lintFixAction.createLintFixActions(
                    params.textDocument.uri,
                    params.context.diagnostics,
                    content,
                ),
            )
        }

        val filtered = actions.filter { it.kind != null && allowsKind(it.kind, params.context.only) }
        logger.debug { "Returning ${filtered.size} code actions for ${params.textDocument.uri}" }
        return filtered
    }

    private fun spockActions(
        params: CodeActionParams,
        snapshot: DocumentSnapshot,
        compiled: CompiledDocumentSnapshot?,
        request: CodeActionRequest?,
    ): List<CodeAction> {
        compiled ?: return emptyList()
        val uri = params.textDocument.uri
        val verdicts = verdictMemory.access(
            uri,
            request?.fingerprint ?: compilationService.workspaceManager.fingerprintFor(URI.create(uri)),
            compiled.parseResult.outcome,
            readSource = { path ->
                if (request == null) currentSourceText(path) else request.observe { currentSourceText(path) }
            },
            listPackage = { path, name ->
                if (request == null) packageFileSet(path, name) else request.observe { packageFileSet(path, name) }
            },
            compiler = SpockVerdictMemory.Compiler(compiled.referenceProducer, request),
        )
        request?.verdicts = verdicts
        return SpockTableAction.createActions(
            SpockTableRequest(
                params.textDocument.uri,
                snapshot.content,
                compiled.parseResult.ast,
                params.range,
                params.context.only,
                snapshot.version,
                isPartial = !compiled.parseResult.isSuccessful,
                verdicts = verdicts,
                onSource = { request?.compilerInput(it) },
            ),
        )
    }
}

private const val MAX_SOURCE_BYTES = 1_000_000
