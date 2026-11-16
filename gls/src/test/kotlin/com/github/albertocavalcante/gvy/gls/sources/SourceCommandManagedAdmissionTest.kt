// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceCommandManagedAdmissionTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `custom source actions decline managed ancestor aliases without compiling or acquiring sources`(): Unit =
        runBlocking {
            assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
            val managed = Files.createDirectory(root.resolve("managed"))
            Files.writeString(managed.resolve("Money.groovy"), "class Money {}")
            val alias = Files.createSymbolicLink(root.resolve("cache-link"), managed).resolve("Money.groovy").toUri()
            val documents = DocumentProvider().apply { put(alias, "class Money {}", 1) }
            val compilation = mockk<GroovyCompilationService>(relaxed = true)
            coEvery { compilation.ensureCompiled(any<java.net.URI>()) } returns null
            every { compilation.getCompiledSnapshot(any()) } returns null
            coEvery { compilation.captureSourceLookupSession(any(), any()) } returns null
            val navigator = mockk<SourceNavigator>(relaxed = true)
            val registry = ExternalSourceRegistry(root.resolve("browser"), managedRoots = listOf(managed))
            val commands = SourceCommandService(compilation, documents, navigator, registry)

            val resolved = commands.resolve(
                SourceResolveParams(alias.toString(), Position(0, 1), allowDownload = true, documentVersion = 1),
            )
            val attached = commands.attach(
                SourceAttachParams(
                    alias.toString(),
                    Position(0, 1),
                    root.resolve("sources.jar").toUri().toString(),
                    documentVersion = 1,
                ),
            )
            val descriptor = SourceDocumentDescriptor(
                sourceDigest = "a".repeat(64),
                contentDigest = "b".repeat(64),
                entryName = "sample/Type.java",
                contentPath = root.resolve("Type.java"),
                origin = SourceOrigin.Unknown(alias),
                context = SourceContextIdentity("context", alias, "fingerprint"),
                displayName = "Type",
                languageId = "java",
            )

            assertEquals(SourceResolveStatus.FAILED, resolved.status)
            assertEquals("INVALID_REQUEST", resolved.reason)
            assertEquals(SourceResolveStatus.FAILED, attached.status)
            assertNull(commands.openLookupSession(descriptor))
            coVerify(exactly = 0) { compilation.ensureCompiled(any<java.net.URI>()) }
            coVerify(exactly = 0) { compilation.ensureCompiled(any(), any()) }
            coVerify(exactly = 0) { navigator.retrySource(any(), any()) }
            coVerify(exactly = 0) { navigator.attachSource(any(), any(), any(), any()) }
        }
}
