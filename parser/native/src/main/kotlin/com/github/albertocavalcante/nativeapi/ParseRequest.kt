package com.github.albertocavalcante.nativeapi

import org.codehaus.groovy.control.Phases
import java.net.URI
import java.nio.file.Path

/**
 * Parsing mode controlling workspace source inclusion and caching behavior.
 *
 * @see <a href="https://github.com/albertocavalcante/gvy/issues/743">Issue #743</a>
 */
enum class ParseMode {
    /**
     * Minimal parsing without workspace sources.
     *
     * Use for on-demand compilation in navigation operations (go-to-definition, hover)
     * where cross-file resolution is handled by SemanticDB or symbol index.
     *
     * Benefits:
     * - Fast: no compilation of workspace sources
     * - Isolated: no interference from other files
     * - Deterministic: always produces same result for same input
     */
    MINIMAL,

    /**
     * Full workspace parsing with all workspace sources.
     *
     * Use for background indexing or full compilation where cross-file
     * type resolution is required at compile time.
     */
    WORKSPACE,
}

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
     * Default is [Phases.CONVERSION] for fault tolerance - this phase builds the AST
     * without resolving types, allowing parsing to succeed even when imports are unresolved.
     *
     * This can be used to analyze source structure that may be rewritten by later compiler phases or AST transforms.
     * Example: Spock feature blocks are represented as Groovy statement labels and can be best observed before
     * `SEMANTIC_ANALYSIS` transforms run.
     */
    val compilePhase: Int = Phases.CONVERSION,
    /**
     * Parse mode controlling workspace source inclusion.
     *
     * Default is [ParseMode.WORKSPACE] for backward compatibility.
     *
     * @see ParseMode
     */
    val parseMode: ParseMode = ParseMode.WORKSPACE,
) {
    val sourceUnitName: String = uri.path ?: uri.toString()
}
