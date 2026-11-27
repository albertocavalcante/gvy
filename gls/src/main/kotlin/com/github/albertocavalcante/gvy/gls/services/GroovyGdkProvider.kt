// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.concurrent.SharedValue
import com.github.albertocavalcante.gvy.gls.sources.GroovySourceResolver
import com.github.albertocavalcante.gvy.gls.sources.GroovySourceResolver.Companion.GDK_CLASSES
import com.github.albertocavalcante.gvy.semantics.native.displayParameterTypes
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Modifier

/** Target type to its GDK methods, for example `java.util.List` to `each`, `collect` and the rest. */
private typealias GdkIndex = Map<String, List<QualifiedGdkMethod>>

/**
 * Provides Groovy GDK methods (extension methods like .each, .collect) for types.
 * Scans all GDK classes defined in [GroovySourceResolver.GDK_CLASSES].
 *
 * Now uses GroovySourceResolver to extract real parameter names from Groovy source JARs
 * instead of relying on reflection which provides synthetic names like "arg0", "arg1".
 *
 * Each index is built whole before any lookup reads it, and is rebuilt when the classpath changes: a lookup sees
 * a complete index, the previous one or the new one, and waits if the one it reads is still being built.
 */
class GroovyGdkProvider(
    private val classpathService: ClasspathService,
    private val groovySourceResolver: GroovySourceResolver? = null,
) {
    private val logger = KotlinLogging.logger {}

    // The index for the current classpath; a classpath change installs a fresh one
    private val currentIndex = SharedValue(newIndex())

    /**
     * Builds the GDK index for the current classpath if no call has built it yet, and returns once it is complete.
     * Lookups build it on first use too, so this only moves the build off the request path.
     */
    fun initialize() {
        index()
    }

    /**
     * Replaces the GDK index with one built from the current classpath, and returns once it is complete. Call it after
     * every [ClasspathService.updateClasspath]. Loading is parent-first, so most GDK classes come from the server's
     * own Groovy, but one the server's Groovy lacks comes from the workspace: `DateGroovyMethods` is in Groovy 3 and
     * not in Groovy 4. A lookup that starts before the swap finishes on the previous index; one that starts after it
     * waits for the new one.
     */
    fun onClasspathChanged() {
        currentIndex.set(newIndex())
        index()
    }

    /**
     * The current complete index. Built by its first caller; callers that arrive during the build wait for it. A lookup
     * reads it once and passes it down, so every type it reads comes from the same index.
     */
    private fun index(): GdkIndex = currentIndex.value.value

    private fun newIndex(): Lazy<GdkIndex> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { buildIndex() }

    /**
     * Indexes every class in [GDK_CLASSES] into a map nothing else can see until it is complete. A class that fails
     * to link is left out instead of failing the build. A source resolver, when given, is initialized inside every
     * build. Production passes none; if one is ever wired, lookups wait for its source download (#2398).
     */
    private fun buildIndex(): GdkIndex {
        // Initialize source resolver if available
        // Note: runBlocking is intentional here - the index must be complete
        // before the provider is usable.
        // Moving to async would require significant refactoring of callers.
        groovySourceResolver?.let { resolver ->
            runBlocking {
                val success = resolver.initialize()
                if (success) {
                    logger.info { "Successfully initialized Groovy source resolver for parameter names" }
                } else {
                    logger.warn { "Failed to initialize Groovy source resolver, will use reflection-based names" }
                }
            }
        }

        val methodsByType = mutableMapOf<String, MutableList<QualifiedGdkMethod>>()
        // Use the same GDK classes as GroovySourceResolver for consistency
        // This ensures parameter names from sources match the methods we index
        GDK_CLASSES.forEach { className ->
            indexGdkClass(className, methodsByType)
        }

        logger.info { "Initialized GDK Provider with ${methodsByType.size} target types" }
        return methodsByType
    }

    /** Adds the GDK methods of [className] to [into]. A class that fails to link adds nothing. */
    private fun indexGdkClass(className: String, into: MutableMap<String, MutableList<QualifiedGdkMethod>>) {
        reflectSafely({ "GDK class $className" }, Unit) {
            val clazz = classpathService.loadClass(className) ?: return@reflectSafely

            // GDK methods are always public static
            val methods = clazz.methods.filter {
                Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) && it.parameterCount > 0
            }

            val found = methods.map { method ->
                // The first parameter is the "self" type (the type being extended)
                val selfType = method.parameterTypes[0].name // Full qualified name

                // Parameters excluding the first one (self)
                val parameterTypes = method.parameterTypes.drop(1).map { it.simpleName }

                // Try to get real parameter names from source resolver first
                val parameterNames = groovySourceResolver?.getParameterNames(
                    clazz.simpleName,
                    method.name,
                    parameterTypes,
                ) ?: method.parameters.drop(1).map { it.name } // Fallback to reflection

                val methodInfo = GdkExtensionMethod(
                    name = method.name,
                    returnType = method.returnType.simpleName,
                    parameterTypes = parameterTypes,
                    parameterNames = parameterNames,
                    originClass = clazz.simpleName,
                    doc = "Groovy GDK method from ${clazz.simpleName}",
                    displayParameterTypes = method.displayParameterTypes().drop(1),
                )

                // Also index by simple name for looser matching if needed, or interfaces
                // e.g. List -> Collection -> Iterable
                // For now, we just index the exact type.
                // Enhancing this to walk the hierarchy (e.g. if I have an ArrayList, show Collection extensions)
                // will be done in the lookup phase.
                selfType to QualifiedGdkMethod(methodInfo, method.parameterTypes.drop(1).map { it.typeName })
            }
            // Added only once the whole class has been read, so a class that fails partway adds nothing
            found.forEach { (selfType, method) -> into.getOrPut(selfType) { mutableListOf() }.add(method) }
        }
    }

    /**
     * Returns GDK methods available for a specific type.
     * Handles class hierarchy (e.g. Iterable methods are available on List).
     */
    fun getMethodsForType(className: String): List<GdkExtensionMethod> =
        collectMethodsForType(className).map { it.display }.distinctBy { it.signatureKey() }

    /**
     * Like [getMethodsForType], but keeps each overload's qualified erased parameter types beside its
     * simple-name display so callers can de-duplicate against source members exactly.
     */
    internal fun getQualifiedMethodsForType(className: String): List<QualifiedGdkMethod> =
        collectMethodsForType(className).distinctBy { it.display.name to it.qualifiedParameters }

    /**
     * Qualified GDK methods for several types at once (a receiver and its binary ancestors), de-duplicated
     * by name and erased parameters. The Object extensions are appended once, not once per type.
     */
    internal fun getQualifiedMethodsForTypes(classNames: List<String>): List<QualifiedGdkMethod> {
        val snapshot = index()
        val results = mutableListOf<QualifiedGdkMethod>()
        classNames.distinct().forEach { snapshot.collectTypeMethods(results, it) }
        snapshot.appendCachedMethods(results, JAVA_LANG_OBJECT)
        return results.distinctBy { it.display.name to it.qualifiedParameters }
    }

    private fun collectMethodsForType(className: String): List<QualifiedGdkMethod> {
        val snapshot = index()
        val results = mutableListOf<QualifiedGdkMethod>()
        snapshot.collectTypeMethods(results, className)

        // Always include Object methods (every Groovy object is an Object).
        snapshot.appendCachedMethods(results, JAVA_LANG_OBJECT)

        return results
    }

    private fun GdkIndex.collectTypeMethods(results: MutableList<QualifiedGdkMethod>, className: String) {
        appendCachedMethods(results, className)

        val clazz = classpathService.loadClass(className)
        if (clazz != null) {
            appendCachedMethodsFromHierarchy(results, clazz)
        } else {
            appendFallbackMethods(results, className)
        }
    }

    private fun GdkIndex.appendCachedMethods(results: MutableList<QualifiedGdkMethod>, className: String) {
        this[className]?.let(results::addAll)
    }

    private fun GdkIndex.appendCachedMethodsFromHierarchy(results: MutableList<QualifiedGdkMethod>, clazz: Class<*>) {
        collectHierarchyTypes(clazz).forEach { parent ->
            appendCachedMethods(results, parent.name)
        }
    }

    /** Every superclass and every interface reachable through any chain (`ArrayList` -> `Collection` -> `Iterable`). */
    private fun collectHierarchyTypes(clazz: Class<*>): Sequence<Class<*>> = sequence {
        val seen = mutableSetOf<Class<*>>()
        val pending = ArrayDeque<Class<*>>()
        pending.addLast(clazz)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            for (parent in current.interfaces) {
                if (seen.add(parent)) {
                    yield(parent)
                    pending.addLast(parent)
                }
            }
            current.superclass?.let { superClass ->
                if (seen.add(superClass)) yield(superClass)
                pending.addLast(superClass)
            }
        }
    }

    private fun GdkIndex.appendFallbackMethods(results: MutableList<QualifiedGdkMethod>, className: String) {
        when (className) {
            in FALLBACK_LIST_TYPES -> {
                appendCachedMethods(results, JAVA_UTIL_LIST)
                appendCachedMethods(results, JAVA_UTIL_COLLECTION)
                appendCachedMethods(results, JAVA_LANG_ITERABLE)
            }

            JAVA_LANG_STRING -> appendCachedMethods(results, JAVA_LANG_CHAR_SEQUENCE)
        }
    }

    private fun GdkExtensionMethod.signatureKey(): String = name + parameterTypes.joinToString(",")

    private companion object {
        private const val JAVA_LANG_OBJECT = "java.lang.Object"
        private const val JAVA_LANG_STRING = "java.lang.String"
        private const val JAVA_LANG_CHAR_SEQUENCE = "java.lang.CharSequence"
        private const val JAVA_LANG_ITERABLE = "java.lang.Iterable"
        private const val JAVA_UTIL_LIST = "java.util.List"
        private const val JAVA_UTIL_COLLECTION = "java.util.Collection"

        private val FALLBACK_LIST_TYPES = setOf("java.util.ArrayList", "java.util.LinkedList")
    }
}

/** A GDK method with simple-name display types and its qualified erased parameter identity. */
internal data class QualifiedGdkMethod(val display: GdkExtensionMethod, val qualifiedParameters: List<String>)

/** Erased simple parameter identities stay separate from source display (`Object[]` versus `Object...`). */
data class GdkExtensionMethod(
    val name: String,
    val returnType: String,
    val parameterTypes: List<String>,
    val parameterNames: List<String>,
    val originClass: String,
    val doc: String,
    val displayParameterTypes: List<String> = parameterTypes,
) {
    // Backward compatibility
    @Deprecated(
        message = "Use parameterTypes instead for clarity",
        replaceWith = ReplaceWith("parameterTypes"),
    )
    val parameters: List<String> get() = parameterTypes
}
