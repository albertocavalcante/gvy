// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.groovylsp.bsp.maven.launcher

import ch.epfl.scala.bsp4j.BuildClient
import com.github.groovylsp.bsp.maven.server.MavenBuildServer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Main entry point for the Maven BSP server.
 *
 * Starts the BSP server listening on stdin/stdout for communication with IDEs.
 */
object MavenBspLauncher {

    private val logger = KotlinLogging.logger {}

    /**
     * Main entry point.
     *
     * @param args Command line arguments: [--local-repo <path>] [--offline] <workspace-root>
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val config = parseArgs(args)
        logger.info { "Starting Maven BSP server for workspace: ${config.workspaceRoot}" }

        val server = createServer(config.workspaceRoot, config.localRepository, config.offline)
        // The executable owns its exit: stdin may stay open after build/exit, so returning is not enough.
        exitProcess(serve(server, System.`in`, System.out))
    }

    /**
     * Creates a MavenBuildServer for the given workspace.
     *
     * @param workspaceRoot The root directory of the Maven project
     * @param localRepository Optional path to local Maven repository
     * @param offline Resolve from the local repository only, never contacting a remote repository
     * @return Configured MavenBuildServer
     */
    fun createServer(workspaceRoot: Path, localRepository: Path? = null, offline: Boolean = false): MavenBuildServer {
        val repoSystem = createRepositorySystem()
        val localRepoPath = localRepository ?: defaultLocalRepository()

        return MavenBuildServer(workspaceRoot, repoSystem) {
            createSession(repoSystem, localRepoPath, offline)
        }
    }

    /**
     * Creates the Aether RepositorySystem for dependency resolution.
     */
    // TODO(#1600): Migrate Resolver bootstrap after resolution/failure compatibility coverage.
    @Suppress("DEPRECATION") // MavenRepositorySystemUtils still constructs the legacy Resolver service locator.
    fun createRepositorySystem(): RepositorySystem {
        val locator = MavenRepositorySystemUtils.newServiceLocator()

        locator.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
        locator.addService(TransporterFactory::class.java, FileTransporterFactory::class.java)
        locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)

        locator.setErrorHandler(object : org.eclipse.aether.impl.DefaultServiceLocator.ErrorHandler() {
            override fun serviceCreationFailed(type: Class<*>?, impl: Class<*>?, exception: Throwable?) {
                logger.error(exception) { "Service creation failed: $type -> $impl" }
            }
        })

        return locator.getService(RepositorySystem::class.java)
    }

    /**
     * Creates a RepositorySystemSession for dependency resolution.
     *
     * @param repositorySystem The Aether repository system
     * @param localRepoPath Path to the local Maven repository
     * @param offline When true, an artifact missing from the local repository fails at once instead of waiting
     *   on a remote repository, whose latency is outside the caller's control
     * @return Configured session
     */
    fun createSession(
        repositorySystem: RepositorySystem,
        localRepoPath: Path,
        offline: Boolean = false,
    ): RepositorySystemSession {
        val session = MavenRepositorySystemUtils.newSession()
        session.isOffline = offline

        val localRepo = LocalRepository(localRepoPath.toFile())
        session.localRepositoryManager = repositorySystem.newLocalRepositoryManager(session, localRepo)

        return session
    }

    /**
     * Parses command line arguments into configuration.
     *
     * @param args Command line arguments
     * @return Parsed configuration
     */
    fun parseArgs(args: Array<String>): LauncherConfig {
        var workspaceRoot: Path? = null
        var localRepository: Path? = null
        var offline = false
        var i = 0

        while (i < args.size) {
            when (args[i]) {
                "--local-repo" -> {
                    require(i + 1 < args.size) { "--local-repo requires a path argument" }
                    localRepository = Paths.get(args[i + 1])
                    i += 2
                }
                "--offline" -> {
                    offline = true
                    i++
                }
                else -> {
                    workspaceRoot = Paths.get(args[i])
                    i++
                }
            }
        }

        return LauncherConfig(
            workspaceRoot = workspaceRoot ?: Paths.get(".").toAbsolutePath().normalize(),
            localRepository = localRepository,
            offline = offline,
        )
    }

    /**
     * Serves BSP over [input]/[output] until the client sends `build/exit` or closes [input], then stops the
     * listener threads and returns the process exit code: 0 if `build/shutdown` was received first, 1 otherwise
     * (exit before shutdown, or end of input without shutdown). Requests still in flight at that point are abandoned.
     */
    internal fun serve(server: MavenBuildServer, input: InputStream, output: OutputStream): Int {
        val threadCount = AtomicInteger()
        val executor = Executors.newCachedThreadPool { task ->
            Thread(task, "maven-bsp-rpc-${threadCount.incrementAndGet()}").apply { isDaemon = true }
        }
        val exitRequested = CompletableFuture<Unit>()
        server.onExit { exitRequested.complete(Unit) }
        try {
            val launcher = Launcher.Builder<BuildClient>()
                .setLocalService(server)
                .setRemoteInterface(BuildClient::class.java)
                .setInput(input)
                .setOutput(output)
                .setExecutorService(executor)
                .create()
            server.connect(launcher.remoteProxy)

            logger.info { "Maven BSP server started, listening on stdin/stdout" }
            // startListening() returns a plain Future, so a waiter bridges end of input into the same signal.
            val listening = launcher.startListening()
            executor.execute {
                runCatching { listening.get() }
                exitRequested.complete(Unit)
            }
            exitRequested.get()
        } finally {
            executor.shutdownNow()
        }
        return if (server.shutdownRequested) 0 else 1
    }

    private fun defaultLocalRepository(): Path {
        val userHome = System.getProperty("user.home")
        return Paths.get(userHome, ".m2", "repository")
    }
}

/**
 * Configuration parsed from command line arguments.
 */
data class LauncherConfig(val workspaceRoot: Path, val localRepository: Path? = null, val offline: Boolean = false)
