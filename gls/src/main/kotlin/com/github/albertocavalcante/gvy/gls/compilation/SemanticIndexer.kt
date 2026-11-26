// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.semantics.db.ImplementationFacts
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import com.github.albertocavalcante.gvy.source.AnalysisGeneration
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import java.lang.ref.WeakReference
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

/** Reference completeness needs a resolved phase and evidence of which source the publication followed. */
internal data class ReferencePublication(
    val document: SemanticDocument,
    val phase: Int,
    val diskConfirmed: Boolean,
    val producer: ReferenceProducer?,
) {
    fun covers(indexed: SemanticDocument): Boolean =
        document === indexed && producer?.complete == true && phase >= Phases.SEMANTIC_ANALYSIS
}

/** The origin and compiler inputs of the AST being installed. */
internal data class SemanticPublicationSource(val fromBuffer: Boolean = true, val producer: ReferenceProducer? = null)

/**
 * The one owner of the workspace semantic index: every compile that yields a module AST for a file
 * reports it here, and cache invalidation and deletes remove entries here.
 *
 * Each file keeps the content hash and the compile phase its document was built at, so:
 * - a document built from different content replaces the stored one (stale is worse than degraded);
 * - for the same content, a later phase takes precedence; a degraded parse never
 *   displaces a resolved one at the same phase, and complete coverage upgrades a degraded same-phase entry;
 * - for the same content and phase, a document begun after [retireAllPublications] replaces one stored
 *   before it: the inputs besides the text (the source list, the classpath) changed, so the kept document
 *   may resolve names the newer one resolves differently;
 * - a current replay of the same binding settles its publication ticket while keeping the original document;
 *   an authoritative disk replay of equivalent phase and coverage can also confirm its source bytes;
 * - a weaker parse in a different mode can keep a stronger document only after validating its original inputs;
 * - a document from disk never replaces the one built from an open editor buffer ([isOpen]);
 * - for different content, a publication begun earlier never lands after one begun later, even if that
 *   one failed or was upgraded meanwhile, so builds that finish out of order cannot bring back older text.
 */
