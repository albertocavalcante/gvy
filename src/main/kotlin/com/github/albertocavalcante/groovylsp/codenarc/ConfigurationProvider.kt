package com.github.albertocavalcante.groovylsp.codenarc

import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import java.nio.file.Path

/**
 * Interface for providing configuration and workspace information to services.
 * This allows services to get current configuration without tight coupling.
 */
interface ConfigurationProvider {
    /**
     * Gets the current server configuration.
     *
     * @return The current server configuration
     */
    fun getServerConfiguration(): ServerConfiguration

    /**
     * Gets the current workspace root path, if available.
     *
     * @return The workspace root path, or null if no workspace is active
     */
    fun getWorkspaceRoot(): Path?
}

/**
 * Configuration context that combines workspace information with server configuration.
 * This is used by services that need both pieces of information.
 */
data class WorkspaceConfiguration(
    val workspaceRoot: Path?,
    val serverConfig: ServerConfiguration,
    val projectType: ProjectType = ProjectType.PlainGroovy,
) {
    /**
     * Returns true if there is an active workspace.
     */
    fun hasWorkspace(): Boolean = workspaceRoot != null

    /**
     * Gets the workspace root, throwing an exception if not available.
     */
    fun requireWorkspace(): Path = workspaceRoot
        ?: error("Workspace root is required but not available")
}
