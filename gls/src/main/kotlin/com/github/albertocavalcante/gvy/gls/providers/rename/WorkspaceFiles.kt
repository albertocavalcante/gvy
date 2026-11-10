package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceScanner
import com.github.albertocavalcante.gvy.gls.utils.ALWAYS_SKIPPED_DIRECTORIES
import java.io.IOException
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Identity of the file at [path]: its real path, resolved through the nearest existing ancestor when the file
 * itself does not exist (an unsaved buffer), so `/tmp` and `/private/tmp` spellings agree either way.
 */
internal fun realKey(path: Path): Path = resolveReal(path.toAbsolutePath().normalize())

private fun resolveReal(absolute: Path): Path {
    val real = runCatching { absolute.toRealPath() }.getOrNull()
    val parent = absolute.parent
    val name = absolute.fileName
    return when {
        real != null -> real
        parent != null && name != null -> resolveReal(parent).resolve(name)
        else -> absolute
    }
}

private val scanner = WorkspaceScanner()

/**
 * Whether [path] can call project code: Groovy and Gradle sources and Jenkinsfiles (as the workspace scanner
 * knows them), `*.jenkinsfile` variants, and Java.
 */
internal fun isSourceFile(path: Path): Boolean {
    val name = path.fileName?.toString()?.lowercase() ?: return false
    return scanner.isGroovyFile(path) || name.endsWith(".java") || name.endsWith(".jenkinsfile")
}

/**
 * File identities for one check. Under a (real) workspace root a path's normalized form is its key, which costs
 * no filesystem calls; anything else is resolved to its real path, so `/tmp` and `/private/tmp` match.
 * With no [roots] (files opened without a folder) nothing is out of scope.
 */
internal class FileKeys(roots: List<Path>) {
    val rootKeys: List<Path> = roots.map(::realKey).distinct()

    fun of(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        return if (rootKeys.any(normalized::startsWith)) normalized else realKey(path)
    }

    fun of(uri: URI): Path? = UriPathConverter.toPath(uri)?.let(::of)

    /** Whether a file at [key] is part of the workspace: under a root, or anywhere when there is no root. */
    fun inScope(key: Path): Boolean = rootKeys.isEmpty() || rootKeys.any(key::startsWith)
}

/** Where the workspace's files are, as lambdas because the layout changes while the server runs. */
internal class WorkspaceLayout(
    val sources: () -> List<Path>,
    val roots: () -> List<Path>,
    val sourceRoots: () -> List<Path> = { emptyList() },
)

/**
 * What a walk of the workspace roots found; [overflow] when there are too many files or entries to verify, and
 * [unreadable] the directories and files it could not read (whose contents are therefore unknown).
 * [directories] holds every directory it listed with its modification time: adding, removing or moving a file or
 * directory changes the parent's time, so a changed time means the walk is out of date.
 */
internal class Walked(
    val files: List<Path>,
    val overflow: Boolean,
    val unreadable: List<Path> = emptyList(),
    val directories: Map<Path, FileTime> = emptyMap(),
)

/** Build output directories: skipped only where they are build output, not where they are a package name. */
private val BUILD_OUTPUTS = setOf("build", "out", "target")

private val MODULE_FILES = listOf(
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
    "pom.xml",
    "build.xml",
)

/**
 * Whether [dir] directly holds Groovy or Java sources, which makes it a package that happens to be named like build
 * output (a flat repository's `build/` package) rather than build output. Sources only in a subpackage of such a
 * directory are still missed.
 */
private fun holdsSources(dir: Path): Boolean = runCatching {
    Files.newDirectoryStream(dir).use { children ->
        children.any { child ->
            val name = child.fileName.toString().lowercase()
            SOURCE_ENDINGS.any(name::endsWith) && Files.isRegularFile(child)
        }
    }
}.getOrDefault(false)

private val SOURCE_ENDINGS = listOf(".groovy", ".java", ".gvy", ".gy", ".gsh")

private fun hasFile(dir: Path, name: String) = Files.exists(dir.resolve(name))

/**
 * Walks [roots] for files that may call project code, without following symlinks (a link could loop, so files
 * reachable only through one are not checked). Nothing inside a source root
 * is skipped (`com/acme/build/` is a package). Outside source roots, `build`, `out` and `target` are skipped
 * only directly under a workspace root or a module root, and only when they hold no source files directly.
 */
