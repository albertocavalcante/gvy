package com.github.groovylsp.bsp.maven.server

import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.InitializeBuildParams
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Shared test fixtures and utilities for Maven BSP server tests.
 */
object TestFixtures {

    // Common test constants
    const val DISPLAY_NAME = "Maven BSP"
    const val SERVER_VERSION = "0.1.0"
    const val BSP_VERSION = "2.1.0"
    const val APP_TARGET_ID = "maven:com.example:my-app"
    const val APP_TEST_TARGET_ID = "maven:com.example:my-app:test"

    /**
     * Creates a simple single-module Maven project in the specified directory.
     *
     * @param tempDir The directory where the pom.xml should be created
     */
    fun createSimpleMavenProject(tempDir: Path) {
        val pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-app</artifactId>
                <version>1.0.0</version>
            </project>
        """.trimIndent()
        tempDir.resolve("pom.xml").writeText(pomContent)
    }

    /**
     * Creates InitializeBuildParams with standard test values.
     *
     * @param tempDir The workspace root directory
     * @param clientName Optional client name (defaults to "Test Client")
     * @param clientVersion Optional client version (defaults to "1.0.0")
     * @return InitializeBuildParams configured for testing
     */
    fun createInitParams(
        tempDir: Path,
        clientName: String = "Test Client",
        clientVersion: String = "1.0.0",
    ): InitializeBuildParams = InitializeBuildParams(
        clientName,
        clientVersion,
        BSP_VERSION,
        tempDir.toUri().toString(),
        BuildClientCapabilities(listOf("java")),
    )
}
