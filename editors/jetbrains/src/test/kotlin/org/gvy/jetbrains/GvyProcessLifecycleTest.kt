// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package org.gvy.jetbrains

import com.intellij.execution.ExecutionException
import com.intellij.ide.impl.TrustedPaths
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.EmptyModuleType
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.BaseProjectDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.replaceService
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/** Exercises the IDE-owned process; the fixture peer is not a replacement for real GVY acceptance. */
class GvyProcessLifecycleTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    @org.junit.Test
    fun `test trust and settings changes stop old process and restart an already open file`() {
        ensureFixtureWorkingDirectory(project, testRootDisposable)
        val directory = Files.createTempDirectory("gvy-lifecycle-")
        val log = directory.resolve("events.log")
        val source = Files.writeString(Path.of(myFixture.tempDirPath, "Open.groovy"), "class Open {}")
        val jar = createFixtureJar(directory)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath()
        // The test application owns this temporary replacement; disposal restores the original service.
        ApplicationManager.getApplication().replaceService(TrustedPaths::class.java, TrustedPaths(), testRootDisposable)
        val settings = GvyProjectSettings.getInstance(project)
        val previousOptions = settings.state
        val manager = LspServerManager.getInstance(project)
        val runtime = project.getService(GvyProjectRuntime::class.java)
        val ownedProcesses = mutableListOf<OwnedProcess>()
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source))
        try {
            awaitEvent(directory, log) {
                ApplicationManager.getApplication().runReadAction<Boolean> {
                    ProjectFileIndex.getInstance(project).isInContent(file) &&
                        BaseProjectDirectories.getInstance(project).getBaseDirectoryFor(file) != null
                }
            }
            assertTrue(ProjectFileIndex.getInstance(project).isInContent(file))
            assertNotNull(BaseProjectDirectories.getInstance(project).getBaseDirectoryFor(file))
            val options =
                GvyProjectSettings.Options(
                    enabled = true,
                    javaExecutable = java.toString(),
                    serverJar = jar.toString(),
                    jvmArguments = "-Xmx64m\n-Dgvy.fixture.log=$log",
                )
            settings.loadState(options)
            TrustedProjects.setProjectTrusted(project, false)
            assertFalse(TrustedProjects.isProjectTrusted(project))
            FileEditorManager.getInstance(project).openFile(file, true)
            manager.startServersIfNeeded(GvyServerSupportProvider::class.java)
            UIUtil.dispatchAllInvocationEvents()
            assertTrue(manager.getServersForProvider(GvyServerSupportProvider::class.java).isEmpty())
            assertFalse("Untrusted project must not launch a process", Files.exists(log))
            var untrustedStarts = 0
            GvyServerSupportProvider().fileOpened(
                project,
                file,
                object : LspServerSupportProvider.LspServerStarter {
                    override fun ensureServerStarted(descriptor: LspServerDescriptor) {
                        untrustedStarts++
                    }
                },
            )
            assertEquals("Untrusted provider must not request a server", 0, untrustedStarts)
            assertThrows(ExecutionException::class.java) { GvyServerDescriptor(project, options).createCommandLine() }

            settings.loadState(previousOptions.copy(enabled = false))
            runtime.restart()
            TrustedProjects.setProjectTrusted(project, true)
            manager.startServersIfNeeded(GvyServerSupportProvider::class.java)
            UIUtil.dispatchAllInvocationEvents()
            assertTrue(manager.getServersForProvider(GvyServerSupportProvider::class.java).isEmpty())
            assertFalse("Disabled provider must not launch a process", Files.exists(log))
            var starts = 0
            GvyServerSupportProvider().fileOpened(
                project,
                file,
                object : LspServerSupportProvider.LspServerStarter {
                    override fun ensureServerStarted(descriptor: LspServerDescriptor) {
                        starts++
                    }
                },
            )
            assertEquals("Disabled provider must not request a server", 0, starts)

            settings.loadState(options)
            runtime.restart()
            awaitEvent(directory, log) { lines -> lines.count { it.startsWith("textDocument/didOpen ") } == 1 }
            val first = processForLastStart(log)
            ownedProcesses.add(OwnedProcess(first, first.info().startInstant().orElse(null)))
            assertTrue(first.isAlive)
            val stale = GvyServerDescriptor(project, options)

            TrustedProjects.setProjectTrusted(project, false)
            awaitEvent(directory, log) { lines ->
                !first.isAlive && "EXIT ${first.pid()}" in lines &&
                    manager.getServersForProvider(GvyServerSupportProvider::class.java).isEmpty()
            }
            assertThrows(ExecutionException::class.java) { stale.createCommandLine() }

            // The same editor remains open throughout the trust transition.
            assertTrue(FileEditorManager.getInstance(project).isFileOpen(file))
            TrustedProjects.setProjectTrusted(project, true)
            assertThrows(ExecutionException::class.java) { stale.createCommandLine() }
            awaitEvent(directory, log) { lines -> lines.count { it.startsWith("textDocument/didOpen ") } == 2 }
            val second = processForLastStart(log)
            ownedProcesses.add(OwnedProcess(second, second.info().startInstant().orElse(null)))
            assertTrue(first.pid() != second.pid())
            assertTrue(second.isAlive)

            val staleAfterRestore = GvyServerDescriptor(project, options)
            settings.loadState(options.copy(jvmArguments = options.jvmArguments + "\n-Dgvy.fixture.revision=2"))
            runtime.restart()
            assertThrows(ExecutionException::class.java) { staleAfterRestore.createCommandLine() }
            awaitEvent(directory, log) { lines ->
                lines.count { it.startsWith("textDocument/didOpen ") } == 3 && !second.isAlive &&
                    "EXIT ${second.pid()}" in lines
            }
            val third = processForLastStart(log)
            ownedProcesses.add(OwnedProcess(third, third.info().startInstant().orElse(null)))
            settings.loadState(options.copy(enabled = false))
            runtime.restart()
            awaitEvent(directory, log) { lines ->
                !third.isAlive && "EXIT ${third.pid()}" in lines &&
                    manager.getServersForProvider(GvyServerSupportProvider::class.java).isEmpty()
            }

            // Crash only the process owned by this fixture and race its shutdown with an explicit restart.
            settings.loadState(options)
            runtime.restart()
            awaitEvent(directory, log) { lines -> lines.count { it.startsWith("textDocument/didOpen ") } == 4 }
            val crashing = processForLastStart(log)
            val crashingStart = crashing.info().startInstant().orElse(null)
            ownedProcesses.add(OwnedProcess(crashing, crashingStart))
            val crashDescriptor = GvyServerDescriptor(project, options)
            val info = crashing.info()
            assertTrue(info.arguments().orElse(emptyArray()).contains("-Dgvy.fixture.log=$log"))
            assertTrue(crashingStart == null || crashingStart == info.startInstant().orElse(null))
            assertTrue(crashing.destroyForcibly())
            runtime.restart()
            assertThrows(ExecutionException::class.java) { crashDescriptor.createCommandLine() }
            awaitEvent(directory, log) { lines ->
                lines.count { it.startsWith("textDocument/didOpen ") } == 5 && !crashing.isAlive
            }
            val replacement = processForLastStart(log)
            ownedProcesses.add(OwnedProcess(replacement, replacement.info().startInstant().orElse(null)))
            assertTrue(replacement.isAlive)
            assertTrue(replacement.pid() != crashing.pid())
            settings.loadState(options.copy(enabled = false))
            runtime.restart()
            awaitEvent(directory, log) { lines ->
                !replacement.isAlive && "EXIT ${replacement.pid()}" in lines &&
                    manager.getServersForProvider(GvyServerSupportProvider::class.java).isEmpty()
            }
        } finally {
            settings.loadState(previousOptions.copy(enabled = false))
            runtime.restart()
            FileEditorManager.getInstance(project).closeFile(file)
            settings.loadState(previousOptions)
            // Identify every live process before cleanup, including starts that never initialized.
            if (Files.exists(log)) {
                Files.readAllLines(log).filter { it.startsWith("START ") }.forEach { line ->
                    val pid = line.substringAfter(' ').toLongOrNull() ?: return@forEach
                    ProcessHandle.of(pid).ifPresent { process ->
                        val info = process.info()
                        if (process.isAlive && ownedProcesses.none { it.handle.pid() == pid }) {
                            ownedProcesses.add(OwnedProcess(process, info.startInstant().orElse(null)))
                        }
                    }
                }
            }
            val unresolved = mutableListOf<Long>()
            for (owned in ownedProcesses.filter { it.handle.isAlive }) {
                val info = owned.handle.info()
                val args = info.arguments().orElse(emptyArray())
                val actualStart = info.startInstant().orElse(null)
                val identityMatches =
                    args.contains("-Dgvy.fixture.log=$log") &&
                        (owned.start == null || owned.start == actualStart)
                if (identityMatches) {
                    owned.handle.destroyForcibly()
                    owned.handle.onExit().get(5, TimeUnit.SECONDS)
                } else {
                    unresolved.add(owned.handle.pid())
                }
            }
            if (unresolved.isEmpty()) {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
            check(unresolved.isEmpty()) { "Could not verify fixture processes $unresolved; retained $directory" }
        }
    }

    @org.junit.Test
    fun `test closing one project leaves the other project and process running`() {
        ensureFixtureWorkingDirectory(project, testRootDisposable)
        val directory = Files.createTempDirectory("gvy-two-projects-")
        val firstLog = directory.resolve("first.log")
        val secondLog = directory.resolve("second.log")
        val secondRoot = Files.createDirectory(directory.resolve("second-project"))
        val firstSource = Files.writeString(Path.of(myFixture.tempDirPath, "First.groovy"), "class First {}")
        val secondSource = Files.writeString(secondRoot.resolve("Second.groovy"), "class Second {}")
        val jar = createFixtureJar(directory)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath()
        ApplicationManager.getApplication().replaceService(TrustedPaths::class.java, TrustedPaths(), testRootDisposable)
        val firstSettings = GvyProjectSettings.getInstance(project)
        val previousOptions = firstSettings.state
        val firstRuntime = project.getService(GvyProjectRuntime::class.java)
        val secondOwner = Disposer.newCheckedDisposable(testRootDisposable, "GVY second test project")
        val ownedProcesses = mutableListOf<OwnedProcess>()
        var secondProject: Project? = null
        val firstFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(firstSource))
        try {
            val otherProject = PlatformTestUtil.loadAndOpenProject(secondRoot, secondOwner)
            secondProject = otherProject
            val otherWorkDirectory = Path.of(requireNotNull(otherProject.basePath))
            check(otherWorkDirectory.normalize().startsWith(secondRoot.normalize())) {
                "The second project working directory must remain inside its owned root"
            }
            Files.createDirectories(otherWorkDirectory)
            assertTrue(Files.isDirectory(otherWorkDirectory))
            val otherFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(secondSource))
            val otherRoot = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(secondRoot))
            WriteAction.run<RuntimeException> {
                val module =
                    ModuleManager.getInstance(otherProject).newModule(
                        secondRoot.resolve("gvy-test.iml"),
                        EmptyModuleType.EMPTY_MODULE,
                    )
                val model = ModuleRootManager.getInstance(module).modifiableModel
                model.addContentEntry(otherRoot)
                model.commit()
            }
            awaitEvent(directory, firstLog) {
                ApplicationManager.getApplication().runReadAction<Boolean> {
                    ProjectFileIndex.getInstance(project).isInContent(firstFile) &&
                        BaseProjectDirectories.getInstance(project).getBaseDirectoryFor(firstFile) != null &&
                        ProjectFileIndex.getInstance(otherProject).isInContent(otherFile) &&
                        BaseProjectDirectories.getInstance(otherProject).getBaseDirectoryFor(otherFile) != null
                }
            }

            val secondSettings = GvyProjectSettings.getInstance(otherProject)
            val secondRuntime = otherProject.getService(GvyProjectRuntime::class.java)
            val firstOptions =
                GvyProjectSettings.Options(
                    enabled = true,
                    javaExecutable = java.toString(),
                    serverJar = jar.toString(),
                    jvmArguments = "-Xmx64m\n-Dgvy.fixture.log=$firstLog",
                )
            val secondOptions = firstOptions.copy(jvmArguments = "-Xmx64m\n-Dgvy.fixture.log=$secondLog")
            firstSettings.loadState(firstOptions)
            secondSettings.loadState(secondOptions)
            TrustedProjects.setProjectTrusted(project, true)
            TrustedProjects.setProjectTrusted(otherProject, false)
            assertTrue(TrustedProjects.isProjectTrusted(project))
            assertFalse(TrustedProjects.isProjectTrusted(otherProject))
            FileEditorManager.getInstance(project).openFile(firstFile, true)
            FileEditorManager.getInstance(otherProject).openFile(otherFile, true)
            firstRuntime.restart()
            secondRuntime.restart()
            awaitEvent(directory, firstLog) { lines -> lines.any { it.startsWith("textDocument/didOpen ") } }
            val first = processForLastStart(firstLog)
            ownedProcesses.add(OwnedProcess(first, first.info().startInstant().orElse(null)))
            assertTrue(first.isAlive)
            assertFalse("Untrusted second project must not start", Files.exists(secondLog))
            var rejectedStarts = 0
            GvyServerSupportProvider().fileOpened(
                otherProject,
                otherFile,
                object : LspServerSupportProvider.LspServerStarter {
                    override fun ensureServerStarted(descriptor: LspServerDescriptor) {
                        rejectedStarts++
                    }
                },
            )
            assertEquals("Untrusted second project must not request a server", 0, rejectedStarts)
            assertThrows(ExecutionException::class.java) {
                GvyServerDescriptor(otherProject, secondOptions).createCommandLine()
            }

            TrustedProjects.setProjectTrusted(otherProject, true)
            awaitEvent(directory, secondLog) { lines -> lines.any { it.startsWith("textDocument/didOpen ") } }
            val second = processForLastStart(secondLog)
            ownedProcesses.add(OwnedProcess(second, second.info().startInstant().orElse(null)))
            assertTrue(first.isAlive)
            assertTrue(second.isAlive)
            assertTrue(first.pid() != second.pid())
            assertEquals(firstOptions, firstSettings.state)
            assertEquals(secondOptions, secondSettings.state)

            Disposer.dispose(secondOwner)
            awaitEvent(directory, secondLog) { lines -> !second.isAlive && "EXIT ${second.pid()}" in lines }
            assertTrue("Closing the second project must not stop the first server", first.isAlive)
            assertTrue(FileEditorManager.getInstance(project).isFileOpen(firstFile))
            assertTrue(TrustedProjects.isProjectTrusted(project))
            assertEquals(firstOptions, firstSettings.state)

            firstSettings.loadState(firstOptions.copy(enabled = false))
            firstRuntime.restart()
            awaitEvent(directory, firstLog) { lines -> !first.isAlive && "EXIT ${first.pid()}" in lines }
        } finally {
            firstSettings.loadState(previousOptions.copy(enabled = false))
            firstRuntime.restart()
            FileEditorManager.getInstance(project).closeFile(firstFile)
            if (!secondOwner.isDisposed) Disposer.dispose(secondOwner)
            firstSettings.loadState(previousOptions)
            cleanupOwnedProcesses(
                directory,
                listOf(firstLog, secondLog),
                ownedProcesses,
                secondProject?.isDisposed != false,
            )
        }
    }

    private fun cleanupOwnedProcesses(
        directory: Path,
        logs: List<Path>,
        owned: MutableList<OwnedProcess>,
        projectClosed: Boolean,
    ) {
        for (log in logs.filter { Files.exists(it) }) {
            Files.readAllLines(log).filter { it.startsWith("START ") }.forEach { line ->
                val pid = line.substringAfter(' ').toLongOrNull() ?: return@forEach
                ProcessHandle.of(pid).ifPresent { process ->
                    if (process.isAlive && owned.none { it.handle.pid() == pid }) {
                        owned.add(OwnedProcess(process, process.info().startInstant().orElse(null)))
                    }
                }
            }
        }
        val unresolved = mutableListOf<Long>()
        for (process in owned.filter { it.handle.isAlive }) {
            val info = process.handle.info()
            val args = info.arguments().orElse(emptyArray())
            val logMatches = logs.any { args.contains("-Dgvy.fixture.log=$it") }
            val startMatches = process.start == null || process.start == info.startInstant().orElse(null)
            if (logMatches && startMatches) {
                process.handle.destroyForcibly()
                process.handle.onExit().get(5, TimeUnit.SECONDS)
            } else {
                unresolved.add(process.handle.pid())
            }
        }
        if (unresolved.isEmpty() && projectClosed) {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        check(unresolved.isEmpty() && projectClosed) {
            "Fixture project or processes remain unresolved ($unresolved); retained $directory"
        }
    }

    private data class OwnedProcess(
        val handle: ProcessHandle,
        val start: Instant?,
    )

    private fun createFixtureJar(directory: Path): Path {
        val manifest =
            Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name.MAIN_CLASS] = FixtureLanguageServer::class.java.name
            }
        val resource = FixtureLanguageServer::class.java.name.replace('.', '/') + ".class"
        val jar = directory.resolve("fixture server.jar")
        JarOutputStream(Files.newOutputStream(jar), manifest).use { output ->
            output.putNextEntry(JarEntry(resource))
            requireNotNull(FixtureLanguageServer::class.java.classLoader.getResourceAsStream(resource)).use {
                it.copyTo(output)
            }
            output.closeEntry()
        }
        return jar
    }

    private fun processForLastStart(log: Path): ProcessHandle {
        val line = Files.readAllLines(log).last { it.startsWith("START ") }
        return ProcessHandle.of(line.substringAfter(' ').toLong()).orElseThrow()
    }

    /** Watch the private log with a bounded wait; PlatformTestUtil pumps the IDE event queue. */
    private fun awaitEvent(
        directory: Path,
        log: Path,
        predicate: (List<String>) -> Boolean,
    ) {
        val future =
            CompletableFuture.runAsync {
                directory.fileSystem.newWatchService().use { watcher ->
                    directory.register(watcher, ENTRY_CREATE, ENTRY_MODIFY)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                    while (true) {
                        val lines = if (Files.exists(log)) Files.readAllLines(log) else emptyList()
                        if (predicate(lines)) return@runAsync
                        val remaining = deadline - System.nanoTime()
                        check(remaining > 0) { "Timed out waiting for fixture events: $lines" }
                        val interval = minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100))
                        val key = watcher.poll(interval, TimeUnit.NANOSECONDS)
                        if (key != null) {
                            key.pollEvents()
                            check(key.reset()) { "Fixture event directory is no longer available" }
                        }
                    }
                }
            }
        PlatformTestUtil.waitForFuture(future, 20_000)
    }
}
