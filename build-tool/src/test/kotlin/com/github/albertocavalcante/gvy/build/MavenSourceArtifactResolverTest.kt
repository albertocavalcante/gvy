// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import com.github.albertocavalcante.gvy.build.maven.RepositoryProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.eclipse.aether.repository.RemoteRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MavenSourceArtifactResolverTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `verified release cache remains usable when download reservation no longer fits`() = runBlocking {
        val archive = "source bytes".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            val body = when {
                exchange.requestURI.path.endsWith("-sources.jar.sha256") -> digest.toByteArray()
                exchange.requestURI.path.endsWith(".jar.sha256") -> "a".repeat(64).toByteArray()
                else -> archive
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val repository = RemoteRepository.Builder(
                "test",
                "default",
                "http://127.0.0.1:${server.address.port}/",
            ).build()
            val provider = object : RepositoryProvider {
                override fun getRepositories() = listOf(repository)
            }
            val limit = 70L * 1_048_576
            val resolver = MavenSourceArtifactResolver(directory, provider, limit)
            val request = VerifiedSourceArtifactRequest(
                ResolvedArtifactCoordinates("sample", "library", "1.0"),
                "a".repeat(64),
                true,
                "test",
            )
            assertIs<VerifiedSourceArtifactResult.Available>(resolver.resolveVerifiedSourceArtifact(request))
            val count = requests.get()
            java.io.RandomAccessFile(defaultVerifiedSourceCacheRoot(directory).resolve("filler").toFile(), "rw").use {
                it.setLength(limit - 1_048_576)
            }
            assertIs<VerifiedSourceArtifactResult.Available>(resolver.resolveVerifiedSourceArtifact(request))
            assertEquals(count, requests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `service unavailable is not retried by HTTP transport`() = runBlocking {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            val repository = RemoteRepository.Builder(
                "test",
                "default",
                "http://127.0.0.1:${server.address.port}/",
            ).build()
            val provider = object : RepositoryProvider {
                override fun getRepositories() = listOf(repository)
            }
            val request = VerifiedSourceArtifactRequest(
                ResolvedArtifactCoordinates("sample", "library", "1.0"),
                "a".repeat(64),
                true,
                "test",
            )
            assertEquals(
                VerifiedSourceArtifactResult.Failed,
                MavenSourceArtifactResolver(directory, provider).resolveVerifiedSourceArtifact(request),
            )
            assertEquals(1, requests.get())
        } finally {
            server.stop(0)
        }
    }
}
