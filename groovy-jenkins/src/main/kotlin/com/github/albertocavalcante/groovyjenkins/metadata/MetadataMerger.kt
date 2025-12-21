package com.github.albertocavalcante.groovyjenkins.metadata

/**
 * Merges multiple metadata sources with priority ordering.
 *
 * Priority order (highest to lowest):
 * 1. User overrides - custom metadata for internal plugins
 * 2. Dynamic classpath scan - user's actual plugin JARs
 * 3. Stable step definitions - hardcoded core steps
 * 4. Versioned metadata - per-LTS-version metadata
 * 5. Bundled metadata - fallback default metadata
 *
 * This allows accurate IntelliSense by preferring the most specific/accurate
 * source of metadata available.
 */
object MetadataMerger {

    /**
     * Merge bundled metadata with stable step definitions.
     *
     * Stable steps take priority over bundled steps (more accurate/complete).
     * Other metadata types (globalVariables, postConditions, etc.) are preserved from bundled.
     *
     * @param bundled The base bundled metadata
     * @param stable Map of stable step definitions (typically from StableStepDefinitions.all())
     * @return Merged metadata with stable steps overriding bundled
     */
    fun merge(bundled: BundledJenkinsMetadata, stable: Map<String, JenkinsStepMetadata>): BundledJenkinsMetadata {
        // Start with bundled steps, then overlay stable steps
        val mergedSteps = bundled.steps.toMutableMap()
        mergedSteps.putAll(stable)

        return bundled.copy(steps = mergedSteps)
    }

    /**
     * Merge with full priority ordering including user overrides.
     *
     * Priority (highest first):
     * 1. User overrides
     * 2. Stable definitions
     * 3. Bundled metadata
     *
     * @param bundled The base bundled metadata
     * @param stable Map of stable step definitions
     * @param userOverrides User-provided step overrides (highest priority)
     * @return Merged metadata with proper priority
     */
    fun mergeWithPriority(
        bundled: BundledJenkinsMetadata,
        stable: Map<String, JenkinsStepMetadata>,
        userOverrides: Map<String, JenkinsStepMetadata> = emptyMap(),
    ): BundledJenkinsMetadata {
        // Start with bundled, overlay stable, then user overrides
        val mergedSteps = bundled.steps.toMutableMap()
        mergedSteps.putAll(stable)
        mergedSteps.putAll(userOverrides)

        return bundled.copy(steps = mergedSteps)
    }

    /**
     * Merge multiple metadata sources with full priority chain.
     *
     * Priority (highest first):
     * 1. User overrides
     * 2. Dynamic classpath scan
     * 3. Stable definitions
     * 4. Versioned metadata
     * 5. Bundled metadata
     *
     * @param bundled The base bundled metadata
     * @param versioned Version-specific metadata (optional)
     * @param stable Map of stable step definitions
     * @param dynamic Dynamically scanned metadata from classpath (optional)
     * @param userOverrides User-provided step overrides (optional)
     * @return Fully merged metadata
     */
    fun mergeAll(
        bundled: BundledJenkinsMetadata,
        versioned: BundledJenkinsMetadata? = null,
        stable: Map<String, JenkinsStepMetadata>,
        dynamic: Map<String, JenkinsStepMetadata>? = null,
        userOverrides: Map<String, JenkinsStepMetadata> = emptyMap(),
    ): BundledJenkinsMetadata {
        // Build up from lowest to highest priority
        val mergedSteps = bundled.steps.toMutableMap()

        // Add versioned metadata
        if (versioned != null) {
            mergedSteps.putAll(versioned.steps)
        }

        // Add stable definitions
        mergedSteps.putAll(stable)

        // Add dynamic classpath scan results
        if (dynamic != null) {
            mergedSteps.putAll(dynamic)
        }

        // Add user overrides (highest priority)
        mergedSteps.putAll(userOverrides)

        // Merge other metadata types from versioned if available
        val mergedGlobalVars = bundled.globalVariables.toMutableMap()
        val mergedPostConditions = bundled.postConditions.toMutableMap()
        val mergedDeclarativeOptions = bundled.declarativeOptions.toMutableMap()
        val mergedAgentTypes = bundled.agentTypes.toMutableMap()

        if (versioned != null) {
            mergedGlobalVars.putAll(versioned.globalVariables)
            mergedPostConditions.putAll(versioned.postConditions)
            mergedDeclarativeOptions.putAll(versioned.declarativeOptions)
            mergedAgentTypes.putAll(versioned.agentTypes)
        }

        return BundledJenkinsMetadata(
            steps = mergedSteps,
            globalVariables = mergedGlobalVars,
            postConditions = mergedPostConditions,
            declarativeOptions = mergedDeclarativeOptions,
            agentTypes = mergedAgentTypes,
        )
    }

    /**
     * Merge step parameters from multiple sources.
     *
     * Used when you want to combine parameters from different definitions
     * rather than replacing entirely.
     *
     * @param base The base step metadata
     * @param overlay The overlay step metadata (parameters added/overwritten)
     * @return Step metadata with merged parameters
     */
    fun mergeStepParameters(base: JenkinsStepMetadata, overlay: JenkinsStepMetadata): JenkinsStepMetadata {
        val mergedParams = base.parameters.toMutableMap()
        mergedParams.putAll(overlay.parameters)

        return base.copy(
            parameters = mergedParams,
            // Prefer overlay's documentation if available
            documentation = overlay.documentation ?: base.documentation,
        )
    }
}
