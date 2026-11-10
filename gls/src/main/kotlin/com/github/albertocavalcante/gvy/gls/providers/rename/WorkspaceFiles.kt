package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceScanner
import java.io.IOException
import java.net.URI
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
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
 */
internal class Walked(val files: List<Path>, val overflow: Boolean, val unreadable: List<Path> = emptyList())

/** Directories that never hold project sources, at any depth. */
private val ALWAYS_SKIPPED = setOf(".git", ".hg", ".svn", ".gradle", ".idea", "node_modules")

/** Build output directories: skipped only where they are build output, not where they are a package name. */
private val BUILD_OUTPUTS = setOf("build", "out", "target")

private val MODULE_FILES = listOf(
    "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "pom.xml", "build.xml",
)

private fun hasFile(dir: Path, name: String) = Files.exists(dir.resolve(name))

/**
 * Walks [roots] for files that may call project code, without following symlinks (a link could loop, so files
 * reachable only through one are not checked). Nothing inside a source root
 * is skipped (`com/acme/build/` is a package). Outside source roots, `build`, `out` and `target` are skipped
 * only directly under a workspace root or a module root.
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
    val visitor = object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (isSkipped(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

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
                name in ALWAYS_SKIPPED -> true
                name in BUILD_OUTPUTS -> isModuleOrWorkspaceRoot(dir.parent)
                else -> false
            }
        }
    }
    for (root in roots) {
        if (!overflow) Files.walkFileTree(root, visitor)
    }
    return Walked(files.toList(), overflow, unreadable)
}

/**
 * Remembers the last walk of the workspace roots, because the walk is the expensive part of a rename check and
 * prepareRename fires on every F2. A result is reused for [ttlMillis] and dropped by [invalidate], which the
 * workspace service calls on any watched-file event. The registered watchers cover only Groovy, Java and GDSL
 * files, so a Jenkinsfile or `.gradle` file created outside the editor can go unseen for up to [ttlMillis].
 * Open buffers are never cached, so a file being typed is always seen.
 */
internal class WorkspaceWalkCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val key: Any, val at: Long, val walked: Walked)

    @Volatile
    private var entry: Entry? = null
    private val generation = AtomicInteger()
    private val walkCounter = AtomicInteger()

    /** How many walks actually ran. */
    val walks: Int get() = walkCounter.get()

    /** How many times the cache was invalidated. */
    val invalidations: Int get() = generation.get()

    fun get(key: Any, walk: () -> Walked): Walked {
        entry?.takeIf { it.key == key && clock() - it.at < ttlMillis }?.let { return it.walked }
        val before = generation.get()
        val walked = walk()
        walkCounter.incrementAndGet()
        // A walk that raced with an invalidation may predate the change it was invalidated for.
        if (generation.get() == before) entry = Entry(key, clock(), walked)
        return walked
    }

    fun invalidate() {
        generation.incrementAndGet()
        entry = null
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 30_000L
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
