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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleWrapperDistributionsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `isolated home reads and writes the host distributions`() {
        val host = Files.createDirectories(temp.resolve("host"))
        val isolated = Files.createDirectories(temp.resolve("isolated"))

        assertTrue(GradleWrapperDistributions.share(host, isolated))
        Files.createDirectories(isolated.resolve("wrapper/dists/gradle-x-bin"))

        assertTrue(host.resolve("wrapper/dists/gradle-x-bin").exists())
        assertTrue(GradleWrapperDistributions.share(host, isolated), "sharing again is a no-op")
    }

    @Test
    fun `existing isolated distributions are left alone`() {
        val host = Files.createDirectories(temp.resolve("host"))
        val isolated = temp.resolve("isolated")
        val own = Files.createDirectories(isolated.resolve("wrapper/dists/gradle-own-bin"))

        assertFalse(GradleWrapperDistributions.share(host, isolated))
        assertTrue(own.exists())
        assertFalse(Files.isSymbolicLink(isolated.resolve("wrapper/dists")))
    }

    @Test
    fun `a home is never linked to itself`() {
        val home = Files.createDirectories(temp.resolve("home"))

        assertFalse(GradleWrapperDistributions.share(home, home))
        assertFalse(Files.isSymbolicLink(home.resolve("wrapper/dists")))
    }

    /**
     * Sharing only avoids a download when the fixtures pin the distribution the repository build
     * already installs (and CI restores from its Gradle cache). Bump fixtures with the wrapper.
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
