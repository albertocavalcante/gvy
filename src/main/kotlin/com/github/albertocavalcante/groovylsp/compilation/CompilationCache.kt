package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovyparser.api.ParseResult
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class CompilationCache {
    private val cache = ConcurrentHashMap<URI, Pair<String, ParseResult>>()

    fun get(uri: URI, content: String): ParseResult? {
        val (cachedContent, parseResult) = cache[uri] ?: return null
        return if (cachedContent == content) parseResult else null
    }

    fun get(uri: URI): ParseResult? = cache[uri]?.second

    fun put(uri: URI, content: String, parseResult: ParseResult) {
        cache[uri] = content to parseResult
    }

    fun invalidate(uri: URI) {
        cache.remove(uri)
    }

    fun clear() {
        cache.clear()
    }

    fun keys(): Set<URI> = cache.keys

    fun getStatistics() = mapOf(
        "cachedResults" to cache.size,
    )
}
