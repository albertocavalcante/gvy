package com.github.albertocavalcante.gvy.build.gradle

import com.github.albertocavalcante.gvy.build.ResolutionCancellation
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drives the bundled Gradle Tooling API into real distribution-install failures against a loopback server, so the
 * messages [GradleFailureAnalyzer] matches are the Tooling API's own, not copies. If a tooling-api upgrade rewords
 * them, these tests fail instead of the retry silently switching off.
 *
 * Nothing leaves the machine: every distribution URL points at 127.0.0.1. Tagged `loopback` so a CI runner without
 * a loopback interface can exclude them. Each test installs into its own temporary Gradle user home, never the
 * user's `~/.gradle`; JUnit deletes it after the test.
 */
@Tag("loopback")
class GradleDistributionInstallFailureTest {

    @TempDir
    lateinit var tempDir: Path

    private val analyzer = GradleFailureAnalyzer()
    private val servers = CopyOnWriteArrayList<HttpServer>()
    private val serverExecutors = CopyOnWriteArrayList<ExecutorService>()
    private val connections = CopyOnWriteArrayList<ProjectConnection>()
    private val releases = CopyOnWriteArrayList<CountDownLatch>()

    @AfterEach
    fun tearDown() {
        // Release stalled handlers first so server threads can finish, then close everything the test opened.
        releases.forEach { it.countDown() }
        connections.forEach { runCatching { it.close() } }
        servers.forEach { it.stop(0) }
        serverExecutors.forEach { it.shutdownNow() }
    }

    @Test
    fun `refused connection during install is transient`() {
        val project = project("http://127.0.0.1:${closedPort()}/$DISTRIBUTION")

        val failure = installFailure(project)

        assertTrue(analyzer.isTransient(failure), "Refused install should be retried:\n${describe(failure)}")
    }

    @Test
    fun `HTTP 503 during install is transient and retried up to the attempt limit`() {
        val unavailable = server { exchange -> exchange.respond(503) }
        val notFound = server { exchange -> exchange.respond(404) }

        val unavailableFailure = installFailure(project(unavailable.url))
        assertTrue(analyzer.isTransient(unavailableFailure), "503 should be retried:\n${describe(unavailableFailure)}")

        // Fetch attempts are counted against a 404 baseline, so this does not depend on how many requests one
        // attempt makes (the BuildEnvironment preflight and the IdeaProject fetch each try to install).
        unavailable.hits.set(0)
        notFound.hits.set(0)
        assertFalse(resolve(project(unavailable.url)).isUsable)
        assertFalse(resolve(project(notFound.url)).isUsable)

        val perAttempt = notFound.hits.get()
        assertTrue(perAttempt > 0, "The 404 baseline never reached the server")
        assertEquals(RETRIES.maxAttempts * perAttempt, unavailable.hits.get(), "503 should use every attempt")
    }

    @Test
    fun `HTTP 404 during install is not transient`() {
        val notFound = server { exchange -> exchange.respond(404) }

        val failure = installFailure(project(notFound.url))

        assertFalse(analyzer.isTransient(failure), "A missing distribution must not be retried:\n${describe(failure)}")
    }

