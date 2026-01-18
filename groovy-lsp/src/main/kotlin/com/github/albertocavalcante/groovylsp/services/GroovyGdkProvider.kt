package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.sources.GroovySourceResolver
import com.github.albertocavalcante.groovylsp.sources.GroovySourceResolver.Companion.GDK_CLASSES
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provides Groovy GDK methods (extension methods like .each, .collect) for types.
 * Scans all GDK classes defined in [GroovySourceResolver.GDK_CLASSES].
 *
 * Now uses GroovySourceResolver to extract real parameter names from Groovy source JARs
 * instead of relying on reflection which provides synthetic names like "arg0", "arg1".
 */
class GroovyGdkProvider(
    private val classpathService: ClasspathService,
    private val groovySourceResolver: GroovySourceResolver? = null,
) {
    private val logger = KotlinLogging.logger {}

    // Map of <TargetType, List<ExtensionMethod>>
    // e.g. "java.util.List" -> [each, collect, ...]
    private val cache = ConcurrentHashMap<String, List<GdkExtensionMethod>>()
    private val isInitialized = AtomicBoolean(false)

    /**
     * Initializes the GDK index. Call this on startup.
     */
    fun initialize() {
        if (!isInitialized.compareAndSet(false, true)) return

        // Initialize source resolver if available
        // Note: runBlocking is intentional here - GDK initialization is a one-time
        // startup cost that must complete before the provider is usable.
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

        // Use the same GDK classes as GroovySourceResolver for consistency
        // This ensures parameter names from sources match the methods we index
        GDK_CLASSES.forEach { className ->
            indexGdkClass(className)
        }

        logger.info { "Initialized GDK Provider with ${cache.size} target types" }
    }

    private fun indexGdkClass(className: String) {
        val clazz = classpathService.loadClass(className) ?: return

        // GDK methods are always public static
        val methods = clazz.methods.filter {
            Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) && it.parameterCount > 0
        }

        methods.forEach { method ->
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
            )

            cache.compute(selfType) { _, list ->
                val newList = list?.toMutableList() ?: mutableListOf()
                newList.add(methodInfo)
                newList
            }

            // Also index by simple name for looser matching if needed, or interfaces
            // e.g. List -> Collection -> Iterable
            // For now, we just index the exact type.
            // Enhancing this to walk the hierarchy (e.g. if I have an ArrayList, show Collection extensions)
            // will be done in the lookup phase.
        }
    }

    /**
     * Returns GDK methods available for a specific type.
     * Handles class hierarchy (e.g. Iterable methods are available on List).
     */
    fun getMethodsForType(className: String): List<GdkExtensionMethod> {
        ensureInitialized()

        val results = mutableListOf<GdkExtensionMethod>()
        appendCachedMethods(results, className)

        val clazz = classpathService.loadClass(className)
        if (clazz != null) {
            appendCachedMethodsFromHierarchy(results, clazz)
        } else {
            appendFallbackMethods(results, className)
        }

        // Always include Object methods (every Groovy object is an Object).
        appendCachedMethods(results, JAVA_LANG_OBJECT)

        return results.distinctBy { it.signatureKey() }
    }

    private fun ensureInitialized() {
        if (!isInitialized.get()) initialize()
    }

    private fun appendCachedMethods(results: MutableList<GdkExtensionMethod>, className: String) {
        cache[className]?.let(results::addAll)
    }

    private fun appendCachedMethodsFromHierarchy(results: MutableList<GdkExtensionMethod>, clazz: Class<*>) {
        collectHierarchyTypes(clazz).forEach { parent ->
            appendCachedMethods(results, parent.name)
        }
    }

    private fun collectHierarchyTypes(clazz: Class<*>): Sequence<Class<*>> = sequence {
        yieldAll(clazz.interfaces.asSequence())

        var superClass = clazz.superclass
        while (superClass != null) {
            yield(superClass)
            yieldAll(superClass.interfaces.asSequence())
            superClass = superClass.superclass
        }
    }

    private fun appendFallbackMethods(results: MutableList<GdkExtensionMethod>, className: String) {
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

data class GdkExtensionMethod(
    val name: String,
    val returnType: String,
    val parameterTypes: List<String>,
    val parameterNames: List<String>,
    val originClass: String,
    val doc: String,
) {
    // Backward compatibility
    @Deprecated(
        message = "Use parameterTypes instead for clarity",
        replaceWith = ReplaceWith("parameterTypes"),
    )
    val parameters: List<String> get() = parameterTypes
}
