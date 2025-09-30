package com.github.albertocavalcante.groovylsp.interop

import org.codehaus.groovy.ast.ClassNode
import org.slf4j.LoggerFactory

/**
 * Simplified Groovy interop for initial testing.
 * This demonstrates basic Kotlin-Groovy integration without complex dependencies.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException") // Interop layer handles all Groovy reflection errors
class SimpleGroovyInterop {

    private val logger = LoggerFactory.getLogger(SimpleGroovyInterop::class.java)

    /**
     * Test basic interop with the Groovy language profile.
     */
    fun testBasicInterop(): Map<String, Any> = try {
        // Access the simple Groovy class we already have
        val groovyProfile = Class.forName("com.github.albertocavalcante.groovylsp.groovy.GroovyLanguageProfile")
        val describeMethod = groovyProfile.getMethod("describe")
        val isKeywordMethod = groovyProfile.getMethod("isKeyword", String::class.java)

        val description = describeMethod.invoke(null) as String
        val isDefKeyword = isKeywordMethod.invoke(null, "def") as Boolean
        val isRandomKeyword = isKeywordMethod.invoke(null, "random") as Boolean

        mapOf<String, Any>(
            "groovyVersion" to description,
            "defIsKeyword" to isDefKeyword,
            "randomIsKeyword" to isRandomKeyword,
            "interopWorking" to true,
        )
    } catch (e: Exception) {
        logger.error("Groovy interop test failed", e)
        mapOf<String, Any>(
            "interopWorking" to false,
            "error" to (e.message ?: "unknown error"),
        )
    }

    /**
     * Test if we can resolve basic methods on ClassNode.
     */
    fun testClassNodeResolution(classNode: ClassNode): Map<String, Any> = try {
        mapOf<String, Any>(
            "className" to classNode.name,
            "isInterface" to classNode.isInterface,
            "methodCount" to classNode.methods.size,
            "fieldCount" to classNode.fields.size,
            "propertyCount" to classNode.properties.size,
            "superClass" to (classNode.superClass?.name ?: "none"),
            "packageName" to (classNode.packageName ?: "default"),
            "resolutionWorking" to true,
        )
    } catch (e: Exception) {
        logger.error("ClassNode resolution test failed", e)
        mapOf<String, Any>(
            "resolutionWorking" to false,
            "error" to (e.message ?: "unknown error"),
        )
    }

    /**
     * Check if a method exists in a class using basic Groovy AST.
     */
    fun hasMethod(classNode: ClassNode, methodName: String): Boolean = try {
        classNode.getMethod(methodName, emptyArray()) != null
    } catch (e: Exception) {
        false
    }

    /**
     * Get all method names from a class.
     */
    fun getMethodNames(classNode: ClassNode): List<String> = try {
        classNode.methods.map { it.name }.distinct().sorted()
    } catch (e: Exception) {
        logger.warn("Error getting method names", e)
        emptyList()
    }

    /**
     * Get all property names from a class.
     */
    fun getPropertyNames(classNode: ClassNode): List<String> = try {
        classNode.properties.map { it.name }.distinct().sorted()
    } catch (e: Exception) {
        logger.warn("Error getting property names", e)
        emptyList()
    }

    /**
     * Simple resolution result for basic cases.
     */
    data class SimpleResolutionResult(
        val symbol: String,
        val found: Boolean,
        val type: String = "unknown",
        val isMethod: Boolean = false,
        val isProperty: Boolean = false,
        val isField: Boolean = false,
    )

    /**
     * Simple symbol resolution using basic AST traversal.
     */
    fun resolveSymbol(classNode: ClassNode, symbolName: String): SimpleResolutionResult {
        try {
            return resolveMethodSymbol(classNode, symbolName)
                ?: resolvePropertySymbol(classNode, symbolName)
                ?: resolveFieldSymbol(classNode, symbolName)
                ?: SimpleResolutionResult(symbolName, false)
        } catch (e: Exception) {
            logger.warn("Error resolving symbol '$symbolName'", e)
            return SimpleResolutionResult(symbolName, false, type = "error")
        }
    }

    private fun resolveMethodSymbol(classNode: ClassNode, symbolName: String): SimpleResolutionResult? {
        val method = classNode.getMethod(symbolName, emptyArray())
        return if (method != null) {
            SimpleResolutionResult(symbol = symbolName, found = true, type = "method", isMethod = true)
        } else {
            null
        }
    }

    private fun resolvePropertySymbol(classNode: ClassNode, symbolName: String): SimpleResolutionResult? {
        val property = classNode.getProperty(symbolName)
        return if (property != null) {
            SimpleResolutionResult(symbol = symbolName, found = true, type = "property", isProperty = true)
        } else {
            null
        }
    }

    private fun resolveFieldSymbol(classNode: ClassNode, symbolName: String): SimpleResolutionResult? {
        val field = classNode.getField(symbolName)
        return if (field != null) {
            SimpleResolutionResult(symbol = symbolName, found = true, type = "field", isField = true)
        } else {
            null
        }
    }
}
