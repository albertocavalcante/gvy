// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.CombinedTypeSolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.concurrent.OnceFlag
import com.github.albertocavalcante.gvy.concurrent.SharedValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.future.asDeferred
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Service to manage the classpath and load classes/methods from the user's project and the JDK.
 * Acts as the "Window to the World" for the LSP.
 *
 * @param buildExecutor Runs class-index builds: the IO dispatcher, or in tests an executor that runs them on demand.
 */
class ClasspathService internal constructor(
    private val classpathIndex: ClasspathIndex,
    private val reflectionProvider: ClasspathReflection?,
    private val buildExecutor: Executor,
) {
    constructor(
        classpathIndex: ClasspathIndex = JvmClasspathIndex(),
        reflectionProvider: ClasspathReflection? = null,
    ) : this(classpathIndex, reflectionProvider, Dispatchers.IO.asExecutor())

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
     * Indexes all classes from the current classpath unless that is done, suspending until a build already running
     * completes. The class-name lookups call this lazily on first use.
     */
    suspend fun indexAllClasses() {
        classNamesOf(generations.current())
    }

    /**
     * The class-name index of [generation]. A ready index returns without suspending. Otherwise the lookup suspends
     * until the build completes, holding no thread, and a cancelled lookup returns at once while the build goes on for
     * the others. It waits through [asDeferred] because `CompletionStage.await()` would cancel the future when the
     * lookup is cancelled, and every lookup on this generation shares that future.
     */
    private suspend fun classNamesOf(generation: ClasspathGeneration): ClassNameIndex {
        val build = classNamesBuild(generation)
        return if (build.isDone && !build.isCompletedExceptionally) build.join() else build.asDeferred().await()
    }

    /**
     * The build of [generation]'s index. The first caller claims the generation's slot and starts the build on
     * [buildExecutor], off the lookup threads; later callers get that future. A claim also replaces a future that
     * completed exceptionally, so a failed or cancelled build cannot poison the slot. Nothing holds a monitor, so a
     * classpath update or close never waits for a build. Each generation keeps its own index, so a build for a replaced
     * classpath is never seen by lookups on the new one.
     */
    private fun classNamesBuild(generation: ClasspathGeneration): CompletableFuture<ClassNameIndex> {
        generation.classNames.value?.takeUnless { it.isCompletedExceptionally }?.let { return it }
        val candidate = CompletableFuture<ClassNameIndex>()
        val build = checkNotNull(
            generation.classNames.update { if (it == null || it.isCompletedExceptionally) candidate else it },
        )
        if (build === candidate) buildExecutor.execute { fillClassNames(generation, candidate) }
        return build
    }

    /**
     * Completes [build], which a lookup claimed for [generation], even when the build fails. A generation that is no
     * longer current when the build starts is not scanned: an update retired it, so its lookups get the current index.
     */
    private fun fillClassNames(generation: ClasspathGeneration, build: CompletableFuture<ClassNameIndex>) {
        val current = generations.current()
        if (current !== generation) {
            generation.classNames.update { if (it === build) null else it }
            classNamesBuild(current).whenComplete { index, failure ->
                if (failure == null) build.complete(index) else build.completeExceptionally(failure)
            }
            return
        }
        runCatching { scanClassNames(generation) }
            .onSuccess { build.complete(it) }
            .onFailure { failure ->
                // The next lookup retries. This build's lookups get no classes rather than waiting forever, and an
                // Error reaches each of them, as it reached the lookup that used to run the build on its own thread.
                generation.classNames.update { if (it === build) null else it }
                if (failure is Error) build.completeExceptionally(failure) else build.complete(ClassNameIndex.EMPTY)
                logger.error(failure) { "Failed to index classes" }
            }
    }

    private fun scanClassNames(generation: ClasspathGeneration): ClassNameIndex {
        logger.info { "Indexing all classes from classpath..." }
        val startTime = System.currentTimeMillis()
        val entries = classpathEntriesFor(
            generation.loader,
            generation.paths,
            System.getProperty("java.class.path").orEmpty(),
        )
        val index = ClassNameIndex.of(classpathIndex.index(entries))
        val elapsedMs = System.currentTimeMillis() - startTime
        logger.info { "Indexed ${index.size} class names in ${elapsedMs}ms" }
        return index
    }

    /**
     * Finds classes by prefix for type parameter completion.
     * Returns list of ClassInfo with simple name and fully qualified name.
     * Suspends while the class index builds. A build that fails with an exception gives no classes; one that fails with
     * an [Error] rethrows that Error here, as in every lookup waiting on that build.
     */
    suspend fun findClassesByPrefix(prefix: String, maxResults: Int = 50): List<ClassInfo> {
        if (prefix.isBlank()) return emptyList()
        return classNamesOf(generations.current()).findBySimpleNamePrefix(prefix, maxResults)
    }

    /**
     * Finds classes by fully qualified prefix (e.g., "java.util.L").
     * Suspends while the class index builds. A build that fails with an exception gives no classes; one that fails with
     * an [Error] rethrows that Error here, as in every lookup waiting on that build.
     */
    suspend fun findClassesByQualifiedPrefix(prefix: String, maxResults: Int = 50): List<ClassInfo> {
        if (prefix.isBlank()) return emptyList()
        return classNamesOf(generations.current()).findByQualifiedPrefix(prefix, maxResults)
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
}

/**
 * The class names of one classpath, for type and import completion. It never changes after [of] builds it, so a lookup
 * reads one whole index without a lock while the index of another classpath is built.
 *
 * @property bySimpleName Simple names in ascending order, each with its fully qualified names in ascending order.
 * @property qualifiedNames Fully qualified names in ascending order.
 */
internal class ClassNameIndex private constructor(
    private val bySimpleName: List<Pair<String, List<String>>>,
    private val qualifiedNames: List<String>,
) {
    /** The number of distinct fully qualified names. */
    val size: Int get() = qualifiedNames.size

    /** The first [maxResults] simple names that start with [prefix] in any case, each with all its variants. */
    fun findBySimpleNamePrefix(prefix: String, maxResults: Int): List<ClassInfo> = bySimpleName.asSequence()
        .filter { (simpleName, _) -> simpleName.startsWith(prefix, ignoreCase = true) }
        .take(maxResults)
        .flatMap { (simpleName, fullNames) -> fullNames.asSequence().map { ClassInfo(simpleName, it, packageOf(it)) } }
        .take(maxResults)
        .toList()

    /** The first [maxResults] fully qualified names that start with [prefix] in any case. */
    fun findByQualifiedPrefix(prefix: String, maxResults: Int): List<ClassInfo> = qualifiedNames.asSequence()
        .filter { it.startsWith(prefix, ignoreCase = true) }
        .take(maxResults)
        .map { ClassInfo(it.substringAfterLast('.'), it, packageOf(it)) }
        .toList()

    companion object {
        val EMPTY = ClassNameIndex(emptyList(), emptyList())

        /** Sorts and deduplicates [classes]. */
        fun of(classes: List<IndexedClass>): ClassNameIndex {
            val bySimpleName = TreeMap<String, TreeSet<String>>()
            val qualifiedNames = TreeSet<String>()
            classes.forEach { (simpleName, fullName) ->
                bySimpleName.getOrPut(simpleName) { TreeSet() }.add(fullName)
                qualifiedNames.add(fullName)
            }
            return ClassNameIndex(
                bySimpleName.map { (simpleName, fullNames) -> simpleName to fullNames.toList() },
                qualifiedNames.toList(),
            )
        }

        private fun packageOf(fullName: String) = fullName.substringBeforeLast('.', "")
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
    private val closed = OnceFlag()

    /** False after close or replacement; file contents are not checked. Do not close concurrently with use. */
    fun isCurrent(): Boolean = !closed.isSet && current()

    override fun close() {
        if (closed.trySet()) release()
    }
}

/** Publishes one generation atomically and retires its loader only after grace and the last lease. */
private class ClasspathGenerations {
    private val logger = KotlinLogging.logger {}
    private val current = SharedValue(generation(ClassLoader.getSystemClassLoader(), emptyList()))
    private var grace: ClasspathGeneration? = null

    fun current(): ClasspathGeneration = current.value

    fun installInitial(loader: ClassLoader) = synchronized(this) {
        check(grace == null && current.value.readers == 0)
        current.set(generation(loader, emptyList()))
    }

    fun replace(loader: ClassLoader, paths: List<Path>) = synchronized(this) {
        val next = runCatching { generation(loader, paths) }.getOrElse { failure ->
            closeLoader(loader)
            throw failure
        }
        val previous = current.exchange(next)
        grace?.let { retire(it, gracePeriod = false) }
        retire(previous, gracePeriod = true)
        grace = previous
    }

    fun acquire(): ClasspathLease = synchronized(this) {
        val generation = current.value
        generation.readers++
        ClasspathLease(
            solver = generation.solver,
            reflection = JvmClasspathReflection { generation.loader },
            classpath = generation.paths,
            classLoader = generation.loader,
            release = { release(generation) },
            current = { synchronized(this) { current.value === generation } },
        )
    }

    fun shutdown() = synchronized(this) {
        grace?.let { retire(it, gracePeriod = false) }
        grace = null
        val previous = current.exchange(generation(ClassLoader.getSystemClassLoader(), emptyList()))
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
        // Lookups use the current generation's index; a lookup already waiting on this one keeps its own reference.
        generation.classNames.set(null)
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

    /** This classpath's class-name index: null until a lookup starts building it, then that build. Lock-free. */
    val classNames = SharedValue<CompletableFuture<ClassNameIndex>?>(null)
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
 * @property parameters Simple JVM type names retained for existing consumers (`Object[]` for varargs).
 * @property parameterNames Available when the class was compiled with `-parameters`; empty otherwise.
 * @property declaringClass The fully qualified name of the class that declares this method.
 * @property displayParameters [parameters] as source writes them (`Object...` when declared varargs), for display only.
 * @property qualifiedParameters Qualified erased JVM types for overload selection, captured with the display snapshot.
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
    val qualifiedParameters: List<String> = parameters,
)

data class ClassInfo(val simpleName: String, val fullName: String, val packageName: String)
