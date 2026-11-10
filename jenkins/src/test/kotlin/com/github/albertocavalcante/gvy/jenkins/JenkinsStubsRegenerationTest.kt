package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.gvy.jenkins.scanning.JenkinsClasspathScanner
import com.github.albertocavalcante.gvy.jenkins.stubs.JenkinsStubGenerator
import com.github.albertocavalcante.gvy.jenkins.stubs.JenkinsStubsLocation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JenkinsStubsRegenerationTest {

    @TempDir
    lateinit var tempDir: Path

    private val cacheRoot: Path get() = tempDir.resolve("cache")
    private val workspace: Path get() = tempDir.resolve("ws").createDirectories()

    /** Simulates a target held open or not writable (e.g. AccessDeniedException on Windows). */
    private val failingGenerator = JenkinsStubGenerator { file, _ -> throw AccessDeniedException(file.toString()) }

    private fun context(
        generator: JenkinsStubGenerator = JenkinsStubGenerator(),
        stubsCacheRoot: Path? = cacheRoot,
        defaultStubsCacheRoot: () -> Path = { error("default cache root must not be used") },
        warn: (String) -> Unit = {},
    ) = JenkinsContext(
        configuration = JenkinsConfiguration(),
        workspaceRoot = workspace,
        pluginManager = JenkinsPluginManager(),
        bundledMetadataLoader = { JenkinsContext.sharedBundledMetadata },
        classpathScanner = JenkinsClasspathScanner()::scan,
        stubHooks = StubHooks(stubsCacheRoot, generator, defaultStubsCacheRoot, warn),
    )

    private fun buildClasspath(generator: JenkinsStubGenerator = JenkinsStubGenerator()): List<Path> =
        context(generator).buildClasspath(emptyList())

    @Test
    fun `failed regeneration keeps a previously complete stub set on the classpath`() {
        val stubsDir = JenkinsStubsLocation.directoryFor(cacheRoot, workspace)
        assertTrue(buildClasspath().contains(stubsDir), "Precondition: first generation succeeds")

        val classpath = buildClasspath(failingGenerator)

        assertTrue(classpath.contains(stubsDir), "Complete earlier stubs must stay on the classpath: $classpath")
        assertTrue(Files.isRegularFile(stubsDir.resolve("org/jenkinsci/plugins/workflow/cps/CpsScript.groovy")))
    }

    @Test
    fun `failed regeneration keeps an earlier Library annotation stub on the classpath`() {
        val implicitStubsDir = JenkinsStubsLocation.directoryFor(cacheRoot, workspace).resolve("implicit")
        assertTrue(buildClasspath().contains(implicitStubsDir), "Precondition: first generation succeeds")

        val classpath = buildClasspath(failingGenerator)

        assertTrue(classpath.contains(implicitStubsDir), "Earlier @Library stub must stay on the classpath: $classpath")
        assertTrue(Files.isRegularFile(implicitStubsDir.resolve("Library.groovy")))
    }

    @Test
    fun `failed first Library annotation write leaves the implicit dir off the classpath`() {
        val implicitStubsDir = JenkinsStubsLocation.directoryFor(cacheRoot, workspace).resolve("implicit")

        val classpath = buildClasspath(failingGenerator)

        assertFalse(classpath.contains(implicitStubsDir), "No @Library stub, so no implicit dir: $classpath")
    }

    @Test
    fun `an unusable default cache root with a leftover workspace stubs dir does not fail the classpath build`() {
        val legacyDir = workspace.resolve(".jenkins-stubs").createDirectories()
        val warnings = mutableListOf<String>()
        val unusableCache = context(
            stubsCacheRoot = null,
            defaultStubsCacheRoot = {
                throw AccessDeniedException(tempDir.resolve("home/.groovy-lsp/cache").toString())
            },
            warn = { warnings += it },
        )

        val classpath = unusableCache.buildClasspath(emptyList())

        assertFalse(classpath.any { it.startsWith(tempDir.resolve("home")) }, "No stubs dir expected: $classpath")
        assertEquals(listOf(JenkinsStubsLocation.legacyStubsWarning(legacyDir)), warnings)
    }

    @Test
    fun `the legacy warning names the leftover dir without touching the cache`() {
        val legacyDir = workspace.resolve(".jenkins-stubs")

        val message = JenkinsStubsLocation.legacyStubsWarning(legacyDir)

        assertTrue(message.contains(legacyDir.toString()), message)
        assertTrue(message.contains("can be deleted"), message)
    }

    @Test
    fun `failed first generation does not put an incomplete stub dir on the classpath`() {
        val classpath = buildClasspath(failingGenerator)

        assertFalse(
            classpath.any {
                it.startsWith(cacheRoot)
            },
            "No stubs dir expected without a complete set: $classpath",
        )
    }
}
