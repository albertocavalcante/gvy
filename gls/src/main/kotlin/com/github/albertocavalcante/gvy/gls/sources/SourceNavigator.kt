// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.gls.documentation.Documentation
import java.net.URI

/**
 * Interface for navigating to source code from binary class references.
 *
 * This abstraction allows for:
 * - Easy mocking in tests
 * - Different implementations (JDK sources, Maven sources, etc.)
 * - Dependency injection following SOLID principles
 *
 * Implementors: [SourceNavigationService]
 */
interface SourceNavigator {
    /** Explicit user retry bypasses a short-lived source availability cache. */
    suspend fun retrySource(symbol: ResolvedExternalSymbol, context: SourceLookupContext): SourceLookupResult =
        locate(symbol, context)

    /**
     * Result of source navigation - either found source or binary fallback.
     */
    sealed class SourceResult {
        /**
         * Source code location found.
         *
         * @property uri URI pointing to the source file (file: scheme for extracted sources)
         * @property className Fully qualified class name
         * @property lineNumber Optional one-based line number where the selected declaration starts
         * @property documentation Optional documentation extracted from the source
         */
        data class SourceLocation(
            val uri: URI,
            val className: String,
            val lineNumber: Int? = null,
            val documentation: Documentation? = null,
            val definition: SourceDefinition? = null,
        ) : SourceResult()

        /**
         * No source available - binary reference only.
         *
         * @property uri URI of the binary class location
         * @property className Fully qualified class name
         * @property reason Human-readable explanation of why source wasn't found
         */
        data class BinaryOnly(val uri: URI, val className: String, val reason: String) : SourceResult()
    }

    /**
     * Legacy direct-URI lookup for a class found in the classpath.
     *
     * Handles JDK sources and source origins recognized as bundled. A raw archive URI does not carry the selected
     * artifact coordinates and digest, so it cannot authorize fetching a Maven source JAR. Use [locate] with the
     * compiler-selected [ResolvedExternalSymbol] and request context for verified dependency-source lookup.
     *
     * @param classpathUri URI of the class (jrt: or jar:file:...)
     * @param className Fully qualified class name (e.g., "java.util.ArrayList")
     * @return SourceResult indicating the source location or why it wasn't found; ordinary archive URIs without
     *     verified provenance return [SourceResult.BinaryOnly]
     */
    suspend fun navigateToSource(classpathUri: URI, className: String): SourceResult

    /** Transitional adapter for existing implementations; line-only results never manufacture a name selection. */
    suspend fun locate(symbol: ResolvedExternalSymbol, context: SourceLookupContext): SourceLookupResult {
        if (!context.isCurrent()) return SourceLookupResult.Stale
        val result = if (symbol.memberName == null) {
            navigateToSource(symbol.resourceUri(), symbol.binaryName)
        } else {
            navigateToMethodSource(symbol.resourceUri(), symbol.binaryName, symbol.memberName)
        }
        if (!context.validateCurrent()) return SourceLookupResult.Stale
        return when (result) {
            is SourceResult.SourceLocation -> result.definition?.let { definition ->
                SourceLookupResult.Located(result.uri, symbol.binaryName, definition)
            } ?: SourceLookupResult.FileOnly(result.uri, symbol.binaryName, MissingSourceReason.DECLARATION_NOT_LOCATED)
            is SourceResult.BinaryOnly -> SourceLookupResult.Unavailable(MissingSourceReason.NO_ATTACHMENT)
        }
    }

    suspend fun attachSource(
        symbol: ResolvedExternalSymbol,
        context: SourceLookupContext,
        sourceJar: java.nio.file.Path,
        expectedSha256: String? = null,
    ): SourceLookupResult = SourceLookupResult.Unavailable(MissingSourceReason.UNKNOWN_ORIGIN)

    /**
     * Legacy direct-URI lookup for a specific method within a class.
     *
     * This method first resolves a JDK or bundled source, then inspects it for the method declaration. As with
     * [navigateToSource], a raw archive URI cannot authorize Maven source acquisition; use [locate] with the
     * compiler-selected symbol and request context for verified dependency sources.
     *
     * A name alone cannot select an overload; implementations must leave ambiguous members unresolved.
     *
     * @param classpathUri URI of the class (jrt: or jar:file:...)
     * @param className Fully qualified class name (e.g., "java.util.ArrayList")
     * @param methodName Simple method name (e.g., "add")
     * @return SourceResult indicating the method location or why it wasn't found
     */
    suspend fun navigateToMethodSource(classpathUri: URI, className: String, methodName: String): SourceResult
}

internal fun ResolvedExternalSymbol.resourceUri(): URI {
    val entry = binaryName.replace('.', '/') + ".class"
    return when (val selected = origin) {
        is SourceOrigin.Archive -> URI.create("jar:" + selected.binaryJar.toUri() + "!/" + entry)
        is SourceOrigin.Bundled -> URI.create("jar:" + selected.binaryContainer + "!/" + entry)
        is SourceOrigin.Jdk -> URI.create("jrt:/" + selected.module + "/" + entry)
        is SourceOrigin.Unknown -> selected.resource
    }
}
