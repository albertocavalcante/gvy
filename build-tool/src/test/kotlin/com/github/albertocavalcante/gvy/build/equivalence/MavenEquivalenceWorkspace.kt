// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.equivalence

import ch.epfl.scala.bsp4j.DependencyModule
import ch.epfl.scala.bsp4j.MavenDependencyModule
import com.github.albertocavalcante.gvy.build.BuildExecutableResolver
import com.github.albertocavalcante.gvy.build.bsp.BspClient
import com.github.albertocavalcante.gvy.build.bsp.BspConnectionDetails
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/** Where the build placed the standalone server JAR and the reused fixtures; both builds pass them as properties. */
internal object MavenBspHarnessInputs {
    private const val SERVER_JAR_PROPERTY = "gvy.mavenBsp.serverJar"
    private const val FIXTURES_PROPERTY = "gvy.mavenBsp.fixtures"

    /** Bazel has no directory labels, so it passes `fixtures/single-module/pom.xml` instead of the directory. */
    private const val FIXTURES_MARKER_PROPERTY = "gvy.mavenBsp.fixtures.marker"

    val serverJar: Path
        get() {
            val jar = Paths.get(requiredProperty(SERVER_JAR_PROPERTY)).toAbsolutePath()
            check(jar.isRegularFile()) { "-D$SERVER_JAR_PROPERTY does not name a file: $jar" }
            return jar
        }

    val fixtures: Path
        get() {
            val directory = System.getProperty(FIXTURES_PROPERTY)?.let { Paths.get(it) }
                ?: Paths.get(requiredProperty(FIXTURES_MARKER_PROPERTY)).toAbsolutePath().parent.parent
            check(directory.isDirectory()) { "Maven BSP fixtures are not a directory: $directory" }
            return directory.toAbsolutePath()
        }

    private fun requiredProperty(name: String): String = checkNotNull(System.getProperty(name)) {
        "-D$name is not set. Run this test through `:build-tool:test` or `//build-tool:build-tool_test`; both " +
            "build the standalone Maven BSP server and pass its location."
    }
}

/** A Maven artifact in the fixture repository: a POM and an empty JAR, recorded as installed locally. */
internal data class FixtureArtifact(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val dependencies: List<FixtureArtifact> = emptyList(),
)

/**
 * A copy of a reused fixture plus everything both imports need to resolve it without a network:
 * a local repository holding every artifact the fixture uses, a `.bsp` connection file that launches the
 * standalone server as a child process, and a Maven wrapper that records and refuses any CLI fallback.
 */
