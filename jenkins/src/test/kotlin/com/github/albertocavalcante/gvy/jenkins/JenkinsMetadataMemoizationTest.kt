// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.gvy.jenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.extracted.StepScope
import com.github.albertocavalcante.gvy.jenkins.plugins.PluginConfiguration
import com.github.albertocavalcante.gvy.jenkins.scanning.JenkinsClasspathScanner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Completion and hover call [JenkinsContext.getAllMetadata] on every request. The bundled
 * metadata (two JSON resources plus the stable-step merge) must be built once, and the
 * merged view must only be rebuilt when its inputs change.
 */
class JenkinsMetadataMemoizationTest {

    @TempDir
    lateinit var tempDir: Path

    private val baseLoads = AtomicInteger()
    private val scans = AtomicInteger()

    private fun step(name: String, plugin: String) = MergedStepMetadata(
        name = name,
        scope = StepScope.GLOBAL,
        positionalParams = emptyList(),
        namedParams = emptyMap(),
        extractedDocumentation = null,
        returnType = null,
        plugin = plugin,
        enrichedDescription = null,
        documentationUrl = null,
        category = null,
        examples = emptyList(),
        deprecation = null,
    )

    private val base = MergedJenkinsMetadata(
        jenkinsVersion = "test",
        steps = mapOf(
            "echo" to step("echo", "workflow-basic-steps"),
            "sh" to step("sh", "workflow-durable-task-step"),
        ),
        globalVariables = emptyMap(),
        sections = emptyMap(),
        directives = emptyMap(),
    )

    private fun dynamic(vararg names: String) = BundledJenkinsMetadata(
        steps = names.associateWith {
            JenkinsStepMetadata(name = it, plugin = "scanned-plugin", parameters = emptyMap())
        },
        globalVariables = emptyMap(),
        postConditions = emptyMap(),
        declarativeOptions = emptyMap(),
        agentTypes = emptyMap(),
    )

    private fun context(
        config: JenkinsConfiguration = JenkinsConfiguration(),
        scanResult: (List<Path>) -> BundledJenkinsMetadata = { dynamic() },
    ) = JenkinsContext(
        configuration = config,
        workspaceRoot = tempDir,
        pluginManager = JenkinsPluginManager(),
        bundledMetadataLoader = {
            baseLoads.incrementAndGet()
            base
        },
        classpathScanner = { classpath ->
            scans.incrementAndGet()
            scanResult(classpath)
        },
    )

    @Test
    fun `repeated requests load the bundled metadata once and reuse the merged view`() {
        val context = context()

        val first = context.getAllMetadata(null)
        repeat(50) { assertSame(first, context.getAllMetadata(null)) }

        assertEquals(1, baseLoads.get())
        assertEquals(setOf("echo", "sh"), first.steps.keys)
    }

