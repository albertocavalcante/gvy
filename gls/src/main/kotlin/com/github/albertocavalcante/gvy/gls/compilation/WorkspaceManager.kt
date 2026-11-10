package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.gls.project.JenkinsCapabilities
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.utils.rethrowIfCancellationOrError
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.extension
import kotlin.io.path.isDirectory

/**
 * Manages the workspace environment for the compilation service.
 *
 * Responsibilities include:
 * - Tracking the workspace root directory.
 * - Managing the dependency classpath for compilation.
 * - Indexing and maintaining source root directories.
 * - Collecting and indexing Groovy source files within the workspace.
 *
 * This class serves as a central point for workspace state, allowing other components
 * of the compilation service to query and update workspace-related information.
 *
 * The configuration is one immutable [WorkspaceModel] replaced atomically (H1): updates are serialized and
 * build the next model before publishing it, and every getter reads a single published model. Callers that
 * need several values from the same configuration read [current] once.
 */
@Suppress("TooManyFunctions")
class WorkspaceManager(
    private val fingerprintComputer: (dependencies: List<Path>, sourceRoots: List<Path>) -> String =
        ConfigurationFingerprint::compute,
) {
    private val logger = KotlinLogging.logger {}

    private val model = AtomicReference(emptyModel(null))

    // Serializes writers; readers never take it.
    private val updateLock = Any()

    // Project strategy registry for project-type-specific functionality
    @Volatile
    private var strategyRegistry: ProjectStrategyRegistry? = null

    /** The current workspace configuration: one consistent snapshot of root, classpath, roots and sources. */
    fun current(): WorkspaceModel = model.get()

    /**
     * Sets the project strategy registry.
     * The registry manages project-type-specific strategies (Jenkins, Gradle, etc.).
     */
    fun setStrategyRegistry(registry: ProjectStrategyRegistry) {
        this.strategyRegistry = registry
        logger.info { "Strategy registry set with ${registry.activeStrategies.size} strategies" }
    }

    /**
     * Gets the project strategy registry.
     */
    fun getStrategyRegistry(): ProjectStrategyRegistry? = strategyRegistry

    /**
     * Gets Jenkins capabilities from the strategy registry.
     * This is the preferred way to access Jenkins-specific functionality.
     *
     * @return JenkinsCapabilities if a Jenkins strategy is active, null otherwise
     */
    fun getJenkinsCapabilities(): JenkinsCapabilities? = strategyRegistry?.findCapability<JenkinsCapabilities>()

    fun initializeWorkspace(workspaceRoot: Path) {
        logger.info { "Initializing workspace (non-blocking): $workspaceRoot" }
        update { current ->
            val roots = current.sourceRoots.ifEmpty { discoverSourceRoots(workspaceRoot) }
            model(workspaceRoot, current.classpath, roots, current.targets)
        }
    }

    fun updateDependencies(newDependencies: List<Path>): Boolean {
        var changed = false
        update { current ->
            if (classpathChanged(current.classpath, newDependencies)) {
                changed = true
                logger.info { "Updated dependency classpath with ${newDependencies.size} dependencies" }
                // A bare classpath update carries no target facts for it: stale evidence is dropped.
                WorkspaceModel(
                    current.root,
                    newDependencies,
                    current.sourceRoots,
                    current.sources,
                    computeFingerprint = fingerprintComputer,
                )
            } else {
                logger.debug { "Dependencies unchanged" }
                current
            }
        }
        return changed
    }

    /**
     * Publishes a new model. [targets] is the build-target evidence resolved together with
     * [dependencies], published in the same snapshot so per-document classpaths, fingerprints and
     * target facts always come from one resolution. The new evidence is always published (it carries
     * the new provenance); it counts as a change only when its facts differ, ignoring provenance.
     */
    fun updateWorkspaceModel(
        workspaceRoot: Path,
        dependencies: List<Path>,
        sourceDirectories: List<Path>,
        targets: TargetDependencyEvidence = TargetDependencyEvidence.Legacy,
    ): Boolean {
        var changed = false
        update { current ->
            val depsChanged = classpathChanged(current.classpath, dependencies)
            if (depsChanged) logger.info { "Updated dependency classpath with ${dependencies.size} dependencies" }
            val sourcesChanged = sourceDirectories.toSet() != current.sourceRoots.toSet()
            val roots = when {
                // Source roots are compared as a set: a reordering keeps the current order, so the
                // order-sensitive fingerprint agrees with the unchanged result (the classpath is ordered, #1313).
                sourceDirectories.isNotEmpty() && !sourcesChanged -> current.sourceRoots
                sourceDirectories.isNotEmpty() -> sourceDirectories.also {
                    logger.info { "Received ${it.distinct().size} source roots from build model" }
                }
                current.sourceRoots.isEmpty() -> discoverSourceRoots(workspaceRoot)
                else -> current.sourceRoots
            }
            val targetsChanged = !sameTargetFacts(current.targets, targets)
            changed = depsChanged || sourcesChanged || targetsChanged
            model(workspaceRoot, if (depsChanged) dependencies else current.classpath, roots, targets)
        }
        return changed
    }

    /**
     * Applies sources created or deleted after the model was built (#1422), publishing one new model.
     * A created path joins only when it is a Groovy source under a current source root, the rule the scan
     * uses; a deleted path leaves the list. Each root's sources stay sorted, as [scanSources] orders them.
     * Classpath, roots, targets and so the fingerprint are unchanged. Returns whether the list changed.
     */
    @Suppress("UnusedParameter")
    fun applySourceChanges(
        created: Collection<Path>,
        deleted: Collection<Path>,
        createdDirectories: Collection<Path> = emptyList(),
        deletedPrefixes: Collection<Path> = emptyList(),
    ): Boolean {
        var changed = false
        update { current ->
            val createdKeys = created.mapTo(HashSet()) { it.toAbsolutePath().normalize() }
            // A path both deleted and created in one notification (a replace-on-save) is decided by the disk.
            val gone = deleted.map { it.toAbsolutePath().normalize() }
                .filterTo(HashSet()) { it !in createdKeys || !Files.isRegularFile(it) }
            val known = current.sources.mapTo(HashSet()) { it.toAbsolutePath().normalize() }
            val roots = current.sourceRoots.map { it.toAbsolutePath().normalize() }
            val added = created.map { it.toAbsolutePath().normalize() }
                .filter { it !in known && it !in gone && isGroovySource(it) }
                .filter { path -> roots.any { root -> path.startsWith(root) } }
                .distinct()
            val kept = current.sources.filterNot { it.toAbsolutePath().normalize() in gone }
            val next = if (added.isEmpty()) kept else ordered(kept + added, roots)
            changed = next != current.sources
            if (!changed) {
                current
            } else {
                WorkspaceModel(
                    current.root,
                    current.classpath,
                    current.sourceRoots,
                    next,
                    current.targets,
                    computeFingerprint = fingerprintComputer,
                )
            }
        }
        return changed
    }

    /** Sources grouped by their first containing root in root order, each group sorted, as a scan yields them. */
    private fun ordered(sources: List<Path>, roots: List<Path>): List<Path> {
        val byRoot = sources.groupBy { source ->
            roots.indexOfFirst { source.toAbsolutePath().normalize().startsWith(it) }
        }
        return byRoot.keys.sortedBy { if (it < 0) Int.MAX_VALUE else it }.flatMap { byRoot.getValue(it).sorted() }
    }

    /** The scan's rule: a `.groovy` regular file, or a link to one. */
    private fun isGroovySource(path: Path): Boolean =
        path.extension.equals("groovy", ignoreCase = true) && Files.isRegularFile(path)

    /** Builds the next model outside any reader's view (including disk walks) and publishes it whole. */
    private inline fun update(next: (WorkspaceModel) -> WorkspaceModel) {
        synchronized(updateLock) { model.set(next(model.get())) }
    }

    private fun model(
        root: Path?,
        classpath: List<Path>,
        sourceRoots: List<Path>,
        targets: TargetDependencyEvidence = TargetDependencyEvidence.Legacy,
    ) = WorkspaceModel(
        root,
        classpath,
        sourceRoots,
        scanSources(sourceRoots.distinct()),
        targets,
        computeFingerprint = fingerprintComputer,
    )

    /** Structural comparison of target facts; provenance (generation) alone is not a change. */
    private fun sameTargetFacts(current: TargetDependencyEvidence, next: TargetDependencyEvidence): Boolean =
        targetFacts(current) == targetFacts(next)

    private fun targetFacts(evidence: TargetDependencyEvidence): List<List<Any?>>? =
        (evidence as? TargetDependencyEvidence.Scoped)?.snapshots?.map { snapshot ->
            listOf(snapshot.target, snapshot.role, snapshot.state, snapshot.artifacts, snapshot.sourceRoots)
        }

    private fun emptyModel(root: Path?) =
        WorkspaceModel(root, emptyList(), emptyList(), emptyList(), computeFingerprint = fingerprintComputer)

    /** Order matters: the JVM loads the first match, so the same jars reordered can resolve other classes. */
    private fun classpathChanged(current: List<Path>, next: List<Path>) = next != current

    private fun discoverSourceRoots(rootPath: Path): List<Path> {
        val sourceRoots = linkedSetOf<Path>()

        // Standard Maven/Gradle source directories
        val standardSourceDirs = listOf(
            rootPath.resolve("src/main/groovy"),
            rootPath.resolve("src/main/java"),
            rootPath.resolve("src/main/kotlin"),
            rootPath.resolve("src/test/groovy"),
        )

        val existingStandardDirs = standardSourceDirs.filter { Files.exists(it) && Files.isDirectory(it) }

        if (existingStandardDirs.isNotEmpty()) {
            existingStandardDirs.forEach(sourceRoots::add)
        }

        // Jenkins shared libraries and other layouts often use test/groovy directly.
        val extraSourceDirs = listOf(
            rootPath.resolve("test/groovy"),
        ).filter { Files.exists(it) && Files.isDirectory(it) }

        extraSourceDirs.forEach(sourceRoots::add)

        if (existingStandardDirs.isEmpty()) {
            // Jenkins Shared Library structure: bare src/ directory
            // TODO(#701): Improve heuristics for detecting source roots in Light Mode
            //   See: https://github.com/albertocavalcante/gvy/issues/701
            val bareSrcDir = rootPath.resolve("src")
            if (Files.exists(bareSrcDir) && Files.isDirectory(bareSrcDir)) {
                sourceRoots.add(bareSrcDir)
            }
        }

        // Add source roots from active strategies
        strategyRegistry?.activeStrategies?.forEach { strategy ->
            strategy.getSourceRoots().forEach { sourceRoot ->
                if (Files.exists(sourceRoot) && Files.isDirectory(sourceRoot)) {
                    sourceRoots.add(sourceRoot)
                    logger.debug { "Added source root from strategy '${strategy.id}': $sourceRoot" }
                }
            }
        }

        logger.info { "Indexed ${sourceRoots.size} source roots: ${sourceRoots.joinToString { it.toString() }}" }
        return sourceRoots.toList()
    }

    private fun scanSources(sourceRoots: List<Path>): List<Path> {
        val workspaceSources = sourceRoots.flatMap { sourceRoot ->
            if (!Files.exists(sourceRoot)) return@flatMap emptyList<Path>()
            collectGroovySources(sourceRoot).sorted()
        }

        logger.info { "Indexed ${workspaceSources.size} Groovy sources from workspace roots" }
        workspaceSources.forEach { logger.debug { "Indexed source: $it" } }
        return workspaceSources
    }

    fun getDependencyClasspath(): List<Path> = current().classpath
    fun getWorkspaceRoot(): Path? = current().root
    fun getSourceRoots(): List<Path> = current().sourceRoots
    fun getWorkspaceSources(): List<Path> = current().sources

    /**
     * Computes a fingerprint of the current workspace configuration.
     * Used for cache coherency - when this changes, cached compilations may be stale.
     * Issue #743: Normalize parse modes and cache authority for cross-file resolution
     */
    fun getConfigurationFingerprint(): String = current().fingerprint

    /**
     * Gets bounded workspace sources filtered by the given URIs.
     *
     * This enables O(k) compilation instead of O(n) by only including relevant files
     * based on dependency information from the DependencyGraph.
     *
     * Issue #743: Normalize parse modes and cache authority for cross-file resolution
     *
     * @param boundedUris Set of URIs to filter workspace sources by
     * @return List of paths that match the bounded URIs
     */
    fun getBoundedWorkspaceSources(boundedUris: Set<URI>): List<Path> =
        getBoundedWorkspaceSources(boundedUris, current())

    /** [getBoundedWorkspaceSources] against one [workspace] snapshot a compile already holds. */
    fun getBoundedWorkspaceSources(boundedUris: Set<URI>, workspace: WorkspaceModel): List<Path> {
        if (boundedUris.isEmpty()) return emptyList()

        val boundedPaths = boundedUris.mapNotNull { uri ->
            runCatching { Path.of(uri) }
                .getOrElse { throwable ->
                    rethrowIfCancellationOrError(throwable)
                    logger.debug(throwable) { "Failed to convert bounded URI to Path: $uri" }
                    null
                }
        }.toSet()

        return workspace.sources.filter { it in boundedPaths }
    }

    /**
     * Gets workspace source URIs for indexing.
     * Converts Path objects to URI objects for use in compilation service.
     */
    fun getWorkspaceSourceUris(): List<URI> = current().sources.mapNotNull { path ->
        runCatching { path.toUri() }
            .getOrElse { throwable ->
                rethrowIfCancellationOrError(throwable)
                logger.warn(throwable) { "Failed to convert path to URI: $path" }
                null
            }
    }

    /**
     * The classpath, fingerprint and target for one document from [workspace] alone (#1235).
     * A strategy that [overrides][ProjectStrategy.overridesClasspath] the document supplies its
     * classpath and keeps the workspace fingerprint, exactly as before; that single predicate also
     * drives [fingerprintFor], so reads and writes always use the same cache key.
     */
    fun compileContextFor(uri: URI, content: String): DocumentCompileContext =
        compileContextFor(uri, content, current())

    /** [compileContextFor] against one [workspace] snapshot a compile already holds. */
    fun compileContextFor(uri: URI, content: String, workspace: WorkspaceModel): DocumentCompileContext {
        overridingStrategy(uri)?.let { strategy ->
            val classpath = strategy.getClasspathForFile(uri, content, workspace.classpath) ?: workspace.classpath
            return DocumentCompileContext(classpath, workspace.fingerprint, TargetSelection.StrategyOverride)
        }
        return workspace.compileContextFor(documentPath(uri))
    }

    /** The fingerprint [compileContextFor] uses for [uri], without computing a classpath. */
    fun fingerprintFor(uri: URI): String = fingerprintFor(uri, current())

    /** [fingerprintFor] against one [workspace] snapshot. */
    fun fingerprintFor(uri: URI, workspace: WorkspaceModel): String = if (overridingStrategy(uri) != null) {
        workspace.fingerprint
    } else {
        workspace.compileContextFor(documentPath(uri)).fingerprint
    }

    /**
     * The classpath for a file: a strategy classpath when one applies, else the selected target's,
     * else the workspace dependency classpath. Same rules as [compileContextFor].
     */
    fun getClasspathForFile(uri: URI, content: String): List<Path> = getClasspathForFile(uri, content, current())

    /** [getClasspathForFile] against one [workspace] snapshot a compile already holds. */
    fun getClasspathForFile(uri: URI, content: String, workspace: WorkspaceModel): List<Path> =
        compileContextFor(uri, content, workspace).classpath

    private fun overridingStrategy(uri: URI): ProjectStrategy? =
        strategyRegistry?.activeStrategies?.firstOrNull { it.overridesClasspath(uri) }

    private fun documentPath(uri: URI): Path? =
        if (uri.scheme == "file") runCatching { Path.of(uri) }.getOrNull() else null
}

/**
 * The Groovy sources under [root], following file symbolic links as `Files.isRegularFile` does. An entry that
 * cannot be read or vanishes mid-walk (a branch switch, a build cleaning its output) is skipped instead of
 * aborting the whole workspace update (#1314). [beforeVisit] runs before each file is classified (a test seam).
 */
internal fun collectGroovySources(root: Path, beforeVisit: (Path) -> Unit = {}): List<Path> {
    val found = mutableListOf<Path>()
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                beforeVisit(file)
                // The walk does not follow links: a symlinked source is a link whose target is a regular file.
                val regular = attrs.isRegularFile || (attrs.isSymbolicLink && Files.isRegularFile(file))
                if (regular && file.extension.equals("groovy", ignoreCase = true)) found.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                skipped(file, exc)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                exc?.let { skipped(dir, it) }
                return FileVisitResult.CONTINUE
            }
        },
    )
    return found
}

private val scanLogger = KotlinLogging.logger {}

private fun skipped(entry: Path, exc: IOException) {
    if (exc is NoSuchFileException) {
        scanLogger.debug { "Skipped a source entry that vanished during the scan: $entry" }
    } else {
        scanLogger.warn { "Skipped an unreadable source entry: $entry (${exc.message})" }
    }
}
