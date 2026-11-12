// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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
    /**
     * Unsaved editor buffers of other `.groovy` files, keyed by URI, compiled together with this
     * file so it can reference classes they declare. A buffer replaces the on-disk copy of the same
     * file in [workspaceSources]. Ignored in [ParseMode.MINIMAL], like [workspaceSources].
     */
    val openSources: Map<URI, String> = emptyMap(),
    /**
     * Source files left out of this compile because they failed it. They are not compiled, and the
     * class loader does not load them from [sourceRoots] either, so a class they declare is simply
     * unresolved instead of failing the whole compilation unit again.
     */
    val excludedSources: Set<Path> = emptySet(),
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
    /**
     * Cooperative cancellation hook, invoked before compilation starts and after every compiler phase.
     *
     * It must throw (typically a `CancellationException`) when the caller no longer wants the result;
     * the exception propagates out of the parse and abandons the rest of the compile. The default does
     * nothing. Note: as a function it takes part in this data class's `equals`/`hashCode`, and nothing
     * in the codebase keys a cache on `ParseRequest` equality.
     */
    val checkCancelled: () -> Unit = {},
    /**
     * Files declaring the top-level class with a given fully qualified name, from the workspace's class index
     * (#1425). Consulted only when Groovy's own source lookup finds no file named after the class, so a
     * [ParseMode.MINIMAL] compile still resolves `Outer2` declared in `Outers.groovy`. The resolver asks for
     * exact candidate names (`pkg.Outer2`, an import, a star-import package), and only a file declaring that
     * exact name is added, read when the compile reaches it. Null: no lookup beyond file names.
     */
    val workspaceClassLocator: ((fullyQualifiedName: String) -> List<Path>)? = null,
) {
    val sourceUnitName: String = uri.path ?: uri.toString()
}
