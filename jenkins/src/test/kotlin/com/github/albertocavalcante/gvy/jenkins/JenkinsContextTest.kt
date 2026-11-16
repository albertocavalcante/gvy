// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.gvy.jenkins.config.UserMetadataLoader
import com.github.albertocavalcante.gvy.jenkins.metadata.MetadataMerger
import com.github.albertocavalcante.gvy.jenkins.metadata.SymbolKind
import com.github.albertocavalcante.gvy.jenkins.metadata.TypedSymbol
import com.github.albertocavalcante.gvy.jenkins.metadata.VersionedMetadataLoader
import com.github.albertocavalcante.gvy.jenkins.metadata.enrichment.JenkinsEnrichment
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for Jenkins context management.
 */
class JenkinsContextTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should build Jenkins classpath from configured libraries`() {
        val lib1 = tempDir.resolve("lib1.jar")
        val lib2 = tempDir.resolve("lib2.jar")
        Files.createFile(lib1)
        Files.createFile(lib2)

        val config = JenkinsConfiguration(
            sharedLibraries = listOf(
                SharedLibrary("lib1", lib1.toString()),
                SharedLibrary("lib2", lib2.toString()),
            ),
        )

        val context = JenkinsContext(config, tempDir)
        val classpath = context.buildClasspath(emptyList())

        // Should include configured libraries (may also include auto-injected jenkins-core)
        assertTrue(classpath.size >= 2, "Expected at least 2 items, got ${classpath.size}")
        assertTrue(classpath.any { it.toString().contains("lib1.jar") })
        assertTrue(classpath.any { it.toString().contains("lib2.jar") })
    }

    @Test
    fun `should build classpath from library references`() {
        val lib1 = tempDir.resolve("lib1.jar")
        Files.createFile(lib1)

        val config = JenkinsConfiguration(
            sharedLibraries = listOf(
                SharedLibrary("lib1", lib1.toString()),
            ),
        )

        val context = JenkinsContext(config, tempDir)
        val refs = listOf(LibraryReference("lib1", null))
        val classpath = context.buildClasspath(refs)

        // Should include lib1 (may also include auto-injected jenkins-core)
        assertTrue(classpath.size >= 1, "Expected at least 1 item, got ${classpath.size}")
        assertTrue(classpath.any { it.toString().contains("lib1.jar") })
    }

    @Test
    fun `should include sources jar when available`() {
        val lib = tempDir.resolve("lib.jar")
        val sources = tempDir.resolve("lib-sources.jar")
        Files.createFile(lib)
        Files.createFile(sources)

        val config = JenkinsConfiguration(
            sharedLibraries = listOf(
                SharedLibrary("mylib", lib.toString(), sources.toString()),
            ),
        )

        val context = JenkinsContext(config, tempDir)
        val refs = listOf(LibraryReference("mylib", null))
        val classpath = context.buildClasspath(refs)

        // Should include both jar and sources (may also include auto-injected jenkins-core)
        assertTrue(classpath.size >= 2, "Expected at least 2 items, got ${classpath.size}")
        assertTrue(classpath.any { it.toString().contains("lib.jar") })
        assertTrue(classpath.any { it.toString().contains("lib-sources.jar") })
    }

    @Test
    fun `should handle missing library references gracefully`() {
        val lib1 = tempDir.resolve("lib1.jar")
        Files.createFile(lib1)

        val config = JenkinsConfiguration(
            sharedLibraries = listOf(
                SharedLibrary("lib1", lib1.toString()),
            ),
        )

        val context = JenkinsContext(config, tempDir)
        val refs = listOf(
            LibraryReference("lib1", null),
            LibraryReference("missing", null),
        )

        // Should not throw, just log warning
        val classpath = context.buildClasspath(refs)

        // Should include lib1 but not the missing one
        assertTrue(classpath.size >= 1)
    }

    @Test
    fun `should load GDSL files from configuration`() {
        val gdsl1 = tempDir.resolve("pipeline.gdsl")
        Files.writeString(gdsl1, "// Pipeline GDSL")

        val config = JenkinsConfiguration(
            gdslPaths = listOf(gdsl1.toString()),
        )

        val context = JenkinsContext(config, tempDir)
        val gdslContent = context.loadGdslMetadata()

        assertEquals(1, gdslContent.successful.size)
        assertEquals(0, gdslContent.failed.size)
    }

    @Test
    fun `should skip GDSL execution when disabled`() {
        val marker = tempDir.resolve("gdsl-executed.txt")
        val markerPath = marker.toAbsolutePath().toString().replace("\\", "\\\\")
        val gdsl1 = tempDir.resolve("pipeline.gdsl")
        Files.writeString(gdsl1, "new File(\"$markerPath\").text = \"executed\"")

        val config = JenkinsConfiguration(
            gdslPaths = listOf(gdsl1.toString()),
            gdslExecutionEnabled = false,
        )

        val context = JenkinsContext(config, tempDir)
        context.loadGdslMetadata()

        assertTrue(Files.notExists(marker))
    }

    @Test
    fun `should report warnings for missing GDSL files`() {
        val config = JenkinsConfiguration(
            gdslPaths = listOf("/nonexistent/pipeline.gdsl"),
        )

        val context = JenkinsContext(config, tempDir)
        val gdslContent = context.loadGdslMetadata()

        assertEquals(0, gdslContent.successful.size)
        assertEquals(1, gdslContent.failed.size)
    }

    @Test
    fun `should include registered plugin jars in classpath`(): Unit = runBlocking {
        val pluginJar = tempDir.resolve("plugin.jar")
        Files.createFile(pluginJar)

        val mockPluginManager = mockk<JenkinsPluginManager>()
        coEvery { mockPluginManager.getRegisteredPluginJars() } returns listOf(pluginJar)

        val config = JenkinsConfiguration()
        // This constructor call will fail compilation initially
        val context = JenkinsContext(config, tempDir, mockPluginManager)
        val classpath = context.buildClasspath(emptyList())

        assertTrue(classpath.any { it == pluginJar }, "Classpath should include registered plugin JAR")
    }

    @Test
    fun `GDSL steps and globals join the metadata and are replaced on reload`() {
        val gdsl = tempDir.resolve("custom.gdsl")
        Files.writeString(
            gdsl,
            """
            contributor(context()) {
                method(name: 'myStep', type: 'void', params: [message: 'java.lang.String'], doc: 'My step')
                property(name: 'myGlobal', type: 'java.lang.String', doc: 'My global')
            }
            """.trimIndent(),
        )
        val context = JenkinsContext(
            JenkinsConfiguration(gdslPaths = listOf(gdsl.toString()), gdslExecutionEnabled = true),
            tempDir,
        )

        context.loadGdslMetadata()
        val loaded = context.getAllMetadata(null)

        assertEquals("My step", loaded.getStep("myStep")?.documentation)
        assertEquals(setOf("message"), loaded.getStep("myStep")?.namedParams?.keys)
        assertEquals("My global", loaded.getGlobalVariable("myGlobal")?.documentation)

        Files.writeString(gdsl, "contributor(context()) { method(name: 'yourStep', type: 'void') }")
        context.loadGdslMetadata()
        val reloaded = context.getAllMetadata(null)

        assertNull(reloaded.getStep("myStep"))
        assertNull(reloaded.getGlobalVariable("myGlobal"))
        assertNotNull(reloaded.getStep("yourStep"))
    }

    @Test
    fun `GDSL does not replace a known step`() {
        val gdsl = tempDir.resolve("custom.gdsl")
        Files.writeString(gdsl, "contributor(context()) { method(name: 'echo', type: 'void', doc: 'Mine') }")
        val context = JenkinsContext(
            JenkinsConfiguration(gdslPaths = listOf(gdsl.toString()), gdslExecutionEnabled = true),
            tempDir,
        )
        val bundledEcho = context.getAllMetadata(null).getStep("echo")

        context.loadGdslMetadata()

        assertNotNull(bundledEcho)
        assertEquals(bundledEcho, context.getAllMetadata(null).getStep("echo"))
    }

    @Test
    fun `GDSL metadata is not added when GDSL reading is disabled`() {
        val gdsl = tempDir.resolve("custom.gdsl")
        Files.writeString(gdsl, "contributor(context()) { method(name: 'myStep', type: 'void') }")
        val context = JenkinsContext(
            JenkinsConfiguration(gdslPaths = listOf(gdsl.toString()), gdslExecutionEnabled = false),
            tempDir,
        )

        context.loadGdslMetadata()

        assertNull(context.getAllMetadata(null).getStep("myStep"))
    }

    @Test
    fun `a GDSL file that cannot be read does not drop the others`() {
        val bad = tempDir.resolve("bad.gdsl")
        Files.writeString(bad, "method(name: 'unterminated")
        val good = tempDir.resolve("good.gdsl")
        Files.writeString(good, "contributor(context()) { method(name: 'myStep', type: 'void') }")
        val context = JenkinsContext(
            JenkinsConfiguration(gdslPaths = listOf(bad.toString(), good.toString()), gdslExecutionEnabled = true),
            tempDir,
        )

        context.loadGdslMetadata()

        assertNotNull(context.getAllMetadata(null).getStep("myStep"))
    }

    @Test
    fun `a GDSL name never duplicates a known step or global in the other namespace`() {
        val gdsl = tempDir.resolve("custom.gdsl")
        Files.writeString(
            gdsl,
            """
            contributor(context()) {
                property(name: 'sh', type: 'java.lang.String')
                method(name: 'env', type: 'void')
                method(name: 'both', type: 'void')
                property(name: 'both', type: 'java.lang.String')
                method(name: 'sh', type: 'void')
                method(name: 'overloaded', type: 'java.lang.String', params: [text: 'String'])
                method(name: 'overloaded', type: 'java.lang.Integer', params: [number: 'int'])
            }
            """.trimIndent(),
        )
        val context = JenkinsContext(
            JenkinsConfiguration(gdslPaths = listOf(gdsl.toString()), gdslExecutionEnabled = true),
            tempDir,
        )
        val before = context.getAllMetadata(null)

        context.loadGdslMetadata()
        val after = context.getAllMetadata(null)

        assertNull(after.getGlobalVariable("sh"))
        assertEquals(before.getStep("env"), after.getStep("env"))
        assertNotNull(after.getStep("both"))
        assertNull(after.getGlobalVariable("both"))
        assertEquals(before.getStep("sh"), after.getStep("sh"), "GDSL must not degrade a known signature")
        assertNull(after.getStep("overloaded"), "A singular legacy view must not choose an overload")
        assertEquals(2, after.catalogue.symbols.count { it.name == "overloaded" })
    }

    @Test
    fun `repeated configured GDSL source retains one canonical method and global`() {
        val file = tempDir.resolve("repeated.gdsl")
        Files.writeString(
            file,
            "contributor(context()) { method(name: 'deployUnique', type: 'String'); " +
                "property(name: 'deploymentUnique', type: 'String') }",
        )
        fun load(paths: List<String>): JenkinsContext {
            val context = JenkinsContext(JenkinsConfiguration(gdslPaths = paths, gdslExecutionEnabled = true), tempDir)
            val result = context.loadGdslMetadata()
            assertTrue(result.successful.isNotEmpty(), "The configured source must actually be read")
            assertTrue(result.failed.isEmpty())
            return context
        }
        val single = load(listOf(file.toString())).getAllMetadata(null)
        assertEquals(1, single.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique").size)
        assertEquals(1, single.catalogue.candidates(SymbolKind.GLOBAL, "deploymentUnique").size)
        assertNotNull(single.getStep("deployUnique"))
        assertNotNull(single.getGlobalVariable("deploymentUnique"))

        val repeated = load(listOf(file.toString(), file.toString())).getAllMetadata(null)
        val methodCount = repeated.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique").size
        val globalCount = repeated.catalogue.candidates(SymbolKind.GLOBAL, "deploymentUnique").size
        assertEquals(listOf(1, 1), listOf(methodCount, globalCount), "One source declaration must not multiply")
        assertNotNull(repeated.getStep("deployUnique"))
        assertNotNull(repeated.getGlobalVariable("deploymentUnique"))
    }

    @Test
    fun `distinct GDSL sources and same-source overloads remain ambiguous`() {
        val first = tempDir.resolve("first.gdsl")
        val second = tempDir.resolve("second.gdsl")
        Files.writeString(
            first,
            "contributor(context()) { method(name: 'deployUnique', type: 'String'); " +
                "method(name: 'overloadUnique', type: 'String'); method(name: 'overloadUnique', type: 'Integer'); " +
                "property(name: 'deploymentUnique', type: 'String') }",
        )
        Files.writeString(
            second,
            "contributor(context()) { method(name: 'deployUnique', type: 'String'); " +
                "property(name: 'deploymentUnique', type: 'String') }",
        )
        val context = JenkinsContext(
            JenkinsConfiguration(gdslPaths = listOf(first.toString(), second.toString()), gdslExecutionEnabled = true),
            tempDir,
        )
        val loaded = context.loadGdslMetadata()
        assertEquals(2, loaded.successful.size)
        assertTrue(loaded.failed.isEmpty())
        val metadata = context.getAllMetadata(null)
        val methods = metadata.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique")
        val globals = metadata.catalogue.candidates(SymbolKind.GLOBAL, "deploymentUnique")
        val overloads = metadata.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "overloadUnique")
        assertEquals(2, methods.size)
        assertEquals(2, globals.size)
        assertEquals(2, overloads.size)
        assertEquals(setOf(first.toString(), second.toString()), methods.map { it.provenance.origin }.toSet())
        assertEquals(setOf(first.toString(), second.toString()), globals.map { it.provenance.origin }.toSet())
        assertEquals(setOf("String", "Integer"), overloads.map { it.returnType }.toSet())
        assertNull(metadata.getStep("deployUnique"))
        assertNull(metadata.getGlobalVariable("deploymentUnique"))
        assertNull(metadata.getStep("overloadUnique"))
    }

    @Test
    fun `configured GDSL source already present in base is not duplicated`() {
        val file = tempDir.resolve("shared.gdsl")
        Files.writeString(
            file,
            "contributor(context()) { method(name: 'deployUnique', type: 'String'); " +
                "property(name: 'deploymentUnique', type: 'String') }",
        )
        val configDir = tempDir.resolve(".gls")
        Files.createDirectories(configDir)
        Files.writeString(configDir.resolve("jenkins.json"), """{"gdslFile":"shared.gdsl"}""")
        val input = assertNotNull(UserMetadataLoader(tempDir).load())
        val base = MetadataMerger.mergeWithEnrichment(input, JenkinsEnrichment())
        val method = base.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique").single()
        val global = base.catalogue.candidates(SymbolKind.GLOBAL, "deploymentUnique").single()
        assertNotNull(base.getStep("deployUnique"))
        assertNotNull(base.getGlobalVariable("deploymentUnique"))
        val context = JenkinsContext(
            configuration = JenkinsConfiguration(gdslPaths = listOf(file.toString()), gdslExecutionEnabled = true),
            workspaceRoot = tempDir,
            pluginManager = JenkinsPluginManager(),
            bundledMetadataLoader = { base },
            classpathScanner = { input },
        )
        val loaded = context.loadGdslMetadata()
        assertEquals(1, loaded.successful.size)
        assertTrue(loaded.failed.isEmpty())
        val combined = context.getAllMetadata(null)
        assertEquals(listOf(method), combined.catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique"))
        assertEquals(listOf(global), combined.catalogue.candidates(SymbolKind.GLOBAL, "deploymentUnique"))
        assertNotNull(combined.getStep("deployUnique"))
        assertNotNull(combined.getGlobalVariable("deploymentUnique"))

        Files.writeString(file, "contributor(context()) { method(name: 'deployUnique', type: 'Integer') }")
        assertEquals(1, context.loadGdslMetadata().successful.size)
        val changed = context.getAllMetadata(null).catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique")
        assertEquals(2, changed.size, "Changed facts from the same source must remain competing")
        assertEquals(setOf("String", "Integer"), changed.map { it.returnType }.toSet())
        assertNull(context.getAllMetadata(null).getStep("deployUnique"))
    }

    @Test
    fun `configured GDSL retains an unrelated occupied presentation identity`() {
        val file = tempDir.resolve("collision.gdsl")
        Files.writeString(file, "contributor(context()) { method(name: 'deployUnique', type: 'String') }")
        val config = JenkinsConfiguration(gdslPaths = listOf(file.toString()), gdslExecutionEnabled = true)
        val probe = JenkinsContext(config, tempDir)
        assertEquals(1, probe.loadGdslMetadata().successful.size)
        val incoming = probe.getAllMetadata(
            null,
        ).catalogue.candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique").single()
        val occupied =
            TypedSymbol(id = incoming.id, name = "unrelatedUnique", kind = SymbolKind.GLOBAL, returnType = "String")
        val defaultBase = VersionedMetadataLoader().loadMerged()
        val base = defaultBase.copy(
            catalogue = defaultBase.catalogue.copy(
                symbols =
                defaultBase.catalogue.symbols + occupied,
            ),
        )
        assertEquals(occupied, base.catalogue.symbols.single { it.id == incoming.id })
        val context = JenkinsContext(
            configuration = config,
            workspaceRoot = tempDir,
            pluginManager = JenkinsPluginManager(),
            bundledMetadataLoader = { base },
            classpathScanner = { error("No classpath scan is expected") },
        )
        assertEquals(1, context.loadGdslMetadata().successful.size)
        val combined = context.getAllMetadata(null).catalogue
        val actual = combined.candidates(SymbolKind.EXECUTABLE_STEP, "deployUnique").single()
        assertEquals(occupied, combined.symbols.single { it.id == occupied.id })
        assertTrue(actual.id != occupied.id)
        assertEquals(incoming.sourceId ?: incoming.id, actual.sourceId)
        assertEquals(incoming.sourceNamespace, actual.sourceNamespace)
        assertEquals(incoming.provenance, actual.provenance)
    }
}
