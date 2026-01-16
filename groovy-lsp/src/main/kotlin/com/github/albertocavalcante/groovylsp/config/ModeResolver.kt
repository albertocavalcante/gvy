package com.github.albertocavalcante.groovylsp.config

import com.github.albertocavalcante.groovylsp.project.JenkinsCapabilities
import java.net.URI

/**
 * Resolves the effective language mode for a given file.
 *
 * Mode resolution priority:
 * 1. Explicit configuration (groovyMode != AUTO)
 * 2. Auto-detection based on JenkinsCapabilities
 *
 * @property configuredMode The mode configured by the user (default: AUTO)
 * @property jenkinsCapabilities Optional Jenkins capabilities for auto-detection
 */
class ModeResolver(
    private val configuredMode: GroovyMode = GroovyMode.AUTO,
    private val jenkinsCapabilities: JenkinsCapabilities? = null,
) {
    /**
     * Resolve the effective mode for a given file URI.
     *
     * @param uri The file URI to resolve mode for
     * @return The effective GroovyMode for this file
     */
    fun resolveMode(uri: URI): GroovyMode = when (configuredMode) {
        GroovyMode.GROOVY -> GroovyMode.GROOVY
        GroovyMode.JENKINS -> GroovyMode.JENKINS
        GroovyMode.AUTO -> autoDetectMode(uri)
    }

    /**
     * Check if Jenkins mode is enabled for a given file.
     * Convenience method for conditional Jenkins feature enabling.
     *
     * @param uri The file URI to check
     * @return true if Jenkins features should be enabled for this file
     */
    fun isJenkinsModeEnabled(uri: URI): Boolean = resolveMode(uri) == GroovyMode.JENKINS

    /**
     * Check if Groovy-only mode is explicitly set.
     * When true, no Jenkins features should be provided.
     *
     * @return true if pure Groovy mode is configured
     */
    fun isGroovyOnlyMode(): Boolean = configuredMode == GroovyMode.GROOVY

    private fun autoDetectMode(uri: URI): GroovyMode = if (jenkinsCapabilities?.isJenkinsFile(uri) == true) {
        GroovyMode.JENKINS
    } else {
        GroovyMode.GROOVY
    }

    companion object {
        /**
         * Create a ModeResolver from ServerConfiguration.
         */
        fun fromConfig(config: ServerConfiguration, jenkinsCapabilities: JenkinsCapabilities?): ModeResolver =
            ModeResolver(
                configuredMode = config.groovyMode,
                jenkinsCapabilities = jenkinsCapabilities,
            )
    }
}
