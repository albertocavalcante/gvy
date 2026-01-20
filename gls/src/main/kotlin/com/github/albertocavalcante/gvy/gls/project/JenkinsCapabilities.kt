package com.github.albertocavalcante.gvy.gls.project

import com.github.albertocavalcante.gvy.jenkins.GlobalVariable
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import java.net.URI
import java.nio.file.Path

/**
 * Capability interface for Jenkins-specific functionality.
 *
 * Consumers should depend on this interface rather than JenkinsProjectStrategy directly.
 * This follows the Interface Segregation Principle - consumers only see what they need.
 *
 * This interface is designed to be easily mocked in tests. Instead of mocking the full
 * ProjectStrategy or JenkinsProjectStrategy, consumers can mock just this capability.
 */
interface JenkinsCapabilities {
    /**
     * Checks if the given URI is a Jenkins pipeline file based on configured patterns.
     */
    fun isJenkinsFile(uri: URI): Boolean

    /**
     * Checks if the given URI is a GDSL file for Jenkins context.
     */
    fun isGdslFile(uri: URI): Boolean

    /**
     * Gets global variables defined in the Jenkins workspace (e.g., vars/ directory).
     */
    fun getGlobalVariables(): List<GlobalVariable>

    /**
     * Gets combined Jenkins metadata (steps, globals) including scanned plugins.
     */
    fun getAllMetadata(): MergedJenkinsMetadata?

    /**
     * Gets the classpath for a Jenkins file, including shared library dependencies.
     *
     * @return Classpath entries for the file, or null if the URI is not a Jenkins file
     */
    fun getClasspathForFile(uri: URI, content: String, projectDependencies: List<Path>): List<Path>?

    /**
     * Reloads GDSL metadata for the Jenkins workspace.
     */
    fun reloadGdsl()

    /**
     * Waits for the async initialization job to complete.
     */
    suspend fun awaitInitialization()
}
