package com.github.albertocavalcante.gvy.semantics.calculator

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.concurrent.write

class ReflectionAccessTest {

    private class GetterBackedList(private val expressions: List<Any?>) {
        private var calls: Int = 0

        fun callCount(): Int = calls

        fun getExpressions(): List<Any?> {
            calls += 1

            return if (calls == 1) {
                expressions.toList()
            } else {
                buildList {
                    addAll(expressions)
                    add("unused")
                }
            }
        }
    }

    private class FieldOnlyList {
        @JvmField
        val expressions: List<Any?> = listOf("a", null, "b")
    }

    private class NotAList {
        private var calls: Int = 0

        fun getExpressions(): Any {
            calls += 1
            return if (calls == 1) 123 else 456
        }

        @JvmField
        val expressions: Any = "nope"
    }

    @Test
    fun `getListFromGetterOrField prefers getter list and filters nulls`() {
        val node = GetterBackedList(listOf(1, null, "x"))

        val result = ReflectionAccess.getListFromGetterOrField(node, "getExpressions", "expressions")

        assertEquals(listOf(1, "x"), result)
        assertEquals(1, node.callCount())
    }

    @Test
    fun `getListFromGetterOrField falls back to field list and filters nulls`() {
        val node = FieldOnlyList()

        val result = ReflectionAccess.getListFromGetterOrField(node, "getExpressions", "expressions")

        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun `getListFromGetterOrField returns null when neither getter nor field is a list`() {
        val node = NotAList()

        val result = ReflectionAccess.getListFromGetterOrField(node, "getExpressions", "expressions")

        assertNull(result)
    }

    @Test
    fun `getProperty returns null for empty property name`() {
        val node = FieldOnlyList()

        val result = ReflectionAccess.getProperty(node, "")

        assertNull(result)
    }

    // Helper methods to access private cache fields for testing
    private fun getMethodCache(): MutableMap<*, *> {
        val field = ReflectionAccess::class.java.getDeclaredField("methodCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(ReflectionAccess) as MutableMap<*, *>
    }

    private fun getFieldCache(): MutableMap<*, *> {
        val field = ReflectionAccess::class.java.getDeclaredField("fieldCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(ReflectionAccess) as MutableMap<*, *>
    }

    private fun getMethodCacheLock(): java.util.concurrent.locks.ReentrantReadWriteLock {
        val field = ReflectionAccess::class.java.getDeclaredField("methodCacheLock")
        field.isAccessible = true
        return field.get(ReflectionAccess) as java.util.concurrent.locks.ReentrantReadWriteLock
    }

    private fun getFieldCacheLock(): java.util.concurrent.locks.ReentrantReadWriteLock {
        val field = ReflectionAccess::class.java.getDeclaredField("fieldCacheLock")
        field.isAccessible = true
        return field.get(ReflectionAccess) as java.util.concurrent.locks.ReentrantReadWriteLock
    }

    private fun readPrivateIntConstant(clazz: Class<*>, fieldName: String): Int {
        val field = clazz.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.getInt(null)
    }

    @AfterEach
    fun clearCaches() {
        // Clear the caches after each test to avoid interference
        getMethodCacheLock().write { getMethodCache().clear() }
        getFieldCacheLock().write { getFieldCache().clear() }
    }

    @Test
    fun `methodCache evicts oldest entries when exceeding max size`() {
        val maxSize = readPrivateIntConstant(ReflectionAccess::class.java, "MAX_METHOD_CACHE_SIZE")
        val cache = getMethodCache()

        // Clear cache first
        getMethodCacheLock().write { cache.clear() }

        // Create test objects with unique classes to populate the cache
        // We'll create (maxSize + 100) unique method lookups
        val testObjects = (1..maxSize + 100).map {
            object {
                @Suppress("unused")
                fun testMethod(): String = "result"
            }
        }

        // Trigger method lookups for each object to populate the cache
        testObjects.forEach { obj ->
            ReflectionAccess.invokeNoArg(obj, "testMethod")
        }

        // Cache size should not exceed max size
        val finalSize = getMethodCacheLock().write { cache.size }
        assertTrue(
            finalSize <= maxSize,
            "Method cache size $finalSize should not exceed MAX_METHOD_CACHE_SIZE $maxSize",
        )
    }

    @Test
    fun `fieldCache evicts oldest entries when exceeding max size`() {
        val maxSize = readPrivateIntConstant(ReflectionAccess::class.java, "MAX_FIELD_CACHE_SIZE")
        val cache = getFieldCache()

        // Clear cache first
        getFieldCacheLock().write { cache.clear() }

        // Create test objects with unique classes to populate the cache
        // We'll create (maxSize + 100) unique field lookups
        val testObjects = (1..maxSize + 100).map {
            object {
                @Suppress("unused")
                @JvmField
                val field: String = "value"
            }
        }

        // Trigger field lookups for each object to populate the cache
        testObjects.forEach { obj ->
            ReflectionAccess.getField(obj, "field")
        }

        // Cache size should not exceed max size
        val finalSize = getFieldCacheLock().write { cache.size }
        assertTrue(
            finalSize <= maxSize,
            "Field cache size $finalSize should not exceed MAX_FIELD_CACHE_SIZE $maxSize",
        )
    }

    @Test
    fun `methodCache exhibits LRU behavior - recently accessed entries are retained`() {
        val maxSize = readPrivateIntConstant(ReflectionAccess::class.java, "MAX_METHOD_CACHE_SIZE")
        val cache = getMethodCache()

        // Clear cache first
        getMethodCacheLock().write { cache.clear() }

        // Create a reusable test class for hot entries
        class HotClass {
            fun hotMethod1(): String = "hot1"
            fun hotMethod2(): String = "hot2"
            fun hotMethod3(): String = "hot3"
        }

        val hotObject = HotClass()

        // Fill cache almost to capacity with entries that will be evicted
        val fillSize = maxSize - 50 // Leave room for hot entries
        val coldObjects = (1..fillSize).map {
            object {
                @Suppress("unused")
                fun coldMethod(): String = "cold"
            }
        }

        coldObjects.forEach { obj ->
            ReflectionAccess.invokeNoArg(obj, "coldMethod")
        }

        // Add and frequently access hot entries
        val hotMethods = listOf("hotMethod1", "hotMethod2", "hotMethod3")
        hotMethods.forEach { methodName ->
            ReflectionAccess.invokeNoArg(hotObject, methodName)
        }

        // Access hot entries multiple times to mark them as "hot" in the LRU cache
        repeat(3) {
            hotMethods.forEach { methodName ->
                ReflectionAccess.invokeNoArg(hotObject, methodName)
            }
        }

        // Now fill cache with 100 more entries, pushing out old entries
        val newObjects = (1..100).map {
            object {
                @Suppress("unused")
                fun newMethod(): String = "new"
            }
        }
        newObjects.forEach { obj ->
            ReflectionAccess.invokeNoArg(obj, "newMethod")
        }

        // Check that recently accessed hot entries are still in cache
        val keysInCache = getMethodCacheLock().write { cache.keys.toSet() }
        val retainedCount = hotMethods.count { methodName ->
            (hotObject::class.java to methodName) in keysInCache
        }

        // All hot entries should be retained in LRU cache (deterministic in single-threaded test)
        assertTrue(
            retainedCount == hotMethods.size,
            "All recently accessed hot entries should be retained in LRU cache, but found $retainedCount out of ${hotMethods.size} retained",
        )
    }

    @Test
    fun `fieldCache exhibits LRU behavior - recently accessed entries are retained`() {
        val maxSize = readPrivateIntConstant(ReflectionAccess::class.java, "MAX_FIELD_CACHE_SIZE")
        val cache = getFieldCache()

        // Clear cache first
        getFieldCacheLock().write { cache.clear() }

        // Create a reusable test class for hot entries
        class HotFieldClass {
            @JvmField
            val hotField1: String = "hot1"

            @JvmField
            val hotField2: String = "hot2"

            @JvmField
            val hotField3: String = "hot3"
        }

        val hotObject = HotFieldClass()

        // Fill cache almost to capacity with entries that will be evicted
        val fillSize = maxSize - 50 // Leave room for hot entries
        val coldObjects = (1..fillSize).map {
            object {
                @Suppress("unused")
                @JvmField
                val coldField: String = "cold"
            }
        }

        coldObjects.forEach { obj ->
            ReflectionAccess.getField(obj, "coldField")
        }

        // Add and frequently access hot entries
        val hotFields = listOf("hotField1", "hotField2", "hotField3")
        hotFields.forEach { fieldName ->
            ReflectionAccess.getField(hotObject, fieldName)
        }

        // Access hot entries multiple times to mark them as "hot" in the LRU cache
        repeat(3) {
            hotFields.forEach { fieldName ->
                ReflectionAccess.getField(hotObject, fieldName)
            }
        }

        // Now fill cache with 100 more entries, pushing out old entries
        val newObjects = (1..100).map {
            object {
                @Suppress("unused")
                @JvmField
                val newField: String = "new"
            }
        }
        newObjects.forEach { obj ->
            ReflectionAccess.getField(obj, "newField")
        }

        // Check that recently accessed hot entries are still in cache
        val keysInCache = getFieldCacheLock().write { cache.keys.toSet() }
        val retainedCount = hotFields.count { fieldName ->
            (hotObject::class.java to fieldName) in keysInCache
        }

        // All hot entries should be retained in LRU cache (deterministic in single-threaded test)
        assertTrue(
            retainedCount == hotFields.size,
            "All recently accessed hot entries should be retained in LRU cache, but found $retainedCount out of ${hotFields.size} retained",
        )
    }
}
