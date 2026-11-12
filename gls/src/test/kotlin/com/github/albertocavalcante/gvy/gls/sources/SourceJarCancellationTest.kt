// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.SourceArtifactResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** A cancelled source-jar lookup ends the navigation cancelled, not with a fallback result (#1311). */
class SourceJarCancellationTest {
    private val jar = URI.create(
        "jar:file:///home/u/.m2/repository/org/example/lib/1.0/lib-1.0.jar!/org/example/Lib.class",
    )
    private val resolver = mockk<SourceArtifactResolver>()
    private val extractor = mockk<SourceJarExtractor> { every { findSourceForClass(any()) } returns null }
    private val service = SourceNavigationService(sourceResolver = resolver, sourceExtractor = extractor)

    @Test
    fun `a cancelled source-jar resolution propagates`() {
        coEvery { resolver.resolveSourceJar(any(), any(), any()) } throws CancellationException("request cancelled")

        assertFailsWith<CancellationException> { runBlocking { service.navigateToSource(jar, "org.example.Lib") } }
    }

    @Test
    fun `an ordinary resolution failure still falls back to the binary`() {
        coEvery { resolver.resolveSourceJar(any(), any(), any()) } throws IOException("offline")

        val result = runBlocking { service.navigateToSource(jar, "org.example.Lib") }

        assertIs<SourceNavigator.SourceResult.BinaryOnly>(result)
    }
}