class SemanticIndexer internal constructor(
    private val index: WorkspaceSymbolIndex,
    private val isOpen: (URI) -> Boolean,
    private val currentProducer: (URI, String, ReferenceProducer) -> Boolean,
    private val build: (ModuleNode, URI) -> SemanticDocument = ::buildSemanticDocument,
) {
    constructor(
        index: WorkspaceSymbolIndex,
        isOpen: (URI) -> Boolean = { false },
        build: (ModuleNode, URI) -> SemanticDocument = ::buildSemanticDocument,
    ) : this(index, isOpen, { _, _, _ -> false }, build)

    /** [generation]: the [retireAllPublications] generation of the publication that stored it. */
    private data class Entry(
        val contentHash: String,
        val phase: Int,
        val generation: AnalysisGeneration,
        val diskConfirmed: Boolean,
        val producer: ReferenceProducer?,
        // The graph owns this document. Keep its identity without retaining a displaced graph.
        val stored: WeakReference<SemanticDocument>? = null,
    ) {
        fun improves(existing: Entry): Boolean {
            if (phase != existing.phase) return phase > existing.phase
            if (generation != existing.generation) return generation > existing.generation
            return producer?.complete == true && existing.producer?.complete != true
        }

        /** A different policy may leave a better document installed only under its own live producer proof. */
        fun keepsCoverageOver(candidate: Entry): Boolean {
            val previous = producer ?: return false
            val weaker = candidate.producer?.complete != true || candidate.phase < phase
            return weaker && previous.complete && phase >= Phases.SEMANTIC_ANALYSIS &&
                contentHash == candidate.contentHash && generation == candidate.generation &&
                (diskConfirmed || !candidate.diskConfirmed) &&
                previous.sharesInputsAcrossModes(candidate.producer)
        }

        fun canRetain(
            decision: Decision,
            candidate: Entry,
            current: (ReferenceProducer, SemanticDocument) -> Boolean,
        ): Boolean {
            val original = producer ?: return false
            val document = stored?.get() ?: return false
            return (decision == Decision.REPLAY || keepsCoverageOver(candidate)) && current(original, document)
        }

        fun matchesDisk(hash: String, activeGeneration: AnalysisGeneration, current: (ReferenceProducer) -> Boolean) =
            generation == activeGeneration && contentHash == hash && producer?.let(current) != false
    }

    private enum class Decision { REJECT, REPLAY, WRITE }

    private class State {
        @Volatile var entry: Entry? = null

        /** Highest ticket that reached a decision; kept when the entry is upgraded or dropped. */
        @Volatile var highestTicket = 0L
        var publications = 0

        fun decision(publication: Publication, candidate: Entry): Decision {
            val stored = entry
            val newer = publication.ticket > highestTicket
            if (stored == null ||
                stored.contentHash != candidate.contentHash ||
                candidate.producer?.matches(stored.producer) == false
            ) {
                return if (newer) Decision.WRITE else Decision.REJECT
            }
            if (candidate.improves(stored)) return Decision.WRITE
            val replay = newer && candidate.generation == stored.generation && candidate.producer != null
            return if (replay) Decision.REPLAY else Decision.REJECT
        }

        fun retain(original: Entry, installed: SemanticDocument?, diskReceipt: Entry?): Boolean {
            if (installed == null || original.stored?.get() !== installed) return false
            if (!original.diskConfirmed && diskReceipt?.diskConfirmed == true &&
                original.phase == diskReceipt.phase && original.producer?.complete == diskReceipt.producer?.complete
            ) {
                entry = original.copy(diskConfirmed = true)
            }
            return true
        }
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
        fun isActiveIn(current: AnalysisGeneration): Boolean = generation == current && !closed.get()

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
    @Volatile private var entries = ConcurrentHashMap<URI, State>()
    private val swap = ReentrantReadWriteLock()
    private val tickets = AtomicLong()

    // Read under [swap]'s read lock, bumped under its write lock: see [retireAllPublications]
    @Volatile private var generation = AnalysisGeneration.INITIAL

    /** Acquire before reading/enqueueing source. Removal invalidates every outstanding publication. */
    internal fun begin(uri: URI): Publication {
        val (state, began) = swap.read {
            val state = checkNotNull(
                entries.compute(uri) { _, existing ->
                    (existing ?: State()).also { it.publications++ }
                },
            )
            state to generation
        }
        return Publication(uri, state, tickets.incrementAndGet(), began) {
            swap.read {
                entries.computeIfPresent(uri) { _, current ->
                    if (current === state) {
                        current.publications--
                        current.takeUnless { it.publications == 0 && it.entry == null }
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
        begin(uri).use { publish(it, content, ast, phase, SemanticPublicationSource(fromBuffer)) }
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
        source: SemanticPublicationSource = SemanticPublicationSource(),
    ) {
        val uri = publication.uri
        val candidate = Entry(
            contentHash(content),
            phase,
            publication.generation,
            diskConfirmed = !source.fromBuffer,
            producer = source.producer,
        )
        val built by lazy(LazyThreadSafetyMode.NONE) { buildOrNull(ast, uri) }
        do {
            val state = entries[uri]
            val existing = state?.entry
            val decision = decision(state, publication, candidate)
            if (decision == Decision.REJECT) return
            // Compiler strategies and source lookups can re-enter publication. Run them outside the index locks.
            val retain = existing?.canRetain(decision, candidate) { producer, document ->
                currentProducer(uri, content, producer) && index.getDocument(uri) === document
            } == true
            val write = decision == Decision.WRITE && !retain
            val document = if (write) built else null
            val decided = decideIfUnchanged(publication, existing, candidate) { current ->
                when {
                    retain -> current.retain(
                        checkNotNull(existing),
                        index.getDocument(uri),
                        diskReceipt = candidate.takeIf { decision == Decision.REPLAY },
                    )
                    write -> {
                        val stored = store(uri, document, content)
                        current.entry = stored?.let { candidate.copy(stored = WeakReference(it)) }
                        true
                    }
                    else -> false
                }
            }
            if (decided) return
        } while (true)
    }

    /** Keeping a stronger document and installing a new one both settle this ticket against the exact entry. */
    private fun decideIfUnchanged(
        publication: Publication,
        existing: Entry?,
        candidate: Entry,
        update: (State) -> Boolean,
    ): Boolean = swap.read {
        var unchanged = true
        entries.computeIfPresent(publication.uri) { _, state ->
            unchanged = state.entry === existing
            if (unchanged && decision(state, publication, candidate) != Decision.REJECT && update(state)) {
                state.highestTicket = maxOf(state.highestTicket, publication.ticket)
            }
            state
        }
        unchanged
    }

    private fun decision(state: State?, publication: Publication, candidate: Entry): Decision {
        if (state == null || state !== publication.identity || !publication.isActiveIn(generation)) {
            return Decision.REJECT
        }
        if (candidate.diskConfirmed && isOpen(publication.uri)) return Decision.REJECT
        return state.decision(publication, candidate)
    }

    /**
     * Puts [built] in the index, or, when building failed, removes the stale document (no document beats
     * one that describes other content). Returns the immutable document installed by this invocation.
     */
    @Suppress("TooGenericExceptionCaught") // an index failure must not fail the compile that produced the AST
    private fun store(uri: URI, built: Built?, content: String): SemanticDocument? = try {
        if (built != null) {
            index.storeDocument(uri, built.document, content, built.implementations)
        } else {
            index.removeDocument(uri)
            null
        }
    } catch (e: Exception) {
        logger.warn(e) { "Failed to store the semantic document for $uri" }
        // Best effort: an index left holding the previous document would disagree with the empty entry.
        try {
            index.removeDocument(uri)
        } catch (removal: Exception) {
            logger.warn(removal) { "Failed to remove the semantic document for $uri" }
        }
        null
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
    operator fun contains(uri: URI): Boolean = entries[uri]?.entry != null

    /** The compile phase of the document stored for [uri], or null when there is none. */
    fun phaseOf(uri: URI): Int? = entries[uri]?.entry?.phase

    /** Kept entries from before a workspace retirement still serve legacy readers, but cannot qualify references. */
    internal fun currentReferencePublication(uri: URI): ReferencePublication? = swap.read {
        var result: ReferencePublication? = null
        // A publisher stores into the semantic DB before installing its entry. Serialize this metadata read
        // with that file's publication so a newly captured document cannot borrow the previous entry's proof.
        entries.computeIfPresent(uri) { _, state ->
            val entry = state.entry
            if (entry != null && entry.generation == generation) {
                result = entry.stored?.get()?.let { document ->
                    ReferencePublication(document, entry.phase, entry.diskConfirmed, entry.producer)
                }
            }
            state
        }
        result
    }

    /** Called under the ownership ticket taken before reading disk. Never confirms a kept, retired generation. */
    internal fun confirmDiskHash(uri: URI, hash: String, current: (ReferenceProducer) -> Boolean): Boolean = swap.read {
        var confirmed = false
        entries.computeIfPresent(uri) { _, state ->
            val entry = state.entry
            if (entry != null && entry.matchesDisk(hash, generation, current)) {
                if (!entry.diskConfirmed) state.entry = entry.copy(diskConfirmed = true)
                confirmed = true
            }
            state
        }
        confirmed
    }

    /** Whether the stored document was built from exactly [content]. */
    fun isCurrent(uri: URI, content: String): Boolean = entries[uri]?.entry?.contentHash == contentHash(content)

    /**
     * Makes every outstanding publication for [uri] stale while keeping its stored document: the buffer it
     * followed closed, or the closed file changed on disk, and a refresh from disk follows. A compile that
     * read the old text can then no longer publish after that refresh.
     */
    internal fun retirePublications(uri: URI) {
        swap.read {
            entries.computeIfPresent(uri) { _, state ->
                state.entry?.let { kept ->
                    State().also {
                        it.entry = kept.copy(diskConfirmed = false)
                        it.highestTicket = state.highestTicket
                    }
                }
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
        swap.write { generation = generation.next() }
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
            entries = ConcurrentHashMap()
            index.clear()
        }
    }
}

private fun buildSemanticDocument(ast: ModuleNode, uri: URI): SemanticDocument =
    SemanticDocumentBuilder(ast, uri).build()
