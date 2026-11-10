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
            // Reads never wait either: the fast classpath's view is there while the slow scan runs.
            val whileSlowRuns = context.getAllMetadata(fast).steps.keys
            releaseSlow.countDown()
            slowScan.get(10, TimeUnit.SECONDS)

            assertTrue(fastFinished, "the second scan waited for the first to finish")
            assertEquals(setOf("fromFast"), whileSlowRuns)
        } finally {
            releaseSlow.countDown()
            pool.shutdownNow()
        }
    }

    /**
     * Two requests, cp1 then cp2, overlap. The scanner for [first] waits until the scanner for [second] has
     * started, so the request order is fixed; [holdFirst] picks which scan finishes last.
     */
    private fun overlappingScans(holdFirst: Boolean): Triple<JenkinsContext, Set<String>, Set<String>> {
        val first = listOf(tempDir.resolve("cp1.jar"))
        val second = listOf(tempDir.resolve("cp2.jar"))
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val context = context { cp ->
            if (cp == first) {
                firstStarted.countDown()
                assertTrue(secondStarted.await(10, TimeUnit.SECONDS), "second scan started")
                if (holdFirst) release.await(30, TimeUnit.SECONDS)
                dynamic("fromCp1")
            } else {
                secondStarted.countDown()
                if (!holdFirst) release.await(30, TimeUnit.SECONDS)
                dynamic("fromCp2")
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val firstScan = pool.submit<Set<String>> { context.scanClasspath(first)?.steps?.keys.orEmpty() }
            // The first request is scanning, so it took its place in line before the second one starts.
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS), "first scan started")
            val secondScan = pool.submit<Set<String>> { context.scanClasspath(second)?.steps?.keys.orEmpty() }
            val early = if (holdFirst) secondScan else firstScan
            early.get(10, TimeUnit.SECONDS) // the scan that is not held publishes first
            release.countDown()
            return Triple(context, firstScan.get(10, TimeUnit.SECONDS), secondScan.get(10, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `the newer request wins when it finishes last`() {
        val (context, firstResult, secondResult) = overlappingScans(holdFirst = false)

        assertEquals(setOf("fromCp1"), context.getAllMetadata(cp("cp1")).steps.keys)
        assertEquals(setOf("fromCp2"), context.getAllMetadata(cp("cp2")).steps.keys)
        assertEquals(setOf("fromCp1"), firstResult)
        assertEquals(setOf("fromCp2"), secondResult)
    }

    @Test
    fun `an older request that finishes last does not replace the newer one`() {
        val (context, firstResult, secondResult) = overlappingScans(holdFirst = true)

        assertEquals(setOf("fromCp1"), context.getAllMetadata(cp("cp1")).steps.keys)
        assertEquals(setOf("fromCp2"), context.getAllMetadata(cp("cp2")).steps.keys)
        assertEquals(setOf("fromCp1"), firstResult, "the older caller still gets its own classpath's scan")
        assertEquals(setOf("fromCp2"), secondResult)
    }

    @Test
    fun `a request for a known classpath is not rescanned and is not replaced by another scan in flight`() {
        val cp1 = listOf(tempDir.resolve("cp1.jar"))
        val cp2 = listOf(tempDir.resolve("cp2.jar"))
        val cp1Scans = AtomicInteger()
        val cp2Started = CountDownLatch(1)
        val releaseCp2 = CountDownLatch(1)
        val context = context { cp ->
            if (cp == cp1) {
                cp1Scans.incrementAndGet()
                dynamic("fromCp1")
            } else {
                cp2Started.countDown()
                releaseCp2.await(30, TimeUnit.SECONDS)
                dynamic("fromCp2")
            }
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            context.scanClasspath(cp1) // published first
            val older = pool.submit { context.scanClasspath(cp2) }
            assertTrue(cp2Started.await(10, TimeUnit.SECONDS), "cp2 scan started")

            val newest = context.scanClasspath(cp1)?.steps?.keys // known classpath, while cp2 is still scanning
            releaseCp2.countDown()
            older.get(10, TimeUnit.SECONDS)

            assertEquals(setOf("fromCp1"), newest)
            assertEquals(setOf("fromCp1"), context.getAllMetadata(cp1).steps.keys)
            assertEquals(setOf("fromCp2"), context.getAllMetadata(cp2).steps.keys)
            assertEquals(1, cp1Scans.get(), "a known classpath is not rescanned")
        } finally {
            releaseCp2.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `readers always see the overlay of exactly their classpath's scan`() {
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
                    while (!stop.get()) context.scanClasspath(cp("w$writer-${i++ % 8}"))
                }
            }
            val readers = (1..4).map {
                pool.submit {
                    repeat(20_000) { i ->
                        val id = "w${1 + i % 2}-${i / 2 % 8}"
                        val steps = context.getAllMetadata(cp(id)).steps.keys
                        val ids = steps.map { it.drop(1) }.toSet()
                        if (steps.isNotEmpty() && (ids != setOf(id) || steps.size != 3)) {
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

    private fun cp(name: String) = listOf(tempDir.resolve("$name.jar"))
}
