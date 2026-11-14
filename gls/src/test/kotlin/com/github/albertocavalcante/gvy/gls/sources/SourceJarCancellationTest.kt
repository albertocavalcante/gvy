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
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Cancellation and failures from verified source resolution retain their distinct outcomes (#1311). */
class SourceJarCancellationTest {
    @TempDir
    lateinit var tempDirectory: Path

    private val symbol = ResolvedExternalSymbol(
        "org.example.Lib",
        SourceOrigin.Archive(
            binaryJar = Path.of("unused-test-binary.jar"),
            coordinates = ResolvedArtifactCoordinates("org.example", "library", "1.0"),
            binarySha256 = "0".repeat(64),
        ),
    )

    private fun service(resolve: suspend () -> VerifiedSourceArtifactResult) = SourceNavigationService(
        sourceResolver = TestResolver(resolve),
        sourceCacheRoot = tempDirectory.resolve("sources"),
    )

    @Test
    fun `a cancelled verified source resolution propagates`() {
        val cancelled = CancellationException("request cancelled")

        service { throw cancelled }.use { navigation ->
            val failure = assertFailsWith<CancellationException> {
                runBlocking { navigation.locate(symbol, context()) }
            }
            assertEquals(cancelled.message, failure.message)
        }
    }

    @Test
    fun `an ordinary verified source failure becomes a lookup failure`() {
        service { throw IOException("offline") }.use { navigation ->
            assertEquals(
                SourceLookupResult.Failed(SourceFailure.IO_FAILURE),
                runBlocking { navigation.locate(symbol, context()) },
            )
        }
    }

    private fun context() = SourceLookupContext(
        SourceContextIdentity("request"),
        SourceLookupPolicy.ALLOW_VERIFIED_DOWNLOADS,
    ) { true }

    private class TestResolver(
        private val resolve: suspend () -> VerifiedSourceArtifactResult,
    ) : SourceArtifactResolver, VerifiedSourceArtifactResolver {
        override val cacheDir: Path = Path.of("unused-test-source-cache")

        override suspend fun resolveVerifiedSourceArtifact(
            request: VerifiedSourceArtifactRequest,
        ): VerifiedSourceArtifactResult = resolve()

        override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? =
            error("Legacy source resolution must not be used")

        override fun isSourcesCached(groupId: String, artifactId: String, version: String): Boolean = false
    }
}
