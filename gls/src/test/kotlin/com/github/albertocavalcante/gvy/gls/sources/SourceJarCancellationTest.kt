// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.ResolvedArtifactCoordinates
import com.github.albertocavalcante.gvy.build.SourceArtifactResolver
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactRequest
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactResolver
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Cancellation and failures from verified source resolution retain their distinct outcomes (#1311). */
class SourceJarCancellationTest {
    @TempDir
    lateinit var tempDirectory: Path

    private val coordinates = ResolvedArtifactCoordinates("org.example", "library", "1.0")

    private fun symbol(): ResolvedExternalSymbol {
        val binary = tempDirectory.resolve("library.jar")
        JarOutputStream(Files.newOutputStream(binary)).use { }
        return ResolvedExternalSymbol(
            "org.example.Lib",
            SourceOrigin.Archive(
                binaryJar = binary,
                coordinates = coordinates,
                binarySha256 = ExternalSourceRegistry.sha256(Files.readAllBytes(binary)),
            ),
        )
    }

    private fun service(resolve: suspend (VerifiedSourceArtifactRequest) -> VerifiedSourceArtifactResult) =
        SourceNavigationService(
            sourceResolver = TestResolver(resolve),
            sourceCacheRoot = tempDirectory.resolve("sources"),
        )

    private fun expectedRequest(symbol: ResolvedExternalSymbol) = VerifiedSourceArtifactRequest(
        coordinates = coordinates,
        selectedBinarySha256 = (symbol.origin as SourceOrigin.Archive).binarySha256!!,
        allowDownload = true,
        contextId = "request",
    )

    @Test
    fun `a cancelled verified source resolution propagates`() {
        val cancelled = CancellationException("request cancelled")
        val symbol = symbol()
        val requests = mutableListOf<VerifiedSourceArtifactRequest>()

        service { request ->
            requests.add(request)
            throw cancelled
        }.use { navigation ->
            val failure = assertFailsWith<CancellationException> {
                runBlocking { navigation.locate(symbol, context()) }
            }
            assertEquals(cancelled.message, failure.message)
        }
        assertEquals(listOf(expectedRequest(symbol)), requests)
    }

    @Test
    fun `an ordinary verified source failure becomes a lookup failure`() {
        val symbol = symbol()
        val requests = mutableListOf<VerifiedSourceArtifactRequest>()
        service { request ->
            requests.add(request)
            throw IOException("offline")
        }.use { navigation ->
            assertEquals(
                SourceLookupResult.Failed(SourceFailure.IO_FAILURE),
                runBlocking { navigation.locate(symbol, context()) },
            )
        }
        assertEquals(listOf(expectedRequest(symbol)), requests)
    }

    private fun context() = SourceLookupContext(
        SourceContextIdentity("request"),
        SourceLookupPolicy.ALLOW_VERIFIED_DOWNLOADS,
    ) { true }

    private class TestResolver(
        private val resolve: suspend (VerifiedSourceArtifactRequest) -> VerifiedSourceArtifactResult,
    ) : SourceArtifactResolver, VerifiedSourceArtifactResolver {
        override val cacheDir: Path = Path.of("unused-test-source-cache")

        override suspend fun resolveVerifiedSourceArtifact(
            request: VerifiedSourceArtifactRequest,
        ): VerifiedSourceArtifactResult = resolve(request)

        override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? =
            error("Legacy source resolution must not be used")

        override fun isSourcesCached(groupId: String, artifactId: String, version: String): Boolean = false
    }
}
