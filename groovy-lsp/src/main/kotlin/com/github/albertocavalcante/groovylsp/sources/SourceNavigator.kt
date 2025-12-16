package com.github.albertocavalcante.groovylsp.sources

import com.github.albertocavalcante.groovylsp.documentation.Documentation
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

    /**
     * Result of source navigation - either found source or binary fallback.
     */
    sealed class SourceResult {
        /**
         * Source code location found.
         *
         * @property uri URI pointing to the source file (file: scheme for extracted sources)
         * @property className Fully qualified class name
         * @property lineNumber Optional line number where the class declaration starts
         * @property documentation Optional documentation extracted from the source
         */
        data class SourceLocation(
            val uri: URI,
            val className: String,
            val lineNumber: Int? = null,
            val documentation: Documentation? = null,
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
     * Navigate to source code for a class found in the classpath.
     *
     * Handles various source locations:
     * - jrt: URIs (JDK classes) -> extracts from $JAVA_HOME/lib/src.zip
     * - jar: URIs (Maven dependencies) -> downloads source JAR from Maven
     *
     * @param classpathUri URI of the class (jrt: or jar:file:...)
     * @param className Fully qualified class name (e.g., "java.util.ArrayList")
     * @return SourceResult indicating the source location or why it wasn't found
     */
    suspend fun navigateToSource(classpathUri: URI, className: String): SourceResult
}
