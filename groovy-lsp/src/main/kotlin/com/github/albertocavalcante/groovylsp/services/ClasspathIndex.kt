package com.github.albertocavalcante.groovylsp.services

import io.github.classgraph.ClassGraph
import org.slf4j.LoggerFactory

data class IndexedClass(val simpleName: String, val fullName: String)

interface ClasspathIndex {
    fun index(classLoader: ClassLoader): List<IndexedClass>
}

class JvmClasspathIndex : ClasspathIndex {
    private val logger = LoggerFactory.getLogger(JvmClasspathIndex::class.java)

    override fun index(classLoader: ClassLoader): List<IndexedClass> {
        val results = mutableListOf<IndexedClass>()

        ClassGraph()
            .enableClassInfo()
            .overrideClassLoaders(classLoader)
            .scan().use { scanResult ->
                scanResult.allClasses.forEach { classInfo ->
                    val simpleName = classInfo.simpleName
                    val fullName = classInfo.name

                    // Skip anonymous classes and synthetic classes
                    if (simpleName.contains('$') || classInfo.isSynthetic) {
                        return@forEach
                    }

                    results.add(IndexedClass(simpleName, fullName))
                }
            }

        logger.debug("JvmClasspathIndex produced {} classes", results.size)
        return results
    }
}
