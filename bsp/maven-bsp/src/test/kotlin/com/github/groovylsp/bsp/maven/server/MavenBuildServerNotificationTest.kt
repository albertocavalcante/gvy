package com.github.groovylsp.bsp.maven.server

import ch.epfl.scala.bsp4j.BuildClient
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.LogMessageParams
import ch.epfl.scala.bsp4j.ShowMessageParams
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.TaskProgressParams
import ch.epfl.scala.bsp4j.TaskStartParams
import com.github.groovylsp.bsp.maven.server.TestFixtures.APP_TARGET_ID
import com.github.groovylsp.bsp.maven.server.TestFixtures.APP_TEST_TARGET_ID
import com.github.groovylsp.bsp.maven.server.TestFixtures.BSP_VERSION
import com.github.groovylsp.bsp.maven.server.TestFixtures.DISPLAY_NAME
import com.github.groovylsp.bsp.maven.server.TestFixtures.SERVER_VERSION
import com.github.groovylsp.bsp.maven.server.TestFixtures.createInitParams
import com.github.groovylsp.bsp.maven.server.TestFixtures.createSimpleMavenProject
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.resolution.DependencyResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tests for BSP notification compliance of MavenBuildServer.
 *
 * According to BSP spec, servers should send notifications to clients:
 * - build/logMessage: For structured logging
 * - build/showMessage: For user-facing messages
 * - build/taskStart: When starting long operations
 * - build/taskProgress: During long operations
 * - build/taskFinish: When completing long operations
 * - build/publishDiagnostics: For compilation errors/warnings
 */
class MavenBuildServerNotificationTest {

    private lateinit var server: MavenBuildServer
    private lateinit var repositorySystem: RepositorySystem
    private lateinit var session: RepositorySystemSession
    private lateinit var mockClient: BuildClient

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        repositorySystem = mockk()
        session = mockk()
        mockClient = mockk(relaxed = true)

        // Mock empty dependency resolution
        val emptyResult = mockk<DependencyResult>()
        every { emptyResult.artifactResults } returns emptyList()
        every {
            repositorySystem.resolveDependencies(any(), any<org.eclipse.aether.resolution.DependencyRequest>())
        } returns emptyResult

