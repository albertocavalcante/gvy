// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.groovylsp.bsp.maven.server

import com.github.groovylsp.bsp.maven.server.TestFixtures.createInitParams
import com.github.groovylsp.bsp.maven.workspace.MavenModuleInfo
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val WAIT_SECONDS = 10L

/**
 * Orders workspace scans against each other and against requests. Every scan is scripted: it returns the modules it
 * was given, and the test holds it until it releases it, so scans overlap on purpose rather than by chance.
 */
class MavenBuildServerScanOrderTest {

    @TempDir
    lateinit var tempDir: Path

    /** One scripted scan: [started] opens when the server runs it, and it returns once [release] opens. */
    private class Scan(private val result: () -> List<MavenModuleInfo>) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun released() = apply { release.countDown() }

        fun run(): List<MavenModuleInfo> {
            started.countDown()
            check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "The scan was never released" }
            return result()
        }
    }

    private fun module(artifactId: String) = MavenModuleInfo(
        pomPath = tempDir.resolve(artifactId).resolve("pom.xml"),
        groupId = "com.example",
        artifactId = artifactId,
        version = "1.0.0",
    )

    private fun scanOf(artifactId: String) = Scan { listOf(module(artifactId)) }

    /** A server whose workspace scans are [scans], taken in the order the server runs them. */
    private fun serverScanning(vararg scans: Scan): MavenBuildServer {
        val next = AtomicInteger()
        return MavenBuildServer(tempDir, mockk(), { mockk() }) { scans[next.getAndIncrement()].run() }
    }

    private fun targetIds(server: MavenBuildServer): List<String> =
        server.workspaceBuildTargets().get(WAIT_SECONDS, TimeUnit.SECONDS).targets.map { it.id.uri }

    @Test
    fun `an older scan that finishes last does not replace the modules of a newer reload`() {
        // Given: a first reload whose scan is held, and a second reload whose scan returns at once
        val older = scanOf("stale")
        val server = serverScanning(older, scanOf("fresh").released())
        val olderReload = server.workspaceReload()
        assertThat(older.started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()

        // When: the second reload finishes first, and the first one after it
        server.workspaceReload().get(WAIT_SECONDS, TimeUnit.SECONDS)
        older.release.countDown()
        olderReload.get(WAIT_SECONDS, TimeUnit.SECONDS)

        // Then: the server keeps the modules of the reload that was requested last
        assertThat(targetIds(server)).containsExactly("maven:com.example:fresh", "maven:com.example:fresh:test")
    }

    @Test
    fun `a request made during a reload waits for it and answers from its modules`() {
        // Given: an initialized server whose reload scan is held
        val reload = scanOf("after")
        val server = serverScanning(scanOf("before").released(), reload)
        server.buildInitialize(createInitParams(tempDir)).get(WAIT_SECONDS, TimeUnit.SECONDS)
        val reloading = server.workspaceReload()

        // When: a request arrives before that scan returns
        val targets = server.workspaceBuildTargets()

        // Then: the request waits for the scan, and its answer has the reloaded modules
        assertThat(targets).isNotDone()
        reload.release.countDown()
        reloading.get(WAIT_SECONDS, TimeUnit.SECONDS)
        assertThat(targets.get(WAIT_SECONDS, TimeUnit.SECONDS).targets.map { it.id.uri })
            .containsExactly("maven:com.example:after", "maven:com.example:after:test")
    }

    @Test
    fun `a failed reload keeps the modules of the scan before it`() {
        // Given: an initialized server whose next scan fails
        val server = serverScanning(scanOf("kept").released(), Scan { error("unreadable pom") }.released())
        server.buildInitialize(createInitParams(tempDir)).get(WAIT_SECONDS, TimeUnit.SECONDS)

        // When: the reload fails
        assertThatThrownBy { server.workspaceReload().get(WAIT_SECONDS, TimeUnit.SECONDS) }
            .hasCauseInstanceOf(IllegalStateException::class.java)

        // Then: requests still answer from the modules the initialize scan found
        assertThat(targetIds(server)).containsExactly("maven:com.example:kept", "maven:com.example:kept:test")
    }
}
