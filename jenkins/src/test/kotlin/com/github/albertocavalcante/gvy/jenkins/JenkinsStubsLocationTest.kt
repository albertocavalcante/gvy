// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.gvy.jenkins.stubs.JenkinsStubSets
import com.github.albertocavalcante.gvy.jenkins.stubs.JenkinsStubsLocation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JenkinsStubsLocationTest {

    @TempDir
    lateinit var tempDir: Path

    private val cacheRoot: Path get() = tempDir.resolve("cache")

    private fun workspace(name: String): Path = tempDir.resolve(name).createDirectories()

    /** The plugin stubs dir; the always-present `implicit` dir (`@Library` stub) lives inside it. */
    private fun stubsDirOf(classpath: List<Path>): Path =
        classpath.single { it.startsWith(cacheRoot) && it.fileName.toString() != "implicit" }

    @Test
    fun `building the classpath leaves the user workspace untouched`() {
        val workspace = workspace("ws")
        val context = JenkinsContext(JenkinsConfiguration(), workspace, stubsCacheRoot = cacheRoot)

        context.buildClasspath(emptyList())

        assertTrue(
            !Files.exists(workspace.resolve(".jenkins-stubs")),
            "Jenkins stubs must not be generated inside the workspace",
        )
        val entries = workspace.listDirectoryEntries()
        assertTrue(entries.isEmpty(), "Workspace must stay empty, found: $entries")
    }

    @Test
    fun `generated stubs are on the classpath under the cache root`() {
        val workspace = workspace("ws")
        val classpath = JenkinsContext(JenkinsConfiguration(), workspace, stubsCacheRoot = cacheRoot)
            .buildClasspath(emptyList())

        val stubsDir = stubsDirOf(classpath)
        // Plugin stubs are one content-keyed set under the workspace's stubs directory (#1367).
        assertEquals(JenkinsStubsLocation.directoryFor(cacheRoot, workspace).resolve("sets"), stubsDir.parent)
        assertTrue(
            Files.isRegularFile(stubsDir.resolve("org/jenkinsci/plugins/workflow/cps/CpsScript.groovy")),
            "CpsScript stub missing in $stubsDir",
        )
    }

    @Test
    fun `regenerating over an existing stubs directory succeeds and is stable`() {
        val workspace = workspace("ws")
        val first = stubsDirOf(
            JenkinsContext(JenkinsConfiguration(), workspace, stubsCacheRoot = cacheRoot).buildClasspath(emptyList()),
        )
        val second = stubsDirOf(
            JenkinsContext(JenkinsConfiguration(), workspace, stubsCacheRoot = cacheRoot).buildClasspath(emptyList()),
        )

        assertEquals(first, second)
        val files = Files.walk(second).use { paths -> paths.filter(Files::isRegularFile).toList() }
            .filterNot { it.fileName.toString() == JenkinsStubSets.COMPLETE_MARKER }
        assertTrue(files.isNotEmpty(), "Expected generated stubs in $second")
        assertTrue(files.all { it.fileName.toString().endsWith(".groovy") }, "Leftover non-stub files: $files")
    }

    @Test
    fun `distinct workspaces get distinct stub directories`() {
        val a = JenkinsStubsLocation.directoryFor(cacheRoot, workspace("a").resolve("same"))
        val b = JenkinsStubsLocation.directoryFor(cacheRoot, workspace("b").resolve("same"))

        assertNotEquals(a, b)
        assertEquals(cacheRoot, a.parent)
        assertTrue(a.fileName.toString().startsWith("same-"), "Readable prefix expected: $a")
    }

    @Test
    fun `without an override the default is the jenkins-stubs entry of the groovy-lsp cache`() {
        val requested = mutableListOf<String>()
        val resolved = JenkinsStubsLocation.resolveCacheRoot(null) { sub ->
            requested += sub
            cacheRoot.resolve(sub)
        }

        assertEquals(listOf("jenkins-stubs"), requested)
        assertEquals(cacheRoot.resolve("jenkins-stubs"), resolved)
        assertEquals(resolved, JenkinsStubsLocation.resolveCacheRoot("  ") { cacheRoot.resolve(it) })
    }

    @Test
    fun `an override replaces the cache root without touching the groovy-lsp cache`() {
        val override = tempDir.resolve("override")

        val resolved = JenkinsStubsLocation.resolveCacheRoot(override.toString()) {
            error("groovy-lsp cache must not be consulted when overridden")
        }

        assertEquals(override, resolved)
    }

    @Test
    fun `a relative override is rejected in favour of the default cache root`() {
        val resolved = JenkinsStubsLocation.resolveCacheRoot("relative/stubs") { cacheRoot.resolve(it) }

        assertEquals(cacheRoot.resolve("jenkins-stubs"), resolved)
    }

    @Test
    fun `a leftover workspace stubs dir is detected but never modified`() {
        val workspace = workspace("ws")
        assertEquals(null, JenkinsStubsLocation.legacyWorkspaceDir(workspace))

        val legacyStub = workspace.resolve(".jenkins-stubs/org/jenkinsci/plugins/workflow/cps/CpsScript.groovy")
        legacyStub.parent.createDirectories()
        Files.writeString(legacyStub, "// stale")
        assertEquals(workspace.resolve(".jenkins-stubs"), JenkinsStubsLocation.legacyWorkspaceDir(workspace))

        JenkinsContext(JenkinsConfiguration(), workspace, stubsCacheRoot = cacheRoot).buildClasspath(emptyList())

        assertEquals("// stale", Files.readString(legacyStub), "User files must be left untouched")
    }

    @Test
    fun `the legacy dir warning is claimed once per workspace per process`() {
        val ws = workspace("ws")
        val other = workspace("other")

        assertTrue(JenkinsStubsLocation.claimLegacyWarning(ws))
        assertFalse(JenkinsStubsLocation.claimLegacyWarning(ws.resolve("child").resolve("..")))
        assertTrue(JenkinsStubsLocation.claimLegacyWarning(other))
    }

    @Test
    fun `test JVMs are wired to an isolated stubs cache root`() {
        val configured = System.getProperty(JenkinsStubsLocation.CACHE_ROOT_PROPERTY)
        assertTrue(
            !configured.isNullOrBlank(),
            "Build must set -D${JenkinsStubsLocation.CACHE_ROOT_PROPERTY} for test JVMs",
        )
        val root = JenkinsStubsLocation.defaultCacheRoot()
        assertTrue(root.isAbsolute, "Isolated stubs root must be absolute: $root")
        val realCache = Path.of(System.getProperty("user.home"), ".groovy-lsp")
        assertFalse(root.normalize().startsWith(realCache), "Tests must not use the real cache: $root")
    }

    @Test
    fun `equivalent workspace paths share one stub directory`() {
        val ws = workspace("ws")
        val viaDotDot = ws.resolve("child").resolve("..")

        assertEquals(
            JenkinsStubsLocation.directoryFor(cacheRoot, ws),
            JenkinsStubsLocation.directoryFor(cacheRoot, viaDotDot),
        )
    }
}
