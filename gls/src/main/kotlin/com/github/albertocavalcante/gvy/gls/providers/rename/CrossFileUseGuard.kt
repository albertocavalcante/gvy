package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import java.net.URI

/** Other workspace files that may use a rename target; either list being non-empty makes a rename unsafe. */
internal data class CrossFileUses(val used: List<URI>, val unsaved: List<URI>) {
    val isEmpty: Boolean get() = used.isEmpty() && unsaved.isEmpty()

    /** E.g. `'foo' is used in 4 other files (B.groovy, C.groovy, D.groovy, …); renaming across files ...`. */
    fun message(name: String): String {
        val parts = buildList {
            if (used.isNotEmpty()) {
                add("is used in ${used.size} other file${if (used.size == 1) "" else "s"} (${used.named()})")
            }
            if (unsaved.isNotEmpty()) {
                val verb = if (unsaved.size == 1) "has" else "have"
                add("may be used in ${unsaved.named()}, which $verb unsaved changes not analyzed yet")
            }
        }
        return "'$name' ${parts.joinToString(" and ")}; renaming across files is not supported yet"
    }

    private fun List<URI>.named(): String =
        take(MAX_NAMED).joinToString(", ") { it.fileName() } + if (size > MAX_NAMED) ", …" else ""

    private fun URI.fileName(): String = path?.substringAfterLast('/')?.ifEmpty { null } ?: toString()

    private companion object {
        const val MAX_NAMED = 3
    }
}

/**
 * Finds uses of a rename target outside its own document. Workspace-wide rename is disabled until indexed
 * identities are sound, so a target used elsewhere is refused rather than half-renamed.
 *
 * Files are compared by path, not URI spelling: the index keys files by `Path.toUri()` while editors send
 * their own spelling (`file:///c:/…` vs `file:///C:/…`, percent-encoded characters), and the document being
 * renamed must never count as another file. Misses are possible (see the PR notes): this reports what the
 * index, indexed source text and open buffers show.
 */
internal class CrossFileUseGuard(
    private val index: WorkspaceSymbolIndex,
    private val openBuffers: () -> Map<URI, String>,
) {

    fun usesOutside(documentUri: URI, definition: ASTNode, name: String): CrossFileUses {
        val symbolId = symbolIdOf(definition) ?: return NONE
        val self = fileKey(documentUri)
        val elsewhere = { uri: URI -> fileKey(uri) != self }
        val word = Regex("\\b${Regex.escape(name)}\\b")

        val used = index.findReferenceDocuments(symbolId).keys.filterTo(linkedSetOf(), elsewhere)
        if (definition is ClassNode) {
            // Type references in declarations (`Foo f`) may not be index occurrences; a false refusal is safe.
            index.implementationSnapshot
                .filter { (uri, doc) -> elsewhere(uri) && doc.sourceText?.let(word::containsMatchIn) == true }
                .keys.toCollection(used)
        }
        val usedFiles = used.mapTo(HashSet(), ::fileKey)
        val unsaved = openBuffers().filter { (uri, text) ->
            elsewhere(uri) && fileKey(uri) !in usedFiles && word.containsMatchIn(text) && !isIndexed(uri, text)
        }.keys
        return CrossFileUses(used.sortedBy { it.toString() }, unsaved.sortedBy { it.toString() })
    }

    /** Whether the index holds a document built from exactly [text] for [uri], under any spelling of it. */
    private fun isIndexed(uri: URI, text: String): Boolean {
        if (index.getDocumentSnapshot(uri)?.sourceText == text) return true
        val key = fileKey(uri)
        return index.implementationSnapshot.any { (indexed, doc) -> fileKey(indexed) == key && doc.sourceText == text }
    }

    /** Identity of the file behind [uri]: its normalized path where it has one (case-insensitive on Windows). */
    private fun fileKey(uri: URI): Any = UriPathConverter.toPath(uri)?.toAbsolutePath()?.normalize() ?: uri

    /** The id the semantic index stores for [definition]; null for locals, parameters and other non-members. */
    private fun symbolIdOf(definition: ASTNode): String? = when (definition) {
        is ClassNode -> SemanticDocumentBuilder.createClassSymbolId(definition)
        is MethodNode -> SemanticDocumentBuilder.createMethodSymbolId(definition.declaringClass, definition)
        is PropertyNode -> definition.declaringClass?.let {
            SemanticDocumentBuilder.createPropertySymbolId(it, definition)
        }
        is FieldNode -> definition.declaringClass?.let {
            SemanticDocumentBuilder.createFieldSymbolId(it, definition)
        }
        else -> null
    }

    private companion object {
        val NONE = CrossFileUses(emptyList(), emptyList())
    }
}
