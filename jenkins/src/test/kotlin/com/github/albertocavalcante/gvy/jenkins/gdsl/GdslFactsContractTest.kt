// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.gdsl

import com.github.albertocavalcante.gvy.gdsl.model.GdslParseResult
import com.github.albertocavalcante.gvy.gdsl.model.MethodDescriptor
import com.github.albertocavalcante.gvy.gdsl.model.PropertyDescriptor
import com.github.albertocavalcante.gvy.jenkins.JenkinsConfiguration
import com.github.albertocavalcante.gvy.jenkins.JenkinsContext
import com.github.albertocavalcante.gvy.jenkins.metadata.MetadataMerger
import com.github.albertocavalcante.gvy.jenkins.metadata.SourceKind
import com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind
import com.github.albertocavalcante.gvy.jenkins.metadata.TypedCatalogue
import com.github.albertocavalcante.gvy.jenkins.metadata.TypedSymbol
import com.github.albertocavalcante.gvy.jenkins.metadata.extracted.StepScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GdslFactsContractTest {
    @TempDir
    lateinit var tempDir: Path

    private val adapter = JenkinsGdslAdapter()

    @Test
    fun `method context and source origin survive the canonical wire round trip`() {
        val method = MethodDescriptor(
            name = "scoped",
            returnType = "example.Result",
            context = mapOf(
                "ctype" to "example.Target",
                "scope" to mapOf("kind" to "closure", "isArg" to true),
                "filetypes" to listOf("groovy", "Jenkinsfile"),
            ),
            enclosingCall = "node",
        )
        val metadata = adapter.convert(GdslParseResult(listOf(method), emptyList()), pluginSource = "fixture.gdsl")
        val symbol = assertNotNull(metadata.typedCatalogue).symbols.single()
        val expectedContext = Json.parseToJsonElement(
            """
            {
                "ctype":"example.Target",
                "scope":{"kind":"closure","isArg":true},
                "filetypes":["groovy","Jenkinsfile"]
            }
            """.trimIndent(),
        )

        assertEquals(expectedContext, wire(symbol)["gdslContext"])
        assertEquals(JsonPrimitive("fixture.gdsl"), wire(symbol).getValue("provenance").jsonObject["origin"])
        assertEquals(SourceKind.GDSL, symbol.provenance.source)
        assertNull(symbol.provenance.plugin, "A source label is not evidence of plugin ownership")
        assertNull(symbol.ownerPlugin)
        assertEquals(listOf("node"), symbol.contexts)
        val decoded = Json.decodeFromString<TypedSymbol>(Json.encodeToString(TypedSymbol.serializer(), symbol))
        assertEquals(wire(symbol), wire(decoded))
    }

    @Test
    fun `source origin stays distinct from legacy plugin label across files`() {
        val parsed = GdslParseResult(
            methods = listOf(MethodDescriptor("deploy", "String")),
            properties = listOf(PropertyDescriptor("deployment", "String")),
        )
        val first = adapter.convert(
            parsed,
            pluginSource = "user-gdsl",
            sourceOrigin = "first.gdsl",
            sourceNamespace = "first",
        )
        val second = adapter.convert(
            parsed,
            pluginSource = "user-gdsl",
            sourceOrigin = "second.gdsl",
            sourceNamespace = "second",
        )
        val merged = MetadataMerger.mergeAll(first, versioned = second, stable = emptyMap())
        val catalogue = assertNotNull(merged.typedCatalogue)

        assertEquals("user-gdsl", assertNotNull(first.getStep("deploy")).plugin)
        for ((kind, name) in listOf(SymbolKind.EXECUTABLE_STEP to "deploy", SymbolKind.GLOBAL to "deployment")) {
            val candidates = catalogue.candidates(kind, name)
            assertEquals(setOf("first.gdsl", "second.gdsl"), candidates.map { it.provenance.origin }.toSet())
            assertEquals(2, candidates.size)
            candidates.forEach { candidate ->
                assertEquals(SourceKind.GDSL, candidate.provenance.source)
                assertNull(candidate.provenance.plugin)
                assertNull(candidate.ownerPlugin)
            }
        }
    }

    @Test
    fun `colliding declaration ids remain stable through repeated source merge and serialization`() {
        val parsed = GdslParseResult(listOf(MethodDescriptor("deploy", "String")), emptyList())
        val first = adapter.convert(parsed, sourceOrigin = "a.gdsl", sourceNamespace = "a")
        val second = adapter.convert(parsed, sourceOrigin = "b.gdsl", sourceNamespace = "b")
        val merged = MetadataMerger.mergeAll(first, versioned = second, stable = emptyMap())
        val repeatedSecond = MetadataMerger.mergeAll(merged, versioned = second, stable = emptyMap())
        val repeatedFirst = MetadataMerger.mergeAll(repeatedSecond, versioned = first, stable = emptyMap())
        val catalogue = assertNotNull(repeatedFirst.typedCatalogue)
        val encoded = Json.encodeToString(TypedCatalogue.serializer(), catalogue)
        val decoded = Json.decodeFromString(TypedCatalogue.serializer(), encoded)
        val reconstructed = repeatedFirst.copy(typedCatalogue = decoded)
        val repeatedDecoded = MetadataMerger.mergeAll(reconstructed, versioned = second, stable = emptyMap())
        val candidates = assertNotNull(repeatedDecoded.typedCatalogue).candidates(SymbolKind.EXECUTABLE_STEP, "deploy")

        assertEquals(2, candidates.size)
        assertEquals(setOf("a.gdsl", "b.gdsl"), candidates.map { it.provenance.origin }.toSet())
        assertEquals(2, candidates.map { it.id }.distinct().size)
        assertNull(repeatedDecoded.getStep("deploy"))
    }

    @Test
    fun `anonymous ingestions remain distinct but each copied symbol retains its identity`() {
        val parsed = GdslParseResult(listOf(MethodDescriptor("deploy", "String")), emptyList())
        val first = adapter.convert(parsed)
        val second = adapter.convert(parsed)
        val firstCopy = first.copy(typedCatalogue = assertNotNull(first.typedCatalogue).copy())
        val once = MetadataMerger.mergeAll(first, versioned = firstCopy, stable = emptyMap())
        assertEquals(1, assertNotNull(once.typedCatalogue).symbols.size)
        val twice = MetadataMerger.mergeAll(once, versioned = second, stable = emptyMap())
        assertEquals(2, assertNotNull(twice.typedCatalogue).symbols.size)
    }

    @Test
    fun `conflicting facts for one source identity stay ambiguous without repeated multiplication`() {
        val parsed = GdslParseResult(listOf(MethodDescriptor("deploy", "String")), emptyList())
        val first = adapter.convert(parsed, sourceOrigin = "a.gdsl", sourceNamespace = "a")
        val symbol = assertNotNull(first.typedCatalogue).symbols.single()
        val conflicting = first.copy(
            steps = emptyMap(),
            typedCatalogue = TypedCatalogue(symbols = listOf(symbol.copy(returnType = "Integer"))),
        )
        val combined = MetadataMerger.mergeAll(first, versioned = conflicting, stable = emptyMap())
        val encoded = Json.encodeToString(TypedCatalogue.serializer(), assertNotNull(combined.typedCatalogue))
        val reconstructed = combined.copy(typedCatalogue = Json.decodeFromString(TypedCatalogue.serializer(), encoded))
        val repeated = MetadataMerger.mergeAll(reconstructed, versioned = conflicting, stable = emptyMap())
        val candidates = assertNotNull(repeated.typedCatalogue).candidates(SymbolKind.EXECUTABLE_STEP, "deploy")

        assertEquals(2, candidates.size)
        assertEquals(setOf("String", "Integer"), candidates.map { it.returnType }.toSet())
        assertNull(repeated.getStep("deploy"))
    }

    @Test
    fun `same named properties retain every context and origin instead of choosing the last`() {
        val properties = listOf(
            PropertyDescriptor("shared", "String", context = mapOf("ctype" to "example.First")),
            PropertyDescriptor("shared", "Integer", context = mapOf("ctype" to "example.Second")),
        )
        val metadata = adapter.convert(GdslParseResult(emptyList(), properties), pluginSource = "properties.gdsl")
        val candidates = assertNotNull(metadata.typedCatalogue).candidates(SymbolKind.GLOBAL, "shared")

        assertEquals(2, candidates.size)
        assertEquals(2, candidates.map { it.id }.distinct().size)
        assertEquals(setOf("String", "Integer"), candidates.map { it.returnType }.toSet())
        assertEquals(
            setOf(
                Json.parseToJsonElement("""{"ctype":"example.First"}"""),
                Json.parseToJsonElement("""{"ctype":"example.Second"}"""),
            ),
            candidates.map { wire(it)["gdslContext"] }.toSet(),
        )
        for (candidate in candidates) {
            assertEquals(SourceKind.GDSL, candidate.provenance.source)
            assertEquals(JsonPrimitive("properties.gdsl"), wire(candidate).getValue("provenance").jsonObject["origin"])
            assertNull(candidate.provenance.plugin)
        }
        assertNull(metadata.globalVariables["shared"], "A singular legacy view cannot choose a contextual property")
    }

    @Test
    fun `scoped GDSL contributions are not advertised as unqualified steps or globals`() {
        val parsed = GdslParseResult(
            methods = listOf(
                MethodDescriptor("onClass", "void", context = mapOf("ctype" to "example.Target")),
                MethodDescriptor(
                    "insideNode",
                    "void",
                    context = mapOf("scope" to mapOf("kind" to "closure")),
                    enclosingCall = "node",
                ),
            ),
            properties = listOf(
                PropertyDescriptor("classProperty", "String", context = mapOf("ctype" to "example.Target")),
            ),
        )
        val metadata = adapter.convert(parsed)

        assertEquals(3, assertNotNull(metadata.typedCatalogue).symbols.size)
        assertNull(metadata.steps["onClass"])
        assertNull(metadata.steps["insideNode"])
        assertNull(metadata.globalVariables["classProperty"])
    }

    @Test
    fun `context retains competing GDSL facts and namespaces without degrading a known signature`() {
        val first = tempDir.resolve("first.gdsl")
        val second = tempDir.resolve("second.gdsl")
        Files.writeString(
            first,
            """
            contributor(context()) {
                method(name: 'echo', type: 'String', params: [different: 'String'])
                property(name: 'echo', type: 'String')
                property(name: 'shared', type: 'String')
            }
            """.trimIndent(),
        )
        Files.writeString(
            second,
            """
            contributor(context(ctype: 'example.Other')) {
                property(name: 'shared', type: 'Integer')
                method(name: 'onlyOnTarget', type: 'void')
            }
            """.trimIndent(),
        )
        val context = JenkinsContext(
            JenkinsConfiguration(gdslPaths = listOf(first.toString(), second.toString()), gdslExecutionEnabled = true),
            tempDir,
        )
        val before = context.getAllMetadata(null)
        val original = assertNotNull(before.getStep("echo"))
        val originalCandidates = before.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "echo")

        context.loadGdslMetadata()
        val after = context.getAllMetadata(null)

        assertEquals(original, after.getStep("echo"), "Raw candidate retention must not erase descriptor precedence")
        assertEquals(
            originalCandidates.size + 1,
            after.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "echo").size,
        )
        assertEquals(1, after.catalogue.candidates(SymbolKind.GLOBAL, "echo").size)
        val properties = after.catalogue.candidates(SymbolKind.GLOBAL, "shared")
        assertEquals(2, properties.size)
        assertEquals(
            setOf(JsonPrimitive(first.toString()), JsonPrimitive(second.toString())),
            properties.map { wire(it).getValue("provenance").jsonObject["origin"] }.toSet(),
        )
        assertNull(after.getGlobalVariable("shared"))
        assertNull(after.getStep("onlyOnTarget"))
    }

    @Test
    fun `an unspecified GDSL scope stays unknown in the compatibility view`() {
        val gdsl = tempDir.resolve("unknown.gdsl")
        Files.writeString(gdsl, "contributor(context()) { method(name: 'unknownScope', type: 'void') }")
        val context = JenkinsContext(
            JenkinsConfiguration(gdslPaths = listOf(gdsl.toString()), gdslExecutionEnabled = true),
            tempDir,
        )

        context.loadGdslMetadata()

        assertEquals(StepScope.UNKNOWN, assertNotNull(context.getAllMetadata(null).getStep("unknownScope")).scope)
    }

    private fun wire(symbol: TypedSymbol): JsonObject =
        Json.encodeToJsonElement(TypedSymbol.serializer(), symbol).jsonObject
}
