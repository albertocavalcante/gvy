package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.diagnostics.codenarc.CodeNarcDiagnosticProvider
import com.github.albertocavalcante.groovylsp.codenarc.WorkspaceConfiguration
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Diagnostic
import java.net.URI
import java.nio.file.Path

class DiagnosticsService(private val workspaceRoot: Path?, private val serverConfig: ServerConfiguration) {
    private val workspaceContext = WorkspaceConfiguration(workspaceRoot, serverConfig)
    private val codeNarcProvider = CodeNarcDiagnosticProvider(workspaceContext)

    suspend fun getDiagnostics(uri: URI, content: String): List<Diagnostic> = withContext(Dispatchers.IO) {
        codeNarcProvider.analyze(content, uri)
    }
}
