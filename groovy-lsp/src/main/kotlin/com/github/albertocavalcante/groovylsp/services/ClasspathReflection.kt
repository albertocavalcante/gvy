package com.github.albertocavalcante.groovylsp.services

import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.reflect.Modifier

interface ClasspathReflection {
    fun getMethods(className: String): List<ReflectedMethod>

    fun loadClass(className: String): Class<*>?
}

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
