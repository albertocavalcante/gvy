package com.github.albertocavalcante.groovylsp.buildtool.maven

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.resolution.ArtifactResult
import org.eclipse.aether.resolution.DependencyResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
}
