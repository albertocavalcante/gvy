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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
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
            repository(sourceArchive(), null, status).use { repository ->
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
        repository(sourceArchive(), "0".repeat(64), binaryChecksum = "b".repeat(64)).use { repository ->
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
            repository(bytes, sha256(bytes), binaryChecksum = "b".repeat(64)).use { otherBinary ->
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

    private fun request(download: Boolean = true, context: String = "context") =
        VerifiedSourceArtifactRequest(coordinates, "a".repeat(64), download, context)

    private fun repository(
        archive: ByteArray,
        checksum: String?,
        errorStatus: Int? = null,
        binaryChecksum: String = "a".repeat(64),
    ): TestRepository = TestRepository(archive, checksum, errorStatus, binaryChecksum)

    private class TestRepository(archive: ByteArray, checksum: String?, errorStatus: Int?, binaryChecksum: String) :
        RepositoryProvider,
        AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                requests += path
                val body = when {
                    path.endsWith("-sources.jar.sha256") -> checksum?.toByteArray(Charsets.US_ASCII)
                    path.endsWith(".jar.sha256") -> binaryChecksum.toByteArray(Charsets.US_ASCII)
                    path.endsWith("-sources.jar") -> archive
                    else -> null
                }
                val status = errorStatus ?: if (body == null) 404 else 200
                exchange.sendResponseHeaders(status, if (body != null && status == 200) body.size.toLong() else -1)
                exchange.responseBody.use { if (body != null && status == 200) it.write(body) }
                exchange.close()
            }
            start()
        }
        override fun getRepositories(): List<RemoteRepository> = listOf(
            RemoteRepository.Builder("isolated", "default", "http://localhost:${server.address.port}/").build(),
        )
        override fun close() = server.stop(0)
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
}
