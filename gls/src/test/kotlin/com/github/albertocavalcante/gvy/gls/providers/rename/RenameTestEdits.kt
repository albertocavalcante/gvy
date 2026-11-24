// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.TextEdit
import java.net.URI
import java.nio.file.Path

/**
 * Applies [edits] to [text]. Fails, listing what each edit would replace, if edits overlap
 * (LSP forbids that) or fall outside the text.
 */
internal fun applyTextEdits(text: String, edits: List<TextEdit>): String {
    val lineStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
    fun offset(p: Position) = lineStarts.getOrNull(p.line)?.plus(p.character) ?: Int.MAX_VALUE
    val spans = edits.map { offset(it.range.start) to offset(it.range.end) }.sortedBy { it.first }
    val described = spans.joinToString("; ") { (from, to) ->
        val replaced = if (to <= text.length) text.substring(from, to).replace("\n", "\\n") else "<out of range>"
        "[$from,$to)=$replaced"
    }
    check(spans.all { it.second <= text.length } && spans.zipWithNext().all { (a, b) -> a.second <= b.first }) {
        "Edits overlap or exceed the text: $described"
    }
    return spans.sortedByDescending { it.first }.fold(text) { acc, (from, to) ->
        acc.substring(0, from) + edits.first { offset(it.range.start) == from }.newText + acc.substring(to)
    }
}

/** One workspace with one document, `Rename.groovy`, renamed from a captured compilation as an editor would. */
internal class SingleFileRename(workspace: Path) {
    private val compilation = GroovyCompilationService().apply { workspaceManager.initializeWorkspace(workspace) }
    private val documents = DocumentProvider()
    private val provider = RenameProvider(compilation) { documents.get(it) }
    val uri: URI = workspace.resolve("Rename.groovy").toUri()

    suspend fun open(content: String) {
        documents.put(uri, content)
        compilation.compile(uri, content)
    }

    fun parsedClass(name: String): ClassNode =
        checkNotNull(compilation.getParseResult(uri)?.ast?.classes?.single { it.nameWithoutPackage == name })

    fun parsedClasses(): List<ClassNode> = checkNotNull(compilation.getParseResult(uri)?.ast).classes

    /** The text after renaming at [position]; the rename's failure if it fails. */
    suspend fun apply(content: String, position: Position, newName: String): Result<String> {
        open(content)
        return runCatching {
            val edits = provider.provideRename(uri.toString(), position, newName).documentChanges
                .filter { it.isLeft && it.left.textDocument.uri == uri.toString() }
                .flatMap { it.left.edits }
            applyTextEdits(content, edits)
        }
    }

    suspend fun prepare(content: String, position: Position): Result<PrepareRenameResult?> {
        open(content)
        return runCatching { provider.providePrepareRename(uri.toString(), position) }
    }
}
