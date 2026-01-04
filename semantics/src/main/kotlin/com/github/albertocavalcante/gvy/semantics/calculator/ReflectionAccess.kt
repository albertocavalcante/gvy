package com.github.albertocavalcante.gvy.semantics.calculator

internal object ReflectionAccess {

    fun invokeNoArg(target: Any, methodName: String): Any? = runCatching {
        val method = target::class.java.getMethod(methodName)
        method.invoke(target)
    }.getOrNull()

    fun getField(target: Any, fieldName: String): Any? = runCatching {
        val field = target::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.get(target)
    }.getOrNull()

    fun getProperty(target: Any, propertyName: String): Any? {
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
