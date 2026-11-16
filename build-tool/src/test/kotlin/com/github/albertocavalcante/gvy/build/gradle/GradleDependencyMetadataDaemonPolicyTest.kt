// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.gradle.tooling.ModelBuilder
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.idea.IdeaProject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * The dependency-tree request must run under the same daemon policy as a resolution, otherwise Gradle starts a
 * second, differently configured daemon that outlives the idle sweep and ignores the configured JDK.
 */
class GradleDependencyMetadataDaemonPolicyTest {

    @Test
    fun `getDependencyMetadata leases the connection and applies daemon arguments and java home`(
        @TempDir projectDir: Path,
        @TempDir javaHome: Path,
    ) {
        projectDir.resolve("build.gradle").toFile().writeText("plugins { id 'java' }")
        val connection = mockk<ProjectConnection>()
        val modelBuilder = mockk<ModelBuilder<IdeaProject>>()
        every { modelBuilder.withCancellationToken(any()) } returns modelBuilder
        every { modelBuilder.withArguments(*anyVararg()) } returns modelBuilder
        every { modelBuilder.setJvmArguments(*anyVararg<String>()) } returns modelBuilder
        every { modelBuilder.setJavaHome(any()) } returns modelBuilder
        every { modelBuilder.get() } returns mockk<IdeaProject>(relaxed = true)
        every { connection.model(IdeaProject::class.java) } returns modelBuilder

        var acquired = 0
        var released = 0
        val factory = object : LeasingConnectionFactory {
            override fun getConnection(projectDir: Path, gradleUserHomeDir: File?): ProjectConnection =
                error("the dependency tree must lease the connection, not take a bare one")

            override fun acquire(projectDir: Path, gradleUserHomeDir: File?): ConnectionLease {
                acquired++
                return ConnectionLease(connection) { released++ }
            }
        }

        GradleBuildTool(connectionFactory = factory, javaHome = javaHome).getDependencyMetadata(projectDir)

        assertEquals(1, acquired)
        assertEquals(1, released, "the lease must be released after the fetch")
        verify { modelBuilder.setJavaHome(javaHome.toFile()) }
        verify { modelBuilder.withArguments(*anyVararg()) }
        verify { modelBuilder.setJvmArguments("-Xmx1g", "-XX:+UseG1GC") }
        verify { modelBuilder.withCancellationToken(any()) }
    }
}
