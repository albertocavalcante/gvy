package com.github.albertocavalcante.groovyjenkins.metadata

import com.github.albertocavalcante.groovyjenkins.metadata.enrichment.JenkinsEnrichment
import com.github.albertocavalcante.groovyjenkins.metadata.extracted.StepScope

/**
 * Merges multiple metadata sources with priority ordering using functional patterns.
 *
 * Implements Semigroup behavior where the "Right" side generally overrides the "Left" side,
 * preventing imperative mutation and ensuring referential transparency.
 */
object MetadataMerger {

    // Functional interface for Semigroup pattern (Arrow 2.x removed strict Semigroup interface)
    fun interface Semigroup<A> {
        fun combine(a: A, b: A): A

        // Helper to allow scoping like: semigroup.run { a.combine(b) }
        fun <R> run(block: Semigroup<A>.() -> R): R = block()
    }

    // Define Semigroups for our types
    private val stepMetadataSemigroup: Semigroup<JenkinsStepMetadata> = Semigroup { a, b ->
        // b overrides a, but we merge parameters
        a.copy(
            plugin = b.plugin,
            documentation = b.documentation ?: a.documentation,
            parameters = a.parameters + b.parameters, // Right overrides Left keys
        )
    }

    private val bundledMetadataSemigroup: Semigroup<BundledJenkinsMetadata> = Semigroup { a, b ->
        // Deep merge steps:
        // 1. Keys in both: combine values
        // 2. Keys only in a: keep a
        // 3. Keys only in b: keep b
        val combinedSteps = (a.steps.keys + b.steps.keys).associateWith { key ->
            val stepA = a.steps[key]
            val stepB = b.steps[key]
            if (stepA != null && stepB != null) {
                stepMetadataSemigroup.run { combine(stepA, stepB) }
            } else {
                stepB ?: stepA!!
            }
        }

        BundledJenkinsMetadata(
            // Prefer version from b (right side) if available, else keep a's version
            jenkinsVersion = b.jenkinsVersion ?: a.jenkinsVersion,
            steps = sortByKey(combinedSteps),
            globalVariables = mergeAndSort(a.globalVariables, b.globalVariables),
            postConditions = mergeAndSort(a.postConditions, b.postConditions),
            declarativeOptions = mergeAndSort(a.declarativeOptions, b.declarativeOptions),
            agentTypes = mergeAndSort(a.agentTypes, b.agentTypes),
        )
    }

    /**
     * Merge stable steps into bundled metadata.
     */
    fun merge(bundled: BundledJenkinsMetadata, stable: Map<String, JenkinsStepMetadata>): BundledJenkinsMetadata {
        val stableMetadata =
            BundledJenkinsMetadata(
                steps = stable,
                globalVariables = emptyMap(),
                postConditions = emptyMap(),
                declarativeOptions = emptyMap(),
                agentTypes = emptyMap(),
            )
        return bundledMetadataSemigroup.run { combine(bundled, stableMetadata) }
    }

    /**
     * Merge with full priority ordering.
     */
    fun mergeWithPriority(
        bundled: BundledJenkinsMetadata,
        stable: Map<String, JenkinsStepMetadata>,
        userOverrides: Map<String, JenkinsStepMetadata> = emptyMap(),
    ): BundledJenkinsMetadata {
        val stableMetadata =
            BundledJenkinsMetadata(
                steps = stable,
                globalVariables = emptyMap(),
                postConditions = emptyMap(),
                declarativeOptions = emptyMap(),
                agentTypes = emptyMap(),
            )
        val userMetadata =
            BundledJenkinsMetadata(
                steps = userOverrides,
                globalVariables = emptyMap(),
                postConditions = emptyMap(),
                declarativeOptions = emptyMap(),
                agentTypes = emptyMap(),
            )

        return bundledMetadataSemigroup.run {
            combineAll(bundled, listOf(stableMetadata, userMetadata), this)
        }
    }

    /**
     * Merge all sources.
     */
    fun mergeAll(
        bundled: BundledJenkinsMetadata,
        versioned: BundledJenkinsMetadata? = null,
        stable: Map<String, JenkinsStepMetadata>,
        dynamic: Map<String, JenkinsStepMetadata>? = null,
        userOverrides: Map<String, JenkinsStepMetadata> = emptyMap(),
    ): BundledJenkinsMetadata {
        val sources = listOfNotNull(
            bundled,
            versioned,
            BundledJenkinsMetadata(
                steps = stable,
                globalVariables = emptyMap(),
                postConditions = emptyMap(),
                declarativeOptions = emptyMap(),
                agentTypes = emptyMap(),
            ),
            dynamic?.let {
                BundledJenkinsMetadata(
                    steps = it,
                    globalVariables = emptyMap(),
                    postConditions = emptyMap(),
                    declarativeOptions = emptyMap(),
                    agentTypes = emptyMap(),
                )
            },
            BundledJenkinsMetadata(
                steps = userOverrides,
                globalVariables = emptyMap(),
                postConditions = emptyMap(),
                declarativeOptions = emptyMap(),
                agentTypes = emptyMap(),
            ),
        )

        return bundledMetadataSemigroup.run {
            combineAll(sources.first(), sources.drop(1), this)
        }
    }

    /**
     * Merge step parameters.
     */
    fun mergeStepParameters(base: JenkinsStepMetadata, overlay: JenkinsStepMetadata): JenkinsStepMetadata =
        stepMetadataSemigroup.run { combine(base, overlay) }

    private fun <V> mergeAndSort(left: Map<String, V>, right: Map<String, V>): Map<String, V> = sortByKey(left + right)

    private fun <V> sortByKey(values: Map<String, V>): Map<String, V> =
        values.entries.sortedBy { it.key }.associate { it.toPair() }

    private fun <A> combineAll(seed: A, rest: List<A>, semigroup: Semigroup<A>): A = rest.fold(seed, semigroup::combine)

    /**
     * Merge with enrichment metadata.
     *
     * Note: This is a complex transformation, not a simple semigroup merge,
     * so we keep it as a specialized function but use immutable operations.
     */
    fun mergeWithEnrichment(bundled: BundledJenkinsMetadata, enrichment: JenkinsEnrichment): MergedJenkinsMetadata {
        val mergedSteps = bundled.steps.mapValues { (stepName, bundledStep) ->
            val stepEnrichment = enrichment.steps[stepName]

            // Merge parameters strictly
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
                scope = StepScope.GLOBAL,
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

        val mergedOptions = bundled.declarativeOptions.mapValues { (optionName, option) ->
            val mergedParams = option.parameters.mapValues { (paramName, param) ->
                MergedParameter(
                    name = paramName,
                    type = param.type,
                    defaultValue = param.default,
                    description = param.documentation,
                    required = param.required,
                    validValues = null,
                    examples = emptyList(),
                )
            }

            MergedDeclarativeOption(
                name = option.name.ifEmpty { optionName },
                plugin = option.plugin,
                parameters = mergedParams,
                documentation = option.documentation,
            )
        }

        return MergedJenkinsMetadata(
            jenkinsVersion = bundled.jenkinsVersion ?: "unknown",
            steps = mergedSteps,
            globalVariables = mergedGlobalVars,
            sections = enrichment.sections,
            directives = enrichment.directives,
            declarativeOptions = mergedOptions,
        )
    }
}
