package com.github.groovylsp.bsp.resolver

import java.nio.file.Path

/**
 * Base interface for resolving project classpaths in a BSP context.
 *
 * Implementations can provide classpath resolution from various sources:
 * - BSP build servers (Gradle, Maven, Bazel, etc.)
 * - Fallback resolvers (parsing build files directly)
 * - Cached resolvers (version-tracked caching)
 *
 * The interface supports functional composition through extension functions:
 * - `or` for cascading fallback (use first non-empty result)
 * - `+` for union (combine all results)
 *
 * Example usage:
 * ```kotlin
 * val resolver = BspResolver(workspace)
 *     .or(GradleResolver(workspace))
 *     .or(BackupResolver())
 * ```
 */
interface ClassPathResolver {
    /**
     * The main classpath entries for compilation and runtime.
     */
    val classpath: Set<ClassPathEntry>

    /**
     * Classpath entries specific to build scripts (e.g., Gradle buildSrc, Maven plugins).
     */
    val buildScriptClasspath: Set<Path>

    /**
     * Version number of the current build file, used for cache invalidation.
     * Increment this when the build file changes to trigger re-resolution.
     */
    val currentBuildFileVersion: Long
}

/**
 * Creates a fallback resolver that uses [other] if this resolver returns empty results.
 *
 * This implements the "first non-empty" strategy: if this resolver provides any
 * classpath entries, they are used; otherwise, [other] is consulted.
 *
 * @param other The fallback resolver to use if this resolver returns empty results
 * @return A new resolver that cascades to [other] on empty results
 */
infix fun ClassPathResolver.or(other: ClassPathResolver): ClassPathResolver = FirstNonEmptyResolver(this, other)

/**
 * Creates a union resolver that combines results from both this and [other].
 *
 * All classpath entries from both resolvers are merged into a single set.
 * The build file version is taken as the maximum of both versions.
 *
 * @param other The resolver whose results should be merged with this one
 * @return A new resolver containing the union of both resolvers' results
 */
operator fun ClassPathResolver.plus(other: ClassPathResolver): ClassPathResolver = UnionResolver(this, other)

/**
 * Internal implementation of the "first non-empty" fallback strategy.
 *
 * @property primary The primary resolver to try first
 * @property fallback The fallback resolver to use if primary returns empty results
 */
private class FirstNonEmptyResolver(private val primary: ClassPathResolver, private val fallback: ClassPathResolver) :
    ClassPathResolver {
    override val classpath: Set<ClassPathEntry>
        get() = primary.classpath.ifEmpty { fallback.classpath }

    override val buildScriptClasspath: Set<Path>
        get() = primary.buildScriptClasspath.ifEmpty { fallback.buildScriptClasspath }

    override val currentBuildFileVersion: Long
        get() = if (classpath.isNotEmpty() || buildScriptClasspath.isNotEmpty()) {
            primary.currentBuildFileVersion
        } else {
            fallback.currentBuildFileVersion
        }
}

/**
 * Internal implementation of the union strategy.
 *
 * @property first The first resolver
 * @property second The second resolver
 */
private class UnionResolver(private val first: ClassPathResolver, private val second: ClassPathResolver) :
    ClassPathResolver {
    override val classpath: Set<ClassPathEntry>
        get() = first.classpath + second.classpath

    override val buildScriptClasspath: Set<Path>
        get() = first.buildScriptClasspath + second.buildScriptClasspath

    override val currentBuildFileVersion: Long
        get() = maxOf(first.currentBuildFileVersion, second.currentBuildFileVersion)
}
