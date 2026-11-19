// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import com.github.albertocavalcante.gvy.build.maven.RepositoryProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.eclipse.aether.repository.RemoteRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.UncheckedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Exercise the real resolver transport against an isolated repository, without public network access. */
class VerifiedSourceArtifactResolverTest {
    @TempDir lateinit var directory: Path
    private val coordinates = ResolvedArtifactCoordinates("sample", "library", "1.0")

    @Test
    fun `checksum is required before jar transfer and validated sources can be reused offline`() = runBlocking {
        val bytes = sourceArchive()
        repository(bytes, sha256(bytes)).use { repository ->
            val resolver = MavenSourceArtifactResolver(directory, repository)
            val first = assertIs<VerifiedSourceArtifactResult.Available>(
                resolver.resolveVerifiedSourceArtifact(request()),
            )
            assertEquals(sha256(bytes), first.expectedSha256)
            assertEquals(bytes.toList(), Files.readAllBytes(first.path).toList())
            val checksumRequest = repository.requests.indexOfFirst { it.endsWith("-sources.jar.sha256") }
            val jarRequest = repository.requests.indexOfFirst { it.endsWith("-sources.jar") }
            assertTrue(
                checksumRequest >= 0 && jarRequest > checksumRequest,
                "Archive transfer must follow the expected checksum",
            )
            val transfers = repository.requests.size
            assertIs<VerifiedSourceArtifactResult.Available>(
                resolver.resolveVerifiedSourceArtifact(request(download = false)),
            )
            assertEquals(transfers, repository.requests.size)
        }
    }

    @Test
    fun `absent expected SHA never downloads the archive`() = runBlocking {
        repository(sourceArchive(), null).use { repository ->
            val result = MavenSourceArtifactResolver(directory, repository).resolveVerifiedSourceArtifact(request())
            assertEquals(VerifiedSourceArtifactResult.IntegrityUnavailable, result)
            assertTrue(repository.requests.none { it.endsWith("-sources.jar") })
        }
    }

    @Test
    fun `incorrect repository SHA is an explicit integrity transport failure`() = runBlocking {
        repository(sourceArchive(), "0".repeat(64)).use { repository ->
            assertEquals(
                VerifiedSourceArtifactResult.Failed,
                MavenSourceArtifactResolver(directory, repository).resolveVerifiedSourceArtifact(request()),
            )
        }
    }

    @Test
    fun `authentication and server failures never become published-source absence`() = runBlocking {
        for (status in listOf(401, 503)) {
            repository(sourceArchive(), null, RepositoryOptions(errorStatus = status)).use { repository ->
                assertEquals(
                    VerifiedSourceArtifactResult.Failed,
                    MavenSourceArtifactResolver(directory.resolve("$status"), repository)
                        .resolveVerifiedSourceArtifact(request()),
                )
            }
        }
    }

    @Test
    fun `offline lookup performs no transfers and coordinate path traversal is rejected`() = runBlocking {
        repository(sourceArchive(), null).use { repository ->
            val resolver = MavenSourceArtifactResolver(directory, repository)
            resolver.resolveVerifiedSourceArtifact(request(download = false))
            assertEquals(
                VerifiedSourceArtifactResult.UnsupportedArtifact,
                resolver.resolveVerifiedSourceArtifact(
                    request().copy(coordinates = coordinates.copy(module = "../../escape")),
                ),
            )
            assertEquals(
                VerifiedSourceArtifactResult.UnsupportedArtifact,
                resolver.resolveVerifiedSourceArtifact(
                    request().copy(coordinates = coordinates.copy(classifier = "tests")),
                ),
            )
            assertEquals(emptyList(), repository.requests.toList())
        }
    }

    @Test
    fun `repository contexts do not share cached source bytes by coordinates alone`() = runBlocking {
        val one = sourceArchive("one")
        val two = sourceArchive("two")
        repository(one, sha256(one)).use { first ->
            repository(two, sha256(two)).use { second ->
                val firstSource = assertIs<VerifiedSourceArtifactResult.Available>(
                    MavenSourceArtifactResolver(directory, first)
                        .resolveVerifiedSourceArtifact(request(context = "one")),
                )
                val secondSource = assertIs<VerifiedSourceArtifactResult.Available>(
                    MavenSourceArtifactResolver(directory, second)
                        .resolveVerifiedSourceArtifact(request(context = "two")),
                )
                assertEquals(one.toList(), Files.readAllBytes(firstSource.path).toList())
                assertEquals(two.toList(), Files.readAllBytes(secondSource.path).toList())
            }
        }
    }

