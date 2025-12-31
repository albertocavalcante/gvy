package com.github.albertocavalcante.groovyparser.resolution.typesolvers

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.groovyparser.resolution.reflectionmodel.ReflectionClassDeclaration
import com.github.albertocavalcante.groovyparser.resolution.reflectionmodel.ReflectionEnumDeclaration
import com.github.albertocavalcante.groovyparser.resolution.reflectionmodel.ReflectionInterfaceDeclaration
import java.io.Closeable
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * Resolves types from JAR files.
 *
 * This solver loads classes from specified JAR files using a dedicated URLClassLoader.
 * It builds an index of available classes for fast lookups.
 *
 * Usage:
 * ```kotlin
 * val solver = JarTypeSolver(Path.of("/path/to/library.jar"))
 * val ref = solver.tryToSolveType("com.example.MyClass")
 * ```
 *
 * @param jarPath Path to the JAR file
 */
class JarTypeSolver(private val jarPath: Path) :
    TypeSolver,
    Closeable {

    override var parent: TypeSolver? = null

    private val classLoader: URLClassLoader
    private val classIndex: Set<String>
    private val cache = ConcurrentHashMap<String, SymbolReference<ResolvedTypeDeclaration>>()

    init {
        require(jarPath.toFile().exists()) { "JAR file does not exist: $jarPath" }
        require(jarPath.toString().endsWith(".jar")) { "Not a JAR file: $jarPath" }

        // Create a class loader for this JAR
        classLoader = URLClassLoader(
            arrayOf(jarPath.toUri().toURL()),
            JarTypeSolver::class.java.classLoader,
        )

        // Index all classes in the JAR for fast lookups
        classIndex = buildClassIndex()
    }

    /**
     * Creates a JarTypeSolver from multiple JARs.
     */
    companion object {
        /**
         * Creates a combined solver from multiple JAR files.
         */
        fun fromJars(vararg paths: Path): CombinedTypeSolver {
            val combined = CombinedTypeSolver()
            paths.forEach { path ->
                combined.add(JarTypeSolver(path))
            }
            return combined
        }

        /**
         * Creates a solver from all JARs in a directory.
         */
        fun fromDirectory(directory: Path): CombinedTypeSolver {
            val combined = CombinedTypeSolver()
            directory.toFile().listFiles { file -> file.extension == "jar" }
                ?.forEach { jarFile ->
                    try {
                        combined.add(JarTypeSolver(jarFile.toPath()))
                    } catch (e: Exception) {
                        // Skip invalid JARs
                    }
                }
            return combined
        }
    }

    private fun buildClassIndex(): Set<String> {
        val classes = mutableSetOf<String>()
        JarFile(jarPath.toFile()).use { jar ->
            jar.entries().asSequence()
                .filter { it.name.endsWith(".class") && !it.name.contains("$") }
                .forEach { entry ->
                    val className = entry.name
                        .removeSuffix(".class")
                        .replace('/', '.')
                    classes.add(className)
                }
        }
        return classes
    }

    override fun tryToSolveType(name: String): SymbolReference<ResolvedTypeDeclaration> {
        // Quick check if this class exists in our JAR
        if (!classIndex.contains(name) && !hasInnerClass(name)) {
            return SymbolReference.unsolved()
        }

        return cache.getOrPut(name) { resolveFromJar(name) }
    }

    private fun hasInnerClass(name: String): Boolean {
        // Check if this might be an inner class (contains $)
        val outerName = name.substringBeforeLast('.')
        val innerName = name.substringAfterLast('.')
        return classIndex.any { it.startsWith("$outerName\$$innerName") }
    }

    private fun resolveFromJar(name: String): SymbolReference<ResolvedTypeDeclaration> = try {
        val clazz = classLoader.loadClass(name)
        val declaration = createDeclaration(clazz)
        SymbolReference.solved(declaration)
    } catch (e: ClassNotFoundException) {
        SymbolReference.unsolved()
    } catch (e: NoClassDefFoundError) {
        // Missing dependency
        SymbolReference.unsolved()
    } catch (e: LinkageError) {
        // Class loading issue
        SymbolReference.unsolved()
    }

    private fun createDeclaration(clazz: Class<*>): ResolvedTypeDeclaration = when {
        clazz.isEnum -> ReflectionEnumDeclaration(clazz, this)
        clazz.isInterface -> ReflectionInterfaceDeclaration(clazz, this)
        else -> ReflectionClassDeclaration(clazz, this)
    }

    /**
     * Returns all class names available in this JAR.
     */
    fun getKnownClasses(): Set<String> = classIndex.toSet()

    /**
     * Returns the number of classes in this JAR.
     */
    fun getClassCount(): Int = classIndex.size

    /**
     * Closes the underlying class loader.
     */
    override fun close() {
        classLoader.close()
    }

    override fun toString(): String = "JarTypeSolver($jarPath, ${classIndex.size} classes)"
}
