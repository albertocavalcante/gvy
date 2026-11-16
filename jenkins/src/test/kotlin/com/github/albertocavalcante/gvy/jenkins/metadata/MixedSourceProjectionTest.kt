// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.metadata

import com.github.albertocavalcante.gvy.gdsl.model.GdslParseResult
import com.github.albertocavalcante.gvy.gdsl.model.MethodDescriptor
import com.github.albertocavalcante.gvy.gdsl.model.NamedParameterDescriptor
import com.github.albertocavalcante.gvy.gdsl.model.PropertyDescriptor
import com.github.albertocavalcante.gvy.jenkins.JenkinsConfiguration
import com.github.albertocavalcante.gvy.jenkins.JenkinsContext
import com.github.albertocavalcante.gvy.jenkins.JenkinsPluginManager
import com.github.albertocavalcante.gvy.jenkins.gdsl.JenkinsGdslAdapter
import com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment
import com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.ParameterEnrichment
import com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.StepCategory
import com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.StepEnrichment
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixedSourceProjectionTest {
    @TempDir
    lateinit var tempDir: Path

    private fun curated(): BundledJenkinsMetadata {
        val step = JenkinsStepMetadata(
            name = "deployUnique",
            plugin = "curated-plugin",
            ownerPlugin = "curated-plugin",
            ownerEvidence = OwnerEvidence.CURATED,
            provenance = MetadataProvenance(SourceKind.CURATED),
            returnType = "CuratedResult",
            documentation = "Curated docs",
            parameters = mapOf(
                "required" to StepParameter("required", "String", required = true),
                "optional" to StepParameter("optional", "String", required = false),
                "unknown" to StepParameter("unknown", "String", required = null),
            ),
        )
        return BundledJenkinsMetadata(
            steps = mapOf(step.name to step),
            globalVariables = mapOf(
                "deploymentUnique" to GlobalVariableMetadata("deploymentUnique", "CuratedGlobal", "Curated global"),
            ),
        )
    }

    private fun gdsl(): BundledJenkinsMetadata = JenkinsGdslAdapter().convert(
        GdslParseResult(
            methods = listOf(
                MethodDescriptor(
                    name = "deployUnique",
                    returnType = "GdslResult",
                    documentation = "GDSL docs",
                    namedParameters = listOf(NamedParameterDescriptor("gdslOnly", "String", required = true)),
                ),
            ),
            properties = listOf(PropertyDescriptor("deploymentUnique", "GdslGlobal", documentation = "GDSL global")),
        ),
        sourceOrigin = "fixture.gdsl",
        sourceNamespace = "fixture-source",
    )

    private fun merged(gdslFirst: Boolean): BundledJenkinsMetadata {
        val first = if (gdslFirst) gdsl() else curated()
        val second = if (gdslFirst) curated() else gdsl()
        return MetadataMerger.mergeAll(first, versioned = second, stable = emptyMap())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(booleans = [false, true])
    fun `curated step projection is isolated from competing GDSL in both merge orders`(gdslFirst: Boolean) {
        val result = merged(gdslFirst)
        val candidates = assertNotNull(result.typedCatalogue).candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique")
        assertEquals(2, candidates.size)
        val descriptor = candidates.single { it.provenance.source == SourceKind.CURATED }
        assertTrue(descriptor.legacyDerived)
        assertEquals("curated-plugin", descriptor.ownerPlugin)
        assertEquals(setOf("required", "optional", "unknown"), descriptor.parameters.map { it.name }.toSet())
        assertEquals(
            listOf(Requiredness.REQUIRED, Requiredness.OPTIONAL, Requiredness.UNKNOWN),
            listOf("required", "optional", "unknown").map { name ->
                descriptor.parameters.single { it.name == name }.requiredness
            },
        )
        val projected = assertNotNull(result.getStep("deployUnique"))
        assertEquals("curated-plugin", projected.plugin, "order=$gdslFirst")
        assertEquals("Curated docs", projected.documentation, "order=$gdslFirst")
        assertEquals("CuratedResult", projected.returnType, "order=$gdslFirst")
        assertEquals(setOf("required", "optional", "unknown"), projected.parameters.keys, "order=$gdslFirst")
        assertEquals(
            listOf(true, false, null),
            listOf("required", "optional", "unknown").map {
                projected.parameters.getValue(it).required
            },
        )
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(booleans = [false, true])
    fun `raw globals retain right hand precedence while canonical candidates remain distinct`(gdslFirst: Boolean) {
        val result = merged(gdslFirst)
        val candidates = assertNotNull(result.typedCatalogue).candidates(SymbolKind.GLOBAL, "deploymentUnique")
        assertEquals(2, candidates.size)
        assertEquals(setOf(SourceKind.GDSL, SourceKind.UNKNOWN), candidates.map { it.provenance.source }.toSet())
        assertEquals(
            if (gdslFirst) "CuratedGlobal" else "GdslGlobal",
            result.getGlobalVariable("deploymentUnique")?.type,
        )
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(booleans = [false, true])
    fun `enriched step retains only descriptor facts and matching adjuncts in both orders`(gdslFirst: Boolean) {
        val enrichment = JenkinsEnrichment(
            steps = mapOf(
                "deployUnique" to StepEnrichment(
                    plugin = "curated-plugin",
                    documentationUrl = "https://example.test/deploy",
                    category = StepCategory.DEPLOY,
                    examples = listOf("deployUnique()"),
                    parameterEnrichment = mapOf("required" to ParameterEnrichment(examples = listOf("value"))),
                ),
            ),
        )
        val result = MetadataMerger.mergeWithEnrichment(merged(gdslFirst), enrichment)
        val candidates = result.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique")
        assertEquals(2, candidates.size)
        assertEquals(1, candidates.count { it.provenance.source == SourceKind.CURATED })
        val step = assertNotNull(result.getStep("deployUnique"))
        assertEquals("curated-plugin", step.plugin)
        assertEquals("Curated docs", step.documentation)
        assertEquals("CuratedResult", step.returnType)
        assertEquals(setOf("required", "optional", "unknown"), step.namedParams.keys)
        assertEquals(
            listOf(true, false, null),
            listOf("required", "optional", "unknown").map {
                step.namedParams.getValue(it).required
            },
        )
        assertEquals("https://example.test/deploy", step.documentationUrl)
        assertEquals(StepCategory.DEPLOY, step.category)
        assertEquals(listOf("deployUnique()"), step.examples)
        assertEquals(listOf("value"), step.namedParams.getValue("required").examples)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @ValueSource(booleans = [false, true])
    fun `enriched global retains selected non GDSL candidate in both merge orders`(gdslFirst: Boolean) {
        val result = MetadataMerger.mergeWithEnrichment(merged(gdslFirst), JenkinsEnrichment())
        val candidates = result.catalogue.candidates(SymbolKind.GLOBAL, "deploymentUnique")
        assertEquals(2, candidates.size)
        assertEquals("CuratedGlobal", candidates.single { it.provenance.source != SourceKind.GDSL }.returnType)
        val global = assertNotNull(result.getGlobalVariable("deploymentUnique"))
        assertEquals("CuratedGlobal", global.type, "order=$gdslFirst")
        assertEquals("Curated global", global.documentation, "order=$gdslFirst")
    }

    @Test
    fun `marked GDSL view preserves identity but edited view remains competing`() {
        val original = gdsl()
        val originalSymbol = assertNotNull(
            original.typedCatalogue,
        ).candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique").single()
        val view = assertNotNull(original.getStep("deployUnique"))
        assertNotNull(view.canonicalProjection)
        assertEquals(
            listOf(originalSymbol),
            original.toTypedCatalogue().candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique"),
        )
        val edited = original.copy(steps = mapOf("deployUnique" to view.copy(returnType = "EditedResult")))
        val candidates = edited.toTypedCatalogue().candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique")
        assertEquals(2, candidates.size)
        assertEquals(setOf("GdslResult", "EditedResult"), candidates.map { it.returnType }.toSet())
        assertNull(MetadataMerger.mergeWithEnrichment(edited, JenkinsEnrichment()).getStep("deployUnique"))
    }

    @Test
    fun `legacy override keeps authenticated enrichment while patched facts win`() {
        val base = curated()
        val enrichment = testEnrichment()
        val before = assertNotNull(MetadataMerger.mergeWithEnrichment(base, enrichment).getStep("deployUnique"))
        assertEquals("https://example.test/deploy", before.documentationUrl)
        assertEquals(listOf("value"), before.namedParams.getValue("required").examples)
        val override = JenkinsStepMetadata(
            name = "deployUnique",
            plugin = "user-label",
            documentation = "User docs",
            parameters = mapOf("required" to StepParameter("required", "String", required = false, default = "new")),
        )
        val patched = MetadataMerger.mergeWithPriority(
            base,
            stable = emptyMap(),
            userOverrides = mapOf("deployUnique" to override),
        )
        val result = MetadataMerger.mergeWithEnrichment(patched, enrichment)
        val symbol = result.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique").single()
        val parameter = symbol.parameters.single { it.name == "required" }
        assertEquals("User docs", symbol.documentation)
        assertEquals(Requiredness.OPTIONAL, parameter.requiredness)
        assertEquals("new", parameter.defaultValue)
        val step = assertNotNull(result.getStep("deployUnique"))
        assertEquals("User docs", step.documentation)
        assertEquals(false, step.namedParams.getValue("required").required)
        assertEquals("new", step.namedParams.getValue("required").defaultValue)
        assertEquals("https://example.test/deploy", step.documentationUrl)
        assertEquals(StepCategory.DEPLOY, step.category)
        assertEquals(listOf("deployUnique()"), step.examples)
        assertEquals(listOf("value"), step.namedParams.getValue("required").examples)
    }

    @Test
    fun `same owner context contribution keeps authenticated enrichment and adds parameters`() {
        val base = MetadataMerger.mergeWithEnrichment(curated(), testEnrichment())
        val before = assertNotNull(base.getStep("deployUnique"))
        assertEquals("https://example.test/deploy", before.documentationUrl)
        assertEquals(listOf("value"), before.namedParams.getValue("required").examples)
        val dynamic = JenkinsStepMetadata(
            name = "deployUnique",
            plugin = "curated-plugin",
            ownerPlugin = "curated-plugin",
            ownerEvidence = OwnerEvidence.CURATED,
            provenance = MetadataProvenance(SourceKind.CURATED),
            parameters = mapOf("dynamic" to StepParameter("dynamic", "String", required = false)),
        )
        val scanned = BundledJenkinsMetadata(steps = mapOf("deployUnique" to dynamic), globalVariables = emptyMap())
        val context = JenkinsContext(
            configuration = JenkinsConfiguration(),
            workspaceRoot = tempDir,
            pluginManager = JenkinsPluginManager(),
            bundledMetadataLoader = { base },
            classpathScanner = { scanned },
        )
        val classpath = listOf(tempDir.resolve("curated-plugin.jar"))
        context.scanClasspath(classpath)
        val result = context.getAllMetadata(classpath)
        val symbol = result.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique").single()
        assertEquals(setOf("required", "optional", "unknown", "dynamic"), symbol.parameters.map { it.name }.toSet())
        val step = assertNotNull(result.getStep("deployUnique"))
        assertEquals(setOf("required", "optional", "unknown", "dynamic"), step.namedParams.keys)
        assertEquals(false, step.namedParams.getValue("dynamic").required)
        assertEquals("https://example.test/deploy", step.documentationUrl)
        assertEquals(StepCategory.DEPLOY, step.category)
        assertEquals(listOf("deployUnique()"), step.examples)
        assertEquals(listOf("value"), step.namedParams.getValue("required").examples)
    }

    private fun testEnrichment() = JenkinsEnrichment(
        steps = mapOf(
            "deployUnique" to StepEnrichment(
                plugin = "curated-plugin",
                documentationUrl = "https://example.test/deploy",
                category = StepCategory.DEPLOY,
                examples = listOf("deployUnique()"),
                parameterEnrichment = mapOf("required" to ParameterEnrichment(examples = listOf("value"))),
            ),
        ),
    )
}
