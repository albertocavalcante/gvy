// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package org.gvy.jetbrains

import com.intellij.ide.impl.TrustedPaths
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.BaseProjectDirectories
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.replaceService
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.jar.JarFile

/** Opt-in native IDE acceptance against the explicitly paired GVY server artifact. */
class GvyRealServerTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    @Test
    fun `test pinned server initializes and shuts down through IDE manager`() {
        val jarProperty = System.getProperty("gvy.test.serverJar")
        val expectedHash = System.getProperty("gvy.test.serverSha256")
        val expectedCommit = System.getProperty("gvy.test.serverCommit")
        assumeTrue(
            "Set gvy.test.serverJar, gvy.test.serverSha256 and gvy.test.serverCommit for real-server acceptance",
            !jarProperty.isNullOrBlank() && !expectedHash.isNullOrBlank() && !expectedCommit.isNullOrBlank(),
        )
        val hash = requireNotNull(expectedHash)
        val commit = requireNotNull(expectedCommit)
        assertTrue("Server SHA-256 must be 64 lowercase hexadecimal characters", hash.matches(Regex("[0-9a-f]{64}")))
        assertTrue("Server commit must be 40 lowercase hexadecimal characters", commit.matches(Regex("[0-9a-f]{40}")))
        val jar = Path.of(requireNotNull(jarProperty)).toRealPath()
        assertTrue("Pinned server must be a readable local file", Files.isRegularFile(jar) && Files.isReadable(jar))
        assertEquals("The server artifact must match the supplied digest", hash, sha256(jar))
        JarFile(jar.toFile()).use { archive ->
            val entry = requireNotNull(archive.getJarEntry("version.properties")) { "Server provenance is missing" }
            val provenance = Properties()
            archive.getInputStream(entry).use { provenance.load(it) }
            assertEquals("The embedded server commit must match", commit, provenance.getProperty("gitSha"))
            assertEquals("The embedded server must come from a clean tree", "false", provenance.getProperty("gitDirty"))
        }
        println("GVY real-server pairing: source=$commit sha256=$hash jar=$jar")

        ensureFixtureWorkingDirectory(project, testRootDisposable)
        val source = Files.writeString(Path.of(myFixture.tempDirPath, "RealServer.groovy"), "class RealServer {}")
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source))
        ApplicationManager.getApplication().replaceService(TrustedPaths::class.java, TrustedPaths(), testRootDisposable)
        val settings = GvyProjectSettings.getInstance(project)
        val previousOptions = settings.state
        val runtime = project.getService(GvyProjectRuntime::class.java)
        val manager = LspServerManager.getInstance(project)
        val ownerArgument = "-Dgvy.test.owner=${UUID.randomUUID()}"
        var server: LspServer? = null
        var owned: OwnedProcess? = null
        var launchRequested = false
        try {
            await("physical source root") {
                ApplicationManager.getApplication().runReadAction<Boolean> {
                    ProjectFileIndex.getInstance(project).isInContent(file) &&
                        BaseProjectDirectories.getInstance(project).getBaseDirectoryFor(file) != null
                }
            }
            launchRequested = true
            settings.loadState(
                GvyProjectSettings.Options(
                    enabled = true,
                    javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath().toString(),
                    serverJar = jar.toString(),
                    jvmArguments = "-Xmx512m\n$ownerArgument",
                ),
            )
            TrustedProjects.setProjectTrusted(project, true)
            FileEditorManager.getInstance(project).openFile(file, true)
            runtime.restart()
            await("real server initialization") {
                manager
                    .getServersForProvider(GvyServerSupportProvider::class.java)
                    .singleOrNull()
                    ?.let { candidate ->
                        if (candidate.state == LspServerState.Running && candidate.initializeResult != null) {
                            server = candidate
                            true
                        } else {
                            false
                        }
                    } ?: false
            }
            val running = requireNotNull(server)
            val capabilities = requireNotNull(running.initializeResult?.capabilities)
            assertNotNull("Real GVY must advertise text synchronization", capabilities.textDocumentSync)
            assertNotNull("Real GVY must advertise completion", capabilities.completionProvider)
            assertNotNull("Real GVY must advertise hover", capabilities.hoverProvider)
            val process = awaitOwnedProcess(ownerArgument)
            owned = process
            assertTrue(process.handle.isAlive)

            settings.loadState(settings.state.copy(enabled = false))
            runtime.restart()
            await("normal real server shutdown") { running.state == LspServerState.ShutdownNormally }
            await("real server process exit") { !process.handle.isAlive }
        } finally {
            settings.loadState(previousOptions.copy(enabled = false))
            runtime.restart()
            FileEditorManager.getInstance(project).closeFile(file)
            settings.loadState(previousOptions)
            val managerStopped =
                runCatching {
                    await("real server cleanup") {
                        manager.getServersForProvider(GvyServerSupportProvider::class.java).none {
                            it.state == LspServerState.Running || it.state == LspServerState.Initializing
                        }
                    }
                }.isSuccess
            val captured = owned ?: findOwnedProcess(ownerArgument)
            if (captured != null && captured.handle.isAlive) {
                val info = captured.handle.info()
                val args = info.arguments().orElse(emptyArray())
                val sameStart = captured.start == null || captured.start == info.startInstant().orElse(null)
                check(args.contains(ownerArgument) && sameStart) {
                    "Cannot verify the test-owned server PID ${captured.handle.pid()}; refusing forced termination"
                }
                captured.handle.destroyForcibly()
                captured.handle.onExit().get(5, TimeUnit.SECONDS)
            }
            check(managerStopped && (!launchRequested || captured != null)) {
                "Test server ownership or shutdown was not established; no unidentified process was terminated"
            }
        }
    }

    private data class OwnedProcess(
        val handle: ProcessHandle,
        val start: Instant?,
    )

    private fun awaitOwnedProcess(ownerArgument: String): OwnedProcess {
        var found: OwnedProcess? = null
        await("test-owned JVM identity") {
            found = findOwnedProcess(ownerArgument)
            found != null
        }
        return requireNotNull(found)
    }

    private fun findOwnedProcess(ownerArgument: String): OwnedProcess? {
        val matches =
            ProcessHandle.allProcesses().use { processes ->
                processes
                    .filter { handle ->
                        handle.isAlive &&
                            handle
                                .info()
                                .arguments()
                                .orElse(emptyArray())
                                .contains(ownerArgument)
                    }.toList()
            }
        check(matches.size <= 1) { "Multiple JVMs claim the unique test owner argument" }
        return matches.singleOrNull()?.let { OwnedProcess(it, it.info().startInstant().orElse(null)) }
    }

    private fun await(
        description: String,
        condition: () -> Boolean,
    ) {
        val future =
            CompletableFuture.runAsync {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                while (!condition()) {
                    check(System.nanoTime() < deadline) { "Timed out waiting for $description" }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50))
                }
            }
        PlatformTestUtil.waitForFuture(future, 50_000)
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
