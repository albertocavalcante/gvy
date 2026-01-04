package com.github.albertocavalcante.groovylsp.buildtool.maven

import org.apache.maven.model.Model
import org.apache.maven.model.Repository
import org.apache.maven.model.io.xpp3.MavenXpp3Writer
import org.eclipse.aether.repository.RemoteRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths

class RepositoryProviderTest {

    @Test
    fun `maven repository provider falls back to defaults when pom is missing`(@TempDir tempDir: File) {
        val pomPath = File(tempDir, "pom.xml").toPath()

        val repositories = MavenRepositoryProvider(pomPath).getRepositories()

        assertEquals(listOf("central", "jenkins"), repositories.map { it.id })
    }

    @Test
    fun `maven repository provider falls back to defaults when pom is invalid`(@TempDir tempDir: File) {
        val pomFile = File(tempDir, "pom.xml").apply { writeText("<not-xml") }

        val repositories = MavenRepositoryProvider(pomFile.toPath()).getRepositories()

        assertEquals(listOf("central", "jenkins"), repositories.map { it.id })
    }

    @Test
    fun `maven repository provider parses basic pom and returns defaults`() {
        val pomPath = resourcePath("/poms/repo-provider/basic.xml")

        val repositories = MavenRepositoryProvider(pomPath).getRepositories()

        assertEquals("central", repositories.first().id)
        assertTrue(repositories.any { it.id == "jenkins" })
    }

    @Test
    fun `maven repository provider parses pom repositories and pluginRepositories`() {
        val pomPath = resourcePath("/poms/repo-provider/with-repos.xml")

        val repositories = MavenRepositoryProvider(pomPath).getRepositories()

        assertEquals("central", repositories.first().id)
        assertTrue(repositories.any { it.id == "corp" })
        assertTrue(repositories.any { it.id == "plugins" })
        assertEquals(1, repositories.count { it.id == "corp" })
        assertTrue(repositories.any { it.id == "jenkins" })
    }

    @Test
    fun `maven repository provider parses pom built via Model apply`() {
        val pomFile = createPom(
            Model().apply {
                modelVersion = "4.0.0"
                groupId = "com.example"
                artifactId = "demo"
                version = "1.0.0"

                repositories =
                    listOf(
                        Repository().apply {
                            id = "corp"
                            url = "https://repo.example.com/maven"
                        },
                    )
                pluginRepositories =
                    listOf(
                        Repository().apply {
                            id = "plugins"
                            url = "https://repo.example.com/plugins"
                        },
                    )
            },
        )

        val repositories = MavenRepositoryProvider(pomFile.toPath()).getRepositories()

        assertEquals("central", repositories.first().id)
        assertTrue(repositories.any { it.id == "corp" })
        assertTrue(repositories.any { it.id == "plugins" })
        assertTrue(repositories.any { it.id == "jenkins" })
    }

    @Test
    fun `maven repository provider does not inject duplicate jenkins when present`() {
        val pomPath = resourcePath("/poms/repo-provider/with-jenkins.xml")

        val repositories = MavenRepositoryProvider(pomPath).getRepositories()

        assertEquals("central", repositories.first().id)
        assertEquals(1, repositories.count { it.id == "jenkins" })
    }

    @Test
    fun `composite repository provider deduplicates by id preferring earlier providers`() {
        val a = repo("a", "https://example.com/a")
        val b1 = repo("b", "https://example.com/b1")
        val b2 = repo("b", "https://example.com/b2")
        val c = repo("c", "https://example.com/c")

        val provider1 = object : RepositoryProvider {
            override fun getRepositories(): List<RemoteRepository> = listOf(a, b1)
        }
        val provider2 = object : RepositoryProvider {
            override fun getRepositories(): List<RemoteRepository> = listOf(b2, c)
        }

        val repositories = CompositeRepositoryProvider(provider1, provider2).getRepositories()

        assertEquals(listOf("a", "b", "c"), repositories.map { it.id })
        assertEquals("https://example.com/b1", repositories.first { it.id == "b" }.url)
    }

    private fun repo(id: String, url: String): RemoteRepository = RemoteRepository.Builder(id, "default", url).build()

    @TempDir
    lateinit var tempDir: File

    private fun createPom(model: Model): File {
        val pomFile = File(tempDir, "pom.xml")
        pomFile.writer().use { MavenXpp3Writer().write(it, model) }
        return pomFile
    }

    private fun resourcePath(path: String): Path =
        urlToPath(requireNotNull(javaClass.getResource(path)) { "Missing test resource: $path" })

    private fun urlToPath(url: URL): Path = Paths.get(url.toURI())
}
