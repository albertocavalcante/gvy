package com.github.albertocavalcante.gvy.gls.services

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WatchKind
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Folder creates, deletes and moves under a source root must be requested from the client (#1424). */
class SourceRootWatcherRegistrarTest {
    private val registered = mutableListOf<RegistrationParams>()
    private val unregistered = mutableListOf<UnregistrationParams>()
    private val client = mockk<LanguageClient> {
        every { registerCapability(any()) } answers {
            registered += firstArg<RegistrationParams>()
            CompletableFuture.completedFuture(null)
        }
        every { unregisterCapability(any()) } answers {
            unregistered += firstArg<UnregistrationParams>()
            CompletableFuture.completedFuture(null)
        }
    }

    private val rootA = Path.of("/ws/a/src").toAbsolutePath()
    private val rootB = Path.of("/ws/b/src").toAbsolutePath()

    private fun capabilities(dynamic: Boolean = true, relative: Boolean? = true) = ClientCapabilities().apply {
        workspace = WorkspaceClientCapabilities().apply {
            didChangeWatchedFiles = DidChangeWatchedFilesCapabilities(dynamic).apply {
                relativePatternSupport = relative
            }
        }
    }

    private fun watchedBases(params: RegistrationParams): List<String> = params.registrations.flatMap { registration ->
        (registration.registerOptions as DidChangeWatchedFilesRegistrationOptions).watchers.map { watcher ->
            assertEquals(WatchKind.Create + WatchKind.Delete, watcher.kind, "folders only need create and delete")
            val pattern = watcher.globPattern.right
            assertEquals("**/*", pattern.pattern)
            pattern.baseUri.right
        }
    }

    @Test
    fun `registers one relative watcher per source root`() {
        val registrar = SourceRootWatcherRegistrar()
        registrar.attach(client, capabilities())
        registrar.sync(listOf(rootA, rootB))

        assertEquals(1, registered.size)
        assertEquals(listOf(rootA.toUri().toString(), rootB.toUri().toString()), watchedBases(registered.single()))
        assertEquals("workspace/didChangeWatchedFiles", registered.single().registrations.single().method)
    }

    @Test
    fun `roots known before the client attaches are registered on attach`() {
        val registrar = SourceRootWatcherRegistrar()
        registrar.sync(listOf(rootA))
        registrar.attach(client, capabilities())

        assertEquals(listOf(rootA.toUri().toString()), watchedBases(registered.single()))
    }

    @Test
    fun `without relative pattern support no folder watcher is registered`() {
        listOf(capabilities(relative = null), capabilities(relative = false), capabilities(dynamic = false), null)
            .forEach { caps ->
                val registrar = SourceRootWatcherRegistrar()
                registrar.attach(client, caps)
                registrar.sync(listOf(rootA))
            }

        assertEquals(emptyList(), registered)
    }

    @Test
    fun `a root change unregisters the old registration before registering the new one`() {
        val registrar = SourceRootWatcherRegistrar()
        registrar.attach(client, capabilities())
        registrar.sync(listOf(rootA))
        val first = registered.single().registrations.single()

        registrar.sync(listOf(rootA, rootB))

        assertEquals(2, registered.size)
        val removed = unregistered.single().unregisterations.single()
        assertEquals(first.id, removed.id)
        assertEquals(first.method, removed.method)
        val second = registered.last().registrations.single()
        assertNotEquals(first.id, second.id, "a fresh id cannot race the old one's removal")
        verifyOrder {
            client.registerCapability(any())
            client.unregisterCapability(any())
            client.registerCapability(any())
        }
    }

    @Test
    fun `the same roots in another order do not re-register`() {
        val registrar = SourceRootWatcherRegistrar()
        registrar.attach(client, capabilities())
        registrar.sync(listOf(rootA, rootB))
        registrar.sync(listOf(rootB, rootA))

        assertEquals(1, registered.size)
        verify(exactly = 0) { client.unregisterCapability(any()) }
    }

    @Test
    fun `losing every root unregisters without registering again`() {
        val registrar = SourceRootWatcherRegistrar()
        registrar.attach(client, capabilities())
        registrar.sync(listOf(rootA))
        registrar.sync(emptyList())

        assertEquals(1, registered.size)
        assertEquals(1, unregistered.size)
    }
}
