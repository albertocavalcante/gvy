package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.gls.project.JenkinsCapabilities
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.utils.ALWAYS_SKIPPED_DIRECTORIES
import com.github.albertocavalcante.gvy.gls.utils.isScannedUnder
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
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
class WorkspaceManager internal constructor(
    private val fingerprintComputer: (dependencies: List<Path>, sourceRoots: List<Path>) -> String,
    private val caseInsensitive: (root: Path) -> Boolean,
    @Suppress("UnusedPrivateProperty")
    private val normalisesNames: Boolean = false,
) {
    constructor(
        fingerprintComputer: (dependencies: List<Path>, sourceRoots: List<Path>) -> String =
            ConfigurationFingerprint::compute,
    ) : this(fingerprintComputer, ::probeCaseInsensitive)

    // Disk probes, once per root path: whether names under the root compare case-insensitively (#1424).
    private val caseInsensitiveRoots = ConcurrentHashMap<Path, Boolean>()

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
     * Applies paths created or deleted after the model was built (#1422, #1424), publishing one new model.
     *
     * A created path under a current source root joins when it is a Groovy source, the rule the scan uses, and a
     * created folder adds the sources a walk of it finds. A deleted path leaves the list; a deleted path that is
     * not a listed source may have been a folder, so it also removes the sources strictly under it, compared by
     * component: a deleted `Jenkinsfile` removes nothing and a deleted `pkg` keeps `pkgUtil`. A path deleted and
     * created in one notification is decided by the disk. Each root's sources stay sorted, as [scanSources]
     * orders them. Classpath, roots, targets and so the fingerprint are unchanged.
     *
     * A path spelled through a symlinked root's real path, or in another case or Unicode normalisation under a
     * case-insensitive root, matches the listed entry ([Spelling]); a case-only rename keeps the new spelling.
     *
     * Every disk read (the spellings, case probes, stats and folder walks) happens before the update lock; the
     * merge re-checks the roots.
     */
    fun applySourceChanges(created: Collection<Path>, deleted: Collection<Path>): SourceListChange {
        // Disk work first, outside the update lock: spellings, case probes, stats and folder walks.
        val spelling = Spelling(current().sourceRoots.map { it.toAbsolutePath().normalize() })
        val createdPaths = created.mapNotNull(spelling::inRoot).distinct()
        val deletedPaths = deleted.mapNotNull(spelling::inRoot).distinct()
        if (createdPaths.isEmpty() && deletedPaths.isEmpty()) return SourceListChange.NONE

        val existing = createdPaths.filter(::isGroovySource)
        val folders = createdPaths.filter { it !in existing && Files.isDirectory(it) }
        val found = (existing + walkFolders(folders)).distinctBy(spelling::key)
        val existingKeys = existing.mapTo(HashSet(), spelling::key)
        val deletedKeys = deletedPaths.mapTo(HashSet(), spelling::key)
        var result = SourceListChange.NONE
        update { current ->
            val listedKeys = current.sources.mapTo(HashSet()) { spelling.key(it.toAbsolutePath().normalize()) }
            val gone = deletedKeys - existingKeys
            val prefixes = deletedKeys.filterTo(HashSet()) { it !in listedKeys }
            // A path both deleted and created in one notification takes the created spelling: a case-only rename.
            val renamed = found.filter { spelling.key(it) in deletedKeys }.associateBy(spelling::key)
            val kept = current.sources.mapNotNull { source ->
                val path = source.toAbsolutePath().normalize()
                val key = spelling.key(path)
                when {
                    key in gone || spelling.hasAncestorIn(path, prefixes) -> null
                    else -> renamed[key] ?: source
                }
            }
            val keptKeys = kept.mapTo(HashSet()) { spelling.key(it.toAbsolutePath().normalize()) }
            val roots = current.sourceRoots.map { it.toAbsolutePath().normalize() }
            val added = found.filter { spelling.key(it) !in keptKeys && isScannedUnder(it, roots) }
            val next = if (added.isEmpty()) kept else ordered(kept + added, roots)
            if (next == current.sources) {
                current
            } else {
                val before = current.sources.toSet()
                val after = next.toSet()
                result = SourceListChange(
                    added = next.filter { it !in before },
                    removed = current.sources.filter { it !in after }.map { it.toAbsolutePath().normalize() }.sorted(),
                )
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
        return result
    }

    /**
     * How one notification's paths are spelled against [roots] (#1424). An event path is translated into the
     * spelling of the root holding it, so the list keeps the scan's spelling: a lexical match is free, and only a
     * miss (a symlinked root's real path, another case) is canonicalised, once per path, and rebased onto the
     * root whose canonical form holds it. Membership compares [key]s: under a case-insensitive root the path is
     * NFC-normalised and lowercased with `Locale.ROOT`, which approximates the file system's own folding (ß and
     * the dotless i fold differently) and is accepted. Built before the update lock; [key] does no disk work.
     */
    private inner class Spelling(private val roots: List<Path>) {
        private val insensitive = roots.associateWith { root ->
            caseInsensitiveRoots.getOrPut(root) { caseInsensitive(root) }
        }
        private val canonicalRoots by lazy { roots.map { DocumentTargetSelector.canonical(it) } }

        fun inRoot(path: Path): Path? {
            val absolute = path.toAbsolutePath().normalize()
            if (isScannedUnder(absolute, roots)) return absolute
            val canonical = DocumentTargetSelector.canonical(absolute)
            val index = canonicalRoots.indices.filter { canonical.startsWith(canonicalRoots[it]) }
                .maxByOrNull { canonicalRoots[it].nameCount } ?: return null
            val rebased = roots[index].resolve(canonicalRoots[index].relativize(canonical))
            return rebased.takeIf { isScannedUnder(it, roots) }
        }

        fun key(path: Path): String {
            val owner = roots.filter { path.startsWith(it) }.maxByOrNull { it.nameCount }
            val text = path.toString()
            return if (owner != null && insensitive[owner] == true) {
                Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            } else {
                text
            }
        }

        /** Whether a proper ancestor of [path] has a key in [prefixes]: [path] lies strictly under it. */
        fun hasAncestorIn(path: Path, prefixes: Set<String>): Boolean =
            prefixes.isNotEmpty() && generateSequence(path.parent) { it.parent }.any { key(it) in prefixes }
    }

    /** The Groovy sources in each folder, each walked once even when a parent folder is also listed. */
    private fun walkFolders(folders: List<Path>): List<Path> {
        val set = folders.toSet()
        return folders.filterNot { hasAncestorIn(it, set) }
            .flatMap { folder -> collectGroovySources(folder).map { it.toAbsolutePath().normalize() } }
    }

    /** Whether a proper ancestor of [path] is in [prefixes]: [path] lies strictly under one, by component. */
    private fun hasAncestorIn(path: Path, prefixes: Set<Path>): Boolean =
        prefixes.isNotEmpty() && generateSequence(path.parent) { it.parent }.any { it in prefixes }

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
 * aborting the whole workspace update (#1314). Directories in [ALWAYS_SKIPPED_DIRECTORIES] below [root] are not
 * entered, the rule watched-file events follow too (#1424). [beforeVisit] runs before each file is classified
 * (a test seam).
 */
internal fun collectGroovySources(root: Path, beforeVisit: (Path) -> Unit = {}): List<Path> {
    // A walk does not follow links, so a root that is itself a link is walked at its target and what it finds
    // is spelled through the root (#1424); otherwise a symlinked source root would list nothing.
    val start = linkTarget(root) ?: root
    val found = mutableListOf<Path>()
    Files.walkFileTree(
        start,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != start && dir.fileName?.toString() in ALWAYS_SKIPPED_DIRECTORIES) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }

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
    return if (start == root) found else found.map { root.resolve(start.relativize(it)) }
}

/** The real directory a root that is itself a symbolic link points at, or null when it is not a link. */
private fun linkTarget(root: Path): Path? = try {
    if (Files.isSymbolicLink(root)) root.toRealPath() else null
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

private val scanLogger = KotlinLogging.logger {}

private fun skipped(entry: Path, exc: IOException) {
    if (exc is NoSuchFileException) {
        scanLogger.debug { "Skipped a source entry that vanished during the scan: $entry" }
    } else {
        scanLogger.warn { "Skipped an unreadable source entry: $entry (${exc.message})" }
    }
}

/** What one file-watch notification did to the source list: the sources it [added] and [removed]. */
data class SourceListChange(val added: List<Path>, val removed: List<Path>) {
    val changed: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()

    companion object {
        val NONE = SourceListChange(emptyList(), emptyList())
    }
}

/**
 * Whether names under [root] compare case-insensitively, probed by flipping the case of the root's last segment
 * and asking whether that names the same directory. A segment with no letters cannot be probed and is treated
 * as case-sensitive, as is any probe the file system refuses.
 */
internal fun probeCaseInsensitive(root: Path): Boolean {
    val name = root.fileName?.toString() ?: return false
    val flipped = name.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("")
    if (flipped == name) return false
    val alias = root.resolveSibling(flipped)
    return try {
        Files.exists(alias) && Files.isSameFile(root, alias)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