        createSimpleMavenProject(tempDir)
        server = MavenBuildServer(tempDir, repositorySystem) { session }
        server.connect(mockClient)
    }

    @Nested
    inner class ClientConnection {

        @Test
        fun `client can be connected to server`() {
            // Given: Fresh server
            val newServer = MavenBuildServer(tempDir, repositorySystem) { session }
            val client = mockk<BuildClient>(relaxed = true)

            // When: Connect client
            newServer.connect(client)

            // Then: No exception thrown
            assertThat(newServer).isNotNull
        }
    }

    @Nested
    inner class LogMessageNotifications {

        @Test
        fun `server does not send log messages during initialization`() {
            // ISSUE: Current implementation only logs to SLF4J, not to BSP client
            // According to BSP spec (recommended but not required):
            // Servers SHOULD send log messages via build/logMessage

            // When: Initialize server
            server.buildInitialize(createInitParams(tempDir)).get()

            // Then: CURRENT BEHAVIOR - No log messages sent to client
            verify(exactly = 0) {
                mockClient.onBuildLogMessage(any<LogMessageParams>())
            }

            // TODO: After implementing notification support, this should change to:
            // verify(atLeast = 1) {
            //     mockClient.onBuildLogMessage(match {
            //         it.message.contains("Initializing Maven BSP server")
            //     })
            // }
        }

        @Test
        fun `server does not send log messages during workspace scan`() {
            // ISSUE: Same as above

            // When
            server.buildInitialize(createInitParams(tempDir)).get()

            // Then: CURRENT BEHAVIOR - No notifications
            verify(exactly = 0) {
                mockClient.onBuildLogMessage(any<LogMessageParams>())
            }

            // TODO: Should send messages like "Found X Maven modules"
        }
    }

    @Nested
    inner class TaskNotifications {

        @Test
        fun `server does not send task notifications during initialization`() {
            // ISSUE: Current implementation doesn't report progress
            // BSP spec recommends task notifications for long operations

            // When: Initialize server (potentially long operation)
            server.buildInitialize(createInitParams(tempDir)).get()

            // Then: CURRENT BEHAVIOR - No task notifications
            verify(exactly = 0) {
                mockClient.onBuildTaskStart(any<TaskStartParams>())
            }
            verify(exactly = 0) {
                mockClient.onBuildTaskProgress(any<TaskProgressParams>())
            }
            verify(exactly = 0) {
                mockClient.onBuildTaskFinish(any<TaskFinishParams>())
            }

            // TODO: Should send:
            // 1. TaskStart with taskId and message "Scanning Maven workspace"
            // 2. TaskFinish with taskId and StatusCode.OK
        }

        @Test
        fun `server does not send task notifications during workspace reload`() {
            // ISSUE: Same as above

            // Given: Initialized server
            server.buildInitialize(createInitParams(tempDir)).get()

            // When: Reload workspace
            server.workspaceReload().get()

            // Then: CURRENT BEHAVIOR - No task notifications
            verify(exactly = 0) {
                mockClient.onBuildTaskStart(any<TaskStartParams>())
            }
            verify(exactly = 0) {
                mockClient.onBuildTaskFinish(any<TaskFinishParams>())
            }
        }

        @Test
        fun `server does not send task notifications during compile`() {
            // ISSUE: Stub implementation doesn't send notifications

            // Given: Initialized server
            server.buildInitialize(createInitParams(tempDir)).get()

            // When: Compile (stub)
            val params = CompileParams(
                listOf(BuildTargetIdentifier(APP_TARGET_ID)),
            )
            server.buildTargetCompile(params).get()

            // Then: CURRENT BEHAVIOR - No task notifications
            verify(exactly = 0) {
                mockClient.onBuildTaskStart(any<TaskStartParams>())
            }
            verify(exactly = 0) {
                mockClient.onBuildTaskFinish(any<TaskFinishParams>())
            }

            // TODO: Real implementation should send task notifications
        }
    }

    @Nested
    inner class ShowMessageNotifications {

        @Test
        fun `server does not send show message notifications`() {
            // ISSUE: No use of showMessage for important user-facing messages

            // When: Various operations
            server.buildInitialize(createInitParams(tempDir)).get()
            server.buildShutdown().get()

            // Then: CURRENT BEHAVIOR - No show message calls
            verify(exactly = 0) {
                mockClient.onBuildShowMessage(any<ShowMessageParams>())
            }

            // Note: showMessage is for important UI messages like errors
            // It's reasonable not to use it for normal operations
        }
    }

    @Nested
    inner class PublishDiagnosticsNotifications {

        @Test
        fun `server does not send diagnostics notifications`() {
            // ISSUE: No diagnostics support (reasonable for current scope)
            // Diagnostics are typically published during compilation

            // When: Initialize and compile
            server.buildInitialize(createInitParams(tempDir)).get()
            val params = CompileParams(
                listOf(BuildTargetIdentifier(APP_TARGET_ID)),
            )
            server.buildTargetCompile(params).get()

            // Then: CURRENT BEHAVIOR - No diagnostics
            verify(exactly = 0) {
                mockClient.onBuildPublishDiagnostics(any())
            }

            // TODO: Real compile implementation should publish diagnostics
        }
    }

    @Nested
    inner class ClientReferenceUsage {

        @Test
        fun `server stores client reference but never uses it`() {
            // ISSUE: Client is connected but never invoked
            // This is a missed opportunity for better IDE integration

            // Given: Client connected
            assertThat(server).isNotNull

            // When: Multiple operations
            server.buildInitialize(createInitParams(tempDir)).get()
            server.workspaceBuildTargets().get()
            server.workspaceReload().get()
            server.buildShutdown().get()

            // Then: CURRENT BEHAVIOR - Client methods never called
            // (mockk with relaxed=true would have recorded any calls)
            verify(exactly = 0) {
                mockClient.onBuildLogMessage(any())
            }
            verify(exactly = 0) {
                mockClient.onBuildShowMessage(any())
            }
            verify(exactly = 0) {
                mockClient.onBuildTaskStart(any())
            }
            verify(exactly = 0) {
                mockClient.onBuildTaskFinish(any())
            }
            verify(exactly = 0) {
                mockClient.onBuildPublishDiagnostics(any())
            }
        }
    }

    @Nested
    inner class RecommendedNotificationPatterns {

        @Test
        fun `initialization should follow recommended notification pattern`() {
            // BSP recommended pattern for long operations:
            // 1. Send taskStart
            // 2. Send logMessage for detailed progress (optional)
            // 3. Send taskFinish with status

            // When
            server.buildInitialize(createInitParams(tempDir)).get()

            // Then: CURRENT BEHAVIOR - No notifications
            // All verification calls would show 0 invocations

            // TODO: Expected pattern:
            // verify {
            //     mockClient.onBuildTaskStart(match {
            //         it.message == "Scanning Maven workspace"
            //     })
            // }
            // verify {
            //     mockClient.onBuildLogMessage(match {
            //         it.message.contains("Found") && it.message.contains("modules")
            //     })
            // }
            // verify {
            //     mockClient.onBuildTaskFinish(match {
            //         it.status == StatusCode.OK
            //     })
            // }
        }
    }
}
