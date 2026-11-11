package com.github.albertocavalcante.gvy.build.gradle

import org.gradle.tooling.ProjectConnection
import java.io.File
import java.nio.file.Path

/**
 * Abstraction over Gradle Tooling API connections to enable deterministic unit tests.
 */
fun interface GradleConnectionFactory {
    fun getConnection(projectDir: Path, gradleUserHomeDir: File?): ProjectConnection
}

/**
 * A [GradleConnectionFactory] that can also hold a connection open while it is in use, so an idle sweep never closes
 * a connection mid-operation.
 */
interface LeasingConnectionFactory : GradleConnectionFactory {
    fun <T> withConnection(projectDir: Path, gradleUserHomeDir: File?, block: (ProjectConnection) -> T): T
}
