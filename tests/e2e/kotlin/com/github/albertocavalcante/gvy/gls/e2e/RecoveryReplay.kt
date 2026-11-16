// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.Version
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.WorkspaceFolder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shared launch qualification and sealed reporting for the real recovery replays. */
internal object RecoveryReplay {
    fun run(
        name: String,
        source: String,
        planned: List<String>,
        execute: (NamedObservationRecorder, MutableMap<String, JsonElement>) -> Unit,
    ) {
        val definition = ScenarioDefinition(Scenario(name, steps = emptyList()), source)
        val recorder = NamedObservationRecorder(planned, definition.source)
        val evidence = ConcurrentHashMap<String, JsonElement>()
        val execution = ScenarioExecution(Duration.ofSeconds(WHOLE_CASE_SECONDS))
        val failure = try {
            execution.run(name, recorder::executorClosed) {
                try {
                    execute(recorder, evidence)
                } finally {
                    recorder.workerFinished()
                }
            }
            null
        } catch (failure: AssertionError) {
            failure
        } catch (failure: Error) {
            throw failure
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            failure
        } catch (failure: Exception) {
            failure
        }
        val outcome = recorder.finish(failure)
        val snapshot = evidence.toMutableMap()
        val complete =
            outcome.cleanup == CleanupOutcome.Succeeded &&
                outcome.completion == ScenarioCompletion.Completed &&
                snapshot.containsKey("bindings")
        snapshot["complete"] = JsonPrimitive(complete)
        ScenarioOutcomeReport.write(definition, outcome, JsonObject(snapshot))
        ScenarioOutcomeJUnit.report(outcome)
    }

    fun launch(): PackagedLaunch {
        val jar = resolveColdRecoveryArtifact(checkNotNull(System.getProperty("groovy.lsp.e2e.execJar")))
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(jar).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return PackagedLaunch(
            javaExecutable = Path.of(System.getProperty("java.home"), "bin", javaName),
            jar = jar,
            expectedSha256 = digest.digest().joinToString("") { "%02x".format(it) },
            expectedSource = SourceStamp(Version.info.gitSha, Version.info.gitDirty != false),
            jvmSettings = JvmSettings(maxHeapMiB = 256, initialHeapMiB = 32),
        )
    }

    fun initialize(
        session: LanguageServerSession,
        workspace: ScenarioWorkspace,
        launch: PackagedLaunch,
        evidence: MutableMap<String, JsonElement>,
        workspaceName: String = "cold-recovery",
    ): InitializeResult {
        evidence["launch"] = JsonBridge.toJsonElement(session.runtimeEvidence)
        val initialized = await(
            session.server.initialize(
                InitializeParams().apply {
                    capabilities = ClientCapabilities()
                    workspaceFolders = listOf(WorkspaceFolder(workspace.rootUri, workspaceName))
                },
            ),
        )
        evidence["initialize"] = JsonBridge.toJsonElement(initialized)
        val runtime = JsonBridge.toJsonElement(await(session.groovyServer.runtimeFacts())).jsonObject
        evidence["runtimeFacts"] = runtime
        val build = runtime.getValue("build").jsonObject
        val process = runtime.getValue("runtime").jsonObject
        assertEquals(launch.expectedSource.commit, build.getValue("commit").jsonPrimitive.content)
        assertEquals(
            checkNotNull(session.process).pid().toString(),
            process.getValue("processId").jsonPrimitive.content,
        )
        assertEquals("false", build.getValue("dirty").jsonPrimitive.content)
        assertEquals("known", process.getValue("javaState").jsonPrimitive.content)
        assertEquals("known", process.getValue("heapState").jsonPrimitive.content)
        assertTrue(process.getValue("maxHeapBytes").jsonPrimitive.long in 1..256L * 1024 * 1024)
        val encoding = initialized.capabilities.positionEncoding
        require(encoding == null || encoding == "utf-16") { "Unsupported negotiated encoding" }
        session.server.initialized(InitializedParams())
        await(session.client.readyFuture)
        return initialized
    }

    fun <T> await(future: CompletableFuture<T>): T = try {
        future.get(REQUEST_SECONDS, TimeUnit.SECONDS)
    } finally {
        if (!future.isDone) future.cancel(true)
    }

    private const val REQUEST_SECONDS = 30L
    private const val WHOLE_CASE_SECONDS = 120L
}
