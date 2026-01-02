package com.github.albertocavalcante.groovyparser.api

import org.codehaus.groovy.control.Phases
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
    /**
     * Groovy compilation phase to compile to.
     *
     * Default is [Phases.CANONICALIZATION] to preserve current behavior.
     *
     * This can be used to analyze source structure that may be rewritten by later compiler phases or AST transforms.
     * Example: Spock feature blocks are represented as Groovy statement labels and can be best observed before
     * `SEMANTIC_ANALYSIS` transforms run.
     */
    val compilePhase: Int = Phases.CANONICALIZATION,
) {
    val sourceUnitName: String = uri.path ?: uri.toString()
}
