package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/** A workspace class lookup by simple name: exact (case-sensitive) or by prefix (case-insensitive). */
sealed interface ClassNameQuery {
    fun matches(symbol: Symbol.Class): Boolean

    data class Exact(val simpleName: String) : ClassNameQuery {
        override fun matches(symbol: Symbol.Class): Boolean = symbol.name == simpleName
    }

    data class Prefix(val prefix: String) : ClassNameQuery {
        override fun matches(symbol: Symbol.Class): Boolean = symbol.name.startsWith(prefix, ignoreCase = true)
    }
}

/**
 * Lower-cased simple class name -> files declaring it, for [SymbolIndexingService]. A candidate set only:
 * callers re-check each file's current classes with [ClassNameQuery.matches].
 *
 * Thread-safe. The owner applies every change to its entries inside [replace] or [clear], so names and
 * entries change together under one lock.
 */
internal class WorkspaceClassNames {
    // Replaced by [clear] under [lock]; [replace] re-reads it under the same lock, so no write lands in a dead map
    // after a clear. Lock-free readers see the old or the new map (#1342).
    @Volatile private var files = ConcurrentSkipListMap<String, MutableSet<URI>>()
    private val lock = Any()

    /**
     * Runs [write], which returns the classes [uri] declared before, and records [next] in their place.
     * Readers do not lock: names [uri] keeps are never touched, new names are added before [write] and
     * dropped names removed after it, so a re-install never hides a class it still declares.
     */
    fun replace(uri: URI, next: List<Symbol.Class>, write: () -> List<Symbol.Class>?) = synchronized(lock) {
        val added = next.mapTo(mutableSetOf()) { key(it.name) }
        added.forEach { files.computeIfAbsent(it) { ConcurrentHashMap.newKeySet() }.add(uri) }
        val removed = write().orEmpty().mapTo(mutableSetOf()) { key(it.name) } - added
        removed.forEach { name ->
            files.computeIfPresent(name) { _, uris ->
                uris.remove(uri)
                uris.takeIf { it.isNotEmpty() }
            }
        }
    }

    /** Runs [write] (the owner's own swap) and swaps in an empty name map, both under the lock: O(1) (#1342). */
    fun clear(write: () -> Unit) = synchronized(lock) {
        write()
        files = ConcurrentSkipListMap()
    }

    fun files(query: ClassNameQuery): Set<URI> = when (query) {
        is ClassNameQuery.Exact -> files[key(query.simpleName)]?.toSet().orEmpty()
        is ClassNameQuery.Prefix -> {
            val prefix = key(query.prefix)
            files.tailMap(prefix).entries.asSequence()
                .takeWhile { it.key.startsWith(prefix) }
                .flatMapTo(mutableSetOf()) { it.value }
        }
    }

    /** Every name key currently recorded; for tests that prove removals leave nothing behind. */
    fun keys(): Set<String> = files.keys.toSet()

    // Folded per character exactly like `startsWith(ignoreCase = true)`, so `İnfo` matches prefix "in".
    private fun key(name: String): String = buildString(name.length) {
        name.forEach { append(it.uppercaseChar().lowercaseChar()) }
    }
}
