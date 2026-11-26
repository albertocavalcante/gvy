// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.concurrent.SharedValue
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.semantics.db.ImplementationFacts
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import com.github.albertocavalcante.gvy.source.AnalysisGeneration
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ModuleNode
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** SHA-256 of [content], the key that says two compiles saw the same text. */
internal fun contentHash(content: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toByteArray()))

/**
 * The one owner of the workspace semantic index: every compile that yields a module AST for a file
 * reports it here, and cache invalidation and deletes remove entries here.
 *
 * Each file keeps the content hash and the compile phase its document was built at, so:
 * - a document built from different content replaces the stored one (stale is worse than degraded);
 * - for the same content, only a document from a later phase replaces it: a degraded parse never
 *   displaces a resolved one, and which of two compiles of the same text lands first does not matter;
 * - for the same content and phase, a document begun after [retireAllPublications] replaces one stored
 *   before it: the inputs besides the text (the source list, the classpath) changed, so the kept document
 *   may resolve names the newer one resolves differently;
 * - a document from disk never replaces the one built from an open editor buffer ([isOpen]);
 * - for different content, a publication begun earlier never lands after one begun later, even if that
 *   one failed or was upgraded meanwhile, so builds that finish out of order cannot bring back older text.
 */
class SemanticIndexer(
    private val index: WorkspaceSymbolIndex,
    private val isOpen: (URI) -> Boolean = { false },
    private val build: (ModuleNode, URI) -> SemanticDocument = { ast, uri ->
        SemanticDocumentBuilder(ast, uri).build()
    },
) {
    /** [generation]: the [retireAllPublications] generation of the publication that stored it. */
    private class Entry(val contentHash: String, val phase: Int, val generation: AnalysisGeneration)

    /**
     * Written only inside [entries]' compute for its key. [entry] and [highestTicket] change together but are also
     * read lock-free by [publish]'s pre-check, which can see a mismatched pair; [publish] decides again under the
     * lock, so that is harmless.
     */
    private class State(initialEntry: Entry? = null, initialHighestTicket: Long = 0L) {
        val entry = SharedValue(initialEntry)

        /** Highest ticket that reached a decision; kept when the entry is upgraded or dropped. */
        val highestTicket = SharedValue(initialHighestTicket)
        var publications = 0
    }

    internal class Publication(
        val uri: URI,
        val identity: Any,
        /** Order in which publications began (callers read or capture their source around [begin]). */
        val ticket: Long,
        /** The [retireAllPublications] generation it began in; any later one makes it stale. */
        val generation: AnalysisGeneration,
        private val finish: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()
        val isClosed: Boolean get() = closed.get()

        override fun close() {
            if (closed.compareAndSet(false, true)) finish()
        }
    }

    private val logger = KotlinLogging.logger {}

    /*
     * Replaced, never emptied, by [clear] (#1342). Every write re-reads it under [swap]'s read lock, which [clear]
     * holds for writing while it swaps both this map and the index's graph: a write that began before the swap
     * finishes on the old map and old graph first, and a later one sees only the new pair. Reads stay lock-free;
     * they see the old or the new map, and a pre-clear publication can never match an entry of the new one.
     */
    private val publishedEntries = SharedValue(ConcurrentHashMap<URI, State>())
    private val entries: ConcurrentHashMap<URI, State> get() = publishedEntries.value
    private val swap = ReentrantReadWriteLock()
    private val tickets = AtomicLong()

    // Read under [swap]'s read lock and by [publish]'s lock-free pre-check, bumped under its write lock: see
    // [retireAllPublications]
    private val generation = SharedValue(AnalysisGeneration.INITIAL)

    /** Acquire before reading/enqueueing source. Removal invalidates every outstanding publication. */
    internal fun begin(uri: URI): Publication {
        val (state, began) = swap.read {
            val state = checkNotNull(
                entries.compute(uri) { _, existing ->
                    (existing ?: State()).also { it.publications++ }
                },
            )
            state to generation.value
        }
        return Publication(uri, state, tickets.incrementAndGet(), began) {
            swap.read {
                entries.computeIfPresent(uri) { _, current ->
                    if (current === state) {
                        current.publications--
                        current.takeUnless { it.publications == 0 && it.entry.value == null }
                    } else {
                        current
                    }
                }
            }
        }
    }

    /**
     * Stores the semantic document of [ast], compiled from [content] to [phase] (the phase the compile
     * reached; [fromBuffer]: the text of an editor buffer rather than the file on disk).
     */
    fun update(uri: URI, content: String, ast: ModuleNode, phase: Int, fromBuffer: Boolean = true) {
        begin(uri).use { publish(it, content, ast, phase, fromBuffer) }
    }

    /** A built document with the implementation facts stored alongside it, or null when building failed. */
    private class Built(val document: SemanticDocument, val implementations: ImplementationFacts.Snapshot)

    /**
     * Stores the document for [content] unless the entry already supersedes it. The document is built before
     * taking the entry's lock, so a slow build blocks no other file and no removal; the decision is then
     * taken again under the lock against whatever landed meanwhile.
     */
    internal fun publish(
        publication: Publication,
        content: String,
        ast: ModuleNode,
        phase: Int,
        fromBuffer: Boolean = true,
    ) {
        val uri = publication.uri
        val hash = contentHash(content)
        if (!replaces(entries[uri], publication, hash, phase, fromBuffer)) return
        val built = buildOrNull(ast, uri)
        // Stores into the index while holding the read lock, so a clear cannot swap the graph mid-store
        swap.read {
            entries.computeIfPresent(uri) { _, state ->
                if (replaces(state, publication, hash, phase, fromBuffer)) {
                    state.entry.set(Entry(hash, phase, publication.generation).takeIf { store(uri, built, content) })
                    state.highestTicket.update { highest -> maxOf(highest, publication.ticket) }
                }
                state
            }
        }
    }

    /** Whether a document for [hash] at [phase] replaces what [state] holds (see the class rules). */
    private fun replaces(
        state: State?,
        publication: Publication,
        hash: String,
        phase: Int,
        fromBuffer: Boolean,
    ): Boolean {
        val existing = state?.entry?.value
        val supersedes = if (existing != null && existing.contentHash == hash) {
            phase > existing.phase || (phase == existing.phase && publication.generation > existing.generation)
        } else {
            publication.ticket > (state?.highestTicket?.value ?: 0L)
        }
        return state === publication.identity &&
            publication.generation == generation.value &&
            !publication.isClosed &&
            (fromBuffer || !isOpen(publication.uri)) &&
            supersedes
    }

    /**
     * Puts [built] in the index, or, when building failed, removes the stale document (no document beats
     * one that describes other content). Returns whether a document is now stored.
     */
    @Suppress("TooGenericExceptionCaught") // an index failure must not fail the compile that produced the AST
    private fun store(uri: URI, built: Built?, content: String): Boolean = try {
        if (built != null) {
            index.updateDocument(uri, built.document, sourceText = content, implementations = built.implementations)
        } else {
            index.removeDocument(uri)
        }
        built != null
    } catch (e: Exception) {
        logger.warn(e) { "Failed to store the semantic document for $uri" }
        // Best effort: an index left holding the previous document would disagree with the empty entry.
        try {
            index.removeDocument(uri)
        } catch (removal: Exception) {
            logger.warn(removal) { "Failed to remove the semantic document for $uri" }
        }
        false
    }

    @Suppress("TooGenericExceptionCaught") // a failing builder must not fail the compile that produced the AST
    private fun buildOrNull(ast: ModuleNode, uri: URI): Built? = try {
        Built(build(ast, uri), ImplementationFacts.build(ast))
    } catch (e: Exception) {
        logger.warn(e) { "Failed to build semantic document for $uri; dropping its stale one" }
        null
    }

    /** Whether [uri] follows an open editor buffer, which a document from disk must not replace. */
    fun followsBuffer(uri: URI): Boolean = isOpen(uri)

    /** Whether a document is stored for [uri]. */
    operator fun contains(uri: URI): Boolean = entries[uri]?.entry?.value != null

    /** The compile phase of the document stored for [uri], or null when there is none. */
    fun phaseOf(uri: URI): Int? = entries[uri]?.entry?.value?.phase

    /** Whether the stored document was built from exactly [content]. */
    fun isCurrent(uri: URI, content: String): Boolean = entries[uri]?.entry?.value?.contentHash == contentHash(content)

    /**
     * Makes every outstanding publication for [uri] stale while keeping its stored document: the buffer it
     * followed closed, or the closed file changed on disk, and a refresh from disk follows. A compile that
     * read the old text can then no longer publish after that refresh.
     */
    internal fun retirePublications(uri: URI) {
        swap.read {
            entries.computeIfPresent(uri) { _, state ->
                state.entry.value?.let { kept -> State(kept, state.highestTicket.value) }
            }
        }
    }

    /**
     * Makes every outstanding publication stale while keeping every stored document, in O(1) (#1342): the inputs a
     * compile reads besides its own text (the source list, a re-stamped classpath entry) changed, so a compile that
     * read the old ones must not publish, but no file's document is dropped. A later compile of a file's unchanged
     * text replaces its kept document at the same phase too, so the kept one lasts only until that file compiles
     * again. Waits for in-flight writes, as [clear].
     */
    internal fun retireAllPublications() {
        swap.write { generation.update { it.next() } }
    }

    fun remove(uri: URI) {
        swap.read {
            entries.compute(uri) { _, _ ->
                index.removeDocument(uri)
                null
            }
        }
    }

    /**
     * Drops every document and retires every outstanding publication in O(1): swaps in an empty map and an
     * empty index graph together under the write lock, after any in-flight write has finished (#1342).
     */
    fun clear() {
        swap.write {
            publishedEntries.set(ConcurrentHashMap())
            index.clear()
        }
    }
}
