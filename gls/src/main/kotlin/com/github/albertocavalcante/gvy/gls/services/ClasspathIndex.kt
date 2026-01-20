package com.github.albertocavalcante.gvy.gls.services

import io.github.classgraph.ClassGraph
import io.github.oshai.kotlinlogging.KotlinLogging

data class IndexedClass(val simpleName: String, val fullName: String)

interface ClasspathIndex {
    fun index(classpathEntries: List<String>): List<IndexedClass>
}

class JvmClasspathIndex : ClasspathIndex {
    private val logger = KotlinLogging.logger {}

    override fun index(classpathEntries: List<String>): List<IndexedClass> {
        val results = mutableListOf<IndexedClass>()

        val classGraph = ClassGraph()
            .enableClassInfo()
            .enableSystemJarsAndModules()
        val configured = if (classpathEntries.isEmpty()) {
            classGraph
        } else {
            classGraph.overrideClasspath(classpathEntries)
        }

        configured.scan().use { scanResult ->
            scanResult.allClasses.forEach { classInfo ->
                val simpleName = classInfo.simpleName
                val fullName = classInfo.name

                // Skip anonymous classes & synthetic classes
                if (simpleName.contains('$') || classInfo.isSynthetic) {
                    return@forEach
                }

                results.add(IndexedClass(simpleName, fullName))
            }
        }

        logger.debug { "JvmClasspathIndex produced ${results.size} classes" }
        return results
    }
}
