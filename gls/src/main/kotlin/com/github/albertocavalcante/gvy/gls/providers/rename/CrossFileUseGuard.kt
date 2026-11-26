// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import java.net.URI
import java.nio.file.Path

/** How much of the workspace one cross-file check may read before it gives up and refuses. */
internal data class ScanLimits(
    val maxFiles: Int = MAX_FILES,
    val maxChars: Long = MAX_CHARS,
    val maxWalkEntries: Int = MAX_WALK_ENTRIES,
) {
    companion object {
        /** Generous because the real bound is the characters read; a mixed repository has many small files. */
        const val MAX_FILES = 20_000
        const val MAX_CHARS = 32L * 1024 * 1024
        const val MAX_WALK_ENTRIES = 200_000
    }
}

/** Hits after which the scan stops: the message names this many files, so reading more would change nothing. */
private const val MAX_REPORTED = 3

/**
 * Finds uses of a rename target outside its own document. Workspace-wide rename is disabled until indexed
 * identities are sound, so a target other files may use is refused rather than half-renamed.
 *
 * Two rules, both conservative:
 * 1. The semantic index proves a reference to the target's symbol id in another file.
 * 2. Another workspace file (Groovy, Java, Gradle, Jenkinsfile) mentions the name as a whole word (for fields
 *    and properties, also as
 *    `get`/`set`/`is` + capitalized name). `private` is not exempt: Groovy ignores it across files, and the
 *    index records only certain occurrences, so untyped receivers, map-constructor keys, static imports,
 *    overrides and files that do not compile are invisible to rule 1.
 *
 * Candidates are every file under the workspace root with a source extension (found by walking the root, so files
 * created since startup and roots without a recognized source layout are covered), plus the workspace's known
 * sources, indexed documents and open buffers. `untitled:` and `jar:` documents and files outside the root never
 * cause a refusal. A file's text is its open buffer, else the file on disk (cached by path, modification time and
 * size). Past [limits], or when the walk is too large, the check refuses instead of guessing. A refusal means
 * "may be used"; the absence of one is not proof that no other file uses the symbol.
 *
 * Not covered: symlinked directories (the walk does not follow links, which could loop), files in `.git`,
 * `node_modules` and similar directories (also when they cannot be read), and readable build output directly under
 * a workspace or module root. Any other directory or source file that cannot be read refuses, including an
 * unreadable `build/`, `out/` or `target/`: it is skipped only if it holds no sources, which needs reading it.
 * Refusals name the directory, so permissions can be fixed.
 *
 * Files are compared by real path, not URI spelling: the index keys files by `Path.toUri()` while editors send
 * their own spelling (`file:///c:/…` vs `file:///C:/…`, percent-encoded characters), and the document being
 * renamed must never count as another file.
 */
