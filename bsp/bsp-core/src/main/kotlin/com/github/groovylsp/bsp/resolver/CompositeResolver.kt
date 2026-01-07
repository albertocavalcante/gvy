package com.github.groovylsp.bsp.resolver

/**
 * Helper functions for building composite resolver chains.
 *
 * These utilities simplify common resolver composition patterns, making it easier
 * to construct fallback chains and unions of multiple resolvers.
 */

/**
 * Creates a cached version of this resolver.
 *
 * The cache is automatically invalidated when [ClassPathResolver.currentBuildFileVersion]
 * changes, ensuring fresh results after build file modifications.
 *
 * @return A cached wrapper around this resolver
 */
fun ClassPathResolver.cached(): ClassPathResolver = CachedResolver(this)

/**
 * Creates a fallback chain from a list of resolvers.
 *
 * The resolvers are tried in order, and the first one that returns non-empty
 * results is used. This is equivalent to chaining multiple `or` operations.
 *
 * Example:
 * ```kotlin
 * val resolver = firstNonEmpty(
 *     BspResolver(workspace),
 *     GradleResolver(workspace),
 *     MavenResolver(workspace),
 *     EmptyResolver()
 * )
 * ```
 *
 * @param resolvers The resolvers to try in order
 * @return A composite resolver that uses the first non-empty result
 * @throws IllegalArgumentException if the list is empty
 */
fun firstNonEmpty(vararg resolvers: ClassPathResolver): ClassPathResolver {
    require(resolvers.isNotEmpty()) { "At least one resolver is required" }
    return resolvers.reduce { acc, resolver -> acc or resolver }
}

/**
 * Creates a union of all provided resolvers.
 *
 * All classpath entries from all resolvers are merged into a single set.
 * This is equivalent to chaining multiple `+` operations.
 *
 * Example:
 * ```kotlin
 * val resolver = union(
 *     BspResolver(workspace),
 *     LocalCacheResolver(),
 *     SystemLibrariesResolver()
 * )
 * ```
 *
 * @param resolvers The resolvers whose results should be merged
 * @return A composite resolver containing the union of all results
 * @throws IllegalArgumentException if the list is empty
 */
fun union(vararg resolvers: ClassPathResolver): ClassPathResolver {
    require(resolvers.isNotEmpty()) { "At least one resolver is required" }
    return resolvers.reduce { acc, resolver -> acc + resolver }
}

/**
 * An empty resolver that returns no classpath entries.
 *
 * Useful as a terminal fallback in resolver chains or for testing.
 *
 * Example:
 * ```kotlin
 * val resolver = BspResolver(workspace)
 *     .or(GradleResolver(workspace))
 *     .or(EmptyResolver())
 * ```
 */
class EmptyResolver : ClassPathResolver {
    override val classpath: Set<ClassPathEntry> = emptySet()
    override val buildScriptClasspath: Set<java.nio.file.Path> = emptySet()
    override val currentBuildFileVersion: Long = 0L
}