internal fun walkWorkspace(
    roots: List<Path>,
    sourceRoots: List<Path>,
    maxFiles: Int,
    maxEntries: Int,
    active: () -> Unit,
): Walked {
    val files = LinkedHashSet<Path>()
    var entries = 0
    var overflow = false
    val unreadable = mutableListOf<Path>()
    val directories = LinkedHashMap<Path, FileTime>()
    val visitor = object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (isSkipped(dir)) return FileVisitResult.SKIP_SUBTREE
            directories[dir] = attrs.lastModifiedTime()
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            active()
            if (isSourceFile(file)) files.add(file)
            overflow = overflow || files.size > maxFiles || ++entries > maxEntries
            return if (overflow) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            unreadable.add(file)
            return FileVisitResult.CONTINUE
        }

        private fun isModuleOrWorkspaceRoot(dir: Path) = dir in roots || MODULE_FILES.any { hasFile(dir, it) }

        private fun isSkipped(dir: Path): Boolean {
            val name = dir.fileName?.toString() ?: return false
            return when {
                dir in roots || sourceRoots.any(dir::startsWith) -> false
                name in ALWAYS_SKIPPED_DIRECTORIES -> true
                name in BUILD_OUTPUTS -> isModuleOrWorkspaceRoot(dir.parent) && !holdsSources(dir)
                else -> false
            }
        }
    }
    for (root in roots) {
        if (!overflow) Files.walkFileTree(root, visitor)
    }
    return Walked(files.toList(), overflow, unreadable, directories)
}

/**
 * Remembers the last walk of the workspace roots, because the walk is the expensive part of a rename check and
 * prepareRename fires on every F2. A result is reused only while all of these hold:
 * - it is younger than [ttlMillis] (a backstop);
 * - no [invalidate] happened since it started (the workspace service calls it on any watched-file event);
 * - every directory the walk listed still exists with the modification time it had. Creating, deleting or moving a
 *   file changes its parent directory's time, so this needs no file watcher. A directory modified within
 *   [RECENT_MILLIS] of the walk is never trusted, because coarse filesystem timestamps cannot order it.
 *
 * What stays unseen for up to [ttlMillis] is an in-place edit of a file the walk already knew, whose content the
 * scan reads from disk or from the open buffer anyway. Open buffers are never cached.
 */
internal class WorkspaceWalkCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val key: Any, val at: Long, val generation: Int, val walked: Walked)

    @Volatile
    private var entry: Entry? = null
    private val generation = AtomicInteger()
    private val walkCounter = AtomicInteger()

    /** How many walks actually ran. */
    val walks: Int get() = walkCounter.get()

    /** How many times the cache was invalidated. */
    val invalidations: Int get() = generation.get()

    fun get(key: Any, walk: () -> Walked): Walked {
        entry?.takeIf { isFresh(it, key) }?.let { return it.walked }
        // The generation is read before the walk and stored with it: an invalidation that lands at any later point,
        // even between the walk and the store, makes this entry stale on its first read.
        val before = generation.get()
        val walked = walk()
        walkCounter.incrementAndGet()
        entry = Entry(key, clock(), before, walked)
        return walked
    }

    fun invalidate() {
        generation.incrementAndGet()
        entry = null
    }

    private fun isFresh(entry: Entry, key: Any): Boolean = entry.key == key &&
        entry.generation == generation.get() &&
        clock() - entry.at < ttlMillis &&
        directoriesUnchanged(entry)

    private fun directoriesUnchanged(entry: Entry): Boolean = entry.walked.directories.all { (dir, time) ->
        time.toMillis() < entry.at - RECENT_MILLIS &&
            runCatching { Files.getLastModifiedTime(dir) }.getOrNull() == time
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 30_000L
        const val RECENT_MILLIS = 2_000L
    }
}

/** The files a cross-file check must read, keyed by [FileKeys.of]; [overflow] when there are too many to verify. */
internal data class CandidateFiles(
    val files: Map<Path, Path>,
    val overflow: Boolean,
    val unreadable: List<Path> = emptyList(),
)

/**
 * Merges every source of candidates: the workspace's known sources, indexed documents and open buffers in scope,
 * and the files a walk found on disk.
 */
internal class CandidateCollector(private val keys: FileKeys, private val maxFiles: Int) {
    private val files = LinkedHashMap<Path, Path>()

    fun collect(sources: Map<Path, Path>, documents: Collection<URI>, walked: Walked): CandidateFiles {
        sources.forEach { (key, path) -> files.putIfAbsent(key, path) }
        for (uri in documents) {
            val path = UriPathConverter.toPath(uri)?.takeIf(::isSourceFile) ?: continue
            keys.of(path).takeIf(keys::inScope)?.let { files.putIfAbsent(it, path) }
        }
        walked.files.forEach { files.putIfAbsent(it, it) }
        return CandidateFiles(files, walked.overflow || files.size > maxFiles, walked.unreadable)
    }
}
