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

/** Directories that hold generated or foreign files, never project sources. */
private val SKIPPED_DIRECTORIES =
    setOf(".git", ".hg", ".svn", ".gradle", ".idea", "node_modules", "build", "out", "target")

/**
 * File identities for one check. Under the (real) workspace root a path's normalized form is its key, which
 * costs no filesystem calls; anything else is resolved to its real path, so `/tmp` and `/private/tmp` match.
 */
internal class FileKeys(root: Path?) {
    val rootKey: Path? = root?.let(::realKey)

    fun of(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        return if (rootKey != null && normalized.startsWith(rootKey)) normalized else realKey(path)
    }

    fun of(uri: URI): Path? = UriPathConverter.toPath(uri)?.let(::of)

    fun isUnderRoot(key: Path): Boolean = rootKey != null && key.startsWith(rootKey)
}

/** The files a cross-file check must read, keyed by [FileKeys.of]; [overflow] when there are too many to verify. */
internal data class CandidateFiles(val files: Map<Path, Path>, val overflow: Boolean)

/**
 * Gathers every file that may use a rename target: the workspace's known sources, indexed documents and open
 * buffers under the workspace root, and whatever the root holds on disk. The walk is what finds files created
 * since startup and workspaces without a recognized source root.
 */
internal class CandidateCollector(
    private val keys: FileKeys,
    private val maxFiles: Int,
    private val maxWalkEntries: Int = MAX_WALK_ENTRIES,
) {
    private val files = LinkedHashMap<Path, Path>()
    private var overflow = false

    fun collect(sources: Map<Path, Path>, documents: Collection<URI>, active: () -> Unit): CandidateFiles {
        sources.forEach { (key, path) -> add(key, path) }
        for (uri in documents) {
            val path = UriPathConverter.toPath(uri)?.takeIf(::isSourceFile) ?: continue
            keys.of(path).takeIf(keys::isUnderRoot)?.let { add(it, path) }
        }
        keys.rootKey?.let { walk(it, active) }
        return CandidateFiles(files, overflow)
    }

    private fun add(key: Path, path: Path) {
        files.putIfAbsent(key, path)
        if (files.size > maxFiles) overflow = true
    }

    private fun walk(root: Path, active: () -> Unit) {
        var entries = 0
        val visitor = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && dir.fileName.toString() in SKIPPED_DIRECTORIES) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                active()
                if (isSourceFile(file)) add(file, file)
                return if (++entries > maxWalkEntries || overflow) stop() else FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

            private fun stop(): FileVisitResult {
                overflow = true
                return FileVisitResult.TERMINATE
            }
        }
        Files.walkFileTree(root, visitor)
    }

    companion object {
        const val MAX_WALK_ENTRIES = 200_000
    }
}