internal class CrossFileUseGuard(
    private val index: WorkspaceSymbolIndex,
    private val openBuffers: () -> Map<URI, String>,
    private val layout: WorkspaceLayout,
    private val walks: WorkspaceWalkCache = WorkspaceWalkCache(),
    private val diskText: WorkspaceTextCache = WorkspaceTextCache(),
    private val limits: ScanLimits = ScanLimits(),
    private val witness: RenameScanWitness? = null,
) {
    /** Known sources keyed for one workspace root; the source list is replaced, never mutated, on refresh. */
    private class KeyedSources(val list: List<Path>, val rootKeys: List<Path>, val byKey: Map<Path, Path>)

    @Volatile
    private var keyedSources: KeyedSources? = null

    /** Reads files on the IO dispatcher and checks for cancellation between files. */
    suspend fun usesOutside(documentUri: URI, definition: ASTNode, name: String): CrossFileUses {
        val symbolId = symbolIdOf(definition) ?: return CrossFileUses()
        return withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val roots = layout.roots()
            val keys = FileKeys(roots)
            diskText.rebind(roots)
            val self = SelfKey(keys, documentUri)
            val known = sourcesKeyed(keys)
            // In the workspace: under a root, or a known source (e.g. a Gradle srcDir outside the roots).
            witness?.begin(walks)
            witness?.observe { FileKeys(layout.roots()).rootKeys == keys.rootKeys }
            val references = witness?.references ?: index.captureReferences()
            val rows = references.findReferenceDocuments(symbolId).filterKeys { uri ->
                keys.of(uri)?.let { !self.matches(it) && (keys.inScope(it) || it in known) } == true
            }
            var remainingChars = limits.maxChars
            val used = rows.filter { (uri, indexed) ->
                context.ensureActive()
                val budget = remainingChars
                witness?.qualifies(uri, indexed, diskText, budget) { consumed -> remainingChars -= consumed } != false
            }.keys.sortedBy { it.toString() }
            // An unqualified positive row must still refuse, even when a fresh text scan cannot name the binding.
            val unqualified = (rows.keys - used.toSet()).sortedBy { it.toString() }
            if (unqualified.isNotEmpty()) return@withContext CrossFileUses(used = used, mentioned = unqualified)
            if (used.size >= MAX_REPORTED) return@withContext CrossFileUses(used = used, truncated = true)

            val buffers = openBuffers()
            val walked = walkedFiles(keys) { context.ensureActive() }
            val found = CandidateCollector(keys, limits.maxFiles)
                .collect(known, references.visibleDocuments + buffers.keys, walked)
            val usedKeys = used.mapNotNullTo(HashSet(), keys::of)
            val candidates = found.files.filterKeys { !self.matches(it) && it !in usedKeys }
            if (found.overflow) {
                return@withContext CrossFileUses(used = used, unverifiable = true)
            }
            if (found.unreadable.isNotEmpty()) {
                return@withContext CrossFileUses(used = used, unreadable = found.unreadable.map(Path::toUri))
            }
            val scan = Scan(
                budget = remainingChars,
                word = NameMatcher.pattern(name, definition),
                indexed = references.visibleDocuments.mapNotNull { uri ->
                    keys.of(uri)?.let { it to references.getDocumentSnapshot(uri)?.sourceText }
                }.toMap(),
                buffers = buffers.entries.mapNotNull { (uri, text) -> keys.of(uri)?.let { it to text } }.toMap(),
            )
            scan.run(candidates, used) { context.ensureActive() }
        }
    }

    private fun walkedFiles(keys: FileKeys, active: () -> Unit): Walked {
        if (keys.rootKeys.isEmpty()) return Walked(emptyList(), overflow = false)
        val sourceRootKeys = layout.sourceRoots().map(keys::of)
        val request = listOf(keys.rootKeys, sourceRootKeys, limits.maxFiles, limits.maxWalkEntries)
        fun walk(checkActive: () -> Unit) =
            walkWorkspace(keys.rootKeys, sourceRootKeys, limits.maxFiles, limits.maxWalkEntries, checkActive)
        val walked = walks.get(request) { walk(active) }
        witness?.observe {
            if (walks.stillLists(request, walked)) {
                true
            } else {
                val validation = currentCoroutineContext()
                val current = walk { validation.ensureActive() }
                current.overflow == walked.overflow && current.unreadable.toSet() == walked.unreadable.toSet() &&
                    current.files.toSet() == walked.files.toSet()
            }
        }
        return walked
    }

    /** Resolves the known sources' keys once per source list (they cost a filesystem call each). */
    private fun sourcesKeyed(keys: FileKeys): Map<Path, Path> {
        val list = layout.sources()
        keyedSources?.takeIf { it.list === list && it.rootKeys == keys.rootKeys }?.let { return it.byKey }
        val byKey = LinkedHashMap<Path, Path>()
        list.forEach { byKey.putIfAbsent(keys.of(it), it) }
        keyedSources = KeyedSources(list, keys.rootKeys, byKey)
        return byKey
    }

    /** One pass over the candidate files, tracking the character budget and stopping at [MAX_REPORTED] hits. */
    private inner class Scan(
        val budget: Long,
        val word: Regex,
        val indexed: Map<Path, String?>,
        val buffers: Map<Path, String>,
    ) {
        private var chars = 0L
        private val mentioned = mutableListOf<URI>()
        private val unsaved = mutableListOf<URI>()
        private val hits: Int get() = mentioned.size + unsaved.size

        fun run(candidates: Map<Path, Path>, used: List<URI>, active: () -> Unit): CrossFileUses {
            val pending = candidates.entries.iterator()
            var complete = true
            while (complete && used.size + hits < MAX_REPORTED && pending.hasNext()) {
                active()
                val (key, path) = pending.next()
                complete = examine(key, path)
            }
            val truncated = complete && pending.hasNext()
            return CrossFileUses(used, sorted(mentioned), sorted(unsaved), truncated, unverifiable = !complete)
        }

        /** False once the character budget is exhausted. */
        private fun examine(key: Path, path: Path): Boolean {
            val buffer = buffers[key] ?: return examineDisk(path)
            chars += buffer.length
            if (word.containsMatchIn(buffer)) {
                val stale = indexed[key]?.let { it != buffer } == true
                (if (stale) unsaved else mentioned).add(path.toUri())
            }
            return chars <= budget
        }

        private fun examineDisk(path: Path): Boolean {
            val remaining = budget - chars
            val physical = witness?.let { realKey(path) }
            val read = diskText.read(path, remaining)
            witness?.observe { realKey(path) == physical && diskText.readCurrent(path, remaining) == read }
            return when (read) {
                is DiskText.Text -> {
                    chars += read.text.length
                    if (word.containsMatchIn(read.text)) mentioned.add(path.toUri())
                    chars <= budget
                }
                DiskText.TooLarge -> false
                DiskText.Unreadable -> {
                    mentioned.add(path.toUri())
                    true
                }
                DiskText.Missing -> true
            }
        }

        private fun sorted(uris: List<URI>): List<URI> = uris.sortedBy { it.toString() }
    }

    /** The id the semantic index stores for [definition]; null for locals, parameters and other non-members. */
    private fun symbolIdOf(definition: ASTNode): String? = when (definition) {
        is ClassNode -> SemanticDocumentBuilder.createClassSymbolId(definition).id
        is MethodNode -> SemanticDocumentBuilder.createMethodSymbolId(definition.declaringClass, definition).id
        is PropertyNode -> definition.declaringClass?.let {
            SemanticDocumentBuilder.createPropertySymbolId(it, definition).id
        }
        is FieldNode -> definition.declaringClass?.let {
            SemanticDocumentBuilder.createFieldSymbolId(it, definition).id
        }
        else -> null
    }
}
