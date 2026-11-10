package com.github.albertocavalcante.gvy.gls.services

import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.reflect.Modifier

interface ClasspathReflection {
    fun getMethods(className: String): List<ReflectedMethod>

    fun getFields(className: String): List<ReflectedField>

    fun loadClass(className: String): Class<*>?
}

data class ReflectedField(
    val name: String,
    val type: String,
    val isStatic: Boolean,
    val isPublic: Boolean,
    val isFinal: Boolean,
    val declaringClass: String,
)

class JvmClasspathReflection(private val classLoaderProvider: () -> ClassLoader) : ClasspathReflection {
    private val logger = KotlinLogging.logger {}

    override fun getMethods(className: String): List<ReflectedMethod> = runCatching {
        val clazz = classLoaderProvider().loadClass(className)
        clazz.methods.map { method ->
            ReflectedMethod(
                name = method.name,
                returnType = method.returnType.simpleName,
                parameters = method.parameterTypes.map { it.simpleName },
                parameterNames = method.parameters.map { it.name },
                isStatic = Modifier.isStatic(method.modifiers),
                isPublic = Modifier.isPublic(method.modifiers),
                doc = "JDK/Classpath method from ${clazz.simpleName}",
                declaringClass = method.declaringClass.name,
            )
        }
    }.getOrElse { throwable ->
        when (throwable) {
            is ClassNotFoundException -> {
                logger.debug(throwable) { "Class not found on classpath: $className" }
                emptyList()
            }

            is NoClassDefFoundError -> {
                logger.debug(throwable) { "Class definition not found: $className" }
                emptyList()
            }

            is Exception -> {
                logger.error(throwable) { "Error reflecting on class $className" }
                emptyList()
            }

            else -> throw throwable
        }
    }

    override fun getFields(className: String): List<ReflectedField> = runCatching {
        val clazz = classLoaderProvider().loadClass(className)
        clazz.fields.map { field ->
            ReflectedField(
                name = field.name,
                type = field.type.simpleName,
                isStatic = Modifier.isStatic(field.modifiers),
                isPublic = Modifier.isPublic(field.modifiers),
                isFinal = Modifier.isFinal(field.modifiers),
                declaringClass = field.declaringClass.name,
            )
        }
    }.getOrElse { throwable ->
        when (throwable) {
            is ClassNotFoundException -> {
                logger.debug(throwable) { "Class not found on classpath: $className" }
                emptyList()
            }

            is NoClassDefFoundError -> {
                logger.debug(throwable) { "Class definition not found: $className" }
                emptyList()
            }

            is Exception -> {
                logger.error(throwable) { "Error reflecting on class $className" }
                emptyList()
            }

            else -> throw throwable
        }
    }

    override fun loadClass(className: String): Class<*>? =
        runCatching { classLoaderProvider().loadClass(className) }.getOrElse { throwable ->
            when (throwable) {
                is ClassNotFoundException -> null
                is NoClassDefFoundError -> null
                is Exception -> {
                    logger.error(throwable) { "Error loading class $className" }
                    null
                }

                else -> throw throwable
            }
        }
}

private val safeReflectionLogger = KotlinLogging.logger {}

/**
 * Runs reflection on a class from the user's classpath. A missing dependency type, or a loader closed by a
 * classpath update while the caller still held the class, surfaces as a [LinkageError] (e.g.
 * `NoClassDefFoundError`) or [TypeNotPresentException]: degrade to [fallback] and log at debug instead of
 * failing the request.
 */
internal inline fun <T> reflectSafely(crossinline what: () -> String, fallback: T, block: () -> T): T = try {
    block()
} catch (error: LinkageError) {
    reportIncompleteReflection(what(), error)
    fallback
} catch (error: TypeNotPresentException) {
    reportIncompleteReflection(what(), error)
    fallback
}

// Bounded: past the cap further distinct failures stay at debug instead of growing the set.
private const val MAX_WARNED_REFLECTION = 1024
private val warnedReflection = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

/**
 * A missing class (a dependency absent, or its loader closed) is expected and logged at debug. Other linkage
 * failures (IncompatibleClassChangeError, ExceptionInInitializerError) point at a broken classpath: warn once.
 */
internal fun reportIncompleteReflection(what: String, error: Throwable) {
    if (error is NoClassDefFoundError || error is TypeNotPresentException) {
        safeReflectionLogger.debug(error) { "Incomplete classpath reflection: $what" }
    } else if (warnedReflection.size < MAX_WARNED_REFLECTION && warnedReflection.add(what)) {
        safeReflectionLogger.warn(error) { "Classpath reflection failed: $what" }
    } else {
        safeReflectionLogger.debug(error) { "Classpath reflection failed: $what" }
    }
}
