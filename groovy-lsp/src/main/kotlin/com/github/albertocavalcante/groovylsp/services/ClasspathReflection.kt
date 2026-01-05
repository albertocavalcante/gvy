package com.github.albertocavalcante.groovylsp.services

import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier

interface ClasspathReflection {
    fun getMethods(className: String): List<ReflectedMethod>

    fun loadClass(className: String): Class<*>?
}

class JvmClasspathReflection(private val classLoaderProvider: () -> ClassLoader) : ClasspathReflection {
    private val logger = LoggerFactory.getLogger(JvmClasspathReflection::class.java)

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
                logger.debug("Class not found on classpath: $className", throwable)
                emptyList()
            }

            is NoClassDefFoundError -> {
                logger.debug("Class definition not found: $className", throwable)
                emptyList()
            }

            is Exception -> {
                logger.error("Error reflecting on class $className", throwable)
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
                    logger.error("Error loading class $className", throwable)
                    null
                }

                else -> throw throwable
            }
        }
}
