// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.ResolvedArtifactCoordinates
import com.github.albertocavalcante.gvy.build.SourceArtifactResolver
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactRequest
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactResolver
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Cancellation remains cancellation, and detaching one request does not cancel another borrower's source work. */
class SourceJarCancellationTest {
    @TempDir lateinit var root: Path

    @Test
    fun `a cancelled source acquisition propagates`(): Unit = runBlocking {
        navigation { throw CancellationException("request cancelled") }.use { service ->
            assertFailsWith<CancellationException> { service.locate(symbol(), context()) }
        }
    }

    @Test
    fun `an ordinary IO failure is an explicit failure rather than binary fallback`() = runBlocking {
        navigation { throw IOException("offline") }.use { service ->
            assertEquals(SourceLookupResult.Failed(SourceFailure.IO_FAILURE), service.locate(symbol(), context()))
        }
    }

    @Test
    fun `last cancelled borrower releases pending acquisition`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        navigation {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }.use { service ->
            val request = async { service.locate(symbol(), context()) }
            started.await()
            request.cancelAndJoin()
            stopped.await()
        }
    }

    @Test
    fun `one cancelled borrower preserves another borrower`() = runBlocking {
        SourceRequestCoalescer<String, Int>().use { coalescer ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val joined = CompletableDeferred<Unit>()
            val first = async {
                coalescer.acquire("same") {
                    started.complete(Unit)
                    release.await()
                    42
                }
            }
            started.await()
            val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                joined.complete(Unit)
                coalescer.acquire("same") { error("A second producer must not start") }
            }
            joined.await()
            first.cancelAndJoin()
            release.complete(Unit)
            assertEquals(42, second.await())
        }
    }

    private fun symbol(): ResolvedExternalSymbol {
        val binary = root.resolve("library.jar")
        if (Files.notExists(binary)) Files.writeString(binary, "binary")
        return ResolvedExternalSymbol(
            "sample.Anno",
            SourceOrigin.Archive(binary, ResolvedArtifactCoordinates("sample", "library", "1")),
        )
    }

    private fun context() = SourceLookupContext(
        SourceContextIdentity("context"),
        SourceLookupPolicy.ALLOW_VERIFIED_DOWNLOADS,
    ) { true }

    private fun navigation(resolve: suspend () -> VerifiedSourceArtifactResult): SourceNavigationService {
        val resolver = object : SourceArtifactResolver, VerifiedSourceArtifactResolver {
            override val cacheDir: Path = root.resolve("resolver")
            override suspend fun resolveVerifiedSourceArtifact(request: VerifiedSourceArtifactRequest) = resolve()
            override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? =
                error("Unexpected legacy resolver")
            override fun isSourcesCached(groupId: String, artifactId: String, version: String) = false
        }
        return SourceNavigationService(sourceResolver = resolver, sourceCacheRoot = root.resolve("sources"))
    }
}
