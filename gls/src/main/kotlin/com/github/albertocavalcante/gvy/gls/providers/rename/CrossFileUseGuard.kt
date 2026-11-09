package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import java.net.URI

/** Other workspace files that may use a rename target; either set being non-empty makes a rename unsafe. */
internal data class CrossFileUses(val used: List<URI>, val unsaved: List<URI>) {
    val isEmpty: Boolean get() = used.isEmpty() && unsaved.isEmpty()

    /** E.g. `'foo' is used in 3 other files (B.groovy, C.groovy, D.groovy, ...); renaming across files ...`. */
    fun message(name: String): String {
        val parts = buildList {
            if (used.isNotEmpty()) {
                val shown = used.take(MAX_NAMED).joinToString(", ") { it.fileName() } +
                    if (used.size > MAX_NAMED) ", …" else ""
                add("is used in ${used.size} other file${if (used.size == 1) "" else "s"} ($shown)")
            }
            if (unsaved.isNotEmpty()) {
                val shown = unsaved.take(MAX_NAMED).joinToString(", ") { it.fileName() } +
                    if (unsaved.size > MAX_NAMED) ", …" else ""
                add("may be used in $shown, which has unsaved changes not analyzed yet")
            }
        }
        return "'$name' ${parts.joinToString(" and ")}; renaming across files is not supported yet"
    }

    private fun URI.fileName(): String = path?.substringAfterLast('/')?.ifEmpty { null } ?: toString()

    private companion object {
        const val MAX_NAMED = 3
    }
}

/**
 * Finds uses of a rename target outside its own document. Workspace-wide rename is disabled until indexed
 * identities are sound, so a target used elsewhere is refused rather than half-renamed.
 *
 * Misses are possible (see PR notes): this reports what the index, indexed source text and open buffers show.
 */
internal class CrossFileUseGuard(
    private val index: WorkspaceSymbolIndex?,
    private val openBuffers: () -> Map<URI, String>,
) {

    fun usesOutside(documentUri: URI, definition: ASTNode, name: String): CrossFileUses {
        val symbolId = symbolIdOf(definition) ?: return NONE
        val workspace = index ?: return NONE
        val word = Regex("\\b${Regex.escape(name)}\\b")
        val indexed = workspace.implementationSnapshot

        val used = linkedSetOf<URI>()
        workspace.findReferenceDocuments(symbolId).keys.filterTo(used) { it != documentUri }
        if (definition is ClassNode) {
            // Type references in declarations (`Foo f`) may not be index occurrences; a false refusal is safe.
            indexed.filter { (uri, doc) -> uri != documentUri && doc.sourceText?.let(word::containsMatchIn) == true }
                .keys.toCollection(used)
        }
        val unsaved = openBuffers().filter { (uri, text) ->
            uri != documentUri && uri !in used && word.containsMatchIn(text) && indexed[uri]?.sourceText != text
        }.keys.toList()
        return CrossFileUses(used.toList().sortedBy { it.toString() }, unsaved.sortedBy { it.toString() })
    }

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
