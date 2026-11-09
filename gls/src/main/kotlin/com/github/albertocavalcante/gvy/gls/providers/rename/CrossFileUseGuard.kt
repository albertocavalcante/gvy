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
import java.nio.file.Path

/** How much of the workspace one cross-file check may read before it gives up and refuses. */
internal data class ScanLimits(val maxFiles: Int = MAX_FILES, val maxBytes: Long = MAX_BYTES) {
    companion object {
        const val MAX_FILES = 2_000
        const val MAX_BYTES = 32L * 1024 * 1024
    }
}

/**
 * Finds uses of a rename target outside its own document. Workspace-wide rename is disabled until indexed
 * identities are sound, so a target other files may use is refused rather than half-renamed.
 *
 * Two rules, both conservative:
 * 1. The semantic index proves a reference to the target's symbol id in another file.
 * 2. Another workspace source file mentions the name as a whole word (for fields and properties, also as
 *    `get`/`set`/`is` + capitalized name). `private` is not exempt: Groovy ignores it across files, and the
 *    index records only certain occurrences, so untyped receivers, map-constructor keys, static imports,
 *    overrides and files that do not compile are invisible to rule 1.
 *
 * Only workspace sources are examined, so open buffers that are not (untitled, `jar:` documents, Jenkinsfiles
 * outside the source roots) never cause a refusal. A file's text is its open buffer, else the text the index
 * was built from, else the file on disk (cached by path, modification time and size). Past [limits] the check
 * refuses instead of guessing. A refusal means "may be used"; the absence of one is not proof that no other
 * file uses the symbol.
 *
 * Files are compared by real path, not URI spelling: the index keys files by `Path.toUri()` while editors send
 * their own spelling (`file:///c:/…` vs `file:///C:/…`, percent-encoded characters), and the document being
 * renamed must never count as another file.
 */
internal class CrossFileUseGuard(
    private val index: WorkspaceSymbolIndex,
    private val openBuffers: () -> Map<URI, String>,
    private val workspaceSources: () -> List<Path>,
    private val diskText: WorkspaceTextCache = WorkspaceTextCache(),
    private val limits: ScanLimits = ScanLimits(),
) {

    fun usesOutside(documentUri: URI, definition: ASTNode, name: String): CrossFileUses {
        val symbolId = symbolIdOf(definition) ?: return CrossFileUses()
        val self = fileKey(documentUri)
        val used = index.findReferenceDocuments(symbolId).keys.filter { fileKey(it) != self }.sortedBy { it.toString() }
        val usedKeys = used.mapTo(HashSet(), ::fileKey)

        val candidates = workspaceSources().map { fileKey(it) to it }.filter { (key, _) -> key != self }
        if (candidates.size > limits.maxFiles) return CrossFileUses(used = used, unverifiable = true)
        val scan = Scan(
            word = mentionPattern(name, definition),
            indexed = index.implementationSnapshot.entries.associate { (uri, doc) -> fileKey(uri) to doc.sourceText },
            buffers = openBuffers().mapKeys { fileKey(it.key) },
        )
        return scan.run(candidates.filter { (key, _) -> key !in usedKeys }, used)
    }

    /** One pass over the candidate files, tracking the byte budget. */
    private inner class Scan(val word: Regex, val indexed: Map<Any, String?>, val buffers: Map<Any, String>) {
        private var bytes = 0L
        private val mentioned = mutableListOf<URI>()
        private val unsaved = mutableListOf<URI>()

        fun run(candidates: List<Pair<Any, Path>>, used: List<URI>): CrossFileUses {
            val complete = candidates.all { (key, path) -> examine(key, path) }
            return CrossFileUses(used, sorted(mentioned), sorted(unsaved), unverifiable = !complete)
        }

        /** False once the byte budget is exhausted. */
        private fun examine(key: Any, path: Path): Boolean {
            val text = buffers[key] ?: indexed[key] ?: return examineDisk(path)
            bytes += text.length
            if (word.containsMatchIn(text)) {
                val stale = indexed[key]?.let { it != text } == true
                (if (stale) unsaved else mentioned).add(path.toUri())
            }
            return bytes <= limits.maxBytes
        }

        private fun examineDisk(path: Path): Boolean = when (val read = diskText.read(path, limits.maxBytes - bytes)) {
            is DiskText.Text -> {
                bytes += read.bytes
                if (word.containsMatchIn(read.text)) mentioned.add(path.toUri())
                true
            }
            DiskText.TooLarge -> false
            DiskText.Unreadable -> {
                mentioned.add(path.toUri())
                true
            }
            DiskText.Missing -> true
        }

        private fun sorted(uris: List<URI>): List<URI> = uris.sortedBy { it.toString() }
    }

    /** The name as a whole word; fields and properties also by their `get`/`set`/`is` accessor names. */
    private fun mentionPattern(name: String, definition: ASTNode): Regex {
        val alternatives = mutableListOf(Regex.escape(name))
        if (definition is PropertyNode || definition is FieldNode) {
            val capitalized = name.replaceFirstChar { it.uppercaseChar() }
            ACCESSOR_PREFIXES.mapTo(alternatives) { Regex.escape(it + capitalized) }
        }
        return Regex("\\b(?:${alternatives.joinToString("|")})\\b")
    }

    /** Identity of the file behind [uri]: its real path where it has one, else the normalized path. */
    private fun fileKey(uri: URI): Any = UriPathConverter.toPath(uri)?.let(::fileKey) ?: uri

    private fun fileKey(path: Path): Any {
        val absolute = path.toAbsolutePath()
        return runCatching { absolute.toRealPath() }.getOrDefault(absolute.normalize())
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
        val ACCESSOR_PREFIXES = listOf("get", "set", "is")
    }
}
