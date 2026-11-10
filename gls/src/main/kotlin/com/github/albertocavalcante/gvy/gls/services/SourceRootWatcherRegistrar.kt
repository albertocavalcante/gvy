package com.github.albertocavalcante.gvy.gls.services

import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.RelativePattern
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WatchKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

private const val WATCHED_FILES_METHOD = "workspace/didChangeWatchedFiles"
private const val REGISTRATION_ID_PREFIX = "groovy-lsp-source-root-watchers-"

/**
 * Asks the client for every create and delete under each source root (#1424). The file watchers' globs name
 * files by extension, so a folder that is copied, moved or deleted, which is reported as one event for the
 * folder, never matched them.
 *
 * Needs LSP 3.17 relative patterns: a bare `**` glob would watch the whole workspace, build output included.
 * Without them no folder watcher is registered, and a folder change is picked up at the next model rebuild.
 * When the roots change, the old registration is removed before a new one, under a fresh id, is added.
 */
internal class SourceRootWatcherRegistrar {
    private val logger = KotlinLogging.logger {}
    private val lock = Any()
    private var client: LanguageClient? = null
    private var roots: Set<Path> = emptySet()
    private var registered: Set<Path> = emptySet()
    private var registrationId: String? = null
    private var generation = 0

    /** Attaches the client once its capabilities are known; roots synced earlier are registered now. */
    fun attach(client: LanguageClient?, capabilities: ClientCapabilities?) = synchronized(lock) {
        val watched = capabilities?.workspace?.didChangeWatchedFiles
        if (client == null || watched?.dynamicRegistration != true || watched.relativePatternSupport != true) {
            logger.info { "Client lacks dynamic relative-pattern watchers - folder events under source roots are off" }
            return@synchronized
        }
        this.client = client
        reconcile(client)
    }

    /** Watches exactly [sourceRoots]; the same roots in any order change nothing. */
    fun sync(sourceRoots: List<Path>) = synchronized(lock) {
        roots = sourceRoots.mapTo(LinkedHashSet()) { it.toAbsolutePath().normalize() }
        client?.let(::reconcile)
    }

    private fun reconcile(client: LanguageClient) {
        if (roots == registered) return
        registrationId?.let { id ->
            request("unregister $id") {
                client.unregisterCapability(UnregistrationParams(listOf(Unregistration(id, WATCHED_FILES_METHOD))))
            }
        }
        registrationId = null
        registered = roots
        if (roots.isEmpty()) return
        val id = REGISTRATION_ID_PREFIX + ++generation
        val kinds = WatchKind.Create + WatchKind.Delete
        val watchers = roots.map { root ->
            FileSystemWatcher(Either.forRight(RelativePattern(Either.forRight(root.toUri().toString()), "**/*")), kinds)
        }
        val registration = Registration(id, WATCHED_FILES_METHOD, DidChangeWatchedFilesRegistrationOptions(watchers))
        registrationId = id
        request("register $id") { client.registerCapability(RegistrationParams(listOf(registration))) }
        logger.info { "Requested folder watchers under ${watchers.size} source roots" }
    }

    /** Sends a client request without letting its failure, synchronous or not, reach the model update caller. */
    private fun request(what: String, send: () -> CompletableFuture<Void>) {
        runCatching(send)
            .onFailure { logger.warn { "Failed to $what: ${it.message}" } }
            .getOrNull()
            ?.exceptionally { error -> null.also { logger.warn { "Failed to $what: ${error.message}" } } }
    }
}
