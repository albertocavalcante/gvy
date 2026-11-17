// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package org.gvy.jetbrains.qa

import com.intellij.ide.impl.TrustedPaths
import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.InstalledPluginsState
import com.intellij.ide.plugins.PluginMainDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.cl.PluginClassLoader
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.EmptyModuleType
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.BaseProjectDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerManagerListener
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.testFramework.loadDescriptorInTest
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestApplicationManager
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.replaceService
import com.intellij.util.ThrowableRunnable
import org.junit.Test
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.jar.JarFile

/**
 * Dedicated-JVM native platform acceptance, selected only by testNativeUnload253/testNativeUnload262.
 * This exercises real plugin unloading and an IDE-owned real GVY process; it is not GUI/license qualification.
 * No production GVY class appears in this test's signatures, fields, imports or listener closures.
 */
class GvyDynamicUnloadTest : UsefulTestCase() {
    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        assertEquals("GVY must have only the sandbox directory discovery path", "", System.getProperty("plugin.path"))
        assertImplementationAbsentFromCore()
        TestApplicationManager.getInstance()
        super.runBare(testRunnable)
    }

    override fun runInDispatchThread(): Boolean = true

    @Test
    fun `test dynamic unload stops real server and reload restarts already open file`() {
        assertEquals("Use the dedicated native unload task", "true", System.getProperty("gvy.test.nativeUnload"))
        assertEquals("true", System.getProperty("idea.force.use.core.classloader"))
        assertEquals("false", System.getProperty("idea.use.core.classloader.for.plugin.path"))
        assertEquals(
            System.getProperty("gvy.test.nativeUnloadBaseline").toInt(),
            ApplicationInfo.getInstance().build.baselineVersion,
        )
        assertDynamicThread()
        assertFalse("A fresh test IDE must not require restart", InstalledPluginsState.getInstance().isRestartRequired)
        val jar = verifiedServerJar()
        val pluginPath = requireNotNull(PluginManagerCore.findPlugin(pluginId)).pluginPath.toRealPath()
        val initialPlugins = loadedPluginIds()
        val firstLoader = isolatedPluginLoader()
        val root = Files.createTempDirectory("gvy-native-unload-").toRealPath()
        val projectOwner = Disposer.newCheckedDisposable(testRootDisposable, "GVY native unload project")
        val ownerArgument = "-Dgvy.test.owner=${UUID.randomUUID()}"
        val events = ConcurrentLinkedQueue<ServerEvent>()
        val owned = mutableListOf<OwnedProcess>()
        var project: Project? = null
        var failure: Throwable? = null
        try {
            val source = Files.writeString(root.resolve("AlreadyOpen.groovy"), "class AlreadyOpen {}")
            val openedProject = PlatformTestUtil.loadAndOpenProject(root, projectOwner)
            project = openedProject
            check(Path.of(requireNotNull(openedProject.basePath)).toRealPath().startsWith(root))
            val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source))
            val contentRoot = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root))
            WriteAction.run<RuntimeException> {
                val module =
                    ModuleManager.getInstance(openedProject).newModule(
                        root.resolve("gvy-unload.iml"),
                        EmptyModuleType.EMPTY_MODULE,
                    )
                val model = ModuleRootManager.getInstance(module).modifiableModel
                model.addContentEntry(contentRoot)
                model.commit()
            }
            ApplicationManager.getApplication().replaceService(TrustedPaths::class.java, TrustedPaths(), projectOwner)
            val manager = LspServerManager.getInstance(openedProject)
            manager.addLspServerManagerListener(
                object : LspServerManagerListener {
                    override fun serverStateChanged(lspServer: LspServer) {
                        // Retain only plain values, never a server/descriptor/provider/classloader from this callback.
                        if (lspServer.providerClass.name == PROVIDER_NAME) {
                            events.add(ServerEvent(System.identityHashCode(lspServer), lspServer.state))
                        }
                    }
                },
                projectOwner,
            )
            await("physical content root") {
                ApplicationManager.getApplication().runReadAction<Boolean> {
                    ProjectFileIndex.getInstance(openedProject).isInContent(file) &&
                        BaseProjectDirectories.getInstance(openedProject).getBaseDirectoryFor(file) != null
                }
            }
            configureServer(openedProject, jar, ownerArgument)
            TrustedProjects.setProjectTrusted(openedProject, true)
            FileEditorManager.getInstance(openedProject).openFile(file, true)
            startServer(openedProject)
            val firstServer = awaitRunningServer(openedProject)
            val first = awaitOwnedProcess(ownerArgument, jar)
            owned.add(first)
            // Save through the platform before unload so the new service instance must recover persisted opt-in.
            PlatformTestUtil.saveProject(openedProject, true)
            println(
                "GVY native unload before: pid=${first.handle.pid()} start=${first.start} loader=${firstLoader.identity}",
            )

            unloadGvy()
            assertFalse("GVY must leave the loaded plugin set", PluginManagerCore.isLoaded(pluginId))
            assertEquals("Only GVY may unload", initialPlugins - pluginId.idString, loadedPluginIds())
            assertFalse(
                "Dynamic unload must not require IDE restart",
                InstalledPluginsState.getInstance().isRestartRequired,
            )
            assertNull("The old isolated plugin classloader must be collectible", firstLoader.reference.get())
            await("normal stop notification from IDE manager") {
                events.any { it.serverIdentity == firstServer && it.state == LspServerState.ShutdownNormally }
            }
            await("unloaded plugin's real JVM exit") { !first.handle.isAlive }
            assertFalse("The project must remain open during unload", openedProject.isDisposed)
            assertTrue(
                "The editor must remain open during unload",
                FileEditorManager.getInstance(openedProject).isFileOpen(file),
            )

            reloadGvy(pluginPath)
            val secondLoader = isolatedPluginLoader()
            assertEquals("Reload must restore the original plugin set", initialPlugins, loadedPluginIds())
            assertFalse("Reload must not require IDE restart", InstalledPluginsState.getInstance().isRestartRequired)
            // No reopen, restart, settings mutation or explicit manager start after load: EP addition owns this start.
            val secondServer = awaitRunningServer(openedProject)
            val second = awaitOwnedProcess(ownerArgument, jar)
            owned.add(second)
            assertTrue("Reload must create a new manager server", firstServer != secondServer)
            assertTrue("Reload must launch a new owned JVM PID", first.handle.pid() != second.handle.pid())
            assertTrue("Reloaded real server must be alive", second.handle.isAlive)
            assertTrue(FileEditorManager.getInstance(openedProject).isFileOpen(file))
            println(
                "GVY native unload after: pid=${second.handle.pid()} start=${second.start} loader=${secondLoader.identity}",
            )

            stopServer(openedProject)
            await("reloaded real JVM normal shutdown") {
                !second.handle.isAlive &&
                    events.any { it.serverIdentity == secondServer && it.state == LspServerState.ShutdownNormally }
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()

            fun cleanupStep(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(cleanupFailures::add)
            }
            cleanupStep {
                if (!PluginManagerCore.isLoaded(pluginId)) reloadGvy(pluginPath)
                if (project?.isDisposed == false) stopServer(requireNotNull(project))
            }
            cleanupStep {
                findOwnedProcess(ownerArgument, jar)?.let { process ->
                    if (owned.none { it.handle.pid() == process.handle.pid() }) owned.add(process)
                }
            }
            // Project closure is cleanup only; all unload/reload assertions above run with this same project open.
            cleanupStep {
                project?.takeUnless { it.isDisposed }?.let { openProject ->
                    val editorManager = FileEditorManager.getInstance(openProject)
                    editorManager.openFiles.forEach(editorManager::closeFile)
                }
            }
            cleanupStep { Disposer.dispose(projectOwner) }
            cleanupStep {
                findOwnedProcess(ownerArgument, jar)?.let { process ->
                    if (owned.none { it.handle.pid() == process.handle.pid() }) owned.add(process)
                }
            }
            for (process in owned) {
                cleanupStep { terminateOwnedProcess(process, ownerArgument, jar) }
            }
            cleanupStep {
                check(project?.isDisposed != false) { "Fixture project remains open; retained $root" }
                check(owned.none { it.handle.isAlive }) { "Fixture processes remain alive; retained $root" }
                assertTrue("Restore GVY before leaving the dedicated test JVM", PluginManagerCore.isLoaded(pluginId))
                check(cleanupFailures.isEmpty()) { "Cleanup incomplete; retained $root" }
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
            if (cleanupFailures.isNotEmpty()) {
                val primary = failure ?: cleanupFailures.first()
                cleanupFailures.filter { it !== primary }.forEach(primary::addSuppressed)
                if (failure == null) throw primary
            }
        }
    }

    private data class ServerEvent(
        val serverIdentity: Int,
        val state: LspServerState,
    )

    private data class LoaderIdentity(
        val reference: WeakReference<ClassLoader>,
        val identity: Int,
    )

    private data class OwnedProcess(
        val handle: ProcessHandle,
        val start: Instant,
    )

    private fun isolatedPluginLoader(): LoaderIdentity {
        assertImplementationAbsentFromCore()
        val descriptor = requireNotNull(PluginManagerCore.findPlugin(pluginId))
        val loader = requireNotNull(descriptor.pluginClassLoader)
        assertTrue("GVY must use an actual PluginClassLoader", loader is PluginClassLoader)
        assertNotSame("GVY must not be loaded by the test classloader", javaClass.classLoader, loader)
        assertNotSame(
            "GVY must not be loaded by the IDE core classloader",
            PluginManagerCore::class.java.classLoader,
            loader,
        )
        for (name in listOf(PROVIDER_NAME, SETTINGS_NAME, RUNTIME_NAME)) {
            assertSame(
                "$name must be defined by GVY's isolated loader",
                loader,
                Class.forName(name, false, loader).classLoader,
            )
        }
        return LoaderIdentity(WeakReference(loader), System.identityHashCode(loader))
    }

    private fun assertImplementationAbsentFromCore() {
        val parentLoaders = listOf(javaClass.classLoader, PluginManagerCore::class.java.classLoader)
        for (loader in parentLoaders) {
            for (name in listOf(PROVIDER_NAME, SETTINGS_NAME, RUNTIME_NAME)) {
                try {
                    Class.forName(name, false, loader)
                    fail("$name must not be available through the test or IDE core classloader")
                } catch (_: ClassNotFoundException) {
                    // The real plugin distribution must be the only source of GVY implementation classes.
                }
            }
        }
    }

    private fun loadedPluginIds(): Set<String> = PluginManagerCore.loadedPlugins.map { it.pluginId.idString }.toSet()

    private fun assertDynamicThread() {
        val app = ApplicationManager.getApplication()
        app.assertIsDispatchThread()
        check(
            !app.isWriteAccessAllowed,
        ) { "Call DynamicPlugins on EDT outside a write action; it owns its write actions" }
    }

    private fun unloadGvy() {
        assertDynamicThread()
        val descriptor = requireNotNull(PluginManagerCore.findPlugin(pluginId))
        check(descriptor is PluginMainDescriptor) { "GVY must have a main plugin descriptor" }
        assertTrue(
            "Platform must approve restart-free GVY unload",
            DynamicPlugins.allowLoadUnloadWithoutRestart(descriptor),
        )
        val options =
            DynamicPlugins.UnloadPluginOptions(
                disable = true,
                save = false,
                waitForClassloaderUnload = true,
                checkImplementationDetailDependencies = false,
                unloadWaitTimeout = 15_000,
            )
        assertTrue(
            "Platform must actually unload GVY",
            DynamicPlugins.unloadPlugins(listOf(descriptor), options = options),
        )
    }

    private fun reloadGvy(path: Path) {
        assertDynamicThread()
        PluginManagerCore.enablePlugin(pluginId)
        val descriptor = loadDescriptorInTest(path)
        check(descriptor.pluginId == pluginId) { "Refusing to load a different plugin" }
        assertTrue("Platform must reload GVY from the same distribution", DynamicPlugins.loadPlugin(descriptor))
    }

    // Reflection is confined to helpers that return before unload. Never cache Class, Method, service or state objects.
    private fun configureServer(
        project: Project,
        jar: Path,
        owner: String,
    ) {
        val type = pluginClass(SETTINGS_NAME)
        val service = requireNotNull(project.getService(type))
        val state = type.getMethod("getState").invoke(service)
        val stateType = state.javaClass
        stateType.getMethod("setEnabled", Boolean::class.javaPrimitiveType).invoke(state, true)
        stateType.getMethod("setJavaExecutable", String::class.java).invoke(
            state,
            javaExecutable().toString(),
        )
        stateType.getMethod("setServerJar", String::class.java).invoke(state, jar.toString())
        stateType.getMethod("setJvmArguments", String::class.java).invoke(state, "-Xmx512m\n$owner")
        type.getMethod("loadState", stateType).invoke(service, state)
    }

    private fun pluginClass(name: String): Class<*> =
        Class.forName(name, true, requireNotNull(PluginManagerCore.findPlugin(pluginId)).pluginClassLoader)

    private fun startServer(project: Project) {
        val provider = pluginClass(PROVIDER_NAME).asSubclass(LspServerSupportProvider::class.java)
        LspServerManager.getInstance(project).startServersIfNeeded(provider)
    }

    private fun stopServer(project: Project) {
        val type = pluginClass(SETTINGS_NAME)
        val service = requireNotNull(project.getService(type))
        val state = type.getMethod("getState").invoke(service)
        state.javaClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType).invoke(state, false)
        type.getMethod("loadState", state.javaClass).invoke(service, state)
        val provider = pluginClass(PROVIDER_NAME).asSubclass(LspServerSupportProvider::class.java)
        LspServerManager.getInstance(project).stopServers(provider)
    }

    private fun awaitRunningServer(project: Project): Int {
        var identity = 0
        await("real GVY Running with initialize result") {
            val provider = pluginClass(PROVIDER_NAME).asSubclass(LspServerSupportProvider::class.java)
            val server = LspServerManager.getInstance(project).getServersForProvider(provider).singleOrNull()
            if (server?.state == LspServerState.Running && server.initializeResult != null) {
                val capabilities = requireNotNull(server.initializeResult).capabilities
                assertNotNull("Real server must advertise synchronization", capabilities.textDocumentSync)
                assertNotNull("Real server must advertise completion", capabilities.completionProvider)
                assertNotNull("Real server must advertise hover", capabilities.hoverProvider)
                identity = System.identityHashCode(server)
                true
            } else {
                false
            }
        }
        return identity
    }

    private fun awaitOwnedProcess(
        owner: String,
        jar: Path,
    ): OwnedProcess {
        var process: OwnedProcess? = null
        await("unique real server JVM identity") {
            process = findOwnedProcess(owner, jar)
            process != null
        }
        return requireNotNull(process)
    }

    private fun findOwnedProcess(
        owner: String,
        jar: Path,
    ): OwnedProcess? {
        val matches =
            ProcessHandle.allProcesses().use { processes ->
                processes
                    .filter {
                        it.isAlive &&
                            it
                                .info()
                                .arguments()
                                .orElse(emptyArray())
                                .contains(owner)
                    }.toList()
            }
        check(matches.size <= 1) { "Multiple processes claim the unique test owner argument" }
        return matches.singleOrNull()?.let { process ->
            val info = process.info()
            val command = info.command().orElse(null)
            val start = info.startInstant().orElse(null)
            val arguments = info.arguments().orElse(emptyArray()).toList()
            // A normally stopping JVM may exit between discovery and this metadata snapshot.
            if (!process.isAlive) return@let null
            check(command != null && start != null) { "Cannot inspect live test-owned PID ${process.pid()}" }
            check(Path.of(command).toRealPath() == javaExecutable())
            check(owner in arguments)
            check(arguments.windowed(2).any { it == listOf("-jar", jar.toString()) })
            OwnedProcess(process, start)
        }
    }

    private fun terminateOwnedProcess(
        process: OwnedProcess,
        owner: String,
        jar: Path,
    ) {
        if (!process.handle.isAlive) return
        val info = process.handle.info()
        val command = info.command().orElse(null)
        val args = info.arguments().orElse(emptyArray()).toList()
        if (!process.handle.isAlive) return
        val matchesIdentity =
            info.startInstant().orElse(null) == process.start &&
                command != null &&
                Path.of(command).toRealPath() == javaExecutable() &&
                owner in args &&
                args.windowed(2).any { it == listOf("-jar", jar.toString()) }
        check(matchesIdentity) {
            "Cannot revalidate test-owned PID ${process.handle.pid()}; refusing termination"
        }
        process.handle.destroyForcibly()
        process.handle.onExit().get(5, TimeUnit.SECONDS)
    }

    private fun javaExecutable(): Path = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath()

    private fun verifiedServerJar(): Path {
        val jar = Path.of(requireNotNull(System.getProperty("gvy.test.serverJar"))).toRealPath()
        val hash = requireNotNull(System.getProperty("gvy.test.serverSha256"))
        val commit = requireNotNull(System.getProperty("gvy.test.serverCommit"))
        check(hash.matches(Regex("[0-9a-f]{64}")) && commit.matches(Regex("[0-9a-f]{40}")))
        check(Files.isRegularFile(jar) && Files.isReadable(jar))
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(jar).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        assertEquals("Pinned real server digest", hash, HexFormat.of().formatHex(digest.digest()))
        JarFile(jar.toFile()).use { archive ->
            val entry = requireNotNull(archive.getJarEntry("version.properties"))
            val provenance = Properties()
            archive.getInputStream(entry).use(provenance::load)
            assertEquals("Pinned server source", commit, provenance.getProperty("gitSha"))
            assertEquals("Clean server source", "false", provenance.getProperty("gitDirty"))
        }
        println("GVY native unload pairing: source=$commit sha256=$hash jar=$jar")
        return jar
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

    companion object {
        private val pluginId = PluginId.getId("org.gvy.jetbrains")
        private const val PROVIDER_NAME = "org.gvy.jetbrains.GvyServerSupportProvider"
        private const val SETTINGS_NAME = "org.gvy.jetbrains.GvyProjectSettings"
        private const val RUNTIME_NAME = "org.gvy.jetbrains.GvyProjectRuntime"
    }
}