    @Test
    fun `cancelling a stalled download ends the resolution and releases the install lock`() {
        val requestArrived = CountDownLatch(1)
        val release = CountDownLatch(1).also(releases::add)
        val stalled = server { exchange ->
            if (exchange.isFirst) {
                requestArrived.countDown()
                try {
                    // Hold the download open until the test lets go; never answer it before cancellation.
                    release.await(STALL_LIMIT_SECONDS, TimeUnit.SECONDS)
                } finally {
                    exchange.respond(404)
                }
            } else {
                exchange.respond(404)
            }
        }
        val project = project(stalled.url)
        val cancellation = ResolutionCancellation()

        val cancelled = CompletableFuture.supplyAsync { resolve(project, cancellation) }
        assertTrue(requestArrived.await(BOUND_SECONDS, TimeUnit.SECONDS), "The distribution download never started")
        cancellation.cancel()

        val failure = assertThrows<ExecutionException>("Cancelled resolution did not end within ${BOUND_SECONDS}s") {
            cancelled.get(BOUND_SECONDS, TimeUnit.SECONDS)
        }
        assertIs<CancellationException>(failure.cause, "Expected cancellation, got:\n${describe(failure.cause)}")
        failure.cause?.cause?.let { toolingFailure ->
            assertFalse(analyzer.isTransient(toolingFailure), "A cancelled install must not be retried")
        }

        // The stalled request is still open. If the cancelled install kept the distribution lock, this install of
        // the same URL into the same user home would wait on it instead of reaching the server.
        val hitsBefore = stalled.hits.get()
        val second = CompletableFuture.supplyAsync { resolve(project) }
        try {
            assertFalse(second.get(BOUND_SECONDS, TimeUnit.SECONDS).isUsable)
        } catch (_: TimeoutException) {
            fail("A second install did not get the distribution lock within ${BOUND_SECONDS}s after cancellation")
        }
        assertTrue(stalled.hits.get() > hitsBefore, "The second install never reached the server")
    }

    private fun installFailure(project: Path): GradleConnectionException {
        val connection = connect(project)
        return assertThrows<GradleConnectionException> { connection.getModel(BuildEnvironment::class.java) }
    }

    private fun resolve(
        project: Path,
        cancellation: ResolutionCancellation = ResolutionCancellation(),
    ): WorkspaceResolution = GradleBuildTool(
        connectionFactory = object : GradleConnectionFactory {
            override fun getConnection(projectDir: Path, gradleUserHomeDir: File?) = connect(projectDir)
        },
        retryConfig = RETRIES,
    ).resolve(project, onProgress = null, cancellation = cancellation)

    /** Connects with a per-test Gradle user home, so installs and their locks never touch `~/.gradle`. */
    private fun connect(project: Path): ProjectConnection = GradleConnector.newConnector()
        .forProjectDirectory(project.toFile())
        .useGradleUserHomeDir(tempDir.resolve("gradle-user-home").toFile())
        .connect()
        .also(connections::add)

    private fun project(distributionUrl: String): Path {
        val project = Files.createTempDirectory(tempDir, "project")
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'install-failure'\n")
        Files.writeString(project.resolve("build.gradle"), "")
        val wrapper = Files.createDirectories(project.resolve("gradle/wrapper"))
        Files.writeString(
            wrapper.resolve("gradle-wrapper.properties"),
            "distributionUrl=${distributionUrl.replace(":", "\\:")}\n",
        )
        return project
    }

    private class LoopbackServer(val url: String, val hits: AtomicInteger)

    private class Exchange(private val exchange: HttpExchange, val isFirst: Boolean) {
        fun respond(status: Int) {
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
    }

    private fun server(handler: (Exchange) -> Unit): LoopbackServer {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        // More than one thread, so a stalled request does not block the next one.
        val executor = Executors.newCachedThreadPool().also(serverExecutors::add)
        server.executor = executor
        server.createContext("/") { exchange -> handler(Exchange(exchange, isFirst = hits.incrementAndGet() == 1)) }
        server.start()
        servers.add(server)
        return LoopbackServer("http://127.0.0.1:${server.address.port}/$DISTRIBUTION", hits)
    }

    private fun closedPort(): Int = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun describe(failure: Throwable?): String = generateSequence(failure) { it.cause }
        .take(MAX_CAUSES)
        .joinToString("\n") { "  ${it.javaClass.name}: ${it.message}" }

    private companion object {
        const val DISTRIBUTION = "gradle-9.2.1-bin.zip"
        const val BOUND_SECONDS = 5L
        const val STALL_LIMIT_SECONDS = 60L
        const val MAX_CAUSES = 10
        val RETRIES = GradleDependencyResolver.RetryConfig(
            maxAttempts = 4,
            initialDelayMs = 0L,
            backoffMultiplier = 1.0,
        )
    }
}
