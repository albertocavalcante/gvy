package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ModuleNode
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

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

    private val logger = KotlinLogging.logger {}
    private val entries = ConcurrentHashMap<URI, Entry>()

    /**
     * Stores the semantic document of [ast], compiled from [content] to [phase] (the phase the compile
     * reached; [fromBuffer]: the text of an editor buffer rather than the file on disk).
     */
    @Suppress("TooGenericExceptionCaught") // a failing builder must not fail the compile that produced the AST
    fun update(uri: URI, content: String, ast: ModuleNode, phase: Int, fromBuffer: Boolean = true) {
        val hash = contentHash(content)
        entries.compute(uri) { _, existing ->
            when {
                !fromBuffer && isOpen(uri) -> existing
                existing != null && existing.contentHash == hash && existing.phase >= phase -> existing
                else -> try {
                    index.updateDocument(uri, build(ast, uri), sourceText = content)
                    Entry(hash, phase)
                } catch (e: Exception) {
                    // The stored document describes other content now: no document beats a stale one.
                    logger.warn(e) { "Failed to build semantic document for $uri; dropped its stale one" }
                    index.removeDocument(uri)
                    null
                }
            }
        }
    }

    /** Whether [uri] follows an open editor buffer, which a document from disk must not replace. */
    fun followsBuffer(uri: URI): Boolean = isOpen(uri)

    /** Whether a document is stored for [uri]. */
    operator fun contains(uri: URI): Boolean = entries.containsKey(uri)

    /** Whether the stored document was built from exactly [content]. */
    fun isCurrent(uri: URI, content: String): Boolean = entries[uri]?.contentHash == contentHash(content)

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
