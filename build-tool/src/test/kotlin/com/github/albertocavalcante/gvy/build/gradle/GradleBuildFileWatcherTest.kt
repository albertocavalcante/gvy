// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

@Timeout(10)
class GradleBuildFileWatcherTest {
    @TempDir
    lateinit var tempDir: Path

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val events = CopyOnWriteArrayList<Path>()
    private val changed = CountDownLatch(1)
    private val watcher = GradleBuildFileWatcher(scope, {
        events.add(it)
        changed.countDown()
    }, debounceDelayMs = 0)

    @AfterEach
    fun cleanup() {
        watcher.stopWatching()
        scope.cancel()
    }

    @ParameterizedTest
    @ValueSource(strings = ["build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"])
    fun `detects creation of supported Gradle files`(filename: String) {
        watcher.startWatching(tempDir)
        assertTrue(watcher.isWatching())

        tempDir.resolve(filename).writeText("// created")

        assertChange()
    }

    @Test
    fun `detects modification of an existing Kotlin build file`() {
        val file = tempDir.resolve("build.gradle.kts")
        file.writeText("// initial")
        watcher.startWatching(tempDir)

        file.writeText("// modified")

        assertChange()
    }

    @Test
    fun `detects successive writes without assuming native event cardinality`() {
        watcher.startWatching(tempDir)
        val file = tempDir.resolve("build.gradle")
        repeat(5) { file.writeText("// change $it") }

        assertChange()
    }

    @Test
    fun `ignores unrelated files while retaining a live positive control`() {
        watcher.startWatching(tempDir)
        tempDir.resolve("README.md").writeText("# unrelated")

        assertFalse(changed.await(2, TimeUnit.SECONDS), "An unrelated file must not trigger a callback")
        tempDir.resolve("build.gradle").writeText("// positive control")
        assertChange()
    }

    private fun assertChange() {
        assertTrue(changed.await(5, TimeUnit.SECONDS), "Expected a real Gradle file callback")
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it == tempDir })
    }
}
