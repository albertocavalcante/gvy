package com.github.albertocavalcante.groovylsp.services

import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provides Groovy GDK methods (extension methods like .each, .collect) for types.
 * Scans DefaultGroovyMethods and StringGroovyMethods.
 */
class GroovyGdkProvider(private val classpathService: ClasspathService) {
    private val logger = LoggerFactory.getLogger(GroovyGdkProvider::class.java)

    // Map of <TargetType, List<ExtensionMethod>>
    // e.g. "java.util.List" -> [each, collect, ...]
    private val cache = ConcurrentHashMap<String, List<GdkExtensionMethod>>()
    private val isInitialized = AtomicBoolean(false)

    /**
     * Initializes the GDK index. Call this on startup.
     */
    fun initialize() {
        if (isInitialized.get()) return

        // Common GDK classes in Groovy
        val gdkClasses = listOf(
            "org.codehaus.groovy.runtime.DefaultGroovyMethods",
            "org.codehaus.groovy.runtime.StringGroovyMethods",
            "org.codehaus.groovy.vmplugin.v8.PluginDefaultGroovyMethods",
        )

        gdkClasses.forEach { className ->
            indexGdkClass(className)
        }

        isInitialized.set(true)
        logger.info("Initialized GDK Provider with ${cache.size} target types")
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

            val methodInfo = GdkExtensionMethod(
                name = method.name,
                returnType = method.returnType.simpleName,
                // Parameters excluding the first one (self)
                parameters = method.parameterTypes.drop(1).map { it.simpleName },
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
        if (!isInitialized.get()) initialize()

        // 1. Exact match
        val results = (cache[className] ?: emptyList()).toMutableList()

        // 2. Hierarchy walk (basic implementation)
        // We need the actual Class object to walk the hierarchy
        val clazz = classpathService.loadClass(className)
        if (clazz != null) {
            val superClasses = mutableListOf<Class<*>>()
            // Add interfaces
            superClasses.addAll(clazz.interfaces)
            // Add superclass
            var superC = clazz.superclass
            while (superC != null) {
                superClasses.add(superC)
                superClasses.addAll(superC.interfaces)
                superC = superC.superclass
            }

            superClasses.forEach { parent ->
                cache[parent.name]?.let { results.addAll(it) }
            }
        } else {
            // If we can't load the class, try some common fallbacks for standard types
            if (className == "java.util.ArrayList" || className == "java.util.LinkedList") {
                cache["java.util.List"]?.let { results.addAll(it) }
                cache["java.util.Collection"]?.let { results.addAll(it) }
                cache["java.lang.Iterable"]?.let { results.addAll(it) }
            }
            if (className == "java.lang.String") {
                cache["java.lang.CharSequence"]?.let { results.addAll(it) }
            }
        }

        // 3. Always include Object methods (as every object in Groovy is an Object, and interfaces are implemented by Objects)
        cache["java.lang.Object"]?.let { results.addAll(it) }

        return results.distinctBy {
            it.name + it.parameters.joinToString(",")
        }
    }
}

data class GdkExtensionMethod(
    val name: String,
    val returnType: String,
    val parameters: List<String>,
    val originClass: String,
    val doc: String,
)
