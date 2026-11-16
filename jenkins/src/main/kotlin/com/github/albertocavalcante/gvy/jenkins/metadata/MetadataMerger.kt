// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.metadata

import com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment
import com.github.albertocavalcante.gvy.jenkins.metadata.extracted.StepScope

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
            positionalParams = b.positionalParams.ifEmpty { a.positionalParams },
            returnType = b.returnType ?: a.returnType,
            provenance = if (a.provenance == b.provenance) a.provenance else MetadataProvenance(SourceKind.UNKNOWN),
            ownerPlugin = b.ownerPlugin ?: a.ownerPlugin,
            ownerEvidence = if (b.ownerPlugin != null) b.ownerEvidence else a.ownerEvidence,
            parameters = a.parameters + b.parameters, // Right overrides Left keys
            canonicalProjection = a.canonicalProjection?.takeIf { left ->
                val right = b.canonicalProjection
                left.matches(a) && right != null && right.matches(b) && left.symbol.sameCanonicalFacts(right.symbol) &&
                    a.copy(canonicalProjection = null) == b.copy(canonicalProjection = null)
            },
        )
    }

    private val bundledMetadataSemigroup: Semigroup<BundledJenkinsMetadata> = Semigroup { a, b ->
        // Deep merge steps:
        // 1. Keys in both: combine values
        // 2. Keys only in a: keep a
        // 3. Keys only in b: keep b
        val combinedSteps = mergeStepMaps(a.steps, b.steps)
        val combinedBaseSteps = mergeStepMaps(a.unpatchedSteps(), b.unpatchedSteps())

        val left = a.toTypedCatalogue()
        val right = b.toTypedCatalogue()
        val combinedCatalogue = left.withLegacyContributions(right.symbols)
        val catalogue = combinedCatalogue.copy(
            unresolvedOverrides = combinedCatalogue.unresolvedOverrides + right.unresolvedOverrides,
        )
        BundledJenkinsMetadata(
            // Prefer version from b (right side) if available, else keep a's version
            jenkinsVersion = b.jenkinsVersion ?: a.jenkinsVersion,
            steps = sortByKey(catalogue.projectBundledSteps(combinedSteps)),
            globalVariables = mergeAndSort(a.globalVariables, b.globalVariables),
            postConditions = mergeAndSort(a.postConditions, b.postConditions),
            declarativeOptions = mergeAndSort(a.declarativeOptions, b.declarativeOptions),
            agentTypes = mergeAndSort(a.agentTypes, b.agentTypes),
            nestedDescribables = mergeAndSort(a.nestedDescribables, b.nestedDescribables),
            typedCatalogue = catalogue,
            legacyOverrides = mergeAndSort(a.legacyOverrides, b.legacyOverrides),
            legacyBaseSteps = combinedBaseSteps,
            directiveEntries = (a.directiveEntries.keys + b.directiveEntries.keys).associateWith { directive ->
                mergeAndSort(a.directiveEntries[directive].orEmpty(), b.directiveEntries[directive].orEmpty())
            },
        )
    }

    /**
     * Merge stable steps into bundled metadata.
     */
    fun merge(bundled: BundledJenkinsMetadata, stable: Map<String, JenkinsStepMetadata>): BundledJenkinsMetadata {
        val curated = stable.mapValues { (_, step) ->
            step.copy(
                ownerPlugin = step.plugin,
                ownerEvidence = OwnerEvidence.CURATED,
                provenance = MetadataProvenance(SourceKind.CURATED, plugin = step.plugin),
            )
        }
        val stableMetadata = BundledJenkinsMetadata(steps = curated, globalVariables = emptyMap())
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
        val base = merge(bundled, stable)
        val overrides = base.legacyOverrides + userOverrides
        val catalogue = base.toTypedCatalogue().withLegacyOverrides(overrides)
        return base.copy(
            steps = catalogue.projectBundledSteps(base.steps),
            typedCatalogue = catalogue,
            legacyOverrides = overrides,
            legacyBaseSteps = base.unpatchedSteps(),
        )
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
        val versionedBase = if (versioned == null) bundled else bundledMetadataSemigroup.combine(bundled, versioned)
        val withStable = merge(versionedBase, stable)
        val withDynamic = if (dynamic == null) {
            withStable
        } else {
            val catalogue = withStable.toTypedCatalogue().withLegacyContributions(
                dynamic.values.map {
                    it.toTypedSymbol()
                },
            )
            val projected = catalogue.projectBundledSteps()
            val updatedBase = withStable.unpatchedSteps() + projected.filterKeys { it in dynamic }
            withStable.copy(
                steps = catalogue.projectBundledSteps(updatedBase),
                typedCatalogue = catalogue,
                legacyBaseSteps = updatedBase,
            )
        }
        val overrides = withDynamic.legacyOverrides + userOverrides
        val catalogue = withDynamic.toTypedCatalogue().withLegacyOverrides(overrides)
        return withDynamic.copy(
            steps = catalogue.projectBundledSteps(withDynamic.steps),
            typedCatalogue = catalogue,
            legacyOverrides = overrides,
            legacyBaseSteps = withDynamic.unpatchedSteps(),
        )
    }

    fun mergeStepParameters(base: JenkinsStepMetadata, overlay: JenkinsStepMetadata): JenkinsStepMetadata =
        stepMetadataSemigroup.run { combine(base, overlay) }

    private fun TypedSymbol.isEnrichmentTarget(fallback: Set<String>): Boolean =
        kind == SymbolKind.EXECUTABLE_STEP && legacyDerived && id in fallback

    private fun BundledJenkinsMetadata.unpatchedSteps(): Map<String, JenkinsStepMetadata> =
        steps + legacyBaseSteps.filterKeys { it in legacyOverrides }

    private fun mergeStepMaps(
        left: Map<String, JenkinsStepMetadata>,
        right: Map<String, JenkinsStepMetadata>,
    ): Map<String, JenkinsStepMetadata> = (left.keys + right.keys).associateWith { name ->
        val a = left[name]
        val b = right[name]
        if (a != null && b != null) stepMetadataSemigroup.combine(a, b) else b ?: checkNotNull(a)
    }

    private fun <V> mergeAndSort(left: Map<String, V>, right: Map<String, V>): Map<String, V> = sortByKey(left + right)

    private fun <V> sortByKey(values: Map<String, V>): Map<String, V> =
        values.entries.sortedBy { it.key }.associate { it.toPair() }

    /**
     * Merge with enrichment metadata.
     *
     * Note: This is a complex transformation, not a simple semigroup merge,
     * so we keep it as a specialized function but use immutable operations.
     */
    fun mergeWithEnrichment(bundled: BundledJenkinsMetadata, enrichment: JenkinsEnrichment): MergedJenkinsMetadata {
        val rawCatalogue = bundled.toTypedCatalogue()
        val base = bundled.copy(
            steps = bundled.unpatchedSteps(),
            typedCatalogue = null,
            legacyOverrides = emptyMap(),
        )
        val fallback = base.toTypedCatalogue().symbols.map { it.id }.toSet()
        val steps = mergeSteps(base, enrichment)
        val enrichedCatalogue = rawCatalogue.copy(
            symbols = rawCatalogue.symbols.map { symbol ->
                val enriched = steps[symbol.name]
                if (symbol.isEnrichmentTarget(fallback) && enriched != null) {
                    val projected = enriched.toTypedSymbol(symbol.provenance)
                    symbol.copy(
                        parameters = projected.parameters,
                        positional = projected.positional,
                        returnType = projected.returnType,
                        documentation = projected.documentation,
                    )
                } else {
                    symbol
                }
            },
        )
        val catalogue = enrichedCatalogue.withLegacyOverrides(bundled.legacyOverrides)
        val nested = mergeSteps(bundled.copy(steps = bundled.nestedDescribables), JenkinsEnrichment())
        return MergedJenkinsMetadata(
            jenkinsVersion = bundled.jenkinsVersion ?: "unknown",
            steps = catalogue.projectSteps(SymbolKind.EXECUTABLE_STEP, steps, fallback, enrichedCatalogue),
            globalVariables = catalogue.projectGlobals(mergeGlobalVariables(bundled, enrichment), fallback),
            sections = enrichment.sections,
            directives = enrichment.directives,
            declarativeOptions = catalogue.projectOptions(SymbolKind.OPTION, "options"),
            directiveEntries = (
                bundled.directiveEntries.keys + catalogue.symbols
                    .filter { it.kind in setOf(SymbolKind.DIRECTIVE_ENTRY, SymbolKind.TOOL, SymbolKind.TRIGGER) }
                    .flatMap { it.contexts.orEmpty() }
                ).associateWith { context ->
                val kind = when (context) {
                    "tools" -> SymbolKind.TOOL
                    "triggers" -> SymbolKind.TRIGGER
                    else -> SymbolKind.DIRECTIVE_ENTRY
                }
                catalogue.projectOptions(kind, context)
            },
            nestedDescribables = catalogue.projectSteps(SymbolKind.NESTED_DESCRIBABLE, nested, fallback),
            catalogue = catalogue,
        )
    }

    private fun mergeSteps(
        bundled: BundledJenkinsMetadata,
        enrichment: JenkinsEnrichment,
    ): Map<String, MergedStepMetadata> = bundled.steps.mapValues { (stepName, bundledStep) ->
        val stepEnrichment = enrichment.steps[stepName]

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
            positionalParams = bundledStep.positionalParams,
            namedParams = mergedParams,
            extractedDocumentation = bundledStep.documentation,
            returnType = bundledStep.returnType,
            plugin = if (bundledStep.provenance.source == SourceKind.GDSL) {
                null
            } else {
                stepEnrichment?.plugin ?: bundledStep.plugin
            },
            enrichedDescription = stepEnrichment?.description,
            documentationUrl = stepEnrichment?.documentationUrl,
            category = stepEnrichment?.category,
            examples = stepEnrichment?.examples ?: emptyList(),
            deprecation = stepEnrichment?.deprecation,
        )
    }

    private fun mergeGlobalVariables(
        bundled: BundledJenkinsMetadata,
        enrichment: JenkinsEnrichment,
    ): Map<String, MergedGlobalVariable> = bundled.globalVariables.mapValues { (varName, bundledVar) ->
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
}