    @Test
    fun `same coordinates with a privately patched binary cannot fetch public source bytes`() = runBlocking {
        repository(
            sourceArchive(),
            "0".repeat(64),
            RepositoryOptions(binaryChecksum = "b".repeat(64)),
        ).use { repository ->
            val result = MavenSourceArtifactResolver(directory, repository).resolveVerifiedSourceArtifact(request())
            assertEquals(VerifiedSourceArtifactResult.BinaryMismatch, result)
            assertTrue(repository.requests.any { it.endsWith("library-1.0.jar.sha256") })
            assertTrue(
                repository.requests.none { it.contains("-sources.") },
                "Wrong binary origin must stop before source metadata or bytes",
            )
        }
    }

    @Test
    fun `binary proof and source checksum are never combined across repositories`() = runBlocking {
        val bytes = sourceArchive()
        repository(bytes, null).use { matchingBinary ->
            repository(bytes, sha256(bytes), RepositoryOptions(binaryChecksum = "b".repeat(64))).use { otherBinary ->
                val repositories = object : RepositoryProvider {
                    override fun getRepositories() = matchingBinary.getRepositories() + otherBinary.getRepositories()
                }
                val result = MavenSourceArtifactResolver(directory, repositories)
                    .resolveVerifiedSourceArtifact(request())
                assertEquals(VerifiedSourceArtifactResult.IntegrityUnavailable, result)
                assertTrue(matchingBinary.requests.none { it.endsWith("-sources.jar") })
                assertEquals(emptyList(), otherBinary.requests.toList())
            }
        }
    }

    @Test
    fun `full verified store refuses another download without deleting existing cache files`() = runBlocking {
        val bytes = sourceArchive()
        repository(bytes, sha256(bytes)).use { repository ->
            val root = defaultVerifiedSourceCacheRoot(directory)
            Files.createDirectories(root)
            val retained = root.resolve("retained-download")
            Files.writeString(retained, "existing")
            val resolver = MavenSourceArtifactResolver(directory, repository, verifiedCacheBytes = 1024)
            assertEquals(VerifiedSourceArtifactResult.LimitExceeded, resolver.resolveVerifiedSourceArtifact(request()))
            assertEquals("existing", Files.readString(retained))
            assertEquals(emptyList(), repository.requests.toList())
        }
    }

    @Test
    fun `snapshot metadata and source transfer share reserved capacity and failed download can retry`() = runBlocking {
        val largeArchive = directory.resolve("near-limit-sources.jar")
        writeNearLimitSourceArchive(largeArchive)
        assertTrue(Files.size(largeArchive) in 63L * 1_048_576L..VERIFIED_SOURCE_MAX_ARTIFACT_BYTES)
        val largeMetadata = snapshotMetadata(2 * 1_048_576)
        assertTrue(largeMetadata.size > 1_048_576)
        assertTrue(Files.size(largeArchive) < VERIFIED_SOURCE_MAX_ARTIFACT_BYTES)
        assertTrue(Files.size(largeArchive) + largeMetadata.size > VERIFIED_SOURCE_MAX_OPERATION_BYTES)
        val smallArchive = sourceArchive("snapshot-retry")
        val root = defaultVerifiedSourceCacheRoot(directory)
        Files.createDirectories(root)
        val retained = Files.writeString(root.resolve("retained-download"), "preserve")
        val filler = Files.write(root.resolve("prefilled-reservation"), ByteArray(8 * 1024))
        val initialBytes = managedFileBytes(root)
        val maximumBytes = initialBytes + VERIFIED_SOURCE_CACHE_RESERVATION_BYTES
        val cache = SnapshotCache(root, maximumBytes, retained, filler)
        val retryCache = cache.copy(maximumBytes = initialBytes + 2 * VERIFIED_SOURCE_CACHE_RESERVATION_BYTES)
        val repository = TestRepository(
            archive = TestBody.fromFile(largeArchive, paced = true),
            checksum = sha256(largeArchive),
            options = RepositoryOptions(metadata = largeMetadata),
        )
        repository.use {
            assertSnapshotBudgetRejection(cache, repository)
            assertSnapshotRetry(retryCache, repository, smallArchive)
        }
    }

