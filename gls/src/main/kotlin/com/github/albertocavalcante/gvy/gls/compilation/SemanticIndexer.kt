package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
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
 * Each file keeps the content hash and resolution quality of its document, so:
 * - a document built from different content replaces the stored one (stale is worse than degraded);
 * - a degraded parse (types unresolved) of content that already has a resolved document is ignored;
 * - a document from disk never replaces the one built from an open editor buffer ([isOpen]).
 */
class SemanticIndexer(private val index: WorkspaceSymbolIndex, private val isOpen: (URI) -> Boolean = { false }) {
    private class Entry(val contentHash: String, val resolved: Boolean)

    private val logger = KotlinLogging.logger {}
    private val entries = ConcurrentHashMap<URI, Entry>()

    /**
     * Stores the semantic document of [ast], compiled from [content] ([fromBuffer]: the text of an editor
     * buffer rather than the file on disk).
     */
    @Suppress("TooGenericExceptionCaught") // a failing builder must not fail the compile that produced the AST
    fun update(uri: URI, content: String, ast: ModuleNode, typesUnresolved: Boolean, fromBuffer: Boolean = true) {
        val hash = contentHash(content)
        entries.compute(uri) { _, existing ->
            when {
                !fromBuffer && isOpen(uri) -> existing
                existing != null && existing.contentHash == hash && (existing.resolved || typesUnresolved) -> existing
                else -> try {
                    index.updateDocument(uri, SemanticDocumentBuilder(ast, uri).build())
                    Entry(hash, resolved = !typesUnresolved)
                } catch (e: Exception) {
                    logger.warn { "Failed to build semantic document for $uri: ${e.message}" }
                    existing
                }
            }
        }
    }

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