internal class MavenEquivalenceWorkspace private constructor(
    val root: Path,
    val localRepository: Path,
    private val scratch: Path,
) {
    val paths = PathNormalizer(mapOf(WORKSPACE to root, REPOSITORY to localRepository))

    /** True when the in-process import fell back to running Maven, which would need the network. */
    val mavenCliInvoked: Boolean
        get() = root.resolve(MAVEN_CLI_MARKER).exists()

    /** Talks to the server directly, through the same client, to show what the protocol carried. */
    fun captureProtocol(): ProtocolCapture {
        val connection = checkNotNull(BspConnectionDetails.findFirst(root)) { "No connection file in $root/.bsp" }
        return BspClient(connection, root).use { client ->
            if (!client.connect()) {
                return@use ProtocolCapture.Unavailable("build/initialize failed", serverStartupOutput(connection))
            }
            val targets = client.workspaceBuildTargets()?.targets
                ?: return@use ProtocolCapture.Unavailable("workspace/buildTargets failed", "")
            val ids = targets.map { it.id }
            val sources = client.buildTargetSources(ids)?.items
                ?: return@use ProtocolCapture.Unavailable("buildTarget/sources failed", "")
            val modules = client.buildTargetDependencyModules(ids)?.items
                ?: return@use ProtocolCapture.Unavailable("buildTarget/dependencyModules failed", "")

            val sourcesByTarget = sources.associate { item ->
                item.target.uri to item.sources.map { source -> paths.normalizeUri(source.uri) }
            }
            val modulesByTarget = modules.associate { item ->
                item.target.uri to item.modules.map(::protocolModule)
            }
            ProtocolCapture.Connected(
                targets.map { target ->
                    ProtocolTarget(
                        id = target.id.uri,
                        sources = sourcesByTarget[target.id.uri].orEmpty(),
                        dependencyModules = modulesByTarget[target.id.uri].orEmpty(),
                    )
                }.sortedBy { it.id },
            )
        }
    }

    private fun protocolModule(module: DependencyModule): ProtocolModule {
        val artifacts = when (val data = module.data) {
            null -> emptyList()
            // Over a real stdio connection `data` arrives as JSON; a typed object would mean the server ran in-process.
            is JsonElement -> gson.fromJson(data, MavenDependencyModule::class.java)?.artifacts.orEmpty()
            else -> error("Dependency module data is ${data.javaClass.name}, not JSON from a child process")
        }
        return ProtocolModule(
            coordinates = "${module.name}:${module.version}",
            dataKind = module.dataKind,
            artifacts = artifacts.map { artifact ->
                ProtocolArtifact(path = paths.normalizeUri(artifact.uri), classifier = artifact.classifier)
            },
        )
    }

    /** Starts the server once more with closed input and returns what it printed, to explain a failed connection. */
    private fun serverStartupOutput(connection: BspConnectionDetails): String {
        val log = scratch.resolve("server-startup.log")
        val process = ProcessBuilder(connection.argv)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
        try {
            process.outputStream.close()
            if (!process.waitFor(STARTUP_DIAGNOSTIC_SECONDS, TimeUnit.SECONDS)) {
                return "server still running after ${STARTUP_DIAGNOSTIC_SECONDS}s with closed input: " +
                    log.readText().take(DIAGNOSTIC_CHARS)
            }
            return "exit ${process.exitValue()}: " + log.readText().take(DIAGNOSTIC_CHARS)
        } finally {
            process.destroyForcibly()
        }
    }

    companion object {
        const val WORKSPACE = "<workspace>"
        const val REPOSITORY = "<repo>"

        private const val MAVEN_CLI_MARKER = ".maven-cli-invoked"
        private const val STARTUP_DIAGNOSTIC_SECONDS = 20L
        private const val DIAGNOSTIC_CHARS = 4_000
        private val gson = Gson()

        /**
         * Copies [fixture] into [scratch], creates the source and output [directories] the case needs (Git cannot
         * hold empty directories, so the reused fixtures carry only POMs), and writes the repository and launchers.
         */
        fun create(
            scratch: Path,
            fixture: String,
            directories: List<String>,
            artifacts: List<FixtureArtifact>,
        ): MavenEquivalenceWorkspace {
            val fixtureDirectory = MavenBspHarnessInputs.fixtures.resolve(fixture)
            check(fixtureDirectory.resolve("pom.xml").isRegularFile()) { "No fixture POM in $fixtureDirectory" }

            val root = scratch.resolve("workspace")
            fixtureDirectory.toFile().copyRecursively(root.toFile())
            directories.forEach { root.resolve(it).createDirectories() }

            val localRepository = scratch.resolve("repository")
            artifacts.forEach { writeArtifact(localRepository, it) }

            writeMavenCliGuard(root)
            writeConnectionFile(root, localRepository)
            return MavenEquivalenceWorkspace(root, localRepository, scratch)
        }

        private fun writeArtifact(repository: Path, artifact: FixtureArtifact) {
            val baseName = "${artifact.artifactId}-${artifact.version}"
            val directory = repository
                .resolve(artifact.groupId.replace('.', '/'))
                .resolve(artifact.artifactId)
                .resolve(artifact.version)
                .createDirectories()
            directory.resolve("$baseName.pom").writeText(pom(artifact))
            directory.resolve("$baseName.jar").writeBytes(ByteArray(0))
            // An empty repository ID is what `mvn install` writes: available to every request, never re-fetched.
            directory.resolve("_remote.repositories").writeText("$baseName.jar>=\n$baseName.pom>=\n")
        }

        private fun pom(artifact: FixtureArtifact): String {
            val dependencies = artifact.dependencies.joinToString(separator = "") { dependency ->
                "<dependency><groupId>${dependency.groupId}</groupId>" +
                    "<artifactId>${dependency.artifactId}</artifactId>" +
                    "<version>${dependency.version}</version></dependency>"
            }
            return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>${artifact.groupId}</groupId>
                  <artifactId>${artifact.artifactId}</artifactId>
                  <version>${artifact.version}</version>
                  <dependencies>$dependencies</dependencies>
                </project>
            """.trimIndent()
        }

        /** The in-process import prefers a workspace wrapper; this one records the call and fails at once. */
        private fun writeMavenCliGuard(root: Path) {
            val unixWrapper = root.resolve("mvnw")
            unixWrapper.writeText("#!/bin/sh\necho \"\$*\" >> \"\$(dirname \"\$0\")/$MAVEN_CLI_MARKER\"\nexit 1\n")
            check(unixWrapper.toFile().setExecutable(true)) { "Could not make $unixWrapper executable" }
            root.resolve("mvnw.cmd").writeText("@echo %*>> \"%~dp0$MAVEN_CLI_MARKER\"\r\n@exit /b 1\r\n")
        }

        /**
         * The argv matches the file `MavenBspConnectionJson` generates (`java -jar <server> <workspace>`), except for
         * an absolute JVM, a heap cap for this shared test JVM's child, and the local repository both imports use.
         */
        private fun writeConnectionFile(root: Path, localRepository: Path) {
            val javaName = if (BuildExecutableResolver.isWindows) "java.exe" else "java"
            val argv = listOf(
                Paths.get(System.getProperty("java.home"), "bin", javaName).toString(),
                "-Xmx256m",
                "-jar",
                MavenBspHarnessInputs.serverJar.toString(),
                "--local-repo",
                localRepository.toString(),
                root.toString(),
            )
            val connection = buildJsonObject {
                put("name", "Maven BSP")
                put("version", "0.1.0")
                put("bspVersion", "2.1.0")
                putJsonArray("languages") {
                    add("groovy")
                    add("java")
                }
                putJsonArray("argv") { argv.forEach { add(it) } }
            }
            root.resolve(".bsp").createDirectories().resolve("maven-bsp.json").writeText(connection.toString())
        }
    }
}

/** What the server sent, per target, before `BspBuildTool` flattened it. */
internal sealed interface ProtocolCapture {
    data class Connected(val targets: List<ProtocolTarget>) : ProtocolCapture

    data class Unavailable(val reason: String, val serverOutput: String) : ProtocolCapture
}

internal data class ProtocolTarget(
    val id: String,
    val sources: List<String>,
    val dependencyModules: List<ProtocolModule>,
)

internal data class ProtocolModule(
    val coordinates: String,
    val dataKind: String?,
    val artifacts: List<ProtocolArtifact>,
)

internal data class ProtocolArtifact(val path: String, val classifier: String?)

/**
 * Snapshots the test JVM's child processes, so processes a test started can be found and killed afterwards
 * even when the test failed before closing its client.
 */
internal class ChildProcessGuard {
    private val existing: Set<Long> = descendants().map { it.pid() }.toSet()

    /** Waits up to [grace] for new descendants to exit, kills the rest, and describes the ones it had to kill. */
    fun killLeftovers(grace: Duration): List<String> {
        val deadline = System.nanoTime() + grace.toNanos()
        var leftovers = started()
        while (leftovers.isNotEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
            leftovers = started()
        }
        leftovers.forEach { it.destroyForcibly() }
        return leftovers.map { process -> "${process.pid()} ${process.info().commandLine().orElse("<unknown>")}" }
    }

    private fun started(): List<ProcessHandle> = descendants().filter { it.isAlive && it.pid() !in existing }

    private fun descendants(): List<ProcessHandle> = ProcessHandle.current().descendants().toList()

    private companion object {
        const val POLL_MILLIS = 100L
    }
}
