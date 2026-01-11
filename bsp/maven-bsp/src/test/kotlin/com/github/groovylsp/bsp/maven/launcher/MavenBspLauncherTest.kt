package com.github.groovylsp.bsp.maven.launcher

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * TDD tests for MavenBspLauncher.
 *
 * These tests verify:
 * - Server creation with workspace root
 * - Argument parsing
 * - Repository system initialization
 */
class MavenBspLauncherTest {

    @TempDir
    lateinit var tempDir: Path

    @Nested
    inner class ServerCreation {

        @Test
        fun `should create server with workspace root`() {
            // Given
            val workspaceRoot = tempDir
            createSimplePom(workspaceRoot)

            // When
            val server = MavenBspLauncher.createServer(workspaceRoot)

            // Then
            assertThat(server).isNotNull
        }

        @Test
        fun `should create server for empty workspace`() {
            // Given: Empty workspace (no pom.xml)
            val workspaceRoot = tempDir

            // When
            val server = MavenBspLauncher.createServer(workspaceRoot)

            // Then: Should not fail
            assertThat(server).isNotNull
        }
    }

    @Nested
    inner class RepositorySystem {

        @Test
        fun `should create repository system`() {
            // When
            val repoSystem = MavenBspLauncher.createRepositorySystem()

            // Then
            assertThat(repoSystem).isNotNull
        }

        @Test
        fun `should create repository session`() {
            // Given
            val repoSystem = MavenBspLauncher.createRepositorySystem()

            // When
            val session = MavenBspLauncher.createSession(repoSystem, tempDir)

            // Then
            assertThat(session).isNotNull
            assertThat(session.localRepository).isNotNull
        }

        @Test
        fun `should use specified local repository`() {
            // Given
            val repoSystem = MavenBspLauncher.createRepositorySystem()
            val localRepoPath = tempDir.resolve(".m2/repository")

            // When
            val session = MavenBspLauncher.createSession(repoSystem, localRepoPath)

            // Then
            assertThat(session.localRepository.basedir.toPath()).isEqualTo(localRepoPath)
        }
    }

    @Nested
    inner class ArgumentParsing {

        @Test
        fun `should parse workspace root from args`() {
            // Given
            val args = arrayOf(tempDir.toString())

            // When
            val config = MavenBspLauncher.parseArgs(args)

            // Then
            assertThat(config.workspaceRoot).isEqualTo(tempDir)
        }

        @Test
        fun `should use current directory when no args`() {
            // Given
            val args = emptyArray<String>()

            // When
            val config = MavenBspLauncher.parseArgs(args)

            // Then
            assertThat(config.workspaceRoot).isNotNull
        }

        @Test
        fun `should parse local repo option`() {
            // Given
            val localRepo = tempDir.resolve(".m2/repository")
            val args = arrayOf("--local-repo", localRepo.toString(), tempDir.toString())

            // When
            val config = MavenBspLauncher.parseArgs(args)

            // Then
            assertThat(config.localRepository).isEqualTo(localRepo)
        }
    }

    private fun createSimplePom(dir: Path) {
        dir.resolve("pom.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test</artifactId>
                <version>1.0.0</version>
            </project>
            """.trimIndent(),
        )
    }
}
