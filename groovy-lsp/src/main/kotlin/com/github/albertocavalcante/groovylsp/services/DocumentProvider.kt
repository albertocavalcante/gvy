package com.github.albertocavalcante.groovylsp.services

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class DocumentProvider {
    private val documents = ConcurrentHashMap<URI, String>()

    fun get(uri: URI): String? = documents[uri]

    fun put(uri: URI, content: String) {
        documents[uri] = content
    }

    fun remove(uri: URI) {
        documents.remove(uri)
    }

    fun snapshot(): Map<URI, String> = documents.toMap()
}
