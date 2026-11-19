// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.groovylsp.bsp.maven.server

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.OutputPathsParams
import com.github.groovylsp.bsp.maven.workspace.MavenWorkspaceScanner
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class MavenModuleEdgeCaseTest {
    @TempDir
    lateinit var tempDir: Path

    private fun pom(artifactId: String, modules: List<String> = emptyList()): String {
        val moduleXml = modules.joinToString("") { "<module>$it</module>" }
        val packaging = if (modules.isEmpty()) "" else "<packaging>pom</packaging><modules>$moduleXml</modules>"
        return """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>g</groupId><artifactId>$artifactId</artifactId><version>1</version>$packaging</project>"""
    }

    @Test
    fun `module artifactId test resolves its main target as main`() {
        tempDir.resolve("pom.xml").writeText(pom("test"))
        val repositorySystem = mockk<RepositorySystem>()
        val result = mockk<DependencyResult>()
        every { result.artifactResults } returns emptyList()
        every { repositorySystem.resolveDependencies(any(), any<DependencyRequest>()) } returns result
        val server = MavenBuildServer(tempDir, repositorySystem) { mockk<RepositorySystemSession>() }
        server.buildInitialize(TestFixtures.createInitParams(tempDir)).get()
        server.onBuildInitialized()

        val main = outputPaths(server, "maven:g:test")
        val test = outputPaths(server, "maven:g:test:test")

        assertThat(main.items.single().outputPaths.single().uri).contains("target/classes")
        assertThat(test.items.single().outputPaths.single().uri).contains("target/test-classes")
    }

    private fun outputPaths(server: MavenBuildServer, id: String) =
        server.buildTargetOutputPaths(OutputPathsParams(listOf(BuildTargetIdentifier(id)))).get()

    @Test
    fun `sibling module outside the aggregator directory is still scanned`() {
        val aggregator = tempDir.resolve("agg").createDirectories()
        aggregator.resolve("pom.xml").writeText(pom("agg", listOf("../sibling")))
        tempDir.resolve("sibling").createDirectories().resolve("pom.xml").writeText(pom("sibling"))

        val modules = MavenWorkspaceScanner().scan(aggregator)

        assertThat(modules.map { it.artifactId }).containsExactly("agg", "sibling")
    }

    @Test
    fun `module cycles are scanned once`() {
        tempDir.resolve("pom.xml").writeText(pom("root", listOf(".", "child")))
        tempDir.resolve("child").createDirectories().resolve("pom.xml").writeText(pom("child", listOf("..")))

        val modules = MavenWorkspaceScanner().scan(tempDir)

        assertThat(modules.map { it.artifactId }).containsExactly("root", "child")
    }
}
