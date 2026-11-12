// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class LanguageServerLaunchConfigurationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `an unavailable isolated Gradle home fails before inheriting the outer build cache`() {
        val blocked = Files.writeString(tempDir.resolve("not-a-directory"), "file")
        var started = false

        withGradleHome(blocked) {
            val factory = LanguageServerSessionFactory(startProcess = {
                started = true
                throw IOException("process should not start")
            })
            assertThatThrownBy { factory.start(ServerConfig(), "unavailable-isolation") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("isolated Gradle user home")
        }

        assertThat(started).isFalse()
    }

    @Test
    fun `an authored Gradle home bypasses implicit cache preparation`() {
        val implicit = tempDir.resolve("implicit-home")
        val authored = tempDir.resolve("authored-home").toString()
        val stop = IOException("stop before launching")
        var observed: String? = null

        withGradleHome(implicit) {
            val factory = LanguageServerSessionFactory(startProcess = { builder ->
                observed = builder.environment()["GRADLE_USER_HOME"]
                throw stop
            })
            assertThatThrownBy {
                factory.start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to authored)), "authored-cache")
            }.isSameAs(stop)
        }

        assertThat(observed).isEqualTo(authored)
        assertThat(implicit).doesNotExist()
    }

    @Test
    fun `Bazel uses its writable test temporary directory for the implicit Gradle home`() {
        val bazelTemp = tempDir.resolve("bazel-test-temp")

        val home = defaultIsolatedGradleHome(mapOf("TEST_TMPDIR" to bazelTemp.toString()), tempDir, PROCESS_ID)

        assertThat(home).isEqualTo(bazelTemp.resolve("e2e-gradle-home-$PROCESS_ID"))
    }

    @Test
    fun `ordinary Gradle execution retains its build directory cache location`() {
        assertThat(defaultIsolatedGradleHome(emptyMap(), tempDir, PROCESS_ID))
            .isEqualTo(tempDir.resolve("build/e2e-gradle-home-$PROCESS_ID"))
    }

    private fun <T> withGradleHome(path: Path, action: () -> T): T {
        val previous = System.getProperty(GRADLE_HOME_PROPERTY)
        System.setProperty(GRADLE_HOME_PROPERTY, path.toString())
        return try {
            action()
        } finally {
            if (previous == null) {
                System.clearProperty(GRADLE_HOME_PROPERTY)
            } else {
                System.setProperty(GRADLE_HOME_PROPERTY, previous)
            }
        }
    }

    private companion object {
        const val GRADLE_HOME_PROPERTY = "groovy.lsp.e2e.gradleUserHome"
        const val PROCESS_ID = 42L
    }
}
