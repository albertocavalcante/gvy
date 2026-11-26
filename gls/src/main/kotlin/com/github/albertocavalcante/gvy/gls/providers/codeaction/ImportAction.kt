// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.compilation.ClassNameQuery
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.utils.ImportUtils
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

/**
 * Provides import actions for missing symbols.
 * Only offers actions when there's a single unambiguous match.
 */
class ImportAction(private val compilationService: GroovyCompilationService) {
    private val logger = KotlinLogging.logger {}

    /**
     * Creates import actions for missing symbols identified in diagnostics.
     * Only returns actions for unambiguous single matches.
     */
    fun createImportActions(uriString: String, diagnostics: List<Diagnostic>, content: String): List<CodeAction> =
        createImportActions(uriString, diagnostics, content, ::findImportCandidates)

    internal fun createImportActions(
        uriString: String,
        diagnostics: List<Diagnostic>,
        content: String,
        findCandidates: (String) -> List<String>,
    ): List<CodeAction> {
        val actions = mutableListOf<CodeAction>()

        // Find diagnostics that indicate missing symbols
        val missingSymbolDiagnostics = diagnostics.filter { isMissingSymbolDiagnostic(it) }

        for (diagnostic in missingSymbolDiagnostics) {
            val symbolName = extractSymbolName(diagnostic) ?: continue
            logger.debug { "Looking for import candidates for symbol: $symbolName" }

            // Find all possible imports for this symbol
            val candidates = findCandidates(symbolName)

            // Only offer action if exactly one candidate exists (unambiguous)
            if (candidates.size == 1) {
                val fullyQualifiedName = candidates.first()
                val action = createImportAction(uriString, symbolName, fullyQualifiedName, content, diagnostic)
                actions.add(action)
                logger.debug { "Created import action for $symbolName -> $fullyQualifiedName" }
            } else if (candidates.isEmpty()) {
                logger.debug { "No import candidates found for $symbolName" }
            } else {
                logger.debug { "Ambiguous import for $symbolName: ${candidates.size} candidates found, declining" }
            }
        }

        return actions
    }

    /**
     * Finds all possible fully-qualified names for a symbol from workspace and dependencies.
     */
    private fun findImportCandidates(symbolName: String): List<String> {
        // Workspace classes by name, in file order, from the indexed lookup rather than a full scan (#1312).
        val candidates = compilationService.symbolIndexingService
            .findClasses(ClassNameQuery.Exact(symbolName))
            .map { it.fullyQualifiedName }
            .filter { it.isNotEmpty() && it.contains('.') }
            .toSet()

        // Search in dependencies (classpath)
        // This would require scanning the classpath for matching class names
        // For now, we'll focus on workspace symbols as a minimal implementation
        // TODO: Add dependency scanning in future enhancement

        return candidates.toList()
    }

    /**
     * Creates a code action to add an import statement.
     */
    @Suppress("UnusedParameter") // symbolName kept for future diagnostics enhancement
    private fun createImportAction(
        uriString: String,
        symbolName: String,
        fullyQualifiedName: String,
        content: String,
        diagnostic: Diagnostic,
    ): CodeAction {
        val eol = if (content.contains("\r\n")) "\r\n" else "\n"
        val insertPosition = findImportInsertionPoint(content)
        val lineCount = content.lines().size

        // The insertion line can be one past the last line (e.g. a file that is only `package foo` with no
        // trailing newline). Clients clamp such positions inconsistently, so append after the last line instead.
        val edit = if (insertPosition.line >= lineCount) {
            val lastLine = content.lines().last()
            val end = Position(lineCount - 1, lastLine.length)
            TextEdit(Range(end, end), "${eol}import $fullyQualifiedName")
        } else {
            val importStatement = "import $fullyQualifiedName$eol"
            TextEdit(Range(insertPosition, insertPosition), importStatement)
        }

        val workspaceEdit = WorkspaceEdit().apply {
            changes = mapOf(uriString to listOf(edit))
        }

        return CodeAction("Import '$fullyQualifiedName'").apply {
            kind = CodeActionKind.QuickFix
            this.edit = workspaceEdit
            diagnostics = listOf(diagnostic)
        }
    }

    /**
     * Finds the position where a new import should be inserted: after the last import, else after the
     * `package` declaration, else at the top but below a `#!` shebang line (a shebang must stay on line 1).
     * Reuses the shared heuristic so license headers before `package` are handled.
     */
    private fun findImportInsertionPoint(content: String): Position {
        val position = ImportUtils.findImportInsertPosition(content)
        return if (position.line == 0 && content.startsWith("#!")) Position(1, 0) else position
    }

    /**
     * Checks if a diagnostic indicates a missing symbol that might need an import.
     */
    private fun isMissingSymbolDiagnostic(diagnostic: Diagnostic): Boolean {
        val message = diagnostic.message.lowercase()
        return message.contains("unable to resolve class") ||
            message.contains("cannot find symbol") ||
            message.contains("cannot resolve symbol") ||
            message.contains("unresolved reference")
    }

    /**
     * Extracts the symbol name from a diagnostic message.
     */
    private fun extractSymbolName(diagnostic: Diagnostic): String? {
        val message = diagnostic.message
        return sequenceOf(
            UNABLE_TO_RESOLVE_PATTERN,
            CANNOT_FIND_PATTERN,
            UNRESOLVED_REFERENCE_PATTERN,
        ).firstNotNullOfOrNull { pattern ->
            Regex(pattern, RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)
        }
    }

    companion object {
        private const val UNABLE_TO_RESOLVE_PATTERN = "unable to resolve class\\s+(\\w+)"
        private const val CANNOT_FIND_PATTERN = "cannot find symbol\\s+(\\w+)"
        private const val UNRESOLVED_REFERENCE_PATTERN = "unresolved reference:\\s+(\\w+)"
    }
}
