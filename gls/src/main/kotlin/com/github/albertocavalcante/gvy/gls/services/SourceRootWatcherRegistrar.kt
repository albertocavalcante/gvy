package com.github.albertocavalcante.gvy.gls.services

import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.services.LanguageClient
import java.nio.file.Path

/** Folder watchers for the source roots (#1424). RED stub. */
internal class SourceRootWatcherRegistrar {
    @Suppress("UnusedParameter")
    fun attach(client: LanguageClient?, capabilities: ClientCapabilities?) = Unit

    @Suppress("UnusedParameter")
    fun sync(sourceRoots: List<Path>) = Unit
}
