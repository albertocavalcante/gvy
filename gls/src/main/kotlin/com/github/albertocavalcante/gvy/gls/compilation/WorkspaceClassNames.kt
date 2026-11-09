package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import java.net.URI
import java.util.Locale
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
    private val files = ConcurrentSkipListMap<String, MutableSet<URI>>()
    private val lock = Any()

    /** Runs [write], which returns the classes [uri] declared before, and records [next] in their place. */
    fun replace(uri: URI, next: List<Symbol.Class>, write: () -> List<Symbol.Class>?) = synchronized(lock) {
        write().orEmpty().forEach { previous ->
            files.computeIfPresent(key(previous.name)) { _, uris ->
                uris.remove(uri)
                uris.takeIf { it.isNotEmpty() }
            }
        }
        next.forEach { files.computeIfAbsent(key(it.name)) { ConcurrentHashMap.newKeySet() }.add(uri) }
    }

    fun clear(write: () -> Unit) = synchronized(lock) {
        write()
        files.clear()
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

    private fun key(name: String): String = name.lowercase(Locale.ROOT)
}
