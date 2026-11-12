// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleWrapperDistributionsTest {
    @TempDir
    lateinit var temp: Path

    private val host by lazy { temp.resolve("host") }
    private val isolated by lazy { temp.resolve("isolated") }

    @Test
    fun `links only the requested distributions the host has installed`() {
        val installed = Files.createDirectories(host.resolve("wrapper/dists/gradle-new-bin/hash"))
        Files.createDirectories(host.resolve("wrapper/dists/gradle-old-bin/hash"))

        val shared = GradleWrapperDistributions.share(host, isolated, setOf("gradle-new-bin", "gradle-missing-bin"))

        assertEquals(listOf("gradle-new-bin"), shared)
        assertTrue(isolated.resolve("wrapper/dists/gradle-new-bin/hash").exists())
        assertTrue(Files.isSameFile(installed, isolated.resolve("wrapper/dists/gradle-new-bin/hash")))
        assertFalse(isolated.resolve("wrapper/dists/gradle-old-bin").exists(), "other host versions stay unreachable")
        assertFalse(host.resolve("wrapper/dists/gradle-missing-bin").exists(), "the host home is never written")
        assertEquals(shared, GradleWrapperDistributions.share(host, isolated, setOf("gradle-new-bin")), "idempotent")
    }

    @Test
    fun `existing isolated distributions are left alone`() {
        Files.createDirectories(host.resolve("wrapper/dists/gradle-x-bin"))
        val own = Files.createDirectories(isolated.resolve("wrapper/dists/gradle-x-bin/own"))

        assertEquals(emptyList(), GradleWrapperDistributions.share(host, isolated, setOf("gradle-x-bin")))
        assertTrue(own.exists())
    }

    @Test
    fun `a dangling link is replaced`() {
        Files.createDirectories(host.resolve("wrapper/dists/gradle-x-bin"))
        val link = Files.createDirectories(isolated.resolve("wrapper/dists")).resolve("gradle-x-bin")
        Files.createSymbolicLink(link, temp.resolve("gone"))

        assertEquals(listOf("gradle-x-bin"), GradleWrapperDistributions.share(host, isolated, setOf("gradle-x-bin")))
        assertTrue(Files.isSameFile(link, host.resolve("wrapper/dists/gradle-x-bin")))
    }

    @Test
    fun `a home shared with itself is not linked`() {
        val dist = Files.createDirectories(host.resolve("wrapper/dists/gradle-x-bin"))

        assertEquals(listOf("gradle-x-bin"), GradleWrapperDistributions.share(host, host, setOf("gradle-x-bin")))
        assertFalse(Files.isSymbolicLink(dist))
    }

    @Test
    fun `fixture distributions are named after their wrapper URLs`() {
        val wrapper = Files.createDirectories(temp.resolve("workspaces/a/gradle/wrapper"))
            .resolve("gradle-wrapper.properties")
        wrapper.writeText("distributionUrl=https\\://services.gradle.org/distributions/gradle-1.2-bin.zip\n")

        assertEquals(
            setOf("gradle-1.2-bin"),
            GradleWrapperDistributions.fixtureDistributions(temp.resolve("workspaces")),
        )
    }

    @Test
    fun `an unreadable fixture wrapper shares nothing instead of throwing`() {
        val wrapper = Files.createDirectories(temp.resolve("workspaces/a/gradle/wrapper"))
            .resolve("gradle-wrapper.properties")
        wrapper.writeText("distributionUrl=https\\://services.gradle.org/distributions/gradle-1.2-bin.zip\n")
        assumeTrue(wrapper.toFile().setReadable(false) && !Files.isReadable(wrapper), "cannot revoke read access")

        assertEquals(emptySet(), GradleWrapperDistributions.fixtureDistributions(temp.resolve("workspaces")))
    }

    @Test
    fun `a failing share step keeps the isolated home`() {
        val home = temp.resolve("isolated-home")

        val prepared = GradleWrapperDistributions.prepareIsolatedHome(home) { throw IllegalStateException("boom") }

        assertEquals(home, prepared, "the server must not fall back to the host Gradle home")
        assertTrue(Files.isDirectory(home))
    }

    /**
     * Sharing only avoids a download when the fixtures pin the distribution the repository build
     * already installs (and CI restores from its Gradle cache). It is also only safe while every
     * fixture pins that single version: a newer-version daemon could otherwise clean an older linked
     * host distribution. Bump fixtures with the wrapper.
     */
    @Test
    fun `fixtures pin the repository Gradle distribution`() {
        val repoWrapper = System.getProperty("groovy.lsp.e2e.repoWrapperProperties")
        assumeTrue(!repoWrapper.isNullOrBlank(), "repository wrapper path is only passed by the Gradle build")
        val expected = distributionUrl(Path.of(repoWrapper))

        val workspaces = Path.of(resolveScenarioDir()).resolveSibling("workspaces")
        val fixtureWrappers = Files.walk(workspaces).use { paths ->
            paths.filter { it.isRegularFile() && it.endsWith("gradle/wrapper/gradle-wrapper.properties") }.toList()
        }

        assertTrue(fixtureWrappers.isNotEmpty(), "no fixture Gradle wrappers under $workspaces")
        fixtureWrappers.forEach { wrapper ->
            assertEquals(expected, distributionUrl(wrapper), "$wrapper must match ${Path.of(repoWrapper)}")
        }
    }

    private fun distributionUrl(properties: Path): String =
        Properties().apply { properties.inputStream().use(::load) }.getProperty("distributionUrl")
}
