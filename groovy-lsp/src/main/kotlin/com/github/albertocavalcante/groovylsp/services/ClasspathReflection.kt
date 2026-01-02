package com.github.albertocavalcante.groovylsp.services

import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier

interface ClasspathReflection {
    fun getMethods(className: String): List<ReflectedMethod>

    fun loadClass(className: String): Class<*>?
}

class JvmClasspathReflection(private val classLoaderProvider: () -> ClassLoader) : ClasspathReflection {
    private val logger = LoggerFactory.getLogger(JvmClasspathReflection::class.java)

    override fun getMethods(className: String): List<ReflectedMethod> = try {
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
    } catch (e: ClassNotFoundException) {
        logger.debug("Class not found on classpath: $className")
        emptyList()
    } catch (e: NoClassDefFoundError) {
        logger.debug("Class definition not found: $className")
        emptyList()
    } catch (e: Exception) {
        logger.error("Error reflecting on class $className", e)
        emptyList()
    }

    override fun loadClass(className: String): Class<*>? = try {
        classLoaderProvider().loadClass(className)
    } catch (e: ClassNotFoundException) {
        null
    } catch (e: NoClassDefFoundError) {
        null
    } catch (e: Exception) {
        logger.error("Error loading class $className", e)
        null
    }
}
