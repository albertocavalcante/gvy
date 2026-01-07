package com.github.groovylsp.bsp.client

import arrow.core.Either
import ch.epfl.scala.bsp4j.BuildServer
import ch.epfl.scala.bsp4j.BuildServerCapabilities
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileProvider
import ch.epfl.scala.bsp4j.CompileResult
import ch.epfl.scala.bsp4j.DependencyModulesParams
import ch.epfl.scala.bsp4j.DependencyModulesResult
import ch.epfl.scala.bsp4j.DependencySourcesParams
import ch.epfl.scala.bsp4j.DependencySourcesResult
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.SourcesResult
import ch.epfl.scala.bsp4j.StatusCode
import ch.epfl.scala.bsp4j.TestParams
import ch.epfl.scala.bsp4j.TestProvider
import ch.epfl.scala.bsp4j.TestResult
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class BuildServerConnectionTest {

    @Test
    fun `workspaceBuildTargets returns successful result`() = runTest {
        val mockServer = mockk<BuildServer>()
        val expectedResult = WorkspaceBuildTargetsResult(emptyList())
        every { mockServer.workspaceBuildTargets() } returns CompletableFuture.completedFuture(expectedResult)

        val capabilities = BspCapabilities(BuildServerCapabilities())
        val connection = BuildServerConnection(mockServer, capabilities)

        val result = connection.workspaceBuildTargets()

        assertIs<Either.Right<WorkspaceBuildTargetsResult>>(result)
        assertEquals(expectedResult, result.value)
    }

    @Test
    fun `workspaceBuildTargets handles timeout`() = runTest {
        val mockServer = mockk<BuildServer>()
        val neverCompletes = CompletableFuture<WorkspaceBuildTargetsResult>()
        every { mockServer.workspaceBuildTargets() } returns neverCompletes

        val capabilities = BspCapabilities(BuildServerCapabilities())
        val config = ConnectionConfig(requestTimeout = 10.milliseconds)
        val connection = BuildServerConnection(mockServer, capabilities, config)

        val result = connection.workspaceBuildTargets()

        assertIs<Either.Left<BspError>>(result)
        assertIs<BspError.Timeout>(result.value)
        assertEquals("workspaceBuildTargets", result.value.operation)
    }

    @Test
    fun `workspaceBuildTargets handles server exception`() = runTest {
        val mockServer = mockk<BuildServer>()
        val exception = RuntimeException("Server error")
        every { mockServer.workspaceBuildTargets() } returns CompletableFuture.failedFuture(exception)

        val capabilities = BspCapabilities(BuildServerCapabilities())
        val connection = BuildServerConnection(mockServer, capabilities)

        val result = connection.workspaceBuildTargets()

        assertIs<Either.Left<BspError>>(result)
        assertIs<BspError.RequestFailed>(result.value)
        assertEquals("workspaceBuildTargets", result.value.operation)
    }

    @Test
    fun `buildTargetSources returns successful result`() = runTest {
        val mockServer = mockk<BuildServer>()
        val targetId = BuildTargetIdentifier("file:///workspace/module")
        val params = SourcesParams(listOf(targetId))
        val expectedResult = SourcesResult(emptyList())
        every { mockServer.buildTargetSources(params) } returns CompletableFuture.completedFuture(expectedResult)

        val capabilities = BspCapabilities(BuildServerCapabilities())
        val connection = BuildServerConnection(mockServer, capabilities)

        val result = connection.buildTargetSources(params)

        assertIs<Either.Right<SourcesResult>>(result)
        assertEquals(expectedResult, result.value)
    }

    @Test
    fun `buildTargetCompile succeeds when capability is supported`() = runTest {
        val mockServer = mockk<BuildServer>()
        val targetId = BuildTargetIdentifier("file:///workspace/module")
        val params = CompileParams(listOf(targetId))
        val expectedResult = CompileResult(StatusCode.OK)
        every { mockServer.buildTargetCompile(params) } returns CompletableFuture.completedFuture(expectedResult)

        val serverCaps = BuildServerCapabilities().apply {
            compileProvider = CompileProvider(listOf("groovy"))
        }
        val capabilities = BspCapabilities(serverCaps)
        val connection = BuildServerConnection(mockServer, capabilities)

        val result = connection.buildTargetCompile(params)

        assertIs<Either.Right<CompileResult>>(result)
        assertEquals(expectedResult, result.value)
        assertEquals(StatusCode.OK, result.value.statusCode)
    }

    @Test
    fun `buildTargetCompile fails when capability not supported`() = runTest {
        val mockServer = mockk<BuildServer>(relaxed = true)
        val targetId = BuildTargetIdentifier("file:///workspace/module")
        val params = CompileParams(listOf(targetId))

        val serverCaps = BuildServerCapabilities() // No compile provider
        val capabilities = BspCapabilities(serverCaps)
        val connection = BuildServerConnection(mockServer, capabilities)

        val result = connection.buildTargetCompile(params)

        assertIs<Either.Left<BspError>>(result)
        assertIs<BspError.UnsupportedCapability>(result.value)
        assertEquals("buildTargetCompile", result.value.operation)
    }

    @Test
    fun `buildTargetTest succeeds when capability is supported`() = runTest {
        val mockServer = mockk<BuildServer>()
        val targetId = BuildTargetIdentifier("file:///workspace/module")
        val params = TestParams(listOf(targetId))
        val expectedResult = TestResult(StatusCode.OK)
        every { mockServer.buildTargetTest(params) } returns CompletableFuture.completedFuture(expectedResult)

        val serverCaps = BuildServerCapabilities().apply {
            testProvider = TestProvider(listOf("groovy"))
        }
        val capabilities = BspCapabilities(serverCaps)
        val connection = BuildServerConnection(mockServer, capabilities)

        val result = connection.buildTargetTest(params)

        assertIs<Either.Right<TestResult>>(result)
        assertEquals(expectedResult, result.value)
    }

    @Test
    fun `buildTargetTest fails when capability not supported`() = runTest {
        val mockServer = mockk<BuildServer>(relaxed = true)
        val targetId = BuildTargetIdentifier("file:///workspace/module")
        val params = TestParams(listOf(targetId))

        val serverCaps = BuildServerCapabilities() // No test provider
        val capabilities = BspCapabilities(serverCaps)
        val connection = BuildServerConnection(mockServer, capabilities)

        val result = connection.buildTargetTest(params)

        assertIs<Either.Left<BspError>>(result)
        assertIs<BspError.UnsupportedCapability>(result.value)
        assertEquals("buildTargetTest", result.value.operation)
    }

    @Test
    fun `buildTargetDependencyModules succeeds when capability is supported`() = runTest {
        val mockServer = mockk<BuildServer>()
        val targetId = BuildTargetIdentifier("file:///workspace/module")
        val params = DependencyModulesParams(listOf(targetId))
        val expectedResult = DependencyModulesResult(emptyList())
        every {
            mockServer.buildTargetDependencyModules(params)
        } returns CompletableFuture.completedFuture(expectedResult)

        val serverCaps = BuildServerCapabilities().apply {
            dependencyModulesProvider = true
        }
        val capabilities = BspCapabilities(serverCaps)
        val connection = BuildServerConnection(mockServer, capabilities)

        val result = connection.buildTargetDependencyModules(params)

        assertIs<Either.Right<DependencyModulesResult>>(result)
        assertEquals(expectedResult, result.value)
    }

    @Test
    fun `buildTargetDependencyModules fails when capability not supported`() = runTest {
        val mockServer = mockk<BuildServer>(relaxed = true)
        val targetId = BuildTargetIdentifier("file:///workspace/module")
        val params = DependencyModulesParams(listOf(targetId))

        val serverCaps = BuildServerCapabilities() // No dependency modules provider
        val capabilities = BspCapabilities(serverCaps)
        val connection = BuildServerConnection(mockServer, capabilities)

        val result = connection.buildTargetDependencyModules(params)

        assertIs<Either.Left<BspError>>(result)
        assertIs<BspError.UnsupportedCapability>(result.value)
    }

    @Test
    fun `buildTargetDependencySources succeeds when capability is supported`() = runTest {
        val mockServer = mockk<BuildServer>()
        val targetId = BuildTargetIdentifier("file:///workspace/module")
        val params = DependencySourcesParams(listOf(targetId))
        val expectedResult = DependencySourcesResult(emptyList())
        every {
            mockServer.buildTargetDependencySources(params)
        } returns CompletableFuture.completedFuture(expectedResult)

        val serverCaps = BuildServerCapabilities().apply {
            dependencySourcesProvider = true
        }
        val capabilities = BspCapabilities(serverCaps)
        val connection = BuildServerConnection(mockServer, capabilities)

        val result = connection.buildTargetDependencySources(params)

        assertIs<Either.Right<DependencySourcesResult>>(result)
        assertEquals(expectedResult, result.value)
    }

    @Test
    fun `buildTargetDependencySources fails when capability not supported`() = runTest {
        val mockServer = mockk<BuildServer>(relaxed = true)
        val targetId = BuildTargetIdentifier("file:///workspace/module")
        val params = DependencySourcesParams(listOf(targetId))

        val serverCaps = BuildServerCapabilities() // No dependency sources provider
        val capabilities = BspCapabilities(serverCaps)
        val connection = BuildServerConnection(mockServer, capabilities)

        val result = connection.buildTargetDependencySources(params)

        assertIs<Either.Left<BspError>>(result)
        assertIs<BspError.UnsupportedCapability>(result.value)
    }

    @Test
    fun `withCapability executes action when capability is supported`() = runTest {
        val mockServer = mockk<BuildServer>(relaxed = true)
        val serverCaps = BuildServerCapabilities().apply {
            compileProvider = CompileProvider(listOf("groovy"))
        }
        val capabilities = BspCapabilities(serverCaps)
        val connection = BuildServerConnection(mockServer, capabilities)

        var actionExecuted = false
        val result = connection.withCapability(
            supported = { capabilities.supportsCompile() },
            action = {
                actionExecuted = true
                "success"
            },
            fallback = { "fallback" },
        )

        assertTrue(actionExecuted)
        assertEquals("success", result)
    }

    @Test
    fun `withCapability uses fallback when capability not supported`() = runTest {
        val mockServer = mockk<BuildServer>(relaxed = true)
        val serverCaps = BuildServerCapabilities() // No compile provider
        val capabilities = BspCapabilities(serverCaps)
        val connection = BuildServerConnection(mockServer, capabilities)

        var actionExecuted = false
        val result = connection.withCapability(
            supported = { capabilities.supportsCompile() },
            action = {
                actionExecuted = true
                "success"
            },
            fallback = { "fallback" },
        )

        assertFalse(actionExecuted)
        assertEquals("fallback", result)
    }

    @Test
    fun `getCapabilities returns the capabilities wrapper`() {
        val mockServer = mockk<BuildServer>(relaxed = true)
        val serverCaps = BuildServerCapabilities().apply {
            compileProvider = CompileProvider(listOf("groovy"))
        }
        val capabilities = BspCapabilities(serverCaps)
        val connection = BuildServerConnection(mockServer, capabilities)

        val retrieved = connection.getCapabilities()

        assertEquals(capabilities, retrieved)
        assertTrue(retrieved.supportsCompile())
    }

    @Test
    fun `isClosed returns false for new connection`() {
        val mockServer = mockk<BuildServer>(relaxed = true)
        val capabilities = BspCapabilities(BuildServerCapabilities())
        val connection = BuildServerConnection(mockServer, capabilities)

        assertFalse(connection.isClosed())
    }

    @Test
    fun `isClosed returns true after close`() {
        val mockServer = mockk<BuildServer>(relaxed = true)
        val capabilities = BspCapabilities(BuildServerCapabilities())
        val connection = BuildServerConnection(mockServer, capabilities)

        connection.close()

        assertTrue(connection.isClosed())
    }

    @Test
    fun `close is idempotent`() {
        val mockServer = mockk<BuildServer>(relaxed = true)
        val capabilities = BspCapabilities(BuildServerCapabilities())
        val connection = BuildServerConnection(mockServer, capabilities)

        connection.close()
        connection.close() // Should not throw

        assertTrue(connection.isClosed())
    }

    @Test
    fun `operations fail after connection is closed`() = runTest {
        val mockServer = mockk<BuildServer>(relaxed = true)
        val capabilities = BspCapabilities(BuildServerCapabilities())
        val connection = BuildServerConnection(mockServer, capabilities)

        connection.close()

        assertThrows<IllegalStateException> {
            runTest {
                connection.workspaceBuildTargets()
            }
        }
    }

    @Test
    fun `BspError Timeout contains operation and cause`() {
        val cause = RuntimeException("Timeout occurred")
        val error = BspError.Timeout("testOperation", cause)

        assertEquals("testOperation", error.operation)
        assertEquals(cause, error.cause)
        assertTrue(error.message.contains("testOperation"))
        assertTrue(error.message.contains("timed out"))
    }

    @Test
    fun `BspError UnsupportedCapability contains operation and reason`() {
        val error = BspError.UnsupportedCapability("buildTargetCompile", "Server does not support compilation")

        assertEquals("buildTargetCompile", error.operation)
        assertEquals("Server does not support compilation", error.reason)
        assertTrue(error.message.contains("buildTargetCompile"))
        assertTrue(error.message.contains("not supported"))
    }

    @Test
    fun `BspError RequestFailed contains operation and cause`() {
        val cause = RuntimeException("Network error")
        val error = BspError.RequestFailed("workspaceBuildTargets", cause)

        assertEquals("workspaceBuildTargets", error.operation)
        assertEquals(cause, error.cause)
        assertTrue(error.message.contains("workspaceBuildTargets"))
        assertTrue(error.message.contains("failed"))
    }
}
