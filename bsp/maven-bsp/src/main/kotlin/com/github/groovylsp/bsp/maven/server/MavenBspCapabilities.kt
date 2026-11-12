// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.groovylsp.bsp.maven.server

import ch.epfl.scala.bsp4j.BuildServerCapabilities

/**
 * Defines the capabilities of the Maven BSP server.
 */
object MavenBspCapabilities {

    private val SUPPORTED_LANGUAGES = listOf("java", "groovy", "kotlin")

    /**
     * Returns the list of supported languages.
     */
    fun supportedLanguages(): List<String> = SUPPORTED_LANGUAGES

    /**
     * Returns the capabilities advertised by this server.
     */
    fun serverCapabilities(): BuildServerCapabilities = BuildServerCapabilities().apply {
        dependencyModulesProvider = true
        dependencySourcesProvider = true
        resourcesProvider = true
        outputPathsProvider = true
        buildTargetChangedProvider = false
        jvmRunEnvironmentProvider = false
        jvmTestEnvironmentProvider = false
        canReload = true
    }
}
