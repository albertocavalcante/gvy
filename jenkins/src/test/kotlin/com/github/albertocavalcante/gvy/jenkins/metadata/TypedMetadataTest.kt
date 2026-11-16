// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.metadata

import com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment
import com.github.albertocavalcante.gvy.jenkins.metadata.json.MetadataJson
import com.github.albertocavalcante.gvy.jenkins.metadata.json.toBundledMetadata
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TypedMetadataTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `legacy omitted requiredness stays unknown while explicit false remains optional`() {
        val input =
            """{"steps":{"sample":{"plugin":"test","parameters":{"unknown":{"type":"String"},""" +
                """"optional":{"type":"String","required":false}}}},"globalVariables":{}}"""
        val bundled = Json.decodeFromString<MetadataJson>(input).toBundledMetadata()
        val params = bundled.steps.getValue("sample").parameters
        assertNull(params.getValue("unknown").required)
        assertEquals(false, params.getValue("optional").required)
    }

    @Test
    fun `typed metadata round trips all facts including unknown availability`() {
        val input = TypedSymbol(
            id = "fixture:binding",
            name = "string",
            kind = SymbolKind.NESTED_DESCRIBABLE,
            aliases = listOf("secretText"),
            parameters = listOf(TypedParameter("credentialsId", "java.lang.String", Requiredness.UNKNOWN)),
            positional = listOf("credentialsId"),
            namedBinding = Knowledge.YES,
            returnType = "example.Binding",
            valueDomain = listOf("one", "two"),
            block = Knowledge.NO,
            contexts = listOf("withCredentials"),
            provenance = MetadataProvenance(SourceKind.CURATED, plugin = "credentials-binding"),
            availability = Availability.UNKNOWN,
        )
        assertEquals(input, Json.decodeFromString<TypedSymbol>(Json.encodeToString(TypedSymbol.serializer(), input)))
    }

    @Test
    fun `credential and parameter string are retained in separate namespaces`() {
        val metadata = VersionedMetadataLoader().loadMerged()
        val named = metadata.catalogue.symbols.filter { it.name == "string" }
        assertEquals(setOf(SymbolKind.NESTED_DESCRIBABLE, SymbolKind.DIRECTIVE_ENTRY), named.map { it.kind }.toSet())
        assertEquals("credentials-binding", named.single { it.kind == SymbolKind.NESTED_DESCRIBABLE }.provenance.plugin)
        assertEquals(Availability.UNKNOWN, named.single { it.kind == SymbolKind.NESTED_DESCRIBABLE }.availability)
        assertEquals(
            setOf("credentialsId", "variable"),
            metadata.nestedDescribables.getValue("string").namedParams.keys,
        )
    }

    @Test
    fun `merged metadata retains return type and unknown parameter requiredness`() {
        val step = JenkinsStepMetadata(
            name = "custom",
            plugin = "example",
            parameters = mapOf("value" to StepParameter("value", "String")),
            returnType = "example.Result",
        )
        val bundled = BundledJenkinsMetadata(steps = mapOf("custom" to step), globalVariables = emptyMap())
        val merged = MetadataMerger.mergeWithEnrichment(
            bundled,
            com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment(),
        )
        assertEquals("example.Result", merged.steps.getValue("custom").returnType)
        assertNull(merged.steps.getValue("custom").namedParams.getValue("value").required)
        assertEquals(Requiredness.UNKNOWN, merged.catalogue.symbols.single().parameters.single().requiredness)
    }

    @Test
    fun `custom executable string coexists with curated credential binding`() {
        val bundled = BundledJenkinsMetadataLoader().load()
        val custom = JenkinsStepMetadata(
            name = "string",
            plugin = "custom-plugin",
            parameters = emptyMap(),
            provenance = MetadataProvenance(SourceKind.STATIC_DISCOVERY, plugin = "custom-plugin"),
            ownerPlugin = "custom-plugin",
            ownerEvidence = OwnerEvidence.CURATED,
        )
        val metadata = MetadataMerger.mergeWithEnrichment(
            bundled.copy(steps = bundled.steps + ("string" to custom)),
            com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment(),
        )
        val matches = metadata.catalogue.symbols.filter { it.name == "string" }
        assertEquals(
            SourceKind.STATIC_DISCOVERY,
            matches.single { it.kind == SymbolKind.EXECUTABLE_STEP }.provenance.source,
        )
        assertEquals(SourceKind.CURATED, matches.single { it.kind == SymbolKind.NESTED_DESCRIBABLE }.provenance.source)
    }

    @Test
    fun `explicit overloads survive and alias lookup returns every candidate`() {
        val first = TypedSymbol(id = "a", name = "deploy", kind = SymbolKind.EXECUTABLE_STEP, aliases = listOf("ship"))
        val second = first.copy(id = "b")
        val catalogue = TypedCatalogue(symbols = listOf(first, second))
        assertEquals(listOf(first, second), catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "ship"))
        assertNull(catalogue.unique(SymbolKind.EXECUTABLE_STEP, "deploy"))
    }

    @Test
    fun `explicit typed entries replace only unique matching owner legacy fallback`() {
        val typed = TypedSymbol(
            id = "descriptor:a",
            name = "deploy",
            kind = SymbolKind.EXECUTABLE_STEP,
            ownerPlugin = "p",
            ownerEvidence = OwnerEvidence.DESCRIPTOR,
            provenance = MetadataProvenance(SourceKind.DESCRIPTOR_PROFILE, plugin = "p"),
        )
        val matching = typed.copy(id = "legacy:a", provenance = MetadataProvenance(SourceKind.CURATED, plugin = "p"))
        val unknownOwner = matching.copy(
            id = "legacy:unknown",
            ownerPlugin = null,
            provenance = MetadataProvenance(SourceKind.CURATED),
        )
        val result = TypedCatalogue(symbols = listOf(typed)).withLegacyFallbacks(listOf(matching, unknownOwner))
        assertEquals(listOf(typed, unknownOwner), result.symbols)
    }

    @Test
    fun `ambiguous override is retained but never applied to an arbitrary candidate`() {
        val first = TypedSymbol(id = "a", name = "deploy", kind = SymbolKind.EXECUTABLE_STEP)
        val second = first.copy(id = "b")
        val override = first.copy(id = "user", provenance = MetadataProvenance(SourceKind.USER_OVERRIDE))
        val result = TypedCatalogue(symbols = listOf(first, second)).withOverrides(listOf(override))
        assertEquals(listOf(first, second), result.symbols)
        assertEquals(listOf(override), result.unresolvedOverrides)
        assertNull(result.unique(SymbolKind.EXECUTABLE_STEP, "deploy"))
    }

    @Test
    fun `override id collisions retain existing entries and the unresolved override`() {
        val target = TypedSymbol(id = "target", name = "deploy", kind = SymbolKind.EXECUTABLE_STEP)
        val other = TypedSymbol(id = "occupied", name = "env", kind = SymbolKind.GLOBAL)
        val catalogue = TypedCatalogue(symbols = listOf(target, other))
        val sameId = catalogue.withOverrides(listOf(target.copy(documentation = "updated")))
        assertEquals("updated", sameId.unique(SymbolKind.EXECUTABLE_STEP, "deploy")?.documentation)
        assertEquals(emptyList(), sameId.unresolvedOverrides)
        val colliding = listOf(
            TypedSymbol(id = "occupied", name = "deploy", kind = SymbolKind.EXECUTABLE_STEP, replacesId = "target"),
            TypedSymbol(id = "occupied", name = "newStep", kind = SymbolKind.EXECUTABLE_STEP),
            TypedSymbol(
                id = "occupied",
                name = "newStep",
                kind = SymbolKind.EXECUTABLE_STEP,
                provenance = MetadataProvenance(SourceKind.USER_OVERRIDE, legacyPlugin = "p"),
            ),
        )
        for (override in colliding) {
            val result = catalogue.withOverrides(listOf(override))
            assertEquals(listOf(target, other), result.symbols)
            assertEquals(listOf(override), result.unresolvedOverrides)
        }
    }

    @Test
    fun `partial legacy patch retains known source lineage across JSON and replay`() {
        val original = TypedSymbol(
            id = "gdsl:0:method:deploy",
            sourceId = "method:deploy",
            sourceNamespace = "file:///fixture.gdsl",
            name = "deploy",
            kind = SymbolKind.EXECUTABLE_STEP,
            aliases = listOf("ship"),
            documentation = "original",
            provenance = MetadataProvenance(SourceKind.GDSL),
        )
        val patch =
            JenkinsStepMetadata(name = "deploy", plugin = "custom", parameters = emptyMap(), documentation = "patched")
        val patched = TypedCatalogue(symbols = listOf(original)).withLegacyOverrides(mapOf("deploy" to patch))
        val symbol = patched.symbols.single()
        assertEquals("patched", symbol.documentation)
        assertEquals(original.id, symbol.id)
        assertEquals(original.sourceNamespace, symbol.sourceNamespace)
        assertEquals(original.aliases, symbol.aliases)
        val decoded = Json.decodeFromString<TypedCatalogue>(Json.encodeToString(TypedCatalogue.serializer(), patched))
        assertEquals(patched, decoded)
        val replayed = decoded.withLegacyContributions(listOf(original))
        assertEquals(1, replayed.symbols.size)
        assertEquals("patched", replayed.symbols.single().documentation)
        assertEquals(patched, decoded.withLegacyOverrides(mapOf("deploy" to patch)))
        assertEquals(1, decoded.symbols.single().replacementLineage.size)
        val changed = original.copy(documentation = "changed upstream")
        val conflict = decoded.withLegacyContributions(listOf(changed))
        assertEquals(2, conflict.symbols.size)
        assertNull(conflict.unique(SymbolKind.EXECUTABLE_STEP, "deploy"))
        val unknown = original.copy(sourceNamespace = null)
        val unknownPatched = TypedCatalogue(symbols = listOf(unknown)).withLegacyOverrides(mapOf("deploy" to patch))
        assertEquals(emptyList(), unknownPatched.symbols.single().replacementLineage)
        assertEquals(2, unknownPatched.withLegacyContributions(listOf(unknown)).symbols.size)
    }

    @Test
    fun `unmatched legacy override id collision remains unresolved without replacing another symbol`() {
        val patch = JenkinsStepMetadata(
            name = "fresh",
            plugin = "declared-plugin",
            parameters = emptyMap(),
            provenance = MetadataProvenance(SourceKind.USER_OVERRIDE),
        )
        val generated = patch.toTypedSymbol()
        val unrelated = TypedSymbol(id = generated.id, name = "existing", kind = SymbolKind.GLOBAL)
        val empty = BundledJenkinsMetadata(steps = emptyMap(), globalVariables = emptyMap())
        val inserted = MetadataMerger.mergeWithPriority(
            empty,
            stable = emptyMap(),
            userOverrides = mapOf("fresh" to patch),
        )
        val insertedSymbol = assertNotNull(inserted.typedCatalogue).unique(SymbolKind.EXECUTABLE_STEP, "fresh")
        assertEquals("declared-plugin", assertNotNull(insertedSymbol).ownerPlugin)
        assertEquals(OwnerEvidence.USER_DECLARED, insertedSymbol.ownerEvidence)

        val matching = TypedSymbol(
            id = "known",
            name = "fresh",
            kind = SymbolKind.EXECUTABLE_STEP,
            ownerPlugin = "existing-plugin",
            ownerEvidence = OwnerEvidence.CURATED,
        )
        val matched = MetadataMerger.mergeWithPriority(
            empty.copy(typedCatalogue = TypedCatalogue(symbols = listOf(matching))),
            stable = emptyMap(),
            userOverrides = mapOf("fresh" to patch),
        )
        val matchedSymbol =
            assertNotNull(assertNotNull(matched.typedCatalogue).unique(SymbolKind.EXECUTABLE_STEP, "fresh"))
        assertEquals("existing-plugin", matchedSymbol.ownerPlugin)
        assertEquals(OwnerEvidence.CURATED, matchedSymbol.ownerEvidence)
        assertEquals("declared-plugin", matchedSymbol.contributors.single().legacyPlugin)

        val encoded = Json.encodeToString(TypedCatalogue.serializer(), TypedCatalogue(symbols = listOf(unrelated)))
        val decoded = Json.decodeFromString(TypedCatalogue.serializer(), encoded)
        assertEquals(listOf(unrelated), decoded.symbols)
        val input = empty.copy(typedCatalogue = decoded)
        val result = MetadataMerger.mergeWithPriority(
            input,
            stable = emptyMap(),
            userOverrides = mapOf("fresh" to patch),
        )
        val catalogue = assertNotNull(result.typedCatalogue)
        assertEquals(listOf(unrelated), catalogue.symbols)
        val unresolved = catalogue.unresolvedOverrides.single()
        assertEquals(generated.id, unresolved.id)
        assertEquals("fresh", unresolved.name)
        assertEquals(SourceKind.USER_OVERRIDE, unresolved.provenance.source)
        assertEquals("declared-plugin", unresolved.provenance.legacyPlugin)
        assertNull(unresolved.ownerPlugin)
        assertEquals(OwnerEvidence.UNKNOWN, unresolved.ownerEvidence)
        val enriched = MetadataMerger.mergeWithEnrichment(result, JenkinsEnrichment())
        assertEquals(listOf(unrelated), enriched.catalogue.symbols)
        assertEquals(listOf(unresolved), enriched.catalogue.unresolvedOverrides)
    }

    @Test
    fun `legacy JSON accepts explicit typed catalogue and rejects unsupported or duplicate identity`() {
        val symbol = TypedSymbol(id = "one", name = "deploy", kind = SymbolKind.EXECUTABLE_STEP)
        val json = Json.encodeToString(TypedCatalogue.serializer(), TypedCatalogue(symbols = listOf(symbol)))
        val input = """{"steps":{},"globalVariables":{},"typedCatalogue":$json}"""
        val metadata = Json.decodeFromString<MetadataJson>(input).toBundledMetadata()
        assertEquals(symbol, metadata.toTypedCatalogue().symbols.single())
        assertFailsWith<IllegalArgumentException> { TypedCatalogue(schemaMajor = 2) }
        assertFailsWith<IllegalArgumentException> { TypedCatalogue(symbols = listOf(symbol, symbol)) }
    }

    @Test
    fun `unique override preserves target owner and conflicting owner stays ambiguous`() {
        val target = TypedSymbol(id = "target", name = "deploy", kind = SymbolKind.EXECUTABLE_STEP, ownerPlugin = "p")
        val override = TypedSymbol(
            id = "user",
            name = "deploy",
            kind = SymbolKind.EXECUTABLE_STEP,
            provenance = MetadataProvenance(SourceKind.USER_OVERRIDE),
        )
        val result = TypedCatalogue(symbols = listOf(target)).withOverrides(listOf(override))
        assertEquals("p", result.symbols.single().ownerPlugin)
        assertEquals(SourceKind.USER_OVERRIDE, result.symbols.single().provenance.source)
        val conflict = override.copy(id = "conflict", ownerPlugin = "other")
        val conflicted = TypedCatalogue(symbols = listOf(target)).withOverrides(listOf(conflict))
        assertEquals(target, conflicted.unique(target.kind, target.name))
        assertEquals(listOf(conflict), conflicted.unresolvedOverrides)
    }

    @Test
    fun `matching known owner legacy contributions preserve both parameter sets`() {
        val bundled = TypedSymbol(
            id = "bundled",
            name = "sh",
            kind = SymbolKind.EXECUTABLE_STEP,
            ownerPlugin = "workflow-durable-task-step",
            ownerEvidence = OwnerEvidence.CURATED,
            legacyDerived = true,
            parameters = listOf(TypedParameter("script", "String", Requiredness.REQUIRED)),
        )
        val scanned = bundled.copy(
            id = "second",
            parameters = listOf(TypedParameter("extra", "String")),
        )
        val merged = TypedCatalogue(symbols = listOf(bundled)).withLegacyContributions(listOf(scanned))
        assertEquals(setOf("script", "extra"), merged.symbols.single().parameters.map { it.name }.toSet())
    }

    @Test
    fun `decoder through merger preserves explicit typed overload ambiguity`() {
        val first = TypedSymbol(
            id = "a",
            name = "deploy",
            kind = SymbolKind.EXECUTABLE_STEP,
            ownerPlugin = "p",
            ownerEvidence = OwnerEvidence.DESCRIPTOR,
        )
        val second = first.copy(id = "b", parameters = listOf(TypedParameter("target", "String")))
        val typed = Json.encodeToString(TypedCatalogue.serializer(), TypedCatalogue(symbols = listOf(first, second)))
        val input = """{"steps":{},"globalVariables":{},"typedCatalogue":$typed}"""
        val decoded = Json.decodeFromString<MetadataJson>(input).toBundledMetadata()
        val merged = MetadataMerger.mergeWithEnrichment(
            decoded,
            com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment(),
        )
        assertEquals(listOf(first, second), merged.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deploy"))
        assertNull(merged.getStep("deploy"))
    }

    @Test
    fun `explicit unknown and empty fields stay authoritative through decoder and merge`() {
        val typed = TypedSymbol(
            id = "descriptor:deploy",
            name = "deploy",
            kind = SymbolKind.EXECUTABLE_STEP,
            ownerPlugin = "p",
            ownerEvidence = OwnerEvidence.DESCRIPTOR,
            parameters = emptyList(),
            block = Knowledge.UNKNOWN,
        )
        val json = Json.encodeToString(TypedCatalogue.serializer(), TypedCatalogue(symbols = listOf(typed)))
        val input =
            """{"steps":{"deploy":{"plugin":"p","parameters":{"old":{"type":"String",""" +
                """"required":true}}}},"globalVariables":{},"typedCatalogue":$json}"""
        val merged = MetadataMerger.mergeWithEnrichment(
            Json.decodeFromString<MetadataJson>(input).toBundledMetadata(),
            com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment(),
        )
        assertEquals(typed, merged.catalogue.unique(SymbolKind.EXECUTABLE_STEP, "deploy"))
        assertEquals(emptyMap(), merged.getStep("deploy")?.namedParams)
    }

    @Test
    fun `programmatic legacy plugin is retained as unattributed input evidence`() {
        val step = JenkinsStepMetadata(name = "custom", plugin = "input-label", parameters = emptyMap())
        val bundled = BundledJenkinsMetadata(steps = mapOf("custom" to step), globalVariables = emptyMap())
        val merged = MetadataMerger.mergeWithEnrichment(
            bundled,
            com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment(),
        )
        val symbol = assertNotNull(merged.catalogue.unique(SymbolKind.EXECUTABLE_STEP, "custom"))
        assertNull(symbol.ownerPlugin)
        assertEquals("input-label", symbol.contributors.single().legacyPlugin)
        assertNull(assertNotNull(merged.getStep("custom")).plugin)
    }

    @Test
    fun `mergeAll retains unknown-owner legacy symbols from separate documents`() {
        fun source() = BundledJenkinsMetadata(
            steps = mapOf("custom" to JenkinsStepMetadata("custom", "unattributed", parameters = emptyMap())),
            globalVariables = mapOf("env" to GlobalVariableMetadata("env", "Env")),
            agentTypes = mapOf("any" to AgentTypeMetadata("any", emptyMap())),
        )
        val merged = MetadataMerger.mergeWithEnrichment(
            MetadataMerger.mergeAll(source(), versioned = source(), stable = emptyMap()),
            com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment(),
        )
        for ((kind, name) in listOf(
            SymbolKind.EXECUTABLE_STEP to "custom",
            SymbolKind.GLOBAL to "env",
            SymbolKind.AGENT to "any",
        )) {
            val candidates = merged.catalogue.candidates(kind, name)
            assertEquals(2, candidates.size, "$kind / $name must retain both sources")
            assertEquals(2, candidates.map { it.id }.distinct().size)
        }
        assertNull(merged.getStep("custom"))
        assertNull(merged.getGlobalVariable("env"))
    }

    @Test
    fun `round-tripped explicit same id wins over legacy values through decoder merger and context`() {
        val legacyJson =
            """{"steps":{"echo":{"plugin":"workflow-basic-steps",""" +
                """"parameters":{"message":{"type":"String"}},"documentation":"old step"}},""" +
                """"globalVariables":{"env":{"type":"OldEnv","documentation":"old global"}}}"""
        val legacy = Json.decodeFromString<MetadataJson>(legacyJson).toBundledMetadata()
        val fallback = legacy.toTypedCatalogue()
        val updated = fallback.copy(
            symbols = fallback.symbols.map { symbol ->
                when (symbol.kind) {
                    SymbolKind.GLOBAL -> symbol.copy(returnType = "NewEnv", documentation = "new global")
                    SymbolKind.EXECUTABLE_STEP -> symbol.copy(documentation = "new step", parameters = emptyList())
                    else -> symbol
                }
            },
        )
        val typedJson = Json.encodeToString(TypedCatalogue.serializer(), updated)
        val input = legacyJson.dropLast(1) + ""","typedCatalogue":$typedJson}"""
        val decoded = Json.decodeFromString<MetadataJson>(input).toBundledMetadata()
        val merged = MetadataMerger.mergeWithEnrichment(
            decoded,
            com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment(),
        )
        val context = com.github.albertocavalcante.gvy.jenkins.JenkinsContext(
            configuration = com.github.albertocavalcante.gvy.jenkins.JenkinsConfiguration(),
            workspaceRoot = tempDir,
            pluginManager = com.github.albertocavalcante.gvy.jenkins.JenkinsPluginManager(),
            bundledMetadataLoader = { merged },
            classpathScanner = { BundledJenkinsMetadata(steps = emptyMap(), globalVariables = emptyMap()) },
        )
        val actual = context.getAllMetadata(null)
        assertEquals("NewEnv", actual.getGlobalVariable("env")?.type)
        assertEquals("new global", actual.getGlobalVariable("env")?.documentation)
        assertEquals("new step", actual.getStep("echo")?.documentation)
        assertEquals(emptyMap(), actual.getStep("echo")?.namedParams)
    }

    @Test
    fun `colliding document scoped typed identities remain distinct across sources`() {
        val first = TypedSymbol(id = "descriptor:1", name = "deploy", kind = SymbolKind.EXECUTABLE_STEP)
        val second = first.copy(documentation = "other source")
        val a = BundledJenkinsMetadata(
            steps = emptyMap(),
            globalVariables = emptyMap(),
            typedCatalogue = TypedCatalogue(symbols = listOf(first)),
        )
        val b = BundledJenkinsMetadata(
            steps = emptyMap(),
            globalVariables = emptyMap(),
            typedCatalogue = TypedCatalogue(symbols = listOf(second)),
        )
        val merged = MetadataMerger.mergeWithEnrichment(
            MetadataMerger.mergeAll(a, versioned = b, stable = emptyMap()),
            com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment(),
        )
        val candidates = merged.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deploy")
        assertEquals(2, candidates.size)
        assertEquals(2, candidates.map { it.id }.distinct().size)
        assertEquals("descriptor:1", candidates.single { it.documentation == "other source" }.sourceId)
        assertNull(merged.getStep("deploy"))
    }

    @Test
    fun `typed replacement with another candidate id collision preserves both`() {
        val legacy = TypedSymbol(
            id = "legacy:deploy",
            name = "deploy",
            kind = SymbolKind.EXECUTABLE_STEP,
            ownerPlugin = "p",
            ownerEvidence = OwnerEvidence.CURATED,
            legacyDerived = true,
        )
        val other = TypedSymbol(id = "descriptor:1", name = "other", kind = SymbolKind.GLOBAL)
        val replacement = TypedSymbol(
            id = "descriptor:1",
            name = "deploy",
            kind = SymbolKind.EXECUTABLE_STEP,
            ownerPlugin = "p",
            ownerEvidence = OwnerEvidence.DESCRIPTOR,
        )
        val merged = TypedCatalogue(symbols = listOf(legacy, other)).withLegacyContributions(listOf(replacement))
        assertEquals(2, merged.symbols.size)
        assertEquals(2, merged.symbols.map { it.id }.distinct().size)
        assertEquals("descriptor:1", merged.unique(SymbolKind.EXECUTABLE_STEP, "deploy")?.sourceId)
        assertEquals(other, merged.unique(SymbolKind.GLOBAL, "other"))
    }

    @Test
    fun `later same-owner contribution survives enrichment and unrelated legacy base`() {
        val first = JenkinsStepMetadata(
            name = "deploy",
            plugin = "p",
            ownerPlugin = "p",
            ownerEvidence = OwnerEvidence.CURATED,
            parameters = mapOf("first" to StepParameter("first", "String")),
        )
        val later = first.copy(parameters = mapOf("later" to StepParameter("later", "String")))
        val base = BundledJenkinsMetadata(
            steps = mapOf("deploy" to first),
            globalVariables = emptyMap(),
            legacyBaseSteps = mapOf("other" to JenkinsStepMetadata("other", "other", parameters = emptyMap())),
        )
        val merged = MetadataMerger.mergeWithEnrichment(
            MetadataMerger.mergeAll(base, stable = emptyMap(), dynamic = mapOf("deploy" to later)),
            com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment(),
        )
        assertEquals(setOf("first", "later"), assertNotNull(merged.getStep("deploy")).namedParams.keys)
    }
}
