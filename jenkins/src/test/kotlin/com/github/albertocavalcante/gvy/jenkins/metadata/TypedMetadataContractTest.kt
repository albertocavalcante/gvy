// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.metadata

import com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TypedMetadataContractTest {
    @Test
    fun `unavailable entries remain raw facts but leave every actionable projection`() {
        val symbols = Availability.entries.flatMap { availability ->
            listOf(
                symbol("step", SymbolKind.EXECUTABLE_STEP, availability),
                symbol("binding", SymbolKind.NESTED_DESCRIBABLE, availability),
                symbol("global", SymbolKind.GLOBAL, availability),
                symbol("option", SymbolKind.OPTION, availability).copy(contexts = listOf("options")),
                symbol("tool", SymbolKind.TOOL, availability).copy(contexts = listOf("tools")),
                symbol("trigger", SymbolKind.TRIGGER, availability).copy(contexts = listOf("triggers")),
                symbol("parameter", SymbolKind.DIRECTIVE_ENTRY, availability).copy(contexts = listOf("parameters")),
            )
        }
        val catalogue = TypedCatalogue(symbols = symbols)
        val merged = merge(catalogue)

        assertEquals(symbols, merged.catalogue.symbols, "Availability remains available for explanation")
        for (availability in Availability.entries) {
            val actionable = availability != Availability.UNAVAILABLE
            assertEquals(actionable, "step-$availability" in merged.steps)
            assertEquals(actionable, "binding-$availability" in merged.nestedDescribables)
            assertEquals(actionable, "global-$availability" in merged.globalVariables)
            assertEquals(actionable, "option-$availability" in merged.declarativeOptions)
            assertEquals(actionable, "tool-$availability" in merged.directiveEntries.getValue("tools"))
            assertEquals(actionable, "trigger-$availability" in merged.directiveEntries.getValue("triggers"))
            assertEquals(actionable, "parameter-$availability" in merged.directiveEntries.getValue("parameters"))
            assertEquals(actionable, "step-$availability" in catalogue.projectBundledSteps())
        }
    }

    @Test
    fun `an unavailable descriptor is not resurrected by its same owner legacy fallback`() {
        val descriptor = symbol("deploy", SymbolKind.EXECUTABLE_STEP, Availability.UNAVAILABLE).copy(name = "deploy")
        val fallback = JenkinsStepMetadata(
            name = "deploy",
            plugin = "fixture-plugin",
            ownerPlugin = "fixture-plugin",
            ownerEvidence = OwnerEvidence.CURATED,
            parameters = emptyMap(),
        )
        val merged = MetadataMerger.mergeWithEnrichment(
            BundledJenkinsMetadata(
                steps = mapOf("deploy" to fallback),
                globalVariables = emptyMap(),
                typedCatalogue = TypedCatalogue(symbols = listOf(descriptor)),
            ),
            JenkinsEnrichment(),
        )

        assertEquals(listOf(descriptor), merged.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deploy"))
        assertNull(merged.getStep("deploy"))
    }

    @Test
    fun `constructor snapshots detach every caller owned collection`() {
        assertCallerCollectionsDetached(useCopy = false)
    }

    @Test
    fun `copy snapshots detach every replacement collection`() {
        assertCallerCollectionsDetached(useCopy = true)
    }

    @Test
    fun `constructed copied and decoded public lists cannot mutate a published catalogue`() {
        val original = completeCatalogue()
        val publications = listOf(original, original.copy(), Json.decodeFromString<TypedCatalogue>(encode(original)))
        for (catalogue in publications) {
            val expected = encode(catalogue)
            val entry = catalogue.symbols.single()
            val mutations = listOf<() -> Unit>(
                { attemptAdd(catalogue.symbols, entry) },
                { attemptAdd(catalogue.unresolvedOverrides, entry) },
                { attemptAdd(entry.aliases, "another") },
                { attemptAdd(entry.parameters, TypedParameter("another", "String")) },
                { attemptAdd(entry.parameters.single().validValues.orEmpty(), "another") },
                { attemptAdd(entry.positional, "another") },
                { attemptAdd(entry.contexts.orEmpty(), "another") },
                { attemptAdd(entry.valueDomain.orEmpty(), "another") },
                { attemptAdd(entry.contributors, MetadataProvenance(SourceKind.UNKNOWN)) },
            )
            mutations.forEachIndexed { index, mutation ->
                mutation()
                assertEquals(expected, encode(catalogue), "Published collection $index changed")
            }
        }
    }

    @Test
    fun `blank canonical names are rejected by construction copy and decoding`() {
        for (blank in listOf("", " ", "\t\n")) {
            assertFailsWith<IllegalArgumentException> {
                TypedSymbol(id = "blank", name = blank, kind = SymbolKind.EXECUTABLE_STEP)
            }
            assertFailsWith<IllegalArgumentException> { completeCatalogue().symbols.single().copy(name = blank) }
            val name = Json.encodeToString(String.serializer(), blank)
            val json = """{"id":"blank","name":$name,"kind":"GLOBAL"}"""
            assertFailsWith<IllegalArgumentException> { Json.decodeFromString<TypedSymbol>(json) }
        }
    }

    @Test
    fun `blank parameter names are rejected by construction copy and decoding`() {
        for (blank in listOf("", " ", "\t\n")) {
            assertFailsWith<IllegalArgumentException> { TypedParameter(blank, "String") }
            assertFailsWith<IllegalArgumentException> { TypedParameter("valid", "String").copy(name = blank) }
            val name = Json.encodeToString(String.serializer(), blank)
            assertFailsWith<IllegalArgumentException> {
                Json.decodeFromString<TypedParameter>("""{"name":$name,"type":null}""")
            }
        }
    }

    @Test
    fun `blank aliases are rejected by construction copy and decoding`() {
        for (blank in listOf("", " ", "\t\n")) {
            assertFailsWith<IllegalArgumentException> {
                TypedSymbol(id = "alias", name = "valid", kind = SymbolKind.GLOBAL, aliases = listOf(blank))
            }
            assertFailsWith<IllegalArgumentException> {
                completeCatalogue().symbols.single().copy(aliases = listOf(blank))
            }
            val alias = Json.encodeToString(String.serializer(), blank)
            val json = """{"id":"alias","name":"valid","kind":"GLOBAL","aliases":[$alias]}"""
            assertFailsWith<IllegalArgumentException> { Json.decodeFromString<TypedSymbol>(json) }
        }
    }

    @Test
    fun `nonblank quoted names and keywords are preserved without an identifier guess`() {
        for (name in listOf("class", "with-dash", "has space", " padded ")) {
            val entry = TypedSymbol(
                id = "valid",
                name = name,
                kind = SymbolKind.EXECUTABLE_STEP,
                aliases = listOf(name),
                parameters = listOf(TypedParameter(name, null)),
            )
            val roundTrip = Json.decodeFromString<TypedSymbol>(Json.encodeToString(TypedSymbol.serializer(), entry))
            assertEquals(entry, roundTrip)
            assertEquals(name, roundTrip.name)
        }
    }

    @Test
    fun `empty catalogue and explicit nullable type retain their existing wire meaning`() {
        assertEquals(TypedCatalogue(), Json.decodeFromString<TypedCatalogue>("{}"))
        val unknown = Json.decodeFromString<TypedParameter>("""{"name":"value","type":null}""")
        assertNull(unknown.type)
        assertEquals(Requiredness.UNKNOWN, unknown.requiredness)
        assertFailsWith<SerializationException> {
            Json.decodeFromString<TypedParameter>("""{"name":"value"}""")
        }
        val absentLists = Json.decodeFromString<TypedSymbol>(
            """{"id":"empty","name":"empty","kind":"EXECUTABLE_STEP"}""",
        )
        assertEquals(emptyList(), absentLists.parameters)
        assertEquals(emptyList(), absentLists.aliases)
        assertEquals(emptyList(), absentLists.positional)
        assertNull(absentLists.contexts)
        assertNull(absentLists.valueDomain)
        assertFalse(absentLists.contexts == emptyList<String>())
    }

    @Test
    fun `raw GDSL context snapshots detach nested JSON construction copy and decoder inputs`() {
        val scope = mutableMapOf<String, JsonElement>("kind" to JsonPrimitive("closure"))
        val filetypes = mutableListOf<JsonElement>(JsonPrimitive("groovy"))
        val input = mutableMapOf<String, JsonElement>(
            "scope" to JsonObject(scope),
            "filetypes" to JsonArray(filetypes),
        )
        val context = JsonObject(input)
        val seed = TypedSymbol("context", name = "context", kind = SymbolKind.EXECUTABLE_STEP)
        val constructed = seed.copy(gdslContext = context)
        val direct = TypedSymbol("context", name = "context", kind = SymbolKind.EXECUTABLE_STEP, gdslContext = context)
        val wire = Json.encodeToJsonElement(TypedSymbol.serializer(), seed).jsonObject.toMutableMap()
        wire["gdslContext"] = context
        val decoded = Json.decodeFromJsonElement(TypedSymbol.serializer(), JsonObject(wire))
        val publications = listOf(constructed, direct, decoded)
        val expected = publications.map { Json.encodeToString(TypedSymbol.serializer(), it) }

        scope["kind"] = JsonPrimitive("changed")
        filetypes += JsonPrimitive("changed")
        input["extra"] = JsonPrimitive("changed")
        wire["name"] = JsonPrimitive("changed")
        assertEquals(expected, publications.map { Json.encodeToString(TypedSymbol.serializer(), it) })

        for ((index, publication) in publications.withIndex()) {
            val snapshot = checkNotNull(publication.gdslContext)
            attemptPut(snapshot, "extra", JsonPrimitive("changed"))
            attemptPut(snapshot.getValue("scope").jsonObject, "kind", JsonPrimitive("changed"))
            attemptAdd(snapshot.getValue("filetypes").jsonArray, JsonPrimitive("changed"))
            assertEquals(expected[index], Json.encodeToString(TypedSymbol.serializer(), publication))
        }
    }

    private fun attemptPut(values: Map<String, JsonElement>, key: String, value: JsonElement) {
        val mutable = values as? MutableMap<String, JsonElement> ?: return
        try {
            mutable[key] = value
        } catch (_: UnsupportedOperationException) {
            // Immutable collection views may reject mutation instead of exposing a mutable interface.
        }
    }

    private fun assertCallerCollectionsDetached(useCopy: Boolean) {
        val values = mutableListOf("first")
        val parameter = if (useCopy) {
            TypedParameter("value", "String").copy(validValues = values)
        } else {
            TypedParameter("value", "String", validValues = values)
        }
        val aliases = mutableListOf("alias")
        val parameters = mutableListOf(parameter)
        val positional = mutableListOf("value")
        val contexts = mutableListOf("steps")
        val domain = mutableListOf("allowed")
        val contributors = mutableListOf(MetadataProvenance(SourceKind.GDSL))
        val entry = if (useCopy) {
            TypedSymbol("one", name = "sample", kind = SymbolKind.EXECUTABLE_STEP).copy(
                aliases = aliases,
                parameters = parameters,
                positional = positional,
                contexts = contexts,
                valueDomain = domain,
                contributors = contributors,
            )
        } else {
            TypedSymbol(
                id = "one",
                name = "sample",
                kind = SymbolKind.EXECUTABLE_STEP,
                aliases = aliases,
                parameters = parameters,
                positional = positional,
                contexts = contexts,
                valueDomain = domain,
                contributors = contributors,
            )
        }
        val symbols = mutableListOf(entry)
        val overrides = mutableListOf(entry.copy(id = "override"))
        val catalogue = if (useCopy) {
            TypedCatalogue().copy(symbols = symbols, unresolvedOverrides = overrides)
        } else {
            TypedCatalogue(symbols = symbols, unresolvedOverrides = overrides)
        }
        val before = encode(catalogue)

        values += "mutated"
        aliases += "mutated"
        parameters += TypedParameter("mutated", "String")
        positional += "mutated"
        contexts += "mutated"
        domain += "mutated"
        contributors += MetadataProvenance(SourceKind.USER_OVERRIDE)
        symbols += entry
        overrides.clear()

        assertEquals(before, encode(catalogue), "A caller mutation changed the validated publication")
        assertEquals(1, catalogue.symbols.size, "A duplicate ID bypassed constructor validation")
    }

    private fun completeCatalogue(): TypedCatalogue = TypedCatalogue(
        symbols = listOf(
            TypedSymbol(
                id = "one",
                name = "sample",
                kind = SymbolKind.EXECUTABLE_STEP,
                aliases = listOf("alias"),
                parameters = listOf(TypedParameter("value", "String", validValues = listOf("first"))),
                positional = listOf("value"),
                contexts = listOf("steps"),
                valueDomain = listOf("allowed"),
                contributors = listOf(MetadataProvenance(SourceKind.GDSL)),
            ),
        ),
        unresolvedOverrides = listOf(TypedSymbol("override", name = "sample", kind = SymbolKind.EXECUTABLE_STEP)),
    )

    private fun <T> attemptAdd(values: List<T>, value: T) {
        val mutable = values as? MutableList<T> ?: return
        try {
            mutable.add(value)
        } catch (_: UnsupportedOperationException) {
            // An immutable public view is an acceptable implementation of the contract.
        }
    }

    private fun encode(catalogue: TypedCatalogue): String = Json.encodeToString(TypedCatalogue.serializer(), catalogue)

    private fun symbol(name: String, kind: SymbolKind, availability: Availability): TypedSymbol = TypedSymbol(
        id = "$name-$availability",
        name = "$name-$availability",
        kind = kind,
        availability = availability,
        ownerPlugin = "fixture-plugin",
        ownerEvidence = OwnerEvidence.DESCRIPTOR,
        returnType = "String",
        provenance = MetadataProvenance(SourceKind.DESCRIPTOR_PROFILE, plugin = "fixture-plugin"),
    )

    private fun merge(catalogue: TypedCatalogue): MergedJenkinsMetadata = MetadataMerger.mergeWithEnrichment(
        BundledJenkinsMetadata(steps = emptyMap(), globalVariables = emptyMap(), typedCatalogue = catalogue),
        JenkinsEnrichment(),
    )
}
