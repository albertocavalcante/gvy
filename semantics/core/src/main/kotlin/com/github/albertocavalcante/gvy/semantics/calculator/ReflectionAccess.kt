package com.github.albertocavalcante.gvy.semantics.calculator

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal object ReflectionAccess {

    private val methodCache = ConcurrentHashMap<Pair<Class<*>, String>, Method?>()
    private val fieldCache = ConcurrentHashMap<Pair<Class<*>, String>, Field?>()

    fun invokeNoArg(target: Any, methodName: String): Any? = runCatching {
        val method = methodCache.getOrPut(target::class.java to methodName) {
            runCatching { target::class.java.getMethod(methodName) }.getOrNull()
        } ?: return null
        method.invoke(target)
    }.getOrNull()

    fun getField(target: Any, fieldName: String): Any? = runCatching {
        val field = fieldCache.getOrPut(target::class.java to fieldName) {
            runCatching {
                val f = target::class.java.getDeclaredField(fieldName)
                f.isAccessible = true
                f
            }.getOrNull()
        } ?: return null
        field.get(target)
    }.getOrNull()

    fun getProperty(target: Any, propertyName: String): Any? {
        if (propertyName.isEmpty()) return null

        // Simple JavaBeans-style getter resolution: getXxx.
        // This is sufficient for Groovy AST node access patterns used here; it does not attempt to
        // handle acronym edge-cases like url -> getURL.
        val getterName = "get" + propertyName.replaceFirstChar { it.uppercase() }
        return invokeNoArg(target, getterName) ?: getField(target, propertyName)
    }

    fun getStringProperty(target: Any, propertyName: String): String? = getProperty(target, propertyName) as? String

    fun getStringFromGetterOrField(target: Any, getterName: String, fieldName: String): String? {
        val fromGetter = invokeNoArg(target, getterName) as? String
        if (fromGetter != null) return fromGetter
        return getField(target, fieldName) as? String
    }

    fun getListFromGetterOrField(target: Any, getterName: String, fieldName: String): List<Any>? {
        val fromGetter = invokeNoArg(target, getterName)
        if (fromGetter is List<*>) return fromGetter.filterNotNull()

        val fromField = getField(target, fieldName)
        if (fromField is List<*>) return fromField.filterNotNull()

        return null
    }
}
