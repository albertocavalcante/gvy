// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.maven

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.ArtifactResult
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResolutionException
import org.eclipse.aether.resolution.DependencyResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MavenDependencyResolverTest {

    @BeforeEach
    fun setup() {
        mockkObject(AetherSessionFactory)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `resolveArtifact should return path when resolution succeeds`() {
        val mockSystem = mockk<RepositorySystem>()
        val mockSession = mockk<RepositorySystemSession>()

        every { AetherSessionFactory.repositorySystem } returns mockSystem
        every { AetherSessionFactory.createSession(any()) } returns mockSession
        every { AetherSessionFactory.resolveLocalRepository() } returns Path.of("/tmp/m2")

        val groupId = "org.example"
        val artifactId = "test-lib"
        val version = "1.0.0"
        val expectedPath = Path.of("/tmp/m2/org/example/test-lib/1.0.0/test-lib-1.0.0.jar")

        val mockResult = mockk<DependencyResult>()
        val mockArtifactResult = mockk<ArtifactResult>()
        val mockArtifact = mockk<Artifact>()
        val mockFile = mockk<File>()

        every { mockFile.toPath() } returns expectedPath
        every { mockArtifact.groupId } returns groupId
        every { mockArtifact.artifactId } returns artifactId
        every { mockArtifact.file } returns mockFile
        every { mockArtifactResult.isResolved } returns true
        every { mockArtifactResult.artifact } returns mockArtifact
        every { mockResult.artifactResults } returns listOf(mockArtifactResult)

        every { mockSystem.resolveDependencies(any(), any()) } returns mockResult

        val resolver = MavenDependencyResolver()
        val result = resolver.resolveArtifact(groupId, artifactId, version)

        assertNotNull(result)
        assertEquals(expectedPath, result)

        verify { mockSystem.resolveDependencies(mockSession, any()) }
    }

    @Test
    fun `resolveArtifact should return null when resolution fails`() {
        val mockSystem = mockk<RepositorySystem>()
        every { AetherSessionFactory.repositorySystem } returns mockSystem
        every { AetherSessionFactory.createSession(any()) } returns mockk()
        every { AetherSessionFactory.resolveLocalRepository() } returns Path.of("/tmp/m2")

        every { mockSystem.resolveDependencies(any(), any()) } throws RuntimeException("Resolution failed")

        val resolver = MavenDependencyResolver()
        val result = resolver.resolveArtifact("org.example", "missing", "1.0.0")

        assertNull(result)
    }

    @TempDir
    lateinit var tempDir: Path

    private val pom = """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>t</groupId><artifactId>t</artifactId><version>1</version>
          <dependencyManagement><dependencies>
            <dependency><groupId>org.example</groupId><artifactId>pinned</artifactId><version>2.0</version>
              <exclusions><exclusion><groupId>x</groupId><artifactId>y</artifactId></exclusion></exclusions>
            </dependency>
          </dependencies></dependencyManagement>
          <dependencies>
            <dependency><groupId>org.example</groupId><artifactId>lib</artifactId><version>1.0</version>
              <optional>true</optional>
              <exclusions><exclusion><groupId>commons-logging</groupId><artifactId>commons-logging</artifactId></exclusion></exclusions>
            </dependency>
          </dependencies>
        </project>
    """.trimIndent()

    private fun stubAether(system: RepositorySystem) {
        every { AetherSessionFactory.repositorySystem } returns system
        every { AetherSessionFactory.createSession(any()) } returns mockk()
        every { AetherSessionFactory.resolveLocalRepository() } returns tempDir.resolve("m2")
    }

    @Test
    fun `resolveDependencies maps exclusions optional and dependencyManagement onto the request`() {
        val system = mockk<RepositorySystem>()
        stubAether(system)
        val request = slot<DependencyRequest>()
        every { system.resolveDependencies(any(), capture(request)) } answers {
            DependencyResult(request.captured)
        }
        val pomFile = tempDir.resolve("pom.xml").also { it.writeText(pom) }

        MavenDependencyResolver().resolveDependencies(pomFile)

        val collect = request.captured.collectRequest
        val lib = collect.dependencies.single()
        assertTrue(lib.isOptional)
        assertEquals(listOf("commons-logging"), lib.exclusions.map { it.groupId })
        val managed = collect.managedDependencies.single()
        assertEquals("pinned", managed.artifact.artifactId)
        assertEquals("2.0", managed.artifact.version)
        assertEquals(listOf("x"), managed.exclusions.map { it.groupId })
    }

    @Test
    fun `resolveDetailed keeps resolved artifacts when another one cannot be resolved`() {
        val system = mockk<RepositorySystem>()
        stubAether(system)
        val jar = tempDir.resolve("good.jar").also { it.writeText("x") }
        every { system.resolveDependencies(any(), any()) } answers {
            val req = secondArg<DependencyRequest>()
            val good = DefaultArtifact("g:good:1").setFile(jar.toFile())
            val bad = DefaultArtifact("g:bad:1")
            val result = DependencyResult(req).setArtifactResults(
                listOf(
                    ArtifactResult(ArtifactRequest(good, null, null)).setArtifact(good),
                    ArtifactResult(ArtifactRequest(bad, null, null)),
                ),
            )
            throw DependencyResolutionException(result, RuntimeException("bad missing"))
        }
        val pomFile = tempDir.resolve("pom.xml").also { it.writeText(pom) }

        val outcome = MavenDependencyResolver().resolveDetailed(pomFile)

        assertEquals(listOf(jar), outcome.dependencies)
        assertTrue(outcome.isPartial)
        assertEquals(listOf("g:bad:jar:1"), outcome.unresolved)
    }
}
