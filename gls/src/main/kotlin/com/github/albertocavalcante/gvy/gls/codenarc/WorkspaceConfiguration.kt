package com.github.albertocavalcante.gvy.gls.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import java.nio.file.Path

/**
 * Configuration context that combines workspace information with server configuration.
 * Implements WorkspaceContext from the diagnostics API.
 */
data class WorkspaceConfiguration(val workspaceRoot: Path?, val serverConfig: ServerConfiguration) : WorkspaceContext {

    override val root: Path?
        get() = workspaceRoot

    override fun getConfiguration(): DiagnosticConfiguration = object : DiagnosticConfiguration {
        override val isEnabled: Boolean
            get() = serverConfig.diagnosticConfig.isProviderEnabled(
                "codenarc",
                serverConfig.codeNarcEnabled,
            )

        override val propertiesFile: String?
            get() = serverConfig.codeNarcPropertiesFile

        override val autoDetectConfig: Boolean
            get() = serverConfig.codeNarcAutoDetect
    }
}
