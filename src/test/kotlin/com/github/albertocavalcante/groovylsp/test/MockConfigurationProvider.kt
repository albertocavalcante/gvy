package com.github.albertocavalcante.groovylsp.test

import com.github.albertocavalcante.groovylsp.codenarc.ConfigurationProvider
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import java.nio.file.Path

/**
 * Simple mock ConfigurationProvider for testing.
 */
class MockConfigurationProvider(
    private val serverConfiguration: ServerConfiguration = ServerConfiguration(),
    private val workspaceRoot: Path? = null,
) : ConfigurationProvider {
    override fun getServerConfiguration(): ServerConfiguration = serverConfiguration
    override fun getWorkspaceRoot(): Path? = workspaceRoot
}
