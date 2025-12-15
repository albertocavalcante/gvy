package com.github.albertocavalcante.groovylsp.gradle

import org.gradle.tooling.ProjectConnection
import java.io.File
import java.nio.file.Path

/**
 * Abstraction over Gradle Tooling API connections to enable deterministic unit tests.
 */
fun interface GradleConnectionFactory {
    fun getConnection(projectDir: Path, gradleUserHomeDir: File?): ProjectConnection
}
