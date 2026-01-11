package com.github.groovylsp.bsp.maven.launcher

import ch.epfl.scala.bsp4j.BuildClient
import com.github.groovylsp.bsp.maven.server.MavenBuildServer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.impl.DefaultServiceLocator
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.nio.file.Path
import java.nio.file.Paths

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
     * @param args Command line arguments: [--local-repo <path>] <workspace-root>
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val config = parseArgs(args)
        logger.info { "Starting Maven BSP server for workspace: ${config.workspaceRoot}" }

        val server = createServer(config.workspaceRoot, config.localRepository)
        startServer(server)
    }

    /**
     * Creates a MavenBuildServer for the given workspace.
     *
     * @param workspaceRoot The root directory of the Maven project
     * @param localRepository Optional path to local Maven repository
     * @return Configured MavenBuildServer
     */
    fun createServer(workspaceRoot: Path, localRepository: Path? = null): MavenBuildServer {
        val repoSystem = createRepositorySystem()
        val localRepoPath = localRepository ?: defaultLocalRepository()

        return MavenBuildServer(workspaceRoot, repoSystem) {
            createSession(repoSystem, localRepoPath)
        }
    }

    /**
     * Creates the Aether RepositorySystem for dependency resolution.
     */
    fun createRepositorySystem(): RepositorySystem {
        val locator = MavenRepositorySystemUtils.newServiceLocator()

        locator.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
        locator.addService(TransporterFactory::class.java, FileTransporterFactory::class.java)
        locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)

        locator.setErrorHandler(object : DefaultServiceLocator.ErrorHandler() {
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
     * @return Configured session
     */
    fun createSession(repositorySystem: RepositorySystem, localRepoPath: Path): RepositorySystemSession {
        val session = MavenRepositorySystemUtils.newSession()

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
        var i = 0

        while (i < args.size) {
            when (args[i]) {
                "--local-repo" -> {
                    if (i + 1 < args.size) {
                        localRepository = Paths.get(args[i + 1])
                        i += 2
                    } else {
                        throw IllegalArgumentException("--local-repo requires a path argument")
                    }
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
        )
    }

    private fun startServer(server: MavenBuildServer) {
        val launcher = Launcher.Builder<BuildClient>()
            .setLocalService(server)
            .setRemoteInterface(BuildClient::class.java)
            .setInput(System.`in`)
            .setOutput(System.out)
            .create()

        val client = launcher.remoteProxy
        server.connect(client)

        logger.info { "Maven BSP server started, listening on stdin/stdout" }
        launcher.startListening().get()
    }

    private fun defaultLocalRepository(): Path {
        val userHome = System.getProperty("user.home")
        return Paths.get(userHome, ".m2", "repository")
    }
}

/**
 * Configuration parsed from command line arguments.
 */
data class LauncherConfig(val workspaceRoot: Path, val localRepository: Path? = null)
