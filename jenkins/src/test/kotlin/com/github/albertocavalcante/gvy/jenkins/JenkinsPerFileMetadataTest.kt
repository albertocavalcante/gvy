package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.gvy.jenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.stubs.JenkinsStubGenerator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Scanned library steps belong to the Jenkinsfile whose classpath loads them (#1242): completion and hover in
 * Jenkinsfile A must not offer the steps of a library only Jenkinsfile B loads, whatever the compile order.
 */
class JenkinsPerFileMetadataTest {

    @TempDir
    lateinit var tempDir: Path

    private val baseLoads = AtomicInteger()
    private val scans = AtomicInteger()

    private val base = MergedJenkinsMetadata(
        jenkinsVersion = "test",
        steps = emptyMap(),
        globalVariables = emptyMap(),
        sections = emptyMap(),
        directives = emptyMap(),
    )

    private fun steps(vararg names: String) = BundledJenkinsMetadata(
        steps = names.associateWith { JenkinsStepMetadata(name = it, plugin = "library", parameters = emptyMap()) },
        globalVariables = emptyMap(),
    )

    /** A test jar named `x.jar` contributes the step `xStep`; other jars (a local jenkins-core) contribute none. */
    private fun scan(classpath: List<Path>) = steps(
        *classpath.map { it.fileName.toString().removeSuffix(".jar") }.filter { TEST_JAR.matches(it) }
            .map { it + "Step" }.toTypedArray(),
    ).also { scans.incrementAndGet() }

    private fun context(
        config: JenkinsConfiguration = JenkinsConfiguration(),
        scanner: (List<Path>) -> BundledJenkinsMetadata = ::scan,
        generator: JenkinsStubGenerator = JenkinsStubGenerator(),
    ) = JenkinsContext(
        configuration = config,
        workspaceRoot = tempDir,
        pluginManager = JenkinsPluginManager(),
        bundledMetadataLoader = {
            baseLoads.incrementAndGet()
            base
        },
        classpathScanner = scanner,
        stubHooks = StubHooks(cacheRoot = tempDir.resolve("stubs"), generator = generator),
    )

    private fun manager(
        scanner: (List<Path>) -> BundledJenkinsMetadata = ::scan,
        generator: JenkinsStubGenerator = JenkinsStubGenerator(),
    ): JenkinsWorkspaceManager {
        val libraries = listOf("a", "b").map { name ->
            val jar = Files.createFile(tempDir.resolve("$name.jar"))
            SharedLibrary(name, jar.toString())
        }
        val config = JenkinsConfiguration(sharedLibraries = libraries)
        return JenkinsWorkspaceManager(
            config,
            tempDir,
            JenkinsPluginManager(),
            tempDir.resolve("stubs"),
            context(config, scanner, generator),
        )
    }

    private fun jenkinsfile(dir: String) = tempDir.resolve(dir).resolve("Jenkinsfile").toUri()

    private fun JenkinsWorkspaceManager.libraryStepsFor(uri: java.net.URI) =
        getAllMetadata(uri).steps.keys.filter { it.endsWith("Step") }.toSet()

    private fun assertScopedSteps(compileAFirst: Boolean) {
        val manager = manager()
        val a = jenkinsfile("a")
        val b = jenkinsfile("b")
        val compileA = { manager.getClasspathForFile(a, "@Library('a') _\nnode { aStep() }") }
        val compileB = { manager.getClasspathForFile(b, "@Library('b') _\nnode { bStep() }") }

        if (compileAFirst) {
            compileA()
            compileB()
        } else {
            compileB()
            compileA()
        }

        assertEquals(setOf("aStep"), manager.libraryStepsFor(a))
        assertEquals(setOf("bStep"), manager.libraryStepsFor(b))
    }

    @Test
    fun `each Jenkinsfile sees only its own library steps when A compiles first`() = assertScopedSteps(true)

    @Test
    fun `each Jenkinsfile sees only its own library steps when B compiles first`() = assertScopedSteps(false)

    @Test
    fun `an older compile of the same file that finishes last does not replace the newer classpath`() {
        val aStarted = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val manager = manager(
            scanner = { classpath ->
                if (classpath.any { it.fileName.toString() == "a.jar" }) {
                    aStarted.countDown()
                    releaseA.await(30, TimeUnit.SECONDS)
                }
                scan(classpath)
            },
        )
        val file = jenkinsfile("edited")
        val pool = Executors.newSingleThreadExecutor()
        try {
            // The old text loads library a and is slow; the edit switches to library b and finishes first.
            val oldCompile = pool.submit { manager.getClasspathForFile(file, "@Library('a') _\nnode {}") }
            assertTrue(aStarted.await(10, TimeUnit.SECONDS), "old compile is scanning")
            manager.getClasspathForFile(file, "@Library('b') _\nnode {}")
            releaseA.countDown()
            oldCompile.get(10, TimeUnit.SECONDS)

            assertEquals(setOf("bStep"), manager.libraryStepsFor(file))
        } finally {
            releaseA.countDown()
            pool.shutdownNow()
        }
    }

    /** The generated `CpsScript` stub on [classpath], whatever directory holds it. */
    private fun cpsScriptOn(classpath: List<Path>): String =
        classpath.map { it.resolve(CPS_SCRIPT_STUB) }.single { Files.isRegularFile(it) }.readText()

    private fun assertScopedStubs(compileAFirst: Boolean) {
        val manager = manager()
        val compileA = { manager.getClasspathForFile(jenkinsfile("a"), "@Library('a') _\nnode {}") }
        val compileB = { manager.getClasspathForFile(jenkinsfile("b"), "@Library('b') _\nnode {}") }

        val (classpathA, classpathB) = if (compileAFirst) {
            compileA() to compileB()
        } else {
            compileB().let { b -> compileA() to b }
        }

        assertTrue("def aStep(" in cpsScriptOn(classpathA) && "bStep" !in cpsScriptOn(classpathA))
        assertTrue("def bStep(" in cpsScriptOn(classpathB) && "aStep" !in cpsScriptOn(classpathB))
    }

    @Test
    fun `each Jenkinsfile compiles against its own step stubs when A compiles first`() = assertScopedStubs(true)

    @Test
    fun `each Jenkinsfile compiles against its own step stubs when B compiles first`() = assertScopedStubs(false)

    @Test
    fun `a stub set that cannot be written falls back to the bundled set, never another file's`() {
        val manager = manager(
            generator = JenkinsStubGenerator { file, content ->
                if ("aStep" in content) throw java.nio.file.AccessDeniedException(file.toString())
                file.writeText(content)
            },
        )
        manager.getClasspathForFile(jenkinsfile("b"), "@Library('b') _\nnode {}")

        val classpathA = manager.getClasspathForFile(jenkinsfile("a"), "@Library('a') _\nnode {}")

        assertTrue("aStep" !in cpsScriptOn(classpathA) && "bStep" !in cpsScriptOn(classpathA))
    }

    @Test
    fun `a Jenkinsfile that was never compiled has no scanned library steps`() {
        val manager = manager()
        manager.getClasspathForFile(jenkinsfile("a"), "@Library('a') _\nnode {}")

        assertEquals(emptySet(), manager.libraryStepsFor(jenkinsfile("never")))
    }

    @Test
    fun `scans are kept per classpath, least recently used evicted beyond the bound`() {
        val context = context()
        val classpaths = (0..JenkinsContext.MAX_SCANNED_CLASSPATHS).map { listOf(tempDir.resolve("cp$it.jar")) }

        classpaths.dropLast(1).forEach(context::scanClasspath) // fills the cache
        context.scanClasspath(classpaths[0]) // touch cp0: now most recently used
        context.scanClasspath(classpaths.last()) // one more: evicts cp1, the least recently used
        val scansBefore = scans.get()
        context.scanClasspath(classpaths[0])
        val cp0Kept = scans.get() == scansBefore
        context.scanClasspath(classpaths[1])
        val cp1Rescanned = scans.get() == scansBefore + 1

        assertEquals(true, cp0Kept, "the touched classpath was evicted")
        assertEquals(true, cp1Rescanned, "the least recently used classpath was not evicted")
        assertEquals(setOf("cp0Step"), context.getAllMetadata(classpaths[0]).steps.keys)
        assertEquals(1, baseLoads.get(), "bundled metadata is loaded once")
    }

    @Test
    fun `the view for a classpath is memoized`() {
        val context = context()
        val cp = listOf(tempDir.resolve("x.jar"))
        context.scanClasspath(cp)

        assertSame(context.getAllMetadata(cp), context.getAllMetadata(cp))
        assertEquals(setOf("xStep"), context.getAllMetadata(cp).steps.keys)
    }

    private companion object {
        val TEST_JAR = Regex("a|b|x|cp\\d+")
        const val CPS_SCRIPT_STUB = "org/jenkinsci/plugins/workflow/cps/CpsScript.groovy"
    }
}
