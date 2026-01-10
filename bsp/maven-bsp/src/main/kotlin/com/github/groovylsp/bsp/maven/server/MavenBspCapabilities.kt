package com.github.groovylsp.bsp.maven.server

import ch.epfl.scala.bsp4j.BuildServerCapabilities
import ch.epfl.scala.bsp4j.CompileProvider
import ch.epfl.scala.bsp4j.RunProvider
import ch.epfl.scala.bsp4j.TestProvider

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
        compileProvider = CompileProvider(SUPPORTED_LANGUAGES)
        testProvider = TestProvider(SUPPORTED_LANGUAGES)
        runProvider = RunProvider(SUPPORTED_LANGUAGES)
        dependencyModulesProvider = true
        dependencySourcesProvider = true
        resourcesProvider = true
        outputPathsProvider = true
        buildTargetChangedProvider = false
        jvmRunEnvironmentProvider = true
        jvmTestEnvironmentProvider = true
        canReload = true
    }
}
