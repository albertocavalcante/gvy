package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.semantics.db.ImplementationFacts
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ModuleNode
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
 * - a document from disk never replaces the one built from an open editor buffer ([isOpen]).
 */
class SemanticIndexer(
    private val index: WorkspaceSymbolIndex,
    private val isOpen: (URI) -> Boolean = { false },
    private val build: (ModuleNode, URI) -> SemanticDocument = { ast, uri ->
        SemanticDocumentBuilder(ast, uri).build()
    },
) {
    private class Entry(val contentHash: String, val phase: Int)

    private class State {
        @Volatile var entry: Entry? = null
        var publications = 0
    }

    internal class Publication(val uri: URI, val identity: Any, private val finish: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        val isClosed: Boolean get() = closed.get()

        override fun close() {
            if (closed.compareAndSet(false, true)) finish()
        }
    }

    private val logger = KotlinLogging.logger {}
    private val entries = ConcurrentHashMap<URI, State>()

    /** Acquire before reading/enqueueing source. Removal invalidates every outstanding publication. */
    internal fun begin(uri: URI): Publication {
        val state = checkNotNull(
            entries.compute(uri) { _, existing ->
                (existing ?: State()).also { it.publications++ }
            },
        )
        return Publication(uri, state) {
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
        entries.computeIfPresent(uri) { _, state ->
            if (replaces(state, publication, hash, phase, fromBuffer)) {
                state.entry = if (built == null) {
                    // The stored document describes other content now: no document beats a stale one.
                    index.removeDocument(uri)
                    null
                } else {
                    index.updateDocument(
                        uri,
                        built.document,
                        sourceText = content,
                        implementations = built.implementations,
                    )
                    Entry(hash, phase)
                }
            }
            state
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
        val existing = state?.entry
        return state === publication.identity && !publication.isClosed &&
            (fromBuffer || !isOpen(publication.uri)) &&
            !(existing != null && existing.contentHash == hash && existing.phase >= phase)
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

    /** Whether the stored document was built from exactly [content]. */
    fun isCurrent(uri: URI, content: String): Boolean = entries[uri]?.entry?.contentHash == contentHash(content)

    fun remove(uri: URI) {
        entries.compute(uri) { _, _ ->
            index.removeDocument(uri)
            null
        }
    }

    fun clear() {
        entries.keys.toList().forEach(::remove)
    }
}
