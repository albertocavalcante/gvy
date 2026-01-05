package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.services.ClasspathService
import com.github.albertocavalcante.groovylsp.utils.ImportUtils
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Provides auto-import completion for types not yet imported.
 * Searches both workspace symbol index and classpath for matching types.
 */
object AutoImportCompletionProvider {
    private val logger = LoggerFactory.getLogger(AutoImportCompletionProvider::class.java)
    private const val MAX_RESULTS = 20

    /**
     * Gets type completions with auto-import support.
     * Uses AST-based import extraction when available for deterministic results.
     *
     * @param prefix The prefix typed by the user (e.g., "ArrayL")
     * @param uri The document URI for AST lookup
     * @param content The file content (fallback for import extraction)
     * @param compilationService The compilation service for workspace index and AST access
     * @param classpathService The classpath service for classpath type search
     * @return List of completion items with additionalTextEdits for imports
     */
    fun getTypeCompletions(
        prefix: String,
        uri: URI,
        content: String,
        compilationService: GroovyCompilationService,
        classpathService: ClasspathService,
    ): List<CompletionItem> {
        if (prefix.isBlank()) return emptyList()

        // Extract import info: AST-based (deterministic) when available, heuristic fallback otherwise
        val ast = compilationService.getAst(uri) as? ModuleNode
        val (existingImports, importPosition) = ImportUtils.extractImportInfo(ast, content)

        val candidates = mutableListOf<TypeCandidate>()

        // Search workspace symbol index
        collectWorkspaceCandidates(compilationService, prefix, existingImports, candidates)

        // Search classpath
        collectClasspathCandidates(classpathService, prefix, existingImports, candidates)

        // Deduplicate by FQN (workspace takes precedence)
        val deduplicated = candidates
            .groupBy { it.fqn }
            .mapValues { (_, list) -> list.firstOrNull { it.source == TypeSource.WORKSPACE } ?: list.first() }
            .values
            .sortedWith(compareBy({ it.source }, { it.simpleName }))
            .take(MAX_RESULTS)

        return deduplicated.map { candidate ->
            candidate.toCompletionItem(importPosition, existingImports.contains(candidate.fqn))
        }
    }

    private fun collectWorkspaceCandidates(
        compilationService: GroovyCompilationService,
        prefix: String,
        existingImports: Set<String>,
        candidates: MutableList<TypeCandidate>,
    ) {
        val workspaceIndex = compilationService.getAllSymbolStorages()
        workspaceIndex.forEach { (indexUri, index) ->
            index.getSymbols(indexUri).filterIsInstance<Symbol.Class>()
                .filter { it.name.startsWith(prefix, ignoreCase = true) }
                .forEach { symbol ->
                    val fqn = symbol.fullyQualifiedName
                    if (fqn !in existingImports) {
                        candidates.add(
                            TypeCandidate(
                                simpleName = symbol.name,
                                fqn = fqn,
                                source = TypeSource.WORKSPACE,
                            ),
                        )
                    }
                }
        }
    }

    /**
     * Collects classpath type candidates matching the prefix.
     *
     * NOTE: ClasspathService.findClassesByPrefix may throw various runtime exceptions
     * (ClassNotFoundException, NoClassDefFoundError, etc.) due to classloader issues.
     * We catch all exceptions to prevent completion failure - this is intentional.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun collectClasspathCandidates(
        classpathService: ClasspathService,
        prefix: String,
        existingImports: Set<String>,
        candidates: MutableList<TypeCandidate>,
    ) {
        try {
            classpathService.findClassesByPrefix(prefix, MAX_RESULTS)
                .filter { it.fullName !in existingImports }
                .forEach { classInfo ->
                    candidates.add(
                        TypeCandidate(
                            simpleName = classInfo.simpleName,
                            fqn = classInfo.fullName,
                            source = TypeSource.CLASSPATH,
                        ),
                    )
                }
        } catch (e: Exception) {
            logger.warn("Failed to search classpath for types", e)
        }
    }

    private data class TypeCandidate(val simpleName: String, val fqn: String, val source: TypeSource)

    private enum class TypeSource {
        WORKSPACE,
        CLASSPATH,
    }

    private fun TypeCandidate.toCompletionItem(importPosition: Position, alreadyImported: Boolean): CompletionItem {
        val packageName = fqn.substringBeforeLast('.', "")

        return CompletionItem().apply {
            label = simpleName
            kind = CompletionItemKind.Class
            detail = if (packageName.isNotEmpty()) "$packageName.$simpleName" else simpleName
            insertText = simpleName
            documentation = Either.forRight(
                MarkupContent(
                    MarkupKind.MARKDOWN,
                    "**Package:** `$packageName`\n\nAuto-import: `import $fqn`",
                ),
            )

            // Add import edit if not already imported
            if (!alreadyImported) {
                additionalTextEdits = listOf(
                    TextEdit(
                        Range(importPosition, importPosition),
                        "import $fqn\n",
                    ),
                )
            }

            // Sort workspace types before classpath types
            sortText = when (source) {
                TypeSource.WORKSPACE -> "0_$simpleName"
                TypeSource.CLASSPATH -> "1_$simpleName"
            }
        }
    }
}
