// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.metadata

import com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment
import com.github.albertocavalcante.gvy.jenkins.metadata.extracted.StepScope
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LegacyMapCollisionTest {
    private fun mergedStep() = MergedStepMetadata(
        name = "same", scope = StepScope.UNKNOWN, positionalParams = emptyList(), namedParams = emptyMap(),
        extractedDocumentation = "step", returnType = null, plugin = null, enrichedDescription = null,
        documentationUrl = null, category = null, examples = emptyList(), deprecation = null,
    )

    @Test
    fun `merged legacy map entries with equal names retain distinct candidates`() {
        val step = mergedStep()
        val global = MergedGlobalVariable("same", "String", "global", null, null, emptyMap())
        val option = MergedDeclarativeOption("same", "plugin", emptyMap(), "option")
        fun build(reverse: Boolean): MergedJenkinsMetadata {
            fun <T> entries(value: T): Map<String, T> = if (reverse) {
                linkedMapOf("second" to value, "first" to value)
            } else {
                linkedMapOf("first" to value, "second" to value)
            }
            return MergedJenkinsMetadata(
                "test",
                entries(step),
                entries(global),
                emptyMap(),
                emptyMap(),
                declarativeOptions = entries(option),
                directiveEntries = mapOf(
                    "tools" to entries(option),
                    "triggers" to entries(option),
                    "parameters" to entries(option),
                ),
                nestedDescribables = entries(step),
            )
        }
        val forward = build(false)
        val reversed = build(true)
        val kinds = listOf(
            SymbolKind.EXECUTABLE_STEP,
            SymbolKind.NESTED_DESCRIBABLE,
            SymbolKind.GLOBAL,
            SymbolKind.OPTION,
            SymbolKind.TOOL,
            SymbolKind.TRIGGER,
            SymbolKind.DIRECTIVE_ENTRY,
        )
        for (kind in kinds) {
            val candidates = forward.catalogue.candidates(kind, "same")
            assertEquals(2, candidates.size, kind.name)
            assertEquals(2, candidates.map { it.id }.distinct().size)
            assertNull(forward.catalogue.unique(kind, "same"))
            assertEquals(
                candidates.map { it.id }.toSet(),
                reversed.catalogue.candidates(kind, "same").map { it.id }.toSet(),
            )
        }
        assertEquals(setOf("first", "second"), forward.steps.keys)
    }

    @Test
    fun `bundled legacy maps reconstruct collisions deterministically without invented provenance`() {
        val step = JenkinsStepMetadata(name = "same", plugin = "legacy", parameters = emptyMap())
        val global = GlobalVariableMetadata("same", "String", "global")
        val option = DeclarativeOptionMetadata("same", "legacy", emptyMap(), "option")
        fun build(reverse: Boolean): BundledJenkinsMetadata {
            fun <T> entries(value: T): Map<String, T> = if (reverse) {
                linkedMapOf("b" to value, "a" to value)
            } else {
                linkedMapOf("a" to value, "b" to value)
            }
            return BundledJenkinsMetadata(
                steps = entries(step),
                globalVariables = entries(global),
                nestedDescribables = entries(step.copy(kind = SymbolKind.NESTED_DESCRIBABLE)),
                declarativeOptions = entries(option),
                directiveEntries = mapOf(
                    "tools" to entries(option),
                    "triggers" to entries(option),
                    "parameters" to entries(option),
                ),
                agentTypes = entries(AgentTypeMetadata("same", emptyMap())),
                postConditions = entries(PostConditionMetadata("same", "condition")),
            )
        }
        assertEquals(SymbolKind.NESTED_DESCRIBABLE, build(false).nestedDescribables.getValue("a").kind)
        val first = build(false).toTypedCatalogue()
        val again = build(false).toTypedCatalogue()
        val reversed = build(true).toTypedCatalogue()
        val kinds = listOf(
            SymbolKind.EXECUTABLE_STEP, SymbolKind.NESTED_DESCRIBABLE, SymbolKind.GLOBAL, SymbolKind.OPTION,
            SymbolKind.TOOL, SymbolKind.TRIGGER, SymbolKind.DIRECTIVE_ENTRY, SymbolKind.AGENT, SymbolKind.CONDITION,
        )
        for (kind in kinds) {
            val candidates = first.candidates(kind, "same")
            assertEquals(2, candidates.size, kind.name)
            assertEquals(2, candidates.map { it.id }.distinct().size)
            assertEquals(candidates.map { it.id }.toSet(), again.candidates(kind, "same").map { it.id }.toSet())
            assertEquals(candidates.map { it.id }.toSet(), reversed.candidates(kind, "same").map { it.id }.toSet())
            assertEquals(listOf(null, null), candidates.map { it.sourceNamespace })
        }
    }

    @Test
    fun `reordered differing facts keep their allocated ids`() {
        val a = JenkinsStepMetadata(name = "same", plugin = "legacy", parameters = emptyMap(), documentation = "a")
        val b = a.copy(documentation = "b")
        fun ids(steps: Map<String, JenkinsStepMetadata>) = BundledJenkinsMetadata(
            steps = steps,
            globalVariables = emptyMap(),
        ).toTypedCatalogue().candidates(SymbolKind.EXECUTABLE_STEP, "same").associate { it.documentation to it.id }
        assertEquals(ids(linkedMapOf("a" to a, "b" to b)), ids(linkedMapOf("b" to b, "a" to a)))
        assertEquals(2, ids(mapOf("a" to a, "b" to b)).size)
        val mergedA = mergedStep().copy(extractedDocumentation = "a")
        val mergedB = mergedA.copy(extractedDocumentation = "b")
        fun mergedIds(steps: Map<String, MergedStepMetadata>) = MergedJenkinsMetadata(
            "test",
            steps,
            emptyMap(),
            emptyMap(),
            emptyMap(),
        ).catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "same").associate { it.documentation to it.id }
        assertEquals(
            mergedIds(linkedMapOf("a" to mergedA, "b" to mergedB)),
            mergedIds(linkedMapOf("b" to mergedB, "a" to mergedA)),
        )
    }

    @Test
    fun `established projection replay collapses but edits and other origins remain`() {
        val symbol = TypedSymbol(
            id = "decl",
            sourceNamespace = "file:///first",
            name = "same",
            kind = SymbolKind.EXECUTABLE_STEP,
        )
        val view = JenkinsStepMetadata(name = "same", plugin = "legacy", parameters = emptyMap())
            .withCanonicalProjection(symbol)
        fun catalogue(second: JenkinsStepMetadata) = BundledJenkinsMetadata(
            steps = mapOf("first" to view, "second" to second),
            globalVariables = emptyMap(),
        ).toTypedCatalogue()
        assertEquals(listOf(symbol), catalogue(view).symbols)
        val edited = catalogue(view.copy(returnType = "Changed"))
        assertEquals(2, edited.candidates(SymbolKind.EXECUTABLE_STEP, "same").size)
        val other = symbol.copy(sourceNamespace = "file:///second")
        val distinct = catalogue(view.copy(canonicalProjection = null).withCanonicalProjection(other))
        assertEquals(setOf("file:///first", "file:///second"), distinct.symbols.map { it.sourceNamespace }.toSet())
        assertEquals(setOf("decl"), distinct.symbols.map { it.sourceId ?: it.id }.toSet())
    }

    @Test
    fun `nested map rejects default executable kind through merger`() {
        val wrong = JenkinsStepMetadata(name = "binding", plugin = "legacy", parameters = emptyMap())
        assertEquals(SymbolKind.EXECUTABLE_STEP, wrong.kind)
        for (catalogue in listOf(null, TypedCatalogue())) {
            val bundled = BundledJenkinsMetadata(
                steps = emptyMap(),
                globalVariables = emptyMap(),
                nestedDescribables = mapOf("binding" to wrong),
                typedCatalogue = catalogue,
            )
            assertFailsWith<IllegalArgumentException>("catalogue=$catalogue") {
                MetadataMerger.mergeWithEnrichment(bundled, JenkinsEnrichment())
            }
        }
    }

    @Test
    fun `step map rejects nested kind even with explicit typed catalogue`() {
        val wrong = JenkinsStepMetadata(
            name = "binding",
            plugin = "legacy",
            parameters = emptyMap(),
            kind = SymbolKind.NESTED_DESCRIBABLE,
        )
        for (catalogue in listOf(null, TypedCatalogue())) {
            val bundled = BundledJenkinsMetadata(
                steps = mapOf("binding" to wrong),
                globalVariables = emptyMap(),
                typedCatalogue = catalogue,
            )
            assertFailsWith<IllegalArgumentException>("catalogue=$catalogue") { bundled.toTypedCatalogue() }
            assertFailsWith<IllegalArgumentException>("override catalogue=$catalogue") {
                bundled.copy(legacyOverrides = mapOf("binding" to wrong)).toTypedCatalogue()
            }
        }
    }

    @Test
    fun `nested map accepts matching canonical projection and rejects contradictory one`() {
        val canonical = TypedSymbol(
            id = "nested",
            sourceNamespace = "file:///source",
            name = "binding",
            kind = SymbolKind.NESTED_DESCRIBABLE,
        )
        val view = JenkinsStepMetadata(
            name = "binding",
            plugin = "legacy",
            parameters = emptyMap(),
            kind = SymbolKind.NESTED_DESCRIBABLE,
        )
        val matching = view.withCanonicalProjection(canonical)
        val contradictory = view.withCanonicalProjection(canonical.copy(kind = SymbolKind.EXECUTABLE_STEP))
        for (catalogue in listOf(null, TypedCatalogue(symbols = listOf(canonical)))) {
            fun bundled(value: JenkinsStepMetadata) = BundledJenkinsMetadata(
                steps = emptyMap(),
                globalVariables = emptyMap(),
                nestedDescribables = mapOf("binding" to value),
                typedCatalogue = catalogue,
            )
            assertEquals(listOf(canonical), bundled(matching).toTypedCatalogue().symbols)
            assertFailsWith<IllegalArgumentException>("catalogue=$catalogue") {
                bundled(contradictory).toTypedCatalogue()
            }
        }
    }

    @Test
    fun `explicit duplicate typed ids remain invalid`() {
        val symbol = TypedSymbol(id = "duplicate", name = "same", kind = SymbolKind.GLOBAL)
        assertFailsWith<IllegalArgumentException> { TypedCatalogue(symbols = listOf(symbol, symbol)) }
    }
}
