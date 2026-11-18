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
import org.eclipse.aether.resolution.ArtifactResolutionException
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
        assertEquals("", managed.scope, "managed entries must not force a scope")
        assertEquals(null, managed.optional, "managed entries must not force optional")
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

    @Test
    fun `resolveDetailed reports collection failures as unresolved`() {
        val system = mockk<RepositorySystem>()
        stubAether(system)
        every { system.resolveDependencies(any(), any()) } answers {
            val result = DependencyResult(secondArg<DependencyRequest>())
                .setCollectExceptions(listOf(RuntimeException("bad version range")))
            throw DependencyResolutionException(result, RuntimeException("boom"))
        }
        val pomFile = tempDir.resolve("pom.xml").also { it.writeText(pom) }

        val outcome = MavenDependencyResolver().resolveDetailed(pomFile)

        assertTrue(outcome.isPartial)
        assertEquals(listOf("collection failed: bad version range"), outcome.unresolved)
    }

    @Test
    fun `versionless managed entries keep their exclusions and managed types map through the registry`() {
        val system = mockk<RepositorySystem>()
        stubAether(system)
        val request = slot<DependencyRequest>()
        every { system.resolveDependencies(any(), capture(request)) } answers {
            DependencyResult(request.captured)
        }
        val pomFile = tempDir.resolve("pom.xml").also {
            it.writeText(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>t</groupId><artifactId>t</artifactId><version>1</version>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>noversion</artifactId>
                      <exclusions><exclusion><groupId>x</groupId><artifactId>y</artifactId></exclusion></exclusions>
                    </dependency>
                    <dependency><groupId>org.example</groupId><artifactId>tests</artifactId><version>3.0</version>
                      <type>test-jar</type>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """.trimIndent(),
            )
        }

        MavenDependencyResolver().resolveDependencies(pomFile)

        val managed = request.captured.collectRequest.managedDependencies.associateBy { it.artifact.artifactId }
        assertEquals(listOf("x"), managed.getValue("noversion").exclusions.map { it.groupId })
        val testJar = managed.getValue("tests").artifact
        assertEquals("jar", testJar.extension)
        assertEquals("tests", testJar.classifier)
    }

    @Test
    fun `import scope BOM supplies the version of a versionless dependency`() {
        val system = mockk<RepositorySystem>()
        stubAether(system)
        val bom = tempDir.resolve("bom.pom").also {
            it.writeText(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId><artifactId>bom</artifactId><version>9</version>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>lib</artifactId><version>7.1</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """.trimIndent(),
            )
        }
        val requestedPoms = mutableListOf<String>()
        every { system.resolveArtifact(any(), any<ArtifactRequest>()) } answers {
            val req = secondArg<ArtifactRequest>()
            requestedPoms.add(req.artifact.toString())
            ArtifactResult(req).setArtifact(req.artifact.setFile(bom.toFile()))
        }
        val request = slot<DependencyRequest>()
        every { system.resolveDependencies(any(), capture(request)) } answers {
            DependencyResult(request.captured)
        }
        val pomFile = tempDir.resolve("pom.xml").also {
            it.writeText(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>t</groupId><artifactId>t</artifactId><version>1</version>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>bom</artifactId><version>9</version>
                      <type>pom</type><scope>import</scope>
                    </dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>lib</artifactId></dependency>
                  </dependencies>
                </project>
                """.trimIndent(),
            )
        }

        MavenDependencyResolver().resolveDependencies(pomFile)

        val collect = request.captured.collectRequest
        assertEquals("7.1", collect.dependencies.single().artifact.version)
        assertEquals(listOf("org.example:bom:pom:9"), requestedPoms)
    }

    @Test
    fun `a remote parent POM supplies managed versions through the model resolver`() {
        val system = mockk<RepositorySystem>()
        stubAether(system)
        val parent = tempDir.resolve("parent.pom").also {
            it.writeText(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId><artifactId>parent</artifactId><version>3</version>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>lib</artifactId><version>5.0</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """.trimIndent(),
            )
        }
        val requestedPoms = mutableListOf<String>()
        every { system.resolveArtifact(any(), any<ArtifactRequest>()) } answers {
            val req = secondArg<ArtifactRequest>()
            requestedPoms.add(req.artifact.toString())
            ArtifactResult(req).setArtifact(req.artifact.setFile(parent.toFile()))
        }
        val request = slot<DependencyRequest>()
        every { system.resolveDependencies(any(), capture(request)) } answers { DependencyResult(request.captured) }
        val pomFile = tempDir.resolve("pom.xml").also {
            it.writeText(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.example</groupId><artifactId>parent</artifactId><version>3</version>
                    <relativePath/>
                  </parent>
                  <artifactId>child</artifactId>
                  <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>lib</artifactId></dependency>
                  </dependencies>
                </project>
                """.trimIndent(),
            )
        }

        MavenDependencyResolver().resolveDependencies(pomFile)

        assertEquals("5.0", request.captured.collectRequest.dependencies.single().artifact.version)
        assertEquals(listOf("org.example:parent:pom:3"), requestedPoms)
    }

    @Test
    fun `an unresolvable BOM yields an empty result without resolving dependencies`() {
        val system = mockk<RepositorySystem>()
        stubAether(system)
        val requested = mutableListOf<String>()
        every { system.resolveArtifact(any(), any<ArtifactRequest>()) } answers {
            requested.add(secondArg<ArtifactRequest>().artifact.toString())
            throw ArtifactResolutionException(listOf(ArtifactResult(secondArg<ArtifactRequest>())))
        }
        val pomFile = tempDir.resolve("pom.xml").also {
            it.writeText(
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>t</groupId><artifactId>t</artifactId><version>1</version>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>missing-bom</artifactId><version>9</version>
                      <type>pom</type><scope>import</scope>
                    </dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>lib</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.trimIndent(),
            )
        }

        val result = MavenDependencyResolver().resolveDetailed(pomFile)

        assertEquals(MavenResolutionResult.EMPTY, result)
        assertEquals(listOf("org.example:missing-bom:pom:9"), requested)
        verify(exactly = 0) { system.resolveDependencies(any(), any()) }
    }
}
