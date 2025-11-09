package com.github.albertocavalcante.groovyparser.api

import java.net.URI
import java.nio.file.Path

/**
 * Input required to parse a Groovy document.
 */
data class ParseRequest(
    val uri: URI,
    val content: String,
    val classpath: List<Path> = emptyList(),
    val sourceRoots: List<Path> = emptyList(),
    val workspaceSources: List<Path> = emptyList(),
    val locatorCandidates: Set<String> = emptySet(),
) {
    val sourceUnitName: String = uri.path ?: uri.toString()
}
