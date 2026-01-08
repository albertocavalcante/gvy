package com.github.albertocavalcante.groovylsp.project

import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import kotlinx.coroutines.Job
import java.nio.file.Path

/**
 * Default fallback strategy for workspaces without a specific project type.
 *
 * This strategy has the lowest possible priority ([Int.MIN_VALUE]) and always returns
 * `true` from [canHandle], serving as a catch-all for any workspace. It provides no
 * additional source roots or classpath entries.
 *
 * ## Purpose
 * Ensures that every workspace has at least one active strategy, preventing empty
 * strategy lists and providing a consistent baseline behavior.
 *
 * ## Typical Usage
 * This strategy is automatically included in the default [ProjectStrategyRegistry]:
 * ```kotlin
 * val registry = ProjectStrategyRegistry(
 *     listOf(
 *         JenkinsProjectStrategy(scope),
 *         DefaultProjectStrategy(),  // Always last due to lowest priority
 *     )
 * )
 * ```
 */
class DefaultProjectStrategy : ProjectStrategy {
    override val id: String = "default"

    override val displayName: String = "Default Groovy Project"

    override val priority: Int = Int.MIN_VALUE // Lowest priority - fallback

    override fun canHandle(workspaceRoot: Path, config: ServerConfiguration): Boolean = true

    override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null

    // No additional source roots or classpath entries
}
