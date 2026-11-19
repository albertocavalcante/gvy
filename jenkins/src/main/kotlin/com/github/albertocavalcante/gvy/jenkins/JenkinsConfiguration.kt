// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("TooGenericExceptionCaught") // Configuration parsing uses catch-all for resilience

package com.github.albertocavalcante.gvy.jenkins

import com.github.albertocavalcante.gvy.jenkins.SharedLibraryConfigurationDiagnostic.Code
import com.github.albertocavalcante.gvy.jenkins.plugins.PluginConfiguration
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Configuration for Jenkins pipeline support.
 */
data class JenkinsConfiguration(
    val filePatterns: List<String> = DEFAULT_FILE_PATTERNS,
    val sharedLibraries: List<SharedLibrary> = emptyList(),
    val gdslPaths: List<String> = emptyList(),
    val gdslExecutionEnabled: Boolean = false,
    val pluginConfig: PluginConfiguration = PluginConfiguration(),
    val pluginsFile: String? = null,
    val jenkinsMetadataFile: String? = null,
) {
    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Files treated as Jenkins pipelines when `jenkins.filePatterns` is not set.
         *
         * Must equal the `groovy.jenkins.filePatterns` default in `editors/code/package.json` (enforced by the
         * extension manifest test), which in turn covers every `jenkinsfile` language pattern, so files the editor
         * opens as Jenkinsfiles get Jenkins features. `Jenkinsfile*` is deliberately avoided: it would claim classes
         * such as `JenkinsfileTest.groovy`.
         */
        val DEFAULT_FILE_PATTERNS: List<String> = listOf(
            "Jenkinsfile",
            "Jenkinsfile.*",
            "Jenkinsfile-*",
            "Jenkinsfile_*",
            "*.jenkinsfile",
            "*.Jenkinsfile",
            "vars/*.groovy",
        )

        /**
         * Parses Jenkins configuration from a map.
         */
        @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any>): JenkinsConfiguration = try {
            val filePatterns = parseFilePatterns(map)
            val sharedLibraries = parseSharedLibraryConfiguration(map["jenkins.sharedLibraries"])
            sharedLibraries.diagnostics.forEach { diagnostic ->
                logger.warn {
                    "${diagnostic.settingPath} [${diagnostic.code}]: ${diagnostic.message}"
                }
            }

            JenkinsConfiguration(
                filePatterns = filePatterns,
                sharedLibraries = sharedLibraries.libraries,
                gdslPaths = parseGdslPaths(map),
                gdslExecutionEnabled = parseGdslExecutionEnabled(map),
                pluginConfig = PluginConfiguration.fromMap(map),
                pluginsFile = map["jenkins.plugins.file"] as? String,
                jenkinsMetadataFile = map["jenkins.metadata.file"] as? String,
            )
        } catch (e: Exception) {
            logger.warn(e) { "Error parsing Jenkins configuration, using defaults" }
            JenkinsConfiguration()
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseFilePatterns(map: Map<String, Any>): List<String> {
            val patterns = map["jenkins.filePatterns"] as? List<*>
            return patterns?.mapNotNull { it as? String } ?: DEFAULT_FILE_PATTERNS
        }

        internal fun parseSharedLibraryConfiguration(value: Any?): SharedLibraryConfigurationParseResult {
            val entries = value as? List<*>
                ?: return SharedLibraryConfigurationParseResult(emptyList(), emptyList())
            val libraries = mutableListOf<SharedLibrary>()
            val diagnostics = mutableListOf<SharedLibraryConfigurationDiagnostic>()

            entries.forEachIndexed { entryIndex, entry ->
                try {
                    val library = entry as? Map<*, *> ?: return@forEachIndexed
                    // Presence, including null or malformed values, prevents legacy JAR fallback.
                    if (library.containsKey("source")) {
                        val code = if (library.containsKey("jar") || library.containsKey("sourcesJar")) {
                            Code.CONFLICTING_FORMATS
                        } else {
                            Code.TYPED_SOURCE_DISABLED
                        }
                        diagnostics.add(SharedLibraryConfigurationDiagnostic(entryIndex, code))
                    } else {
                        parseLegacySharedLibrary(library)?.let(libraries::add)
                    }
                } catch (_: Exception) {
                    // Exception messages and entries can contain private configuration values.
                    diagnostics.add(SharedLibraryConfigurationDiagnostic(entryIndex, Code.UNREADABLE_ENTRY))
                }
            }
            return SharedLibraryConfigurationParseResult(libraries, diagnostics)
        }

        private fun parseLegacySharedLibrary(library: Map<*, *>): SharedLibrary? {
            val name = library["name"] as? String ?: return null
            val jar = library["jar"] as? String ?: return null
            return SharedLibrary(name, jar, library["sourcesJar"] as? String)
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseGdslPaths(map: Map<String, Any>): List<String> {
            val paths = map["jenkins.gdslPaths"] as? List<*>
            return paths?.mapNotNull { it as? String } ?: emptyList()
        }

        private fun parseGdslExecutionEnabled(map: Map<String, Any>): Boolean =
            map["jenkins.gdslExecution.enabled"] as? Boolean ?: false
    }
}

/**
 * Represents a Jenkins shared library configuration.
 */
data class SharedLibrary(val name: String, val jar: String, val sourcesJar: String? = null)
