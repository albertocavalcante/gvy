package com.github.albertocavalcante.groovylsp.services

import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service to manage the classpath and load classes/methods from the user's project and the JDK.
 * Acts as the "Window to the World" for the LSP.
 */
class ClasspathService(
    private val classpathIndex: ClasspathIndex = JvmClasspathIndex(),
    reflectionProvider: ClasspathReflection? = null,
) {
    private val logger = LoggerFactory.getLogger(ClasspathService::class.java)

    // Start with the system classloader to access JDK and Groovy runtime
    private var currentClassLoader: ClassLoader = ClassLoader.getSystemClassLoader()
    private var classpathEntries: List<String> = emptyList()
    private val reflection: ClasspathReflection =
        reflectionProvider ?: JvmClasspathReflection { currentClassLoader }

    // Class index for type parameter completion: SimpleName -> List<FullyQualifiedName>
    private val classIndex = ClassIndex()
    private val isIndexed = AtomicBoolean(false)

    /**
     * Updates the active classloader with the provided paths (JARs or directories).
     *
     * @param paths List of paths to add to the classpath
     */
    fun updateClasspath(paths: List<Path>) {
        val oldClassLoader = currentClassLoader
        val oldClasspathEntries = classpathEntries
        try {
            logger.info("Updating classpath with ${paths.size} paths")
            val urls = paths.map { it.toUri().toURL() }.toTypedArray()
            // Parent is null to strictly separate (or system to inherit JDK/Groovy)
            // We likely want system as parent to get the GDK classes
            currentClassLoader = URLClassLoader(urls, ClassLoader.getSystemClassLoader())
            classpathEntries = paths.map { it.toString() }

            // Invalidate index when classpath changes
            isIndexed.set(false)
            classIndex.clear()
        } catch (e: Exception) {
            logger.error("Failed to update classpath", e)
            // Restore previous classloader to maintain consistent state
            currentClassLoader = oldClassLoader
            classpathEntries = oldClasspathEntries
        }
    }

    /**
     * Indexes all classes from the classpath.
     * This is called lazily on first type parameter completion request.
     */
    fun indexAllClasses() {
        if (isIndexed.get()) return

        synchronized(this) {
            if (isIndexed.get()) return

            logger.info("Indexing all classes from classpath...")
            val startTime = System.currentTimeMillis()

            try {
                classpathIndex.index(resolveClasspathEntries()).forEach { entry ->
                    classIndex.add(entry.simpleName, entry.fullName)
                }

                isIndexed.set(true)
                val elapsedMs = System.currentTimeMillis() - startTime
                logger.info("Indexed ${classIndex.size()} class names in ${elapsedMs}ms")
            } catch (e: Exception) {
                logger.error("Failed to index classes", e)
            }
        }
    }

    private fun resolveClasspathEntries(): List<String> {
        if (classpathEntries.isNotEmpty()) {
            return classpathEntries
        }

        val urlEntries = (currentClassLoader as? URLClassLoader)
            ?.urLs
            ?.mapNotNull { url ->
                if (url.protocol != "file") {
                    return@mapNotNull null
                }
                runCatching { Paths.get(url.toURI()).toString() }.getOrNull()
            }
            .orEmpty()
        if (urlEntries.isNotEmpty()) {
            return urlEntries.distinct()
        }

        val classpathProperty = System.getProperty("java.class.path").orEmpty()
        if (classpathProperty.isBlank()) {
            return emptyList()
        }

        return classpathProperty
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .distinct()
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
     * Tries to load a class by name and returns its reflected methods.
     */
    fun getMethods(className: String): List<ReflectedMethod> = reflection.getMethods(className)

    /**
     * Tries to load a class by name.
     */
    fun loadClass(className: String): Class<*>? = reflection.loadClass(className)

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
}

data class ReflectedMethod(
    val name: String,
    val returnType: String,
    val parameters: List<String>,
    val parameterNames: List<String> = emptyList(),
    val isStatic: Boolean,
    val isPublic: Boolean,
    val doc: String,
)

data class ClassInfo(val simpleName: String, val fullName: String, val packageName: String)