    private suspend fun assertSnapshotBudgetRejection(cache: SnapshotCache, repository: TestRepository) {
        val resolver = MavenSourceArtifactResolver(directory, repository, verifiedCacheBytes = cache.maximumBytes)
        val probe = DiskUsageProbe(cache.root)
        probe.use {
            assertEquals(
                VerifiedSourceArtifactResult.LimitExceeded,
                resolver.resolveVerifiedSourceArtifact(snapshotRequest()),
            )
        }
        val peakBytes = probe.peakBytes.get()

        assertEquals("preserve", Files.readString(cache.retained))
        assertEquals(8L * 1_024L, Files.size(cache.filler))
        assertTrue(peakBytes <= cache.maximumBytes)
        assertTrue(peakBytes > Files.size(cache.retained) + 1_048_576)
        assertTrue(repository.requests.any { it.endsWith("maven-metadata.xml") })
        assertTrue(repository.requests.any { it.endsWith("-sources.jar") })
        assertTrue(repository.metadataBytesSent.get() > 1_048_576)
        assertTrue(repository.sourceJarBytesSent.get() > 1_048_576)
        assertTrue(managedFileBytes(cache.root) <= cache.maximumBytes)
        assertTrue(managedFiles(cache.root).none { it.fileName.toString().endsWith("-sources.jar") })
        assertTrue(managedFiles(cache.root).none { it.fileName.toString().endsWith(".tmp") })
    }

    private suspend fun assertSnapshotRetry(
        cache: SnapshotCache,
        repository: TestRepository,
        smallArchive: ByteArray,
    ) {
        repository.replaceFixture(
            archive = TestBody.fromBytes(smallArchive),
            checksum = sha256(smallArchive),
            metadata = snapshotMetadata(0, timestamp = "20260102.000000", buildNumber = 2),
        )
        val resolver = MavenSourceArtifactResolver(directory, repository, verifiedCacheBytes = cache.maximumBytes)
        val result = resolver.resolveVerifiedSourceArtifact(snapshotRequest())
        val recovered =
            assertIs<VerifiedSourceArtifactResult.Available>(result, "retry requests: ${repository.requests}")
        assertEquals(sha256(smallArchive), recovered.expectedSha256)
        assertEquals(smallArchive.toList(), Files.readAllBytes(recovered.path).toList())
        assertEquals("preserve", Files.readString(cache.retained))
        assertEquals(8L * 1_024L, Files.size(cache.filler))
        assertTrue(managedFileBytes(cache.root) <= cache.maximumBytes)
    }

    private data class SnapshotCache(val root: Path, val maximumBytes: Long, val retained: Path, val filler: Path)

    @Test
    fun `implicit checksum sidecar is never fetched outside the transfer listener`() = runBlocking {
        val archive = sourceArchive()
        repository(
            archive,
            sha256(archive),
            RepositoryOptions(hugeSourceChecksumOnRepeat = true),
        ).use { repository ->
            val result = assertIs<VerifiedSourceArtifactResult.Available>(
                MavenSourceArtifactResolver(directory, repository).resolveVerifiedSourceArtifact(request()),
            )
            assertEquals(sha256(archive), result.expectedSha256)
            assertEquals(1, repository.sourceChecksumRequests.get())
            assertEquals(1, repository.requests.count { it.endsWith("-sources.jar.sha256") })
        }
    }

    private fun request(download: Boolean = true, context: String = "context") =
        VerifiedSourceArtifactRequest(coordinates, "a".repeat(64), download, context)

    private fun snapshotRequest() = request(context = "snapshot-quota").copy(
        coordinates = coordinates.copy(version = "1.0-SNAPSHOT"),
    )

    private fun repository(
        archive: ByteArray,
        checksum: String?,
        options: RepositoryOptions = RepositoryOptions(),
    ): TestRepository = TestRepository(
        archive = TestBody.fromBytes(archive),
        checksum = checksum,
        options = options,
    )

    private data class RepositoryOptions(
        val errorStatus: Int? = null,
        val binaryChecksum: String = "a".repeat(64),
        val metadata: ByteArray? = null,
        val hugeSourceChecksumOnRepeat: Boolean = false,
    )