    @Test
    fun `concurrent first requests load the bundled metadata once`() {
        val context = context()
        val threads = 16
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map {
                pool.submit<MergedJenkinsMetadata> {
                    start.await()
                    context.getAllMetadata(null)
                }
            }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, baseLoads.get())
            assertTrue(results.all { it.steps.keys == setOf("echo", "sh") })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a new classpath scan invalidates the merged view without reloading the bundled metadata`() {
        val cpA = listOf(tempDir.resolve("a.jar"))
        val cpB = listOf(tempDir.resolve("b.jar"))
        val context = context(scanResult = { cp -> if (cp == cpA) dynamic("fromA") else dynamic("fromB") })

        val before = context.getAllMetadata(null)
        context.scanClasspath(cpA)
        val afterA = context.getAllMetadata(cpA)
        context.scanClasspath(cpA) // unchanged classpath: no rescan, no rebuild
        val afterSameA = context.getAllMetadata(cpA)
        context.scanClasspath(cpB)
        val afterB = context.getAllMetadata(cpB)

        assertEquals(setOf("echo", "sh"), before.steps.keys)
        assertEquals(setOf("echo", "sh", "fromA"), afterA.steps.keys)
        assertSame(afterA, afterSameA)
        assertEquals(setOf("echo", "sh", "fromB"), afterB.steps.keys)
        assertEquals(2, scans.get())
        assertEquals(1, baseLoads.get())
    }

    @Test
    fun `a scanner that throws leaves the previous snapshot in place`() {
        val cpA = listOf(tempDir.resolve("a.jar"))
        val context = context(
            scanResult = { cp -> if (cp == cpA) dynamic("fromA") else error("corrupt jar") },
        )

        val broken = listOf(tempDir.resolve("broken.jar"))
        context.scanClasspath(cpA)
        context.scanClasspath(broken)

        assertEquals(setOf("echo", "sh", "fromA"), context.getAllMetadata(cpA).steps.keys)
        assertEquals(setOf("echo", "sh"), context.getAllMetadata(broken).steps.keys, "no scanned steps for it")
    }

    @Test
    fun `the production scanner reports an unreadable jar as empty metadata instead of throwing`() {
        val corruptJar = tempDir.resolve("corrupt.jar")
        corruptJar.writeText("not a zip archive")

        val result = JenkinsClasspathScanner().scan(listOf(corruptJar))

        assertTrue(result.steps.isEmpty())
        assertTrue(result.globalVariables.isEmpty())
    }

    @Test
    fun `plugin configuration change yields a context with freshly filtered metadata`() {
        val durableOnly = JenkinsConfiguration(
            pluginConfig = PluginConfiguration(
                plugins = listOf("workflow-durable-task-step"),
                includeDefaultPlugins = false,
            ),
        )
        val basicOnly = JenkinsConfiguration(
            pluginConfig = PluginConfiguration(plugins = listOf("workflow-basic-steps"), includeDefaultPlugins = false),
        )

        val first = context(config = durableOnly)
        assertEquals(setOf("sh"), first.getAllMetadata(null).steps.keys)
        assertEquals(setOf("sh"), first.getAllMetadata(null).steps.keys)

        val second = context(config = basicOnly)
        assertEquals(setOf("echo"), second.getAllMetadata(null).steps.keys)
    }

    @Test
    fun `plugin filtering keeps core directive entries and drops entries from missing plugins`() {
        val manager = JenkinsWorkspaceManager(
            JenkinsConfiguration(
                pluginConfig = PluginConfiguration(
                    plugins = listOf("workflow-basic-steps"),
                    includeDefaultPlugins = false,
                ),
            ),
            tempDir,
        )

        val entries = manager.getAllMetadata(UNCOMPILED).directiveEntries

        assertEquals(setOf("jdk", "maven"), entries.getValue("tools").keys, "gradle needs the gradle plugin")
        assertEquals(setOf("cron", "pollSCM", "upstream"), entries.getValue("triggers").keys)
    }

    @Test
    fun `workspace manager configuration update rebuilds the filtered bundled metadata`() {
        val manager = JenkinsWorkspaceManager(
            JenkinsConfiguration(
                pluginConfig = PluginConfiguration(
                    plugins = listOf("workflow-durable-task-step"),
                    includeDefaultPlugins = false,
                ),
            ),
            tempDir,
        )
        val durableSteps = manager.getAllMetadata(UNCOMPILED).steps
        assertTrue("sh" in durableSteps, "sh comes from workflow-durable-task-step")
        assertTrue("echo" !in durableSteps, "echo is filtered out without workflow-basic-steps")

        val updated = manager.updateConfiguration(
            JenkinsConfiguration(
                pluginConfig = PluginConfiguration(
                    plugins = listOf("workflow-basic-steps"),
                    includeDefaultPlugins = false,
                ),
            ),
        )
        val basicSteps = updated.getAllMetadata(UNCOMPILED).steps
        assertTrue("echo" in basicSteps, "echo comes from workflow-basic-steps")
        assertTrue("sh" !in basicSteps, "sh is filtered out after the plugin set changed")
    }

    @Test
    fun `default context returns the same merged view on repeated requests`() {
        val context = JenkinsContext(JenkinsConfiguration(), tempDir)

        assertSame(context.getAllMetadata(null), context.getAllMetadata(null))
    }

    @Test
    fun `contexts share one bundled metadata instance`() {
        val a = JenkinsContext(JenkinsConfiguration(), tempDir.resolve("a"))
        val b = JenkinsContext(JenkinsConfiguration(), tempDir.resolve("b"))

        assertSame(a.getAllMetadata(null), b.getAllMetadata(null))
    }

    private companion object {
        /** A Jenkinsfile never compiled: its view has bundled metadata only. */
        val UNCOMPILED: java.net.URI = java.net.URI.create("file:///workspace/Jenkinsfile")
    }
}
