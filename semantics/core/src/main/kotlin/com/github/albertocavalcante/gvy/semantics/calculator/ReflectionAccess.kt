package com.github.albertocavalcante.gvy.semantics.calculator

import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.concurrent.write

internal object ReflectionAccess {

    private const val MAX_METHOD_CACHE_SIZE = 1000
    private const val MAX_FIELD_CACHE_SIZE = 1000

    private val methodCacheLock = java.util.concurrent.locks.ReentrantReadWriteLock()
    private val methodCache = object : LinkedHashMap<Pair<Class<*>, String>, Method?>(
        16, // initial capacity
        0.75f, // load factor
        true, // accessOrder=true for LRU
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Class<*>, String>, Method?>?): Boolean =
            size > MAX_METHOD_CACHE_SIZE
    }

    private val fieldCacheLock = java.util.concurrent.locks.ReentrantReadWriteLock()
    private val fieldCache = object : LinkedHashMap<Pair<Class<*>, String>, Field?>(
        16, // initial capacity
        0.75f, // load factor
        true, // accessOrder=true for LRU
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Class<*>, String>, Field?>?): Boolean =
            size > MAX_FIELD_CACHE_SIZE
    }

    fun invokeNoArg(target: Any, methodName: String): Any? = runCatching {
        // A 'get' on an access-ordered LinkedHashMap is a write operation, so we need a write lock.
        // We check for the key and return if present, all within a brief write lock.
        val key = target::class.java to methodName
        methodCacheLock.write {
            methodCache[key]?.let { return@runCatching it.invoke(target) }
        }

        // If not in cache, compute the result outside of any lock
        val method = runCatching { target::class.java.getMethod(methodName) }.getOrNull()

        // After computing, acquire the write lock again to put the result into the cache.
        // Use getOrPut to handle the race condition where another thread might have
        // computed and inserted the same key while we were working.
        val cachedMethod = methodCacheLock.write {
            methodCache.getOrPut(key) { method }
        }

        cachedMethod?.invoke(target)
    }.getOrNull()

    fun getField(target: Any, fieldName: String): Any? = runCatching {
        // A 'get' on an access-ordered LinkedHashMap is a write operation, so we need a write lock.
        // We check for the key and return if present, all within a brief write lock.
        val key = target::class.java to fieldName
        fieldCacheLock.write {
            fieldCache[key]?.let { return@runCatching it.get(target) }
        }

        // If not in cache, compute the result outside of any lock
        val field = runCatching {
            val f = target::class.java.getDeclaredField(fieldName)
            f.isAccessible = true
            f
        }.getOrNull()

        // After computing, acquire the write lock again to put the result into the cache.
        // Use getOrPut to handle the race condition where another thread might have
        // computed and inserted the same key while we were working.
        val cachedField = fieldCacheLock.write {
            fieldCache.getOrPut(key) { field }
        }

        cachedField?.get(target)
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
