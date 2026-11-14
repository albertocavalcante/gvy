// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.services.Formatter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Main provider for code actions (quick fixes) in Groovy files.
 * Supports:
 * - Missing import resolution (unambiguous only)
 * - Formatting actions
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
        val directory = runCatching { Path.of(path).parent }.getOrNull()?.let(::real) ?: return null
        val packagePath = packageName.split('.').filter(String::isNotEmpty)
        val roots = compilationService.workspaceManager.getSourceRoots().map(::real)
        val directories = (roots.map { root -> packagePath.fold(root) { dir, part -> dir.resolve(part) } } + directory)
            .map { it.normalize() }
            .distinct()
        val onDisk = directories.map { listSources(it) ?: return null }.flatten()
        val open = documentProvider.getAllUris()
            .mapNotNull { uri -> runCatching { real(Path.of(uri)) }.getOrNull() }
            .filter { it.parent in directories && isSourceFile(it) }
        return (onDisk + open).map(Path::toString).toSortedSet().joinToString(",")
    }

    /** The source files directly in [directory]; empty when it does not exist, null when it cannot be read. */
    private fun listSources(directory: Path): List<Path>? = when {
        !Files.isDirectory(directory) -> emptyList()
        else -> runCatching {
            Files.list(directory).use { entries ->
                entries.filter { Files.isRegularFile(it) && isSourceFile(it) }.map(::real).toList()
            }
        }.getOrNull()
    }

    /** The real path when it exists, else the absolute normalized path, so roots and sources compare alike. */
    private fun real(path: Path): Path =
        runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }

    private fun isSourceFile(path: Path): Boolean =
        path.fileName.toString().let { it.endsWith(".groovy") || it.endsWith(".java") }

    /** A source's current text by normalized path: the open buffer when there is one, else the file. */
    private fun currentSourceText(path: String): String? {
        val file = runCatching { Path.of(path) }.getOrNull() ?: return null
        documentProvider.getSnapshot(file.toUri())?.let { return it.content }
        return runCatching {
            file.takeIf { Files.isRegularFile(it) && Files.size(it) <= MAX_SOURCE_BYTES }?.let(Files::readString)
        }.getOrNull()
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

        val content = snapshot.content
        val actions = buildList {
            addAll(spockActions(uri, params, snapshot))

            if (supportsAnalysisRemedies()) {
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
                importAction.createImportActions(
                    params.textDocument.uri,
                    params.context.diagnostics,
                    content,
                ),
            )

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

    private fun spockActions(uri: URI, params: CodeActionParams, snapshot: DocumentSnapshot): List<CodeAction> {
        val compiled = compilationService.getCompiledSnapshot(uri) ?: return emptyList()
        if (compiled.sourceText != snapshot.content) return emptyList()
        return SpockTableAction.createActions(
            SpockTableRequest(
                params.textDocument.uri,
                snapshot.content,
                compiled.parseResult.ast,
                params.range,
                params.context.only,
                snapshot.version,
                isPartial = !compiled.parseResult.isSuccessful,
                verdicts = verdictMemory.access(
                    params.textDocument.uri,
                    compilationService.workspaceManager.fingerprintFor(uri),
                    compiled.parseResult.outcome,
                    ::currentSourceText,
                    ::packageFileSet,
                ),
            ),
        )
    }
}

private const val MAX_SOURCE_BYTES = 1_000_000L
