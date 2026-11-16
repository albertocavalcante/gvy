// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.metadata

import com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.StepCategory
import com.github.albertocavalcante.gvy.jenkins.metadata.extracted.StepScope
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypedCatalogueBoundaryTest {
    @Test
    fun `unrelated occupied fallback id retains both sources`() {
        val fallback = JenkinsStepMetadata(name = "deploy", plugin = "legacy", parameters = emptyMap()).toTypedSymbol()
        val unrelated = TypedSymbol(id = fallback.id, name = "environment", kind = SymbolKind.GLOBAL)
        val input = BundledJenkinsMetadata(
            steps = mapOf("deploy" to JenkinsStepMetadata(name = "deploy", plugin = "legacy", parameters = emptyMap())),
            globalVariables = emptyMap(),
            typedCatalogue = TypedCatalogue(symbols = listOf(unrelated)),
        )
        assertEquals(fallback.id, input.steps.getValue("deploy").toTypedSymbol().id)
        val result = input.toTypedCatalogue()
        assertEquals(unrelated, result.unique(SymbolKind.GLOBAL, "environment"))
        assertEquals(2, result.symbols.size)
        val retained = result.candidates(SymbolKind.EXECUTABLE_STEP, "deploy").single()
        assertEquals(fallback.name, retained.name)
        assertEquals(fallback.ownerPlugin, retained.ownerPlugin)
        assertEquals(2, result.symbols.map { it.id }.distinct().size)
    }

    @Test
    fun `incoming fallbacks with colliding ids remain distinct`() {
        val first = TypedSymbol(id = "shared", name = "first", kind = SymbolKind.EXECUTABLE_STEP)
        val second = TypedSymbol(id = "shared", name = "second", kind = SymbolKind.GLOBAL)
        val result = TypedCatalogue().withLegacyFallbacks(listOf(first, second))
        assertEquals("first", result.unique(SymbolKind.EXECUTABLE_STEP, "first")?.name)
        assertEquals("second", result.unique(SymbolKind.GLOBAL, "second")?.name)
        assertEquals(2, result.symbols.map { it.id }.distinct().size)
    }

    @Test
    fun `singular projections reject primary name collision with alias`() {
        val alpha = TypedSymbol(
            id = "alpha",
            name = "alpha",
            kind = SymbolKind.EXECUTABLE_STEP,
            aliases = listOf("beta"),
            ownerPlugin = "p",
            ownerEvidence = OwnerEvidence.CURATED,
        )
        val beta = alpha.copy(id = "beta", name = "beta", aliases = emptyList())
        val catalogue = TypedCatalogue(symbols = listOf(alpha, beta))
        assertEquals(listOf(alpha, beta), catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "beta"))
        assertNull(catalogue.unique(SymbolKind.EXECUTABLE_STEP, "beta"))
        assertNull(catalogue.projectBundledSteps()["beta"])
    }

    @Test
    fun `merged step primary name colliding with alias is not projected`() {
        val alpha = TypedSymbol(id = "a", name = "alpha", kind = SymbolKind.EXECUTABLE_STEP, aliases = listOf("beta"))
        val beta = alpha.copy(id = "b", name = "beta", aliases = emptyList())
        val catalogue = TypedCatalogue(symbols = listOf(alpha, beta))
        assertEquals(2, catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "beta").size)
        assertNull(catalogue.projectSteps(SymbolKind.EXECUTABLE_STEP)["beta"])
    }

    @Test
    fun `global primary name colliding with alias is not projected`() {
        val alpha =
            TypedSymbol(
                id = "a",
                name = "alpha",
                kind = SymbolKind.GLOBAL,
                aliases = listOf("beta"),
                returnType = "String",
            )
        val beta = alpha.copy(id = "b", name = "beta", aliases = emptyList())
        val catalogue = TypedCatalogue(symbols = listOf(alpha, beta))
        assertEquals(2, catalogue.candidates(SymbolKind.GLOBAL, "beta").size)
        assertNull(catalogue.projectGlobals()["beta"])
    }

    @Test
    fun `option alias collision is scoped to its context`() {
        val alpha = TypedSymbol(
            id = "a",
            name = "alpha",
            kind = SymbolKind.DIRECTIVE_ENTRY,
            aliases = listOf("beta"),
            ownerPlugin = "p",
            ownerEvidence = OwnerEvidence.CURATED,
            contexts = listOf("pipeline"),
        )
        val beta = alpha.copy(id = "b", name = "beta", aliases = emptyList())
        val separate = TypedCatalogue(symbols = listOf(alpha.copy(contexts = listOf("stage")), beta))
        assertEquals("beta", separate.projectOptions(SymbolKind.DIRECTIVE_ENTRY, "pipeline")["beta"]?.name)
        assertEquals(
            "beta",
            TypedCatalogue(symbols = listOf(beta)).projectOptions(SymbolKind.DIRECTIVE_ENTRY, "pipeline")["beta"]?.name,
        )
        val sameContext = TypedCatalogue(symbols = listOf(alpha, beta))
        assertEquals(2, sameContext.candidates(SymbolKind.DIRECTIVE_ENTRY, "beta").size)
        assertNull(sameContext.projectOptions(SymbolKind.DIRECTIVE_ENTRY, "pipeline")["beta"])
    }

    @Test
    fun `ordinary unknown owner views replay without multiplying retained facts`() {
        val input = BundledJenkinsMetadata(
            steps = mapOf("deploy" to JenkinsStepMetadata("deploy", "unattributed", parameters = emptyMap())),
            globalVariables = mapOf("environment" to GlobalVariableMetadata("environment", "String")),
        )
        val once = input.toTypedCatalogue()
        val repeated = input.copy(typedCatalogue = once).toTypedCatalogue()
        assertEquals(1, repeated.candidates(SymbolKind.EXECUTABLE_STEP, "deploy").size)
        assertEquals(1, repeated.candidates(SymbolKind.GLOBAL, "environment").size)
        assertNull(repeated.unique(SymbolKind.EXECUTABLE_STEP, "deploy")?.ownerPlugin)
        assertNull(repeated.unique(SymbolKind.GLOBAL, "environment")?.ownerPlugin)
        val decoded = Json.decodeFromString(
            TypedCatalogue.serializer(),
            Json.encodeToString(TypedCatalogue.serializer(), once),
        )
        val replay = input.copy(typedCatalogue = decoded).toTypedCatalogue()
        assertEquals(1, replay.candidates(SymbolKind.EXECUTABLE_STEP, "deploy").size)
        assertEquals(1, replay.candidates(SymbolKind.GLOBAL, "environment").size)
    }

    @Test
    fun `changed derived fallback facts remain distinct from their earlier view`() {
        val original = TypedSymbol(
            id = "legacy",
            name = "environment",
            kind = SymbolKind.GLOBAL,
            legacyDerived = true,
            returnType = "String",
        )
        val changed = original.copy(returnType = "Integer")
        val replay = TypedCatalogue(symbols = listOf(original)).withLegacyFallbacks(listOf(original))
        assertEquals(listOf(original), replay.symbols)
        val result = replay.withLegacyFallbacks(listOf(changed))
        assertEquals(2, result.candidates(SymbolKind.GLOBAL, "environment").size)
        assertEquals(setOf("String", "Integer"), result.symbols.map { it.returnType }.toSet())
        assertEquals(2, result.symbols.map { it.id }.distinct().size)
        val repeated = result.withLegacyFallbacks(listOf(changed))
        assertEquals(result, repeated, "A disambiguated changed fallback must not multiply on replay")
    }

    @Test
    fun `equal facts from explicitly distinct sources remain separate`() {
        val first = TypedSymbol(
            id = "first",
            sourceId = "declaration",
            sourceNamespace = "one",
            name = "environment",
            kind = SymbolKind.GLOBAL,
            returnType = "String",
        )
        val second = first.copy(id = "second", sourceNamespace = "two")
        val result = TypedCatalogue(symbols = listOf(first)).withLegacyFallbacks(listOf(second))
        assertEquals(listOf(first, second), result.candidates(SymbolKind.GLOBAL, "environment"))
        assertNull(result.unique(SymbolKind.GLOBAL, "environment"))
    }

    @Test
    fun `invalid catalogue ids remain rejected`() {
        val symbol = TypedSymbol(id = "valid", name = "sample", kind = SymbolKind.GLOBAL)
        assertFailsWith<IllegalArgumentException> { TypedCatalogue(symbols = listOf(symbol, symbol)) }
        assertFailsWith<IllegalArgumentException> { TypedCatalogue(symbols = listOf(symbol.copy(id = " "))) }
    }

    @Test
    fun `aliases in different kinds do not make the step ambiguous`() {
        val step = TypedSymbol(id = "step", name = "deploy", kind = SymbolKind.EXECUTABLE_STEP, ownerPlugin = "p")
        val global = TypedSymbol(id = "global", name = "other", kind = SymbolKind.GLOBAL, aliases = listOf("deploy"))
        val catalogue = TypedCatalogue(symbols = listOf(step, global))
        assertEquals(listOf(step), catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deploy"))
        assertTrue(catalogue.projectBundledSteps().containsKey("deploy"))
        assertEquals(listOf(global), catalogue.candidates(SymbolKind.GLOBAL, "deploy"))
    }

    @Test
    fun `foreign legacy adjuncts cannot attach to the selected descriptor`() {
        val descriptor = TypedSymbol(
            id = "descriptor",
            name = "deploy",
            kind = SymbolKind.EXECUTABLE_STEP,
            legacyDerived = true,
            ownerPlugin = "curated",
            ownerEvidence = OwnerEvidence.CURATED,
            documentation = "Curated docs",
            parameters = listOf(TypedParameter("required", "String")),
        )
        val foreign = MergedStepMetadata(
            name = "deploy", scope = StepScope.GLOBAL, positionalParams = emptyList(),
            namedParams = mapOf("foreign" to MergedParameter("foreign", "String", null, null, null, null, emptyList())),
            extractedDocumentation = "Foreign docs", returnType = "ForeignResult", plugin = "foreign",
            enrichedDescription = null, documentationUrl = "https://foreign.test/", category = StepCategory.DEPLOY,
            examples = listOf("foreign()"), deprecation = null,
        )
        val result = TypedCatalogue(symbols = listOf(descriptor)).projectSteps(
            SymbolKind.EXECUTABLE_STEP,
            mapOf("deploy" to foreign),
            setOf(descriptor.id),
        ).getValue("deploy")
        assertEquals("curated", result.plugin)
        assertEquals("Curated docs", result.documentation)
        assertEquals(setOf("required"), result.namedParams.keys)
        assertNull(result.documentationUrl)
        assertNull(result.category)
        assertTrue(result.examples.isEmpty())
    }

    @Test
    fun `constructed legacy projection preserves representable callable facts`() {
        val descriptor = TypedSymbol(
            id = "descriptor", name = "deploy", kind = SymbolKind.EXECUTABLE_STEP,
            ownerPlugin = "curated", ownerEvidence = OwnerEvidence.CURATED,
            aliases = listOf("ship"), contexts = listOf("pipeline"), namedBinding = Knowledge.YES,
            block = Knowledge.NO, valueDomain = listOf("one", "two"),
            parameters = listOf(TypedParameter("value", "String", Requiredness.UNKNOWN)),
        )
        val result = TypedCatalogue(symbols = listOf(descriptor)).projectBundledSteps().getValue("deploy")
        assertEquals("curated", result.ownerPlugin)
        assertEquals(listOf("ship"), result.aliases)
        assertEquals(listOf("pipeline"), result.contexts)
        assertEquals(Knowledge.YES, result.namedBinding)
        assertEquals(Knowledge.NO, result.block)
        assertEquals(listOf("one", "two"), result.valueDomain)
        assertNull(result.parameters.getValue("value").required)
    }
}
