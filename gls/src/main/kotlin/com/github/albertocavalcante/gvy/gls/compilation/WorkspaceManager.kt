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
    private val normalisesNames: Boolean = PLATFORM_NORMALISES_NAMES,
    /** Resolves a path's real spelling (a disk read); a seam so a test can count the reads (#1440). */
    private val canonicalise: (Path) -> Path = { DocumentTargetSelector.canonical(it) },
) {
    constructor(
        fingerprintComputer: (dependencies: List<Path>, sourceRoots: List<Path>) -> String =
            ConfigurationFingerprint::compute,
    ) : this(fingerprintComputer, ::probeCaseInsensitive)

    // Disk probes, once per existing root path: whether names under the root compare case-insensitively (#1424).
    private val caseInsensitiveRoots = ConcurrentHashMap<Path, Boolean>()

    // Roots refused at the trust boundary, each warned about once. It only grows: a root fixed later stays quiet
    // for the session, but whether to follow it is decided afresh on every scan and notification.
    private val refusedLinks: MutableSet<Path> = ConcurrentHashMap.newKeySet()

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
     * Publishes [refreshed], the current target evidence with re-read classpath stamps (#1364), only while the model
     * still holds exactly [expected]: a dependency observation published meanwhile is newer and is never overwritten
     * by a re-stamp of the evidence it replaced. Classpath, roots and the source list are kept as they are; no disk
     * I/O happens here. Returns whether the model changed.
     */
    fun restampTargets(expected: TargetDependencyEvidence.Scoped, refreshed: TargetDependencyEvidence.Scoped): Boolean {
        var published = false
        update { current ->
            if (current.targets !== expected) {
                current
            } else {
                published = true
                WorkspaceModel(
                    current.root,
                    current.classpath,
                    current.sourceRoots,
                    current.sources,
                    refreshed,
                    computeFingerprint = fingerprintComputer,
                )
            }
        }
        return published
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
        val start = current()
        val spelling = Spelling(start.sourceRoots.map { it.toAbsolutePath().normalize() }, start.root)
        val createdPaths = created.mapNotNull(spelling::inRoot).distinct().filter(spelling::isContained)
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
     * The listed spelling of each of [paths] (#1440), so a cache or index keyed by the listed source is found: an
     * event path spelled through a symlinked root's real path, an aliased root, or another case or normalisation
     * under a case-insensitive root. Translated exactly as [applySourceChanges] spells an event path ([Spelling]),
     * then matched by key against the listed sources. A path outside every root, or under a refused one, keeps its
     * own spelling. Takes no lock and changes nothing.
     *
     * It runs on the LSP notification thread for every source event, so the [Spelling] and the listed keys are
     * built once per model ([ListedIndex]); the trust boundary and link targets it saw are then those of the
     * model's first notification, until the next model. [applySourceChanges] still decides them afresh.
     */
    fun listedSpellings(paths: Collection<Path>): Map<Path, Path> {
        if (paths.isEmpty()) return emptyMap()
        val workspace = current()
        val index = listedIndex?.takeIf { it.workspace === workspace }
            ?: ListedIndex(workspace).also { listedIndex = it }
        return paths.associateWith { path ->
            index.spelling.inRoot(path)?.let(index::listedSpelling) ?: path
        }
    }

    /** One model's [Spelling] and its listed sources by key: models are immutable, so it is keyed by identity. */
    private inner class ListedIndex(val workspace: WorkspaceModel) {
        val spelling = Spelling(workspace.sourceRoots.map { it.toAbsolutePath().normalize() }, workspace.root)

        // A translated path already is its listed spelling when every key is the path's own text (no root folds
        // case and the platform keeps normalisations apart) and the roots, so the sources, are spelled normalised.
        private val direct = spelling.keysAreSpellings &&
            workspace.sourceRoots.all { it == it.toAbsolutePath().normalize() }

        // O(sources), so built only where it is needed, and on first use rather than on every model change.
        private val listed: Map<String, Path> by lazy {
            workspace.sources.associateBy { spelling.key(it.toAbsolutePath().normalize()) }
        }

        fun listedSpelling(translated: Path): Path =
            if (direct) translated else listed[spelling.key(translated)] ?: translated
    }

    @Volatile
    private var listedIndex: ListedIndex? = null

    /** The probe for [root], run on its [canonical] directory; cached only once the root exists. */
    private fun isCaseInsensitive(root: Path, canonical: Path): Boolean = caseInsensitiveRoots[root]
        ?: caseInsensitive(canonical).also { if (Files.isDirectory(root)) caseInsensitiveRoots[root] = it }

    /**
     * Where [root] may be scanned from, or null when the trust boundary refuses it: a root that is itself a link
     * is scanned at its target only inside the workspace ([followedLink]), and a root lexically inside the
     * workspace whose real path leaves it (an ancestor link, `src/main -> /usr/share`) is refused. A root lexically
     * outside the workspace without links (a build's `srcDir '../shared'`) is the build's choice and is scanned.
     * Links are only followed inside the workspace, even for a root declared outside it: the model does not record
     * where a root came from, so a link cannot be trusted by origin (#1424).
     */
    private fun scanStart(root: Path, workspaceRoot: Path?): Path? {
        val workspace = workspaceRoot?.toAbsolutePath()?.normalize()
        val absolute = root.toAbsolutePath().normalize()
        return when {
            Files.isSymbolicLink(absolute) -> followedLink(absolute, workspace)
            workspace == null || !absolute.startsWith(workspace) -> absolute
            DocumentTargetSelector.canonical(absolute).startsWith(realWorkspace(workspace)) -> absolute
            else -> null.also { refuse(absolute, "resolves outside the workspace through a linked ancestor") }
        }
    }

    private fun realWorkspace(workspace: Path): Path =
        realPathOrNull(workspace) ?: DocumentTargetSelector.canonical(workspace)

    /**
     * The directory a source root that is itself a symbolic link may be scanned at: its real target, only when
     * that lies strictly under the workspace's real path. A cloned repository's `src -> /` or `src -> ~`, a link to
     * the workspace or an ancestor of it, or a broken link is refused (warned about once) and lists nothing, so a
     * scan never walks outside the workspace through a link. Null for a refused link or a root that is no link.
     */
    private fun followedLink(root: Path, workspaceRoot: Path?): Path? {
        val target = realPathOrNull(root) ?: return null.also { if (Files.isSymbolicLink(root)) refuse(root, "broken") }
        val workspace = workspaceRoot?.let(::realWorkspace)
        val trusted = workspace != null && target != workspace && target.startsWith(workspace)
        if (!trusted) refuse(root, "points outside the workspace: $target")
        return target.takeIf { trusted }
    }

    private fun refuse(root: Path, why: String) {
        if (refusedLinks.add(root)) logger.warn { "Not following source root link $root: $why" }
    }

    /** The roots a scan walks, each with its walk start, and every skipped alias root mapped to the walked one. */
    private class ScannedRoots(val scanned: List<Pair<Path, Path>>, val aliasOf: Map<Path, Path>)

    /**
     * The roots a scan walks (#1431): each existing root the trust boundary accepts, with where it is walked from,
     * except a root naming the same directory as an earlier one (a link to it, or another case or normalisation
     * under a case-insensitive root). The first such root in order is walked; [ScannedRoots.aliasOf] maps every
     * later one, absolute and normalised, onto it. Missing and refused roots are dropped first, so a refused link
     * never displaces a trusted root. The published roots, and so the fingerprint, are not changed.
     *
     * Decided afresh by every scan and every notification's [Spelling], as the trust boundary is: both read the
     * disk at their own time. They can only disagree when a root appears or vanishes in between, and then the
     * disk agrees with the notification: a vanished first root lists nothing more, and the next model build
     * rescans from the roots as they are.
     */
    private fun scannedRoots(roots: List<Path>, workspaceRoot: Path?): ScannedRoots {
        val firstByKey = HashMap<String, Path>()
        val scanned = mutableListOf<Pair<Path, Path>>()
        val aliasOf = HashMap<Path, Path>()
        roots.distinct().forEach { root ->
            if (!Files.exists(root)) return@forEach
            val start = scanStart(root, workspaceRoot) ?: return@forEach
            val absolute = root.toAbsolutePath().normalize()
            val canonical = DocumentTargetSelector.canonical(start)
            val key = nameKey(canonical.toString(), isCaseInsensitive(absolute, canonical), normalisesNames)
            val first = firstByKey.putIfAbsent(key, absolute)
            if (first == null) {
                scanned.add(root to start)
            } else if (first != absolute) {
                aliasOf[absolute] = first
                logger.info { "Source root $root names the same directory as $first; scanned once, as $first" }
            }
        }
        return ScannedRoots(scanned, aliasOf)
    }

    /**
     * How one notification's paths are spelled against [roots] (#1424). An event path is translated into the
     * spelling of the root holding it, so the list keeps the scan's spelling: a lexical match is free, and only a
     * miss (a symlinked root's real path, another case) is canonicalised, once per path, and rebased onto the
     * root whose canonical form holds it. Membership compares [key]s: under a case-insensitive root the path is
     * NFC-normalised and lowercased with `Locale.ROOT`, which approximates the file system's own folding (ß and
     * the dotless i fold differently) and is accepted. Built before the update lock; [key] does no disk work.
     */
    private inner class Spelling(private val roots: List<Path>, workspaceRoot: Path?) {
        // A root that is a link the trust boundary refuses takes no events at all: walking under it would cross it.
        private val starts = roots.associateWith { scanStart(it, workspaceRoot) }
        private val refused = starts.filterValues { it == null }.keys
        private val canonicalRoots = roots.map { root ->
            when {
                root in refused -> root
                Files.isSymbolicLink(root) -> starts.getValue(root) ?: root
                else -> canonicalise(root)
            }
        }
        private val insensitive = roots.indices.associate { index ->
            roots[index] to isCaseInsensitive(roots[index], canonicalRoots[index])
        }

        /** Whether every [key] is the path's own text: no root folds case and the platform keeps normalisations. */
        val keysAreSpellings: Boolean = !normalisesNames && insensitive.values.none { it }

        // A root naming the same directory as an earlier one is not scanned: its events take that root's spelling.
        private val aliasOf = scannedRoots(roots, workspaceRoot).aliasOf

        fun inRoot(path: Path): Path? {
            val absolute = path.toAbsolutePath().normalize()
            val translated = if (isScannedUnder(absolute, roots)) absolute else rebased(absolute)
            return translated?.let(::ontoScannedRoot)
                ?.takeUnless { candidate -> refused.any { candidate.startsWith(it) } }
        }

        /** [path] respelled under the scanned root when the root holding it is an alias of one (#1431). */
        private fun ontoScannedRoot(path: Path): Path {
            val owner = roots.filter { path.startsWith(it) }.maxByOrNull { it.nameCount } ?: return path
            val scanned = aliasOf[owner] ?: return path
            return scanned.resolve(owner.relativize(path))
        }

        // Parent directories' real paths, looked up once per notification: a branch switch creates many siblings.
        // Concurrent: a model's Spelling is shared by the LSP and source-queue threads ([ListedIndex]).
        private val canonicalDirectories = ConcurrentHashMap<Path, Path>()

        /**
         * Whether a created [path] really lies in its root's directory. A directory link inside an allowed root
         * (`src/evil -> /`) passes every lexical check, and a stat or walk through it would follow it; the scan
         * never enters such a link, so neither do events. A directory is checked by its own real path, anything
         * else by its parent's: a single file link (`src/A.groovy -> ../shared/A.groovy`, as Jenkins shared
         * libraries use) is read wherever it points, as the scan and `main` read it.
         */
        fun isContained(path: Path): Boolean {
            val index = roots.indices.filter { path.startsWith(roots[it]) }.maxByOrNull { roots[it].nameCount }
            val directory = if (Files.isDirectory(path)) path else path.parent
            return index != null && directory != null &&
                canonicalDirectories.getOrPut(directory) { canonicalise(directory) }
                    .startsWith(canonicalRoots[index])
        }

        /**
         * A path no root holds lexically, respelled under the root whose directory holds it. A path already spelled
         * through a root's real path (its key, under a case-insensitive root) is rebased with no disk read. Any
         * other path is checked by its parent directory's real path, read once per directory ([canonicalParent]):
         * a burst of generated files under `build/` costs one read, not one per file (#1440).
         */
        private fun rebased(absolute: Path): Path? {
            val (index, relative) = underCanonicalRoot(absolute)
                ?: canonicalParent(absolute)?.let(::underCanonicalRoot)
                ?: return null
            val rebased = roots[index].resolve(relative)
            return rebased.takeIf { isScannedUnder(it, roots) }
        }

        private val canonicalRootKeys = roots.indices.map { index ->
            nameKey(canonicalRoots[index].toString(), insensitive.getValue(roots[index]), normalisesNames)
        }

        /** The deepest accepted, scanned root whose real path holds [path] lexically, and [path] relative to it. */
        private fun underCanonicalRoot(path: Path): Pair<Int, String>? {
            val index = roots.indices
                .filter { roots[it] !in refused && roots[it] !in aliasOf && holds(it, path) }
                .maxByOrNull { canonicalRoots[it].nameCount } ?: return null
            val depth = canonicalRoots[index].nameCount
            val relative = if (path.nameCount == depth) "" else path.subpath(depth, path.nameCount).toString()
            return index to relative
        }

        private fun holds(index: Int, path: Path): Boolean {
            val root = canonicalRoots[index]
            if (path.startsWith(root)) return true
            // Under a case-insensitive root, or on a platform that normalises names, compare the prefix by key.
            val prefix = path.takeIf { it.nameCount >= root.nameCount && it.root == root.root }
                ?.let { it.root?.resolve(it.subpath(0, root.nameCount)) } ?: return false
            return nameKey(prefix.toString(), insensitive.getValue(roots[index]), normalisesNames) ==
                canonicalRootKeys[index]
        }

        /**
         * Parent directories' real paths, at most [MAX_CANONICAL_PARENTS], least recently used dropped. Scoped to
         * this [Spelling], so to one notification or one model: a directory replaced by a link while it lives keeps
         * its old real path here until then, an accepted limit. Shared by the LSP and queue threads, so locked.
         */
        private val canonicalParents = object : LinkedHashMap<Path, Path>(LRU_INITIAL_CAPACITY, LRU_LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Path>?): Boolean =
                size > MAX_CANONICAL_PARENTS
        }

        private fun canonicalParent(absolute: Path): Path? {
            val parent = absolute.parent ?: return null
            val name = absolute.fileName ?: return null
            val real = synchronized(canonicalParents) { canonicalParents[parent] }
                ?: canonicalise(parent).also { synchronized(canonicalParents) { canonicalParents[parent] = it } }
            return real.resolve(name.toString())
        }

        fun key(path: Path): String {
            val owner = roots.filter { path.startsWith(it) }.maxByOrNull { it.nameCount }
            return nameKey(path.toString(), foldsCase = owner != null && insensitive[owner] == true, normalisesNames)
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
        scanSources(sourceRoots.distinct(), root),
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

    private fun scanSources(sourceRoots: List<Path>, workspaceRoot: Path?): List<Path> {
        // A refused root lists nothing, an accepted link root is walked at its checked target, and a directory
        // named by several roots is walked once, under the first ([scannedRoots]).
        val workspaceSources = scannedRoots(sourceRoots, workspaceRoot).scanned.flatMap { (sourceRoot, start) ->
            collectGroovySources(sourceRoot, start = start).sorted()
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
internal fun collectGroovySources(root: Path, start: Path = root, beforeVisit: (Path) -> Unit = {}): List<Path> {
    // A walk does not follow links: a root that is itself a link is walked at the [start] its caller trusts, and
    // what it finds is spelled through the root (#1424).
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

/** The real path of [path], or null when it does not exist or cannot be resolved (a broken link). */
private fun realPathOrNull(path: Path): Path? = try {
    path.toRealPath()
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

/**
 * How a path compares under its root: NFC-normalised when the root [foldsCase] or the platform [normalisesNames],
 * and lowercased with `Locale.ROOT` when it [foldsCase] (#1424, #1431).
 */
private fun nameKey(text: String, foldsCase: Boolean, normalisesNames: Boolean): String {
    val normalised = if (foldsCase || normalisesNames) Normalizer.normalize(text, Normalizer.Form.NFC) else text
    return if (foldsCase) normalised.lowercase(Locale.ROOT) else normalised
}

/** Parent directories whose real path one [WorkspaceManager] spelling remembers (#1440). */
private const val MAX_CANONICAL_PARENTS = 1024

// [java.util.HashMap]'s defaults, spelled out because the access-ordered constructor needs them.
private const val LRU_INITIAL_CAPACITY = 16
private const val LRU_LOAD_FACTOR = 0.75f

/** macOS file systems (APFS, HFS+) ignore Unicode normalisation in names, even on case-sensitive volumes. */
private val PLATFORM_NORMALISES_NAMES: Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
