// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.config

import com.github.albertocavalcante.gvy.jenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MetadataMerger
import com.github.albertocavalcante.gvy.jenkins.metadata.SourceKind
import com.github.albertocavalcante.gvy.jenkins.metadata.StepParameter
import com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind
import com.github.albertocavalcante.gvy.jenkins.metadata.TypedCatalogue
import com.github.albertocavalcante.gvy.jenkins.metadata.TypedSymbol
import com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment
import com.github.albertocavalcante.gvy.jenkins.metadata.projectBundledSteps
import com.github.albertocavalcante.gvy.jenkins.metadata.toTypedCatalogue
import com.github.albertocavalcante.gvy.jenkins.metadata.withCanonicalProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GdslProjectionBoundaryTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `singleton method projection does not become another raw candidate during loader merge`() {
        val loaded = load("method(name: 'deploy', type: 'String', params: [target: 'String'])")
        val original = assertNotNull(loaded.typedCatalogue)
            .candidates(SymbolKind.EXECUTABLE_STEP, "deploy").single()
        assertNotNull(loaded.getStep("deploy"))

        val merged = MetadataMerger.mergeWithEnrichment(loaded, JenkinsEnrichment())
        val candidates = merged.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deploy")

        assertEquals(listOf(original), candidates, "The compatibility projection is not an independent source")
        assertSourceFacts(candidates.single())
        val step = assertNotNull(merged.getStep("deploy"))
        assertEquals("String", step.returnType)
        assertEquals("String", step.namedParams.getValue("target").type)
        assertNull(step.plugin, "The GDSL file path does not establish plugin ownership")
    }

    @Test
    fun `singleton global projection does not become an unknown source during loader merge`() {
        val loaded = load("property(name: 'deployment', type: 'String', doc: 'Deployment identity')")
        val original = assertNotNull(loaded.typedCatalogue)
            .candidates(SymbolKind.GLOBAL, "deployment").single()
        assertNotNull(loaded.getGlobalVariable("deployment"))

        val merged = MetadataMerger.mergeWithEnrichment(loaded, JenkinsEnrichment())
        val candidates = merged.catalogue.candidates(SymbolKind.GLOBAL, "deployment")

        assertEquals(listOf(original), candidates, "The compatibility projection must retain its source identity")
        assertSourceFacts(candidates.single())
        val global = assertNotNull(merged.getGlobalVariable("deployment"))
        assertEquals("String", global.type)
        assertEquals("Deployment identity", global.documentation)
    }

    @Test
    fun `repeated merges retain each projected canonical candidate once`() {
        val loaded = load(
            "method(name: 'deploy', type: 'String', params: [target: 'String']); " +
                "property(name: 'deployment', type: 'String')",
        )
        val original = assertNotNull(loaded.typedCatalogue).symbols
        val once = MetadataMerger.mergeAll(loaded, stable = emptyMap())
        val twice = MetadataMerger.mergeAll(once, versioned = loaded, stable = emptyMap())
        val result = MetadataMerger.mergeWithEnrichment(twice, JenkinsEnrichment())

        assertEquals(original, result.catalogue.symbols)
        assertNotNull(result.getStep("deploy"))
        assertNotNull(result.getGlobalVariable("deployment"))
    }

    @Test
    fun `independently generated views of one canonical symbol survive repeated merging`() {
        val loaded = load("method(name: 'deploy', type: 'String', params: [target: 'String'])")
        val encoded = Json.encodeToString(TypedCatalogue.serializer(), assertNotNull(loaded.typedCatalogue))
        val catalogue = Json.decodeFromString(TypedCatalogue.serializer(), encoded)
        val symbol = catalogue.symbols.single()
        val plain = assertNotNull(loaded.getStep("deploy")).copy(canonicalProjection = null)

        fun reconstructed(): BundledJenkinsMetadata {
            val view = plain.withCanonicalProjection(symbol)
            return loaded.copy(
                steps = catalogue.projectBundledSteps(mapOf("deploy" to view)),
                typedCatalogue = catalogue,
            )
        }

        val first = reconstructed()
        val second = reconstructed()
        val once = MetadataMerger.mergeAll(first, versioned = second, stable = emptyMap())
        val twice = MetadataMerger.mergeAll(once, versioned = reconstructed(), stable = emptyMap())
        val result = MetadataMerger.mergeWithEnrichment(twice, JenkinsEnrichment())

        assertEquals(listOf(symbol), result.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deploy"))
        assertNotNull(result.getStep("deploy"))
    }

    @Test
    fun `separate GDSL contributors with the same name remain ambiguous`() {
        val first = load("method(name: 'deploy', type: 'String')")
        val firstSymbol = assertNotNull(first.typedCatalogue).symbols.single()
        val secondSymbol = firstSymbol.copy(
            id = "other-contributor",
            provenance = firstSymbol.provenance.copy(origin = "other.gdsl"),
        )
        val second = BundledJenkinsMetadata(
            steps = emptyMap(),
            globalVariables = emptyMap(),
            typedCatalogue = TypedCatalogue(
                symbols = listOf(secondSymbol),
            ),
        )
        val merged = MetadataMerger.mergeAll(first, versioned = second, stable = emptyMap())
        val result = MetadataMerger.mergeWithEnrichment(merged, JenkinsEnrichment())

        assertEquals(
            listOf(firstSymbol, secondSymbol),
            result.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deploy"),
        )
        assertNull(result.getStep("deploy"))
    }

    @Test
    fun `copied canonical symbol retains its ingestion identity`() {
        val first = load("method(name: 'deploy', type: 'String')")
        val symbol = assertNotNull(first.typedCatalogue).symbols.single()
        val second = BundledJenkinsMetadata(
            steps = emptyMap(),
            globalVariables = emptyMap(),
            typedCatalogue = TypedCatalogue(
                symbols = listOf(symbol.copy()),
            ),
        )
        val merged = MetadataMerger.mergeAll(first, versioned = second, stable = emptyMap())
        val candidates = assertNotNull(merged.typedCatalogue).candidates(SymbolKind.EXECUTABLE_STEP, "deploy")

        assertEquals(listOf(symbol), candidates)
    }

    @Test
    fun `edited projections are adapted instead of hiding caller changes`() {
        val loaded = load(
            "method(name: 'deploy', type: 'String', params: [target: 'String']); " +
                "property(name: 'deployment', type: 'String')",
        )
        val step = assertNotNull(loaded.getStep("deploy"))
        val global = assertNotNull(loaded.getGlobalVariable("deployment"))
        val edits = listOf(
            step.copy(returnType = "Integer"),
            step.copy(parameters = mapOf("other" to StepParameter("other", "Boolean"))),
            step.copy(documentation = "changed"),
            step.copy(ownerPlugin = "new-owner"),
        )
        for (edited in edits) {
            val symbols = loaded.copy(steps = mapOf("deploy" to edited)).toTypedCatalogue()
                .candidates(SymbolKind.EXECUTABLE_STEP, "deploy")
            assertEquals(2, symbols.size)
            val adapted = symbols.single { it.id != step.canonicalProjection?.symbol?.id }
            assertEquals(edited.returnType, adapted.returnType)
            assertEquals(edited.parameters.keys, adapted.parameters.map { it.name }.toSet())
            assertEquals(edited.documentation, adapted.documentation)
            assertEquals(edited.ownerPlugin, adapted.ownerPlugin)
        }
        val changedGlobal = global.copy(type = "Integer", documentation = "changed")
        val globals = loaded.copy(globalVariables = mapOf("deployment" to changedGlobal)).toTypedCatalogue()
            .candidates(SymbolKind.GLOBAL, "deployment")
        assertEquals(2, globals.size)
        assertEquals("Integer", globals.single { it.provenance.source == SourceKind.UNKNOWN }.returnType)
    }

    @Test
    fun `caller mutation does not alter an already adapted catalogue`() {
        val loaded = load("method(name: 'deploy', type: 'String', params: [target: 'String'])")
        val step = assertNotNull(loaded.getStep("deploy"))
        val parameters = step.parameters.toMutableMap()
        val input = loaded.copy(steps = mapOf("deploy" to step.copy(parameters = parameters)))
        val before = input.toTypedCatalogue()
        parameters["extra"] = StepParameter("extra", "Boolean")

        assertEquals(1, before.candidates(SymbolKind.EXECUTABLE_STEP, "deploy").size)
        val after = input.toTypedCatalogue().candidates(SymbolKind.EXECUTABLE_STEP, "deploy")
        assertEquals(2, after.size)
        assertEquals(setOf("target", "extra"), after.last().parameters.map { it.name }.toSet())
        assertEquals(setOf("target"), before.symbols.single().parameters.map { it.name }.toSet())
    }

    @Test
    fun `decoded canonical catalogue and its compatibility views adapt repeatedly without duplicates`() {
        val loaded = load("method(name: 'deploy', type: 'String'); property(name: 'deployment', type: 'String')")
        val original = assertNotNull(loaded.typedCatalogue)
        val encoded = Json.encodeToString(TypedCatalogue.serializer(), original)
        val decoded = Json.decodeFromString(TypedCatalogue.serializer(), encoded)
        val reconstructed = loaded.copy(typedCatalogue = decoded)
        val once = reconstructed.toTypedCatalogue()
        val twice = reconstructed.copy(typedCatalogue = once).toTypedCatalogue()

        assertEquals(original, twice)
        val result = MetadataMerger.mergeWithEnrichment(reconstructed, JenkinsEnrichment())
        assertEquals(original, result.catalogue)
    }

    @Test
    fun `explicit replacement does not resurrect its stale projected view`() {
        val loaded = load("method(name: 'deploy', type: 'String')")
        val original = assertNotNull(loaded.typedCatalogue).symbols.single()
        val replacement = original.copy(id = "replacement", replacesId = original.id, aliases = listOf("ship"))
        val updated = assertNotNull(loaded.typedCatalogue).withOverrides(listOf(replacement))
        val result = loaded.copy(typedCatalogue = updated).toTypedCatalogue()

        val surviving = result.candidates(SymbolKind.EXECUTABLE_STEP, "deploy").single()
        assertEquals("replacement", surviving.id)
        assertEquals(listOf(surviving), result.candidates(SymbolKind.EXECUTABLE_STEP, "ship"))
    }

    @Test
    fun `replacement lineage follows rewritten source ids without suppressing other sources or conflicts`() {
        val loaded = load("method(name: 'deploy', type: 'String')")
        val original = assertNotNull(loaded.typedCatalogue).symbols.single()
        val view = assertNotNull(loaded.getStep("deploy"))
        for (rewrittenId in listOf("gdsl:0:${original.id}", "${original.id}:source:1")) {
            val rewritten = original.copy(id = rewrittenId, sourceId = original.id)
            val replacement = original.copy(id = "replacement", replacesId = rewrittenId, aliases = listOf("ship"))
            val replaced = TypedCatalogue(symbols = listOf(rewritten)).withOverrides(listOf(replacement))
            val encoded = Json.encodeToString(TypedCatalogue.serializer(), replaced)
            val decoded = Json.decodeFromString(TypedCatalogue.serializer(), encoded)
            val preserved = loaded.copy(typedCatalogue = decoded).toTypedCatalogue()

            assertEquals(listOf("replacement"), preserved.symbols.map { it.id })
            assertEquals("replacement", assertNotNull(preserved.unique(SymbolKind.EXECUTABLE_STEP, "ship")).id)
            val remerged = MetadataMerger.mergeAll(
                loaded.copy(steps = emptyMap(), typedCatalogue = decoded),
                versioned = loaded,
                stable = emptyMap(),
            )
            assertEquals(listOf("replacement"), assertNotNull(remerged.typedCatalogue).symbols.map { it.id })

            val unrelated = original.copy(sourceNamespace = "another-source")
            val otherView = view.copy(canonicalProjection = null).withCanonicalProjection(unrelated)
            val withOther = loaded.copy(steps = mapOf("deploy" to otherView), typedCatalogue = decoded)
                .toTypedCatalogue()
            assertEquals(2, withOther.candidates(SymbolKind.EXECUTABLE_STEP, "deploy").size)

            val conflicting = original.copy(returnType = "Integer")
            val changedView = view.copy(returnType = "Integer", canonicalProjection = null)
                .withCanonicalProjection(conflicting)
            val withConflict = loaded.copy(steps = mapOf("deploy" to changedView), typedCatalogue = decoded)
                .toTypedCatalogue()
            assertEquals(2, withConflict.candidates(SymbolKind.EXECUTABLE_STEP, "deploy").size)
        }
    }

    @Test
    fun `replacement chain retains all ancestor identities across serialization and remerging`() {
        val loaded = load("method(name: 'deploy', type: 'String')")
        val original = assertNotNull(loaded.typedCatalogue).symbols.single()
        val view = assertNotNull(loaded.getStep("deploy"))
        val rewritten = original.copy(id = "gdsl:0:${original.id}", sourceId = original.id)
        val firstOverride = original.copy(id = "replacement-1", replacesId = rewritten.id, aliases = listOf("ship"))
        val firstCatalogue = TypedCatalogue(symbols = listOf(rewritten)).withOverrides(listOf(firstOverride))
        val first = firstCatalogue.symbols.single()
        val secondOverride = original.copy(id = "replacement-2", replacesId = first.id, aliases = listOf("release"))
        val secondCatalogue = firstCatalogue.withOverrides(listOf(secondOverride))
        val encoded = Json.encodeToString(TypedCatalogue.serializer(), secondCatalogue)
        val decoded = Json.decodeFromString(TypedCatalogue.serializer(), encoded)
        val firstView = view.copy(aliases = first.aliases, canonicalProjection = null).withCanonicalProjection(first)

        for (stale in listOf(view, firstView)) {
            val adapted = loaded.copy(steps = mapOf("deploy" to stale), typedCatalogue = decoded).toTypedCatalogue()
            assertEquals(listOf("replacement-2"), adapted.symbols.map { it.id })
        }
        val replacementOnly = loaded.copy(steps = emptyMap(), typedCatalogue = decoded)
        val originalSource = loaded.copy(
            steps = emptyMap(),
            typedCatalogue = TypedCatalogue(symbols = listOf(original)),
        )
        val firstSource = loaded.copy(steps = emptyMap(), typedCatalogue = TypedCatalogue(symbols = listOf(first)))
        val once = MetadataMerger.mergeAll(replacementOnly, versioned = originalSource, stable = emptyMap())
        val twice = MetadataMerger.mergeAll(once, versioned = firstSource, stable = emptyMap())
        val result = assertNotNull(twice.typedCatalogue)
        assertEquals(listOf("replacement-2"), result.symbols.map { it.id })
        assertEquals("replacement-2", assertNotNull(result.unique(SymbolKind.EXECUTABLE_STEP, "release")).id)

        val other = original.copy(sourceNamespace = "unrelated-source")
        val changed = original.copy(returnType = "Integer")
        for (candidate in listOf(other, changed)) {
            val input = loaded.copy(steps = emptyMap(), typedCatalogue = TypedCatalogue(symbols = listOf(candidate)))
            val merged = MetadataMerger.mergeAll(replacementOnly, versioned = input, stable = emptyMap())
            assertEquals(2, assertNotNull(merged.typedCatalogue).candidates(SymbolKind.EXECUTABLE_STEP, "deploy").size)
        }
    }

    private fun load(contribution: String): BundledJenkinsMetadata {
        Files.createDirectories(tempDir.resolve(".gls"))
        Files.writeString(tempDir.resolve(".gls/jenkins.json"), """{"gdslFile":"singleton.gdsl"}""")
        Files.writeString(
            tempDir.resolve("singleton.gdsl"),
            "contributor(context(scope: scriptScope())) { $contribution }",
        )
        return assertNotNull(UserMetadataLoader(tempDir).load())
    }

    private fun assertSourceFacts(symbol: TypedSymbol) {
        assertEquals(SourceKind.GDSL, symbol.provenance.source)
        assertEquals(tempDir.resolve("singleton.gdsl").toString(), symbol.provenance.origin)
        assertNull(symbol.provenance.plugin)
        assertNull(symbol.ownerPlugin)
        assertEquals<JsonElement?>(Json.parseToJsonElement("""{"scope":{"type":"script"}}"""), symbol.gdslContext)
    }
}
