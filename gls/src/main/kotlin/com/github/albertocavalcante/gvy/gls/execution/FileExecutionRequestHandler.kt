// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.execution

import com.github.albertocavalcante.gvy.build.BuildExecutableResolver
import com.github.albertocavalcante.gvy.build.gradle.GradleBuildFiles
import com.github.albertocavalcante.gvy.build.gradle.GradleExecutionContext
import com.github.albertocavalcante.gvy.build.gradle.GradleExecutionRuntime
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private data class CapturedDocument(val uri: URI, val path: Path, val snapshot: DocumentSnapshot)
private data class ExecutionRoute(val root: Path?, val requiresModel: Boolean, val knownSources: Set<Path>)
private data class RoutingFacts(val hasBuild: Boolean, val requiresModel: Boolean, val knownSources: Set<Path>)

/** Routes only trusted requests to the build model; cached membership is a hint, never launch authority. */
class FileExecutionRequestHandler(
    private val documents: DocumentProvider,
    private val workspaceFolders: () -> List<Path>,
    private val sourceRoots: () -> List<Path>,
    private val model: suspend (Path, Path?) -> List<GradleExecutionContext>?,
) {
    private val sourceHints = ConcurrentHashMap<Path, Set<Path>>()

    suspend fun prepare(params: PrepareFileExecutionParams): FileExecutionResponse =
        captureDocument(params).flatMap { document ->
            val hosted = hostedDslResponse(document.path.fileName.toString())
            if (hosted != null) {
                ExecutionStep.Stop(hosted)
            } else {
                routeRequest(params, document.path).flatMap { route ->
                    observeContexts(params, document.path, route).map { contexts ->
                        finishRequest(params, document, route, contexts)
                    }
                }
            }
        }.response()

    private fun finishRequest(
        params: PrepareFileExecutionParams,
        document: CapturedDocument,
        route: ExecutionRoute,
        contexts: List<FileExecutionContext>,
    ): FileExecutionResponse {
        if (documents.getSnapshot(document.uri) !== document.snapshot) {
            return unavailable("The document changed during preparation.")
        }
        val planningParams = if (params.contextId == "inspect-project") {
            params.copy(contextId = null, forceProject = true)
        } else {
            params
        }
        return FileExecutionPlanner().prepare(planningParams, document.snapshot.content, contexts, route.root)
    }

    private fun captureDocument(params: PrepareFileExecutionParams): ExecutionStep<CapturedDocument> =
        if (!params.trusted) {
            stop("Trust the workspace before execution.")
        } else {
            documentUri(params.uri).flatMap { uri ->
                currentSnapshot(uri, params.version).flatMap { snapshot ->
                    documentPath(uri).flatMap { source -> checkSavedDocument(uri, source, snapshot) }
                }
            }
        }

    private fun documentUri(value: String): ExecutionStep<URI> = try {
        ExecutionStep.Value(URI.create(value))
    } catch (_: IllegalArgumentException) {
        stop("Save the document to a local file first.")
    }

    private fun currentSnapshot(uri: URI, version: Int): ExecutionStep<DocumentSnapshot> {
        val snapshot = documents.getSnapshot(uri)
        return when {
            snapshot == null -> stop("Open and save the document first.")
            snapshot.version != version -> stop("The document version changed; run again.")
            else -> ExecutionStep.Value(snapshot)
        }
    }

    private fun documentPath(uri: URI): ExecutionStep<Path> = try {
        require(uri.scheme == "file")
        ExecutionStep.Value(executionPath(Path.of(uri)))
    } catch (_: IllegalArgumentException) {
        stop("Save the document to a local file first.")
    }

    private fun checkSavedDocument(
        uri: URI,
        source: Path,
        snapshot: DocumentSnapshot,
    ): ExecutionStep<CapturedDocument> = try {
        if (Files.readString(source) != snapshot.content) {
            stop("Save the current document before execution.")
        } else {
            ExecutionStep.Value(CapturedDocument(uri, source, snapshot))
        }
    } catch (_: java.io.IOException) {
        stop("Cannot read the saved file.")
    }

    private fun routeRequest(params: PrepareFileExecutionParams, source: Path): ExecutionStep<ExecutionRoute> {
        val root = workspaceForFile(source, workspaceFolders())
        val nested = root != null && params.contextId != "standalone" && nestedBuildBoundary(source, root) != null
        val projectRequested = params.forceProject || (params.contextId != null && params.contextId != "standalone")
        return when {
            nested -> stop("Open the nested or included build as its workspace, or use an explicit standalone profile.")
            root == null && projectRequested -> {
                val code = if (params.contextId != null) "invalidContext" else null
                stop("The file is outside the workspace; choose Standalone or open its project.", code)
            }
            else -> routeWithinWorkspace(params, source, root, projectRequested)
        }
    }

    private fun routeWithinWorkspace(
        params: PrepareFileExecutionParams,
        source: Path,
        root: Path?,
        projectRequested: Boolean,
    ): ExecutionStep<ExecutionRoute> {
        val facts = routingFacts(params, source, root, projectRequested)
        return when {
            shouldOfferContextChoice(params, source, root, facts) -> ExecutionStep.Stop(
                FileExecutionResponse(
                    kind = "choices",
                    choiceType = "context",
                    choices = listOf(
                        ExecutionChoice("standalone", "Standalone"),
                        ExecutionChoice("inspect-project", "Inspect Gradle Project…"),
                    ),
                ),
            )
            facts.requiresModel && params.savedScopeDirectory != root.toString() ->
                ExecutionStep.Stop(FileExecutionResponse("save", scopeDirectory = root.toString()))
            else -> ExecutionStep.Value(ExecutionRoute(root, facts.requiresModel, facts.knownSources))
        }
    }

    private fun shouldOfferContextChoice(
        params: PrepareFileExecutionParams,
        source: Path,
        root: Path?,
        facts: RoutingFacts,
    ): Boolean = !facts.requiresModel && facts.hasBuild && root != null && source.parent != root &&
        params.contextId == null && !params.forceProject

    private fun routingFacts(
        params: PrepareFileExecutionParams,
        source: Path,
        root: Path?,
        projectRequested: Boolean,
    ): RoutingFacts {
        val roots = sourceRoots().filter { candidate ->
            root != null && executionPath(candidate).let { it != root && it.startsWith(root) }
        }
        val conventional = root != null && isConventionalGroovyCandidate(source, root)
        val known = root?.let { sourceHints[it].orEmpty() }.orEmpty()
        val hasBuild = root != null && GradleBuildFiles.fileNames.any { Files.isRegularFile(root.resolve(it)) }
        val requiresModel = params.contextId != "standalone" && root != null &&
            (source in known || hasBuild) && needsExecutionModel(source, roots, known, projectRequested || conventional)
        return RoutingFacts(hasBuild, requiresModel, known)
    }

    private suspend fun observeContexts(
        params: PrepareFileExecutionParams,
        source: Path,
        route: ExecutionRoute,
    ): ExecutionStep<List<FileExecutionContext>> = when {
        !route.requiresModel -> ExecutionStep.Value(emptyList())
        "GRADLE_USER_HOME" in params.options.environment ->
            stop("GRADLE_USER_HOME cannot be overridden for project execution.")
        else -> projectJavaHome(params.options).fold(
            onSuccess = { javaHome -> observeProject(params, source, route, javaHome) },
            onFailure = { failure -> stop(failure.message ?: "Invalid project Java home.") },
        )
    }

    private suspend fun observeProject(
        params: PrepareFileExecutionParams,
        source: Path,
        route: ExecutionRoute,
        javaHome: Path?,
    ): ExecutionStep<List<FileExecutionContext>> {
        val contexts = route.root?.let { executionContexts(it, javaHome) }
            ?: return stop("The project execution model is unavailable; use Run with Project or Standalone.")
        val currentSources = contexts.flatMap { it.sourceFiles }.map(::executionPath).toSet()
        val missingOwner = previousOwnerMissing(source, route.knownSources, currentSources)
        return if (!params.forceProject && params.contextId == null && missingOwner) {
            stop("The previously selected project no longer owns this file; choose a context.", "invalidContext")
        } else {
            ExecutionStep.Value(contexts)
        }
    }

    private suspend fun executionContexts(root: Path, javaHome: Path?): List<FileExecutionContext>? {
        val contexts = model(root, javaHome) ?: return null
        val observed = contexts.flatMap { it.sourceFiles }.map(::executionPath).toSet()
        sourceHints.merge(root, observed) { previous, current -> previous + current }
        return contexts.map { context ->
            val runtime = context.runtime as? GradleExecutionRuntime.Established
            val wrapper = context.buildDirectory.resolve("gradlew")
            val executable = if (Files.exists(wrapper)) {
                wrapper.toString()
            } else {
                BuildExecutableResolver.resolveGradle(context.buildDirectory)
            }
            FileExecutionContext(
                id = context.targetId.toString(), label = "Project ${context.projectPath}/${context.sourceSet}",
                directory = context.projectDirectory,
                sourceFiles = context.sourceFiles,
                inputFiles = context.inputFiles,
                compileClasspath = context.compileClasspath.map(Path::toString),
                runtimeClasspath = context.runtimeClasspath.map(Path::toString),
                runtime = runtime?.jars?.map(Path::toString), runtimeLabel = runtime?.let { "Groovy ${it.version}" },
                generation = context.generation, scopeDirectory = context.buildDirectory, complete = context.complete,
                preparation = ExecutionProcess(
                    executable = executable,
                    arguments = listOf(context.preparationTask),
                    workingDirectory = context.buildDirectory.toString(),
                    environment = if (javaHome == null) emptyMap() else mapOf("JAVA_HOME" to javaHome.toString()),
                ),
            )
        }
    }

    private fun unavailable(reason: String, code: String? = null) =
        FileExecutionResponse("unavailable", reason = reason, reasonCode = code)

    private fun stop(reason: String, code: String? = null) = ExecutionStep.Stop(unavailable(reason, code))
}
