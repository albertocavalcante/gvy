// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.CombinedTypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Service to manage the classpath and load classes/methods from the user's project and the JDK.
 * Acts as the "Window to the World" for the LSP.
 */
class ClasspathService(
    private val classpathIndex: ClasspathIndex = JvmClasspathIndex(),
    private val reflectionProvider: ClasspathReflection? = null,
) {
    private val logger = KotlinLogging.logger {}

    internal companion object {
        /** On success takes ownership of [loader] except the system loader; on failure the caller retains it. */
        fun withInitialLoader(index: ClasspathIndex, loader: ClassLoader): ClasspathService =
            ClasspathService(index).apply { generations.installInitial(loader) }
    }

    private val generations = ClasspathGenerations()

    /** What long-lived legacy resolvers hold: each lookup uses the current generation. */
    private val typeSolverFacade = object : TypeSolver {
        override var parent: TypeSolver? = null

        override fun tryToSolveType(name: String) = generations.current().solver.tryToSolveType(name)

        override fun toString(): String = "ClasspathTypeSolver(${generations.current().solver})"
    }

    private val reflection: ClasspathReflection =
        reflectionProvider ?: JvmClasspathReflection { generations.current().loader }

    // Class index for type parameter completion: SimpleName -> List<FullyQualifiedName>
    private val classIndex = ClassIndex()
    private val qualifiedNameIndex = QualifiedNameIndex()
    private val isIndexed = AtomicBoolean(false)

    /**
     * Updates the active classloader with the provided paths (JARs or directories).
     * Callers must not mutate [paths] during this call; the generation owns a detached copy afterward.
     *
     * @param paths List of paths to add to the classpath
     */
    fun updateClasspath(paths: List<Path>) = synchronized(this) { replaceClasspath(paths) }

    private fun replaceClasspath(paths: List<Path>) {
        val configured = paths.toList()
        val loader = try {
            logger.info { "Updating classpath with ${paths.size} paths" }
            URLClassLoader(configured.map { it.toUri().toURL() }.toTypedArray(), ClassLoader.getSystemClassLoader())
        } catch (e: java.net.MalformedURLException) {
            logger.error(e) { "Failed to update classpath" }
            return
        }
        generations.replace(loader, configured)
        isIndexed.set(false)
        classIndex.clear()
        qualifiedNameIndex.clear()
    }

    /**
     * Pins the current loader and solver until closed. Paths are ordered but file contents and class indexes
     * are not frozen; request acquisition must validate the corresponding workspace/artifact identity.
     * A prebuilt custom reflection provider is not known to be generation-bound, so acquisition rejects it.
     */
    fun acquireClasspath(): ClasspathLease {
        check(reflectionProvider == null) { "Cannot lease a classpath with a custom reflection provider" }
        return generations.acquire()
    }

    /** Retires current and grace generations; active leases release their loaders when the last reader closes. */
    fun close() = synchronized(this) { generations.shutdown() }

    /**
     * Indexes all classes from the classpath.
     * This is called lazily on first type parameter completion request.
     */
    fun indexAllClasses() {
        if (isIndexed.get()) return

        synchronized(this) {
            if (isIndexed.get()) return

            logger.info { "Indexing all classes from classpath..." }
            val startTime = System.currentTimeMillis()

            try {
                classpathIndex.index(resolveClasspathEntries()).forEach { entry ->
                    classIndex.add(entry.simpleName, entry.fullName)
                    qualifiedNameIndex.add(entry.fullName)
                }

                isIndexed.set(true)
                val elapsedMs = System.currentTimeMillis() - startTime
                logger.info { "Indexed ${classIndex.size()} class names in ${elapsedMs}ms" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to index classes" }
            }
        }
    }

    private fun resolveClasspathEntries(): List<String> {
        val generation = generations.current()
        return classpathEntriesFor(generation.loader, generation.paths, System.getProperty("java.class.path").orEmpty())
    }

    /**
     * Finds classes by prefix for type parameter completion.
     * Returns list of ClassInfo with simple name and fully qualified name.
     */
    fun findClassesByPrefix(prefix: String, maxResults: Int = 50): List<ClassInfo> {
        if (prefix.isBlank()) return emptyList()

        if (!isIndexed.get()) {
            indexAllClasses()
        }

        val results = mutableListOf<ClassInfo>()

        // Find all simple names that start with the prefix (case-insensitive)
        classIndex.snapshot()
            .asSequence()
            .filter { (simpleName, _) -> simpleName.startsWith(prefix, ignoreCase = true) }
            .sortedWith(
                compareBy<Map.Entry<String, List<String>>> { it.key },
            )
            .take(maxResults)
            .forEach { (simpleName, fullNames) ->
                // For each matching simple name, add all fully qualified variants
                fullNames.forEach { fullName ->
                    val packageName = fullName.substringBeforeLast('.', "")
                    results.add(ClassInfo(simpleName, fullName, packageName))
                }
            }

        return results.take(maxResults)
    }

    /**
     * Finds classes by fully qualified prefix (e.g., "java.util.L").
     */
    fun findClassesByQualifiedPrefix(prefix: String, maxResults: Int = 50): List<ClassInfo> {
        if (prefix.isBlank()) return emptyList()

        if (!isIndexed.get()) {
            indexAllClasses()
        }

        val results = mutableListOf<ClassInfo>()
        qualifiedNameIndex.snapshot()
            .asSequence()
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .take(maxResults)
            .forEach { fullName ->
                val simpleName = fullName.substringAfterLast('.')
                val packageName = fullName.substringBeforeLast('.', "")
                results.add(ClassInfo(simpleName, fullName, packageName))
            }

        return results
    }

    /**
     * Tries to load a class by name and returns its reflected methods.
     */
    fun getMethods(className: String): List<ReflectedMethod> = reflection.getMethods(className)

    /**
     * Loads fields (including constants) from a class using reflection.
     *
     * @param className The fully qualified class name (e.g., "java.lang.Math")
     * @return List of reflected fields
     */
    fun getFields(className: String): List<ReflectedField> = reflection.getFields(className)

    /**
     * Tries to load a class by name.
     */
    fun loadClass(className: String): Class<*>? = reflection.loadClass(className)

    /**
     * Returns a TypeSolver that resolves against the current classpath generation at each lookup (a fresh cache
     * per generation), so callers may keep it for the whole session.
     */
    fun getTypeSolver(): TypeSolver = typeSolverFacade

    internal class ClassIndex {
        private val index = ConcurrentHashMap<String, MutableSet<String>>()

        fun add(simpleName: String, fullName: String) {
            index.compute(simpleName) { _, existing ->
                val entries = existing ?: linkedSetOf()
                entries.add(fullName)
                entries
            }
        }

        fun snapshot(): Map<String, List<String>> = index.entries.associate { (simple, fullNames) ->
            simple to fullNames.sorted()
        }

        fun size(): Int = index.size

        fun clear() {
            index.clear()
        }
    }

    internal class QualifiedNameIndex {
        /**
         * Thread-safe index of fully qualified class names for prefix lookups.
         */
        private val index = ConcurrentSkipListSet<String>()

        fun add(fullName: String) {
            index.add(fullName)
        }

        fun snapshot(): List<String> = index.toList()

        fun clear() {
            index.clear()
        }
    }
}

/** A request-owned classpath generation. Close once the request no longer uses any of its views. */
class ClasspathLease internal constructor(
    val solver: TypeSolver,
    val reflection: ClasspathReflection,
    val classpath: List<Path>,
    internal val classLoader: ClassLoader,
    private val release: () -> Unit,
    private val current: () -> Boolean,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    /** False after close or replacement; file contents are not checked. Do not close concurrently with use. */
    fun isCurrent(): Boolean = !closed.get() && current()

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/** Publishes one generation atomically and retires its loader only after grace and the last lease. */
private class ClasspathGenerations {
    private val logger = KotlinLogging.logger {}
    private val current = AtomicReference(generation(ClassLoader.getSystemClassLoader(), emptyList()))
    private var grace: ClasspathGeneration? = null

    fun current(): ClasspathGeneration = current.get()

    fun installInitial(loader: ClassLoader) = synchronized(this) {
        check(grace == null && current.get().readers == 0)
        current.set(generation(loader, emptyList()))
    }

    fun replace(loader: ClassLoader, paths: List<Path>) = synchronized(this) {
        val next = runCatching { generation(loader, paths) }.getOrElse { failure ->
            closeLoader(loader)
            throw failure
        }
        val previous = current.getAndSet(next)
        grace?.let { retire(it, gracePeriod = false) }
        retire(previous, gracePeriod = true)
        grace = previous
    }

    fun acquire(): ClasspathLease = synchronized(this) {
        val generation = current.get()
        generation.readers++
        ClasspathLease(
            solver = generation.solver,
            reflection = JvmClasspathReflection { generation.loader },
            classpath = generation.paths,
            classLoader = generation.loader,
            release = { release(generation) },
            current = { synchronized(this) { current.get() === generation } },
        )
    }

    fun shutdown() = synchronized(this) {
        grace?.let { retire(it, gracePeriod = false) }
        grace = null
        val previous = current.getAndSet(generation(ClassLoader.getSystemClassLoader(), emptyList()))
        retire(previous, gracePeriod = false)
    }

    private fun release(generation: ClasspathGeneration) = synchronized(this) {
        check(generation.readers > 0)
        generation.readers--
        closeIfRetired(generation)
    }

    private fun retire(generation: ClasspathGeneration, gracePeriod: Boolean) {
        generation.retired = true
        generation.grace = gracePeriod
        closeIfRetired(generation)
    }

    private fun closeIfRetired(generation: ClasspathGeneration) {
        if (!generation.retired || generation.grace) return
        if (generation.readers != 0 || generation.closed) return
        generation.closed = true
        closeLoader(generation.loader)
    }

    private fun closeLoader(loader: ClassLoader) {
        if (loader === ClassLoader.getSystemClassLoader()) return
        val closable = loader as? URLClassLoader ?: return
        try {
            closable.close()
        } catch (e: java.io.IOException) {
            logger.warn(e) { "Failed to close the replaced classpath loader" }
        }
    }

    private fun generation(loader: ClassLoader, paths: List<Path>) = ClasspathGeneration(
        loader = loader,
        solver = CombinedTypeSolver(ReflectionTypeSolver(classLoader = loader, jreOnly = false)),
        paths = Collections.unmodifiableList(paths.toList()),
    )
}

private class ClasspathGeneration(val loader: ClassLoader, val solver: TypeSolver, val paths: List<Path>) {
    var readers: Int = 0
    var retired: Boolean = false
    var grace: Boolean = false
    var closed: Boolean = false
}

internal fun classpathEntriesFor(loader: ClassLoader, configured: List<Path>, systemClasspath: String): List<String> {
    if (configured.isNotEmpty()) return configured.map { it.toString() }
    val urls = (loader as? URLClassLoader)?.urLs?.mapNotNull { url ->
        if (url.protocol != "file") null else runCatching { Paths.get(url.toURI()).toString() }.getOrNull()
    }.orEmpty()
    if (urls.isNotEmpty()) return urls.distinct()
    return systemClasspath.split(File.pathSeparator).filter { it.isNotBlank() }.distinct()
}

/**
 * Reflection snapshot for a classpath method.
 *
 * @property parameters Simple type names as the JVM sees them (`Object[]` for varargs); matching keys on these.
 * @property parameterNames Available when the class was compiled with `-parameters`; empty otherwise.
 * @property declaringClass The fully qualified name of the class that declares this method.
 * @property displayParameters [parameters] as source writes them (`Object...` when declared varargs), for display only.
 */
data class ReflectedMethod(
    val name: String,
    val returnType: String,
    val parameters: List<String>,
    val parameterNames: List<String> = emptyList(),
    val isStatic: Boolean,
    val isPublic: Boolean,
    val doc: String,
    val declaringClass: String,
    val displayParameters: List<String> = parameters,
)

data class ClassInfo(val simpleName: String, val fullName: String, val packageName: String)
