package com.github.albertocavalcante.groovylsp.providers.codeaction

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolCategory
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.slf4j.LoggerFactory

/**
 * Provides import actions for missing symbols.
 * Only offers actions when there's a single unambiguous match.
 */
class ImportAction(private val compilationService: GroovyCompilationService) {
    private val logger = LoggerFactory.getLogger(ImportAction::class.java)

    /**
     * Creates import actions for missing symbols identified in diagnostics.
     * Only returns actions for unambiguous single matches.
     */
    fun createImportActions(uriString: String, diagnostics: List<Diagnostic>, content: String): List<CodeAction> {
        val actions = mutableListOf<CodeAction>()

        // Find diagnostics that indicate missing symbols
        val missingSymbolDiagnostics = diagnostics.filter { isMissingSymbolDiagnostic(it) }

        for (diagnostic in missingSymbolDiagnostics) {
            val symbolName = extractSymbolName(diagnostic) ?: continue
            logger.debug("Looking for import candidates for symbol: $symbolName")

            // Find all possible imports for this symbol
            val candidates = findImportCandidates(symbolName)

            // Only offer action if exactly one candidate exists (unambiguous)
            if (candidates.size == 1) {
                val fullyQualifiedName = candidates.first()
                val action = createImportAction(uriString, symbolName, fullyQualifiedName, content, diagnostic)
                actions.add(action)
                logger.debug("Created import action for $symbolName -> $fullyQualifiedName")
            } else if (candidates.isEmpty()) {
                logger.debug("No import candidates found for $symbolName")
            } else {
                logger.debug("Ambiguous import for $symbolName: ${candidates.size} candidates found, declining")
            }
        }

        return actions
    }

    /**
     * Finds all possible fully-qualified names for a symbol from workspace and dependencies.
     */
    private fun findImportCandidates(symbolName: String): List<String> {
        // Search in workspace symbols
        val candidates = compilationService.getAllSymbolStorages()
            .flatMap { (symbolUri, symbolIndex) ->
                symbolIndex.findByCategory(
                    symbolUri,
                    SymbolCategory.CLASS,
                )
            }
            .asSequence()
            .filterIsInstance<Symbol.Class>()
            .filter { it.name == symbolName }
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
        // Find where to insert the import
        val insertPosition = findImportInsertionPoint(content)

        // Create the import statement
        val importStatement = "import $fullyQualifiedName\n"

        val edit = TextEdit(
            Range(insertPosition, insertPosition),
            importStatement,
        )

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
     * Finds the position where a new import should be inserted.
     * Returns the position after the package declaration or at the beginning.
     */
    private fun findImportInsertionPoint(content: String): Position {
        val lines = content.lines()
        var insertLine = 0

        // Find the last import or package declaration
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("package ")) {
                insertLine = index + 1
            } else if (trimmed.startsWith("import ")) {
                insertLine = index + 1
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*")) {
                // First non-comment, non-import line
                break
            }
        }

        return Position(insertLine, 0)
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
