package com.github.albertocavalcante.groovylsp.services

import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provides Groovy GDK methods (extension methods like .each, .collect) for types.
 * Scans DefaultGroovyMethods and StringGroovyMethods.
 *
 * TODO(#830): Implement Groovy source JAR resolution for GDK parameter names
 *   Current limitation: Parameter names for GDK methods are obtained via reflection,
 *   which provides non-deterministic names like "arg0", "arg1" when debug symbols are missing.
 *
 *   Proposed solution: Similar to JdkSourceResolver handling of src.zip, implement
 *   GroovySourceResolver to extract parameter names from Groovy source JARs:
 *
 *   1. Detect Groovy version from classpath
 *      - Scan classpath for groovy-*.jar (e.g., groovy-4.0.23.jar)
 *      - Extract version string (e.g., "4.0.23")
 *
 *   2. Resolve corresponding sources JAR
 *      - Look for adjacent groovy-4.0.23-sources.jar
 *      - If not found, download from Maven Central:
 *        https://repo1.maven.org/maven2/org/apache/groovy/groovy/{version}/groovy-{version}-sources.jar
 *      - Cache downloaded JAR in ~/.gls/cache/groovy-sources/
 *
 *   3. Extract and index GDK source files
 *      - Extract DefaultGroovyMethods.java and related GDK classes
 *      - Use JavaSourceInspector to parse method declarations
 *      - Build index: methodName -> List<ParameterName>
 *
 *   4. Enhance GdkExtensionMethod with real parameter names
 *      - Replace reflection-based parameter names with source-extracted names
 *      - Provides deterministic parameter names for inlay hints and completion
 *
 *   Benefits:
 *   - Accurate parameter names for GDK methods in IDE (e.g., "closure" instead of "arg0")
 *   - No need to rebuild Groovy with debug symbols
 *   - Consistent experience across all GDK methods
 *
 *   Implementation files to create:
 *   - groovy-lsp/src/main/kotlin/com/github/albertocavalcante/groovylsp/sources/GroovySourceResolver.kt
 *   - groovy-lsp/src/test/kotlin/com/github/albertocavalcante/groovylsp/sources/GroovySourceResolverTest.kt
 *
 *   See: https://github.com/albertocavalcante/groovy-lsp/issues/830
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
                parameterTypes = method.parameterTypes.drop(1).map { it.simpleName },
                parameterNames = method.parameters.drop(1).map { it.name },
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
