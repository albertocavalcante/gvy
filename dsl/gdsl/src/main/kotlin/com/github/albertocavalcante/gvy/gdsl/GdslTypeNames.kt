package com.github.albertocavalcante.gvy.gdsl

/**
 * Resolves a class literal's name the way Groovy's default imports would (`Closure` →
 * `groovy.lang.Closure`, `Map` → `java.util.Map`), without loading any class from the script.
 * Lookups go to the server's own class loader with initialization off and only for the
 * default-import packages, so a GDSL file cannot make the server load or initialize its classes.
 */
internal object GdslTypeNames {
    private val DEFAULT_IMPORT_PACKAGES =
        listOf("java.lang.", "java.util.", "java.io.", "java.net.", "groovy.lang.", "groovy.util.")
    private val DEFAULT_IMPORT_CLASSES = mapOf(
        "BigDecimal" to "java.math.BigDecimal",
        "BigInteger" to "java.math.BigInteger",
    )
    private val SIMPLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun resolve(name: String): String {
        if (!SIMPLE_NAME.matches(name)) return name
        DEFAULT_IMPORT_CLASSES[name]?.let { return it }
        return DEFAULT_IMPORT_PACKAGES.asSequence()
            .map { it + name }
            .firstOrNull(::exists)
            ?: name
    }

    private fun exists(qualifiedName: String): Boolean = try {
        Class.forName(qualifiedName, false, GdslTypeNames::class.java.classLoader)
        true
    } catch (ignored: ClassNotFoundException) {
        false
    } catch (ignored: LinkageError) {
        false
    }
}