    private class TestRepository(archive: TestBody, checksum: String?, private val options: RepositoryOptions) :
        RepositoryProvider,
        AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        val sourceChecksumRequests = AtomicInteger()
        val sourceJarBytesSent = AtomicLong()
        val metadataBytesSent = AtomicLong()
        private val archiveBody = AtomicReference(archive)
        private val sourceChecksumBody = AtomicReference(checksum?.toByteArray(Charsets.US_ASCII))
        private val metadataBody = AtomicReference(options.metadata?.let(TestBody::fromBytes))
        private val repeatedChecksumBody = if (options.hugeSourceChecksumOnRepeat) {
            TestBody.fromBytes(ByteArray(2 * 1_048_576) { 'a'.code.toByte() })
        } else {
            null
        }
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                requests += path
                val body = when {
                    path.endsWith("maven-metadata.xml") -> metadataBody.get()
                    path.endsWith("-sources.jar.sha256") -> sourceChecksumResponse()
                    path.endsWith(
                        ".jar.sha256",
                    ) -> TestBody.fromBytes(options.binaryChecksum.toByteArray(Charsets.US_ASCII))
                    path.endsWith("-sources.jar") -> archiveBody.get()
                    else -> null
                }
                val status = options.errorStatus ?: if (body == null) 404 else 200
                exchange.sendResponseHeaders(status, if (body != null && status == 200) body.length else -1)
                try {
                    exchange.responseBody.use { output ->
                        if (body != null && status == 200) {
                            body.writeTo(output) { count ->
                                if (path.endsWith("-sources.jar")) sourceJarBytesSent.addAndGet(count.toLong())
                                if (path.endsWith("maven-metadata.xml")) metadataBytesSent.addAndGet(count.toLong())
                            }
                        }
                    }
                } catch (_: IOException) {
                    // The resolver closes an over-budget transfer before consuming the response body.
                } finally {
                    exchange.close()
                }
            }
            start()
        }

        fun replaceFixture(archive: TestBody, checksum: String, metadata: ByteArray?) {
            archiveBody.set(archive)
            sourceChecksumBody.set(checksum.toByteArray(Charsets.US_ASCII))
            metadataBody.set(metadata?.let(TestBody::fromBytes))
        }

        private fun sourceChecksumResponse(): TestBody? {
            val requestNumber = sourceChecksumRequests.getAndIncrement()
            val bytes = if (requestNumber > 0) repeatedChecksumBody else null
            return bytes ?: sourceChecksumBody.get()?.let(TestBody::fromBytes)
        }

        override fun getRepositories(): List<RemoteRepository> = listOf(
            RemoteRepository.Builder("isolated", "default", "http://localhost:${server.address.port}/").build(),
        )
        override fun close() = server.stop(0)
    }

    private class TestBody(val length: Long, private val writer: (OutputStream) -> Unit) {
        fun writeTo(output: OutputStream, onWrite: (Int) -> Unit = {}) {
            val countingOutput = object : OutputStream() {
                override fun write(value: Int) {
                    output.write(value)
                    onWrite(1)
                }

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    output.write(buffer, offset, length)
                    onWrite(length)
                }
            }
            writer(countingOutput)
        }

        companion object {
            fun fromBytes(bytes: ByteArray) = TestBody(bytes.size.toLong()) { output -> output.write(bytes) }

            fun fromFile(path: Path, paced: Boolean = false) = TestBody(Files.size(path)) { output ->
                Files.newInputStream(path).use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        output.flush()
                        if (paced) Thread.sleep(1)
                    }
                }
            }
        }
    }

    private fun sourceArchive(version: String = "default"): ByteArray = ByteArrayOutputStream().also { bytes ->
        JarOutputStream(bytes).use { jar ->
            jar.putNextEntry(JarEntry("sample/Library.java"))
            jar.write("package sample; public class Library {} // $version".toByteArray(Charsets.UTF_8))
            jar.closeEntry()
        }
    }.toByteArray()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun snapshotMetadata(
        commentBytes: Int,
        timestamp: String = "20260101.000000",
        buildNumber: Int = 1,
    ): ByteArray = (
        """<?xml version="1.0" encoding="UTF-8"?>
            |<metadata><groupId>sample</groupId><artifactId>library</artifactId><version>1.0-SNAPSHOT</version>
            |<versioning><snapshot><timestamp>$timestamp</timestamp><buildNumber>$buildNumber</buildNumber></snapshot>
            |<lastUpdated>${timestamp.replace(".", "").take(14)}</lastUpdated><snapshotVersions>
            |<snapshotVersion><extension>jar.sha256</extension><value>1.0-$timestamp-$buildNumber</value><updated>${timestamp.replace(
            ".",
            "",
        ).take(14)}</updated></snapshotVersion>
            |<snapshotVersion><extension>jar.sha256</extension><classifier>sources</classifier><value>1.0-$timestamp-$buildNumber</value><updated>${timestamp.replace(
            ".",
            "",
        ).take(14)}</updated></snapshotVersion>
            |<snapshotVersion><extension>jar</extension><classifier>sources</classifier><value>1.0-$timestamp-$buildNumber</value><updated>${timestamp.replace(
            ".",
            "",
        ).take(14)}</updated></snapshotVersion>
            |</snapshotVersions></versioning></metadata>
            |<!--${"x".repeat(commentBytes)}-->
        """.trimMargin()
        ).toByteArray(Charsets.UTF_8)

    private fun writeNearLimitSourceArchive(path: Path) {
        val payloadBytes = VERIFIED_SOURCE_MAX_ARTIFACT_BYTES - 256 * 1_024L
        val payloadChunk = ByteArray(32 * 1_024) { (it % 251).toByte() }
        val crc = CRC32()
        var remaining = payloadBytes
        while (remaining > 0) {
            val count = minOf(payloadChunk.size.toLong(), remaining).toInt()
            crc.update(payloadChunk, 0, count)
            remaining -= count
        }

        Files.newOutputStream(path).use { output ->
            JarOutputStream(output).use { jar ->
                jar.putNextEntry(JarEntry("sample/Library.java"))
                jar.write("package sample; public class Library {}".toByteArray(Charsets.UTF_8))
                jar.closeEntry()

                val padding = JarEntry("padding.bin").apply {
                    method = ZipEntry.STORED
                    size = payloadBytes
                    compressedSize = payloadBytes
                    this.crc = crc.value
                }
                jar.putNextEntry(padding)
                remaining = payloadBytes
                while (remaining > 0) {
                    val count = minOf(payloadChunk.size.toLong(), remaining).toInt()
                    jar.write(payloadChunk, 0, count)
                    remaining -= count
                }
                jar.closeEntry()
            }
        }
    }

    private fun managedFileBytes(root: Path): Long = Files.walk(root).use { paths ->
        val iterator = paths.iterator()
        var total = 0L
        while (iterator.hasNext()) {
            val path = iterator.next()
            if (Files.isRegularFile(path)) total += Files.size(path)
        }
        total
    }

    private fun managedFiles(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList()
    }

    @Test
    fun `disk usage probe skips disappearing files and propagates other sampling failures`() {
        val missingFileObserved = CountDownLatch(1)
        val samples = AtomicInteger()
        val probe = DiskUsageProbe(directory) {
            if (samples.getAndIncrement() == 0) {
                missingFileObserved.countDown()
                throw NoSuchFileException(directory.resolve("transient.tmp").toString())
            }
            7L
        }

        try {
            assertTrue(missingFileObserved.await(5, TimeUnit.SECONDS))
        } finally {
            probe.close()
        }
        assertFalse(probe.isWorkerAlive)
        assertEquals(7L, probe.peakBytes.get())

        val unexpectedFailureObserved = CountDownLatch(1)
        val failingSamples = AtomicInteger()
        val failingProbe = DiskUsageProbe(directory) {
            if (failingSamples.getAndIncrement() == 0) {
                unexpectedFailureObserved.countDown()
                throw IOException("disk usage sample failed")
            }
            7L
        }

        try {
            assertTrue(unexpectedFailureObserved.await(5, TimeUnit.SECONDS))
            val failure = assertFailsWith<AssertionError> { failingProbe.close() }
            assertIs<IOException>(failure.cause)
            assertFalse(failingProbe.isWorkerAlive)
        } finally {
            if (failingProbe.isWorkerAlive) {
                runCatching { failingProbe.close() }
            }
        }
    }

    private inner class DiskUsageProbe(
        private val root: Path,
        private val sampleBytes: () -> Long = { managedFileBytes(root) },
    ) : AutoCloseable {
        val peakBytes = AtomicLong()
        private val running = AtomicBoolean(true)
        private val workerFailure = AtomicReference<Throwable?>()

        val isWorkerAlive: Boolean get() = worker.isAlive

        private val worker = Thread({
            try {
                while (running.get()) {
                    sample()
                    Thread.sleep(1)
                }
            } catch (interrupted: InterruptedException) {
                if (running.get()) workerFailure.compareAndSet(null, interrupted)
            } catch (failure: Throwable) {
                workerFailure.compareAndSet(null, failure)
            }
        }, "source-cache-usage-probe").apply {
            isDaemon = true
            start()
        }

        override fun close() {
            running.set(false)
            worker.interrupt()
            worker.join(TimeUnit.SECONDS.toMillis(5))
            check(!worker.isAlive) { "Source cache usage probe did not stop" }
            val finalSize = sampleBytes()
            peakBytes.updateAndGet { previous -> maxOf(previous, finalSize) }
            workerFailure.get()?.let { failure ->
                throw AssertionError("Source cache usage probe failed").apply { initCause(failure) }
            }
        }

        private fun sample() {
            try {
                val current = sampleBytes()
                peakBytes.updateAndGet { previous -> maxOf(previous, current) }
            } catch (_: NoSuchFileException) {
                // Files.walk and Files.size can race with cache cleanup between probes.
            } catch (failure: UncheckedIOException) {
                if (failure.cause !is NoSuchFileException) throw failure
            }
        }
    }
}
