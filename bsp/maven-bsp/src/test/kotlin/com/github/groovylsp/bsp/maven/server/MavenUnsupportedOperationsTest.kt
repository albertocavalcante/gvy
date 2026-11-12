// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.groovylsp.bsp.maven.server

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CleanCacheParams
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.DebugSessionParams
import ch.epfl.scala.bsp4j.JvmRunEnvironmentParams
import ch.epfl.scala.bsp4j.JvmTestEnvironmentParams
import ch.epfl.scala.bsp4j.RunParams
import ch.epfl.scala.bsp4j.TestParams
import com.github.groovylsp.bsp.maven.server.TestFixtures.createInitParams
import com.github.groovylsp.bsp.maven.server.TestFixtures.createSimpleMavenProject
import io.mockk.mockk
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MavenUnsupportedOperationsTest {
    @TempDir
    lateinit var workspace: Path

    private lateinit var server: MavenBuildServer
    private val target = BuildTargetIdentifier(TestFixtures.APP_TARGET_ID)
    private val targets = listOf(target)

    @BeforeEach
    fun setUp() {
        createSimpleMavenProject(workspace)
        val repository = mockk<RepositorySystem>()
        val session = mockk<RepositorySystemSession>()
        server = MavenBuildServer(workspace, repository) { session }
    }

    @Test
    fun `initialize and both target kinds advertise metadata without execution capabilities`() {
        val capabilities = server.buildInitialize(createInitParams(workspace)).get().capabilities
        assertNull(capabilities.compileProvider)
        assertNull(capabilities.testProvider)
        assertNull(capabilities.runProvider)
        assertNull(capabilities.debugProvider)
        assertTrue(capabilities.jvmRunEnvironmentProvider != true)
        assertTrue(capabilities.jvmTestEnvironmentProvider != true)
        assertTrue(capabilities.dependencyModulesProvider)
        assertTrue(capabilities.dependencySourcesProvider)
        val discovered = server.workspaceBuildTargets().get().targets
        assertEquals(2, discovered.size)
        assertEquals(
            setOf(TestFixtures.APP_TARGET_ID, TestFixtures.APP_TEST_TARGET_ID),
            discovered.map { it.id.uri }.toSet(),
        )
        for (item in discovered) {
            assertFalse(item.capabilities.canCompile)
            assertFalse(item.capabilities.canTest)
            assertFalse(item.capabilities.canRun)
            assertFalse(item.capabilities.canDebug)
        }
    }

    @Test
    fun `unimplemented requests fail explicitly before and after initialization`() {
        assertAllUnsupported()
        server.buildInitialize(createInitParams(workspace)).get()
        assertAllUnsupported()
        assertEquals(2, server.workspaceBuildTargets().get().targets.size)
    }

    @Test
    fun `rejected clean does not remove existing target output`() {
        server.buildInitialize(createInitParams(workspace)).get()
        val marker = workspace.resolve("target/classes/marker.txt")
        marker.parent.createDirectories()
        marker.writeText("existing output")
        assertUnsupported { server.buildTargetCleanCache(CleanCacheParams(targets)) }
        assertEquals("existing output", marker.readText())
    }

    @Suppress("DEPRECATION") // Exercise the retained compatibility signatures deliberately.
    private fun assertAllUnsupported() {
        val operations: List<Pair<String, () -> CompletableFuture<*>>> = listOf(
            "compile" to { server.buildTargetCompile(CompileParams(targets)) },
            "test" to { server.buildTargetTest(TestParams(targets)) },
            "run" to { server.buildTargetRun(RunParams(target)) },
            "debug" to { server.debugSessionStart(DebugSessionParams(targets)) },
            "clean" to { server.buildTargetCleanCache(CleanCacheParams(targets)) },
            "jvm run" to { server.jvmRunEnvironment(JvmRunEnvironmentParams(targets)) },
            "jvm test" to { server.jvmTestEnvironment(JvmTestEnvironmentParams(targets)) },
        )
        assertAll(operations.map { (name, operation) -> Executable { assertUnsupported(name, operation) } })
    }

    private fun assertUnsupported(name: String = "clean", operation: () -> CompletableFuture<*>) {
        val error = assertFailsWith<ExecutionException>(name) { operation().get(5, TimeUnit.SECONDS) }
        val response = assertIs<ResponseErrorException>(error.cause, name)
        assertEquals(ResponseErrorCode.MethodNotFound.value, response.responseError.code, name)
    }
}
