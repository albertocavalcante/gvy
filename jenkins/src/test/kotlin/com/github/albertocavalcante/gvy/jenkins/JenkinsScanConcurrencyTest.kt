package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.gvy.jenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every Jenkinsfile compile (completion included) builds its classpath, which scans it when it changed (#1241).
 * A long scan for one file's classpath must not hold up another file's request, and readers must always see the
 * overlay of exactly one published scan.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JenkinsScanConcurrencyTest {

    @TempDir
    lateinit var tempDir: Path

    private val base = MergedJenkinsMetadata(
        jenkinsVersion = "test",
        steps = emptyMap(),
        globalVariables = emptyMap(),
        sections = emptyMap(),
        directives = emptyMap(),
    )

    private fun dynamic(vararg names: String) = BundledJenkinsMetadata(
        steps = names.associateWith { JenkinsStepMetadata(name = it, plugin = "scanned", parameters = emptyMap()) },
        globalVariables = emptyMap(),
    )

    private fun context(scanner: (List<Path>) -> BundledJenkinsMetadata) = JenkinsContext(
        configuration = JenkinsConfiguration(),
        workspaceRoot = tempDir,
        pluginManager = JenkinsPluginManager(),
        bundledMetadataLoader = { base },
        classpathScanner = scanner,
    )

    @Test
    fun `a scan for another classpath does not wait for a scan in progress`() {
        val slow = listOf(tempDir.resolve("slow.jar"))
        val fast = listOf(tempDir.resolve("fast.jar"))
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val context = context { cp ->
            if (cp == slow) {
                slowStarted.countDown()
                releaseSlow.await(30, TimeUnit.SECONDS)
                dynamic("fromSlow")
            } else {
                dynamic("fromFast")
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val slowScan = pool.submit { context.scanClasspath(slow) }
            assertTrue(slowStarted.await(10, TimeUnit.SECONDS), "slow scan started")

            val fastScan = pool.submit { context.scanClasspath(fast) }
            val fastFinished = runCatching { fastScan.get(5, TimeUnit.SECONDS) }.isSuccess
            // Reads never wait either: the view is the last published scan.
            val whileSlowRuns = context.getAllMetadata().steps.keys
            releaseSlow.countDown()
            slowScan.get(10, TimeUnit.SECONDS)

            assertTrue(fastFinished, "the second scan waited for the first to finish")
            assertEquals(setOf("fromFast"), whileSlowRuns)
        } finally {
            releaseSlow.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a scan that lost the race does not replace the snapshot published meanwhile`() {
        val slow = listOf(tempDir.resolve("slow.jar"))
        val fast = listOf(tempDir.resolve("fast.jar"))
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val context = context { cp ->
            if (cp == slow) {
                slowStarted.countDown()
                releaseSlow.await(30, TimeUnit.SECONDS)
                dynamic("fromSlow")
            } else {
                dynamic("fromFast")
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val slowScan = pool.submit { context.scanClasspath(slow) }
            assertTrue(slowStarted.await(10, TimeUnit.SECONDS), "slow scan started")
            val fastScan = pool.submit { context.scanClasspath(fast) }
            runCatching { fastScan.get(5, TimeUnit.SECONDS) } // published while the slow scan still runs
            releaseSlow.countDown()
            slowScan.get(10, TimeUnit.SECONDS)
            fastScan.get(10, TimeUnit.SECONDS)

            // The slow scan started from the older snapshot, so it is discarded instead of replacing the newer one.
            assertEquals(setOf("fromFast"), context.getAllMetadata().steps.keys)
        } finally {
            releaseSlow.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `readers always see the overlay of exactly one scan`() {
        val scans = AtomicInteger()
        val context = context { cp ->
            val id = cp.single().fileName.toString().removeSuffix(".jar")
            scans.incrementAndGet()
            dynamic("a$id", "b$id", "c$id")
        }
        val stop = AtomicBoolean(false)
        val torn = mutableListOf<Set<String>>()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val writers = (1..2).map { writer ->
                pool.submit {
                    var i = 0
                    while (!stop.get()) context.scanClasspath(listOf(tempDir.resolve("w$writer-${i++ % 8}.jar")))
                }
            }
            val readers = (1..4).map {
                pool.submit {
                    repeat(20_000) {
                        val steps = context.getAllMetadata().steps.keys
                        val ids = steps.map { it.drop(1) }.toSet()
                        if (steps.isNotEmpty() && (ids.size != 1 || steps.size != 3)) {
                            synchronized(torn) { torn += steps }
                        }
                    }
                }
            }
            readers.forEach { it.get(30, TimeUnit.SECONDS) }
            stop.set(true)
            writers.forEach { it.get(10, TimeUnit.SECONDS) }

            assertTrue(torn.isEmpty(), "torn views: ${torn.take(3)}")
            assertTrue(scans.get() > 1, "the writers published several scans")
        } finally {
            stop.set(true)
            pool.shutdownNow()
        }
    }
}
