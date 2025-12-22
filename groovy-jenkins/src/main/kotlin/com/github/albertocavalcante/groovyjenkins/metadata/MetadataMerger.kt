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

    /**
     * Merge bundled metadata with enrichment metadata to create MergedJenkinsMetadata.
     *
     * This combines machine-extracted metadata (bundled) with human-curated enrichment
     * to provide the best available information for LSP features.
     *
     * Priority:
     * - Extracted data (types, parameters, scopes) from bundled
     * - Enrichment overlay (descriptions, examples, valid values, categories)
     *
     * @param bundled The base bundled metadata (extracted, deterministic)
     * @param enrichment The enrichment metadata (curated, high-quality)
     * @return Merged metadata ready for use by LSP features
     */
    fun mergeWithEnrichment(
        bundled: BundledJenkinsMetadata,
        enrichment: com.github.albertocavalcante.groovyjenkins.metadata.enrichment.JenkinsEnrichment,
    ): MergedJenkinsMetadata {
        // Merge steps
        val mergedSteps = bundled.steps.mapValues { (stepName, bundledStep) ->
            val stepEnrichment = enrichment.steps[stepName]

            // Merge parameters
            val mergedParams = bundledStep.parameters.mapValues { (paramName, bundledParam) ->
                val paramEnrichment = stepEnrichment?.parameterEnrichment?.get(paramName)

                MergedParameter(
                    name = paramName,
                    type = bundledParam.type,
                    defaultValue = bundledParam.default,
                    description = paramEnrichment?.description ?: bundledParam.documentation,
                    required = paramEnrichment?.required ?: bundledParam.required,
                    validValues = paramEnrichment?.validValues,
                    examples = paramEnrichment?.examples ?: emptyList(),
                )
            }

            MergedStepMetadata(
                name = stepName,
                scope = com.github.albertocavalcante.groovyjenkins.metadata.extracted.StepScope.GLOBAL,
                positionalParams = emptyList(),
                namedParams = mergedParams,
                extractedDocumentation = bundledStep.documentation,
                returnType = null,
                plugin = stepEnrichment?.plugin ?: bundledStep.plugin,
                enrichedDescription = stepEnrichment?.description,
                documentationUrl = stepEnrichment?.documentationUrl,
                category = stepEnrichment?.category,
                examples = stepEnrichment?.examples ?: emptyList(),
                deprecation = stepEnrichment?.deprecation,
            )
        }

        // Merge global variables
        val mergedGlobalVars = bundled.globalVariables.mapValues { (varName, bundledVar) ->
            val varEnrichment = enrichment.globalVariables[varName]

            MergedGlobalVariable(
                name = varName,
                type = bundledVar.type,
                extractedDocumentation = bundledVar.documentation,
                enrichedDescription = varEnrichment?.description,
                documentationUrl = varEnrichment?.documentationUrl,
                properties = varEnrichment?.properties ?: emptyMap(),
            )
        }

        return MergedJenkinsMetadata(
            jenkinsVersion = "2.426.3", // TODO: Get from bundled metadata
            steps = mergedSteps,
            globalVariables = mergedGlobalVars,
            sections = enrichment.sections,
            directives = enrichment.directives,
        )
    }
}
