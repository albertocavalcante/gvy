// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.gvy.gls.compilation.ClassNameQuery
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.ClassInfo
import com.github.albertocavalcante.gvy.gls.services.ClasspathService
import com.github.albertocavalcante.gvy.gls.utils.ImportUtils
import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.net.URI

/**
 * Provides auto-import completion for types not yet imported.
 * Searches both workspace symbol index and classpath for matching types.
 */
object AutoImportCompletionProvider {
    private val logger = KotlinLogging.logger {}
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

        // This public convenience API also serves callers without an owned parsing copy.
        val module = compilationService.getAst(uri) as? ModuleNode
        return candidates(
            content,
            module,
            workspace = { compilationService.symbolIndexingService.findClasses(ClassNameQuery.Prefix(prefix)) },
            classpath = {
                runSuspendCatching { classpathService.findClassesByPrefix(prefix, MAX_RESULTS) }.getOrElse {
                    logger.warn(it) { "Failed to search classpath for types" }
                    emptyList()
                }
            },
        )
    }

    internal fun getTypeCompletions(prefix: String, content: String, inputs: CompletionInputs): List<CompletionItem> {
        if (prefix.isBlank()) return emptyList()
        return candidates(
            content,
            inputs.native.held.parseResult.ast,
            workspace = { inputs.workspaceClasses(ClassNameQuery.Prefix(prefix)) },
            classpath = { inputs.classNames(prefix, MAX_RESULTS) },
        )
    }

    private fun candidates(
        content: String,
        module: ModuleNode?,
        workspace: () -> List<Symbol.Class>,
        classpath: () -> List<ClassInfo>,
    ): List<CompletionItem> {
        val (existingImports, position) = ImportUtils.extractImportInfo(module, content)
        val candidates = buildList {
            workspace().forEach { add(TypeCandidate(it.name, it.fullyQualifiedName, TypeSource.WORKSPACE)) }
            classpath().forEach { add(TypeCandidate(it.simpleName, it.fullName, TypeSource.CLASSPATH)) }
        }
        // A workspace declaration wins when both views expose the same name.
        return candidates.filterNot { it.fqn in existingImports }.distinctBy { it.fqn }
            .sortedWith(compareBy({ it.source }, { it.simpleName })).take(MAX_RESULTS)
            .map { it.toCompletionItem(position) }
    }

    private data class TypeCandidate(val simpleName: String, val fqn: String, val source: TypeSource)

    private enum class TypeSource {
        WORKSPACE,
        CLASSPATH,
    }

    private fun TypeCandidate.toCompletionItem(importPosition: Position): CompletionItem {
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

            additionalTextEdits = listOf(TextEdit(Range(importPosition, importPosition), "import $fqn\n"))

            // Sort workspace types before classpath types
            sortText = when (source) {
                TypeSource.WORKSPACE -> "0_$simpleName"
                TypeSource.CLASSPATH -> "1_$simpleName"
            }
        }
    }
}
