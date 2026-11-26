// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import groovy.lang.GroovyClassLoader
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.Attributes
import java.util.jar.Manifest

internal open class ProjectGroovyClassLoader(parent: ClassLoader) : GroovyClassLoader(parent) {
    private val project = ProjectClassBytes({ getURLs() }, { super.findResource(it) })
    private val decompiling = ThreadLocal<String>()
    private val defined = ConcurrentHashMap.newKeySet<Class<*>>()

    fun binaryInputs() = project.snapshot()

    fun <T> decompile(name: String, lookup: () -> T): T {
        val previous = decompiling.get()
        decompiling.set(classResource(name))
        return try {
            lookup()
        } finally {
            if (previous == null) decompiling.remove() else decompiling.set(previous)
        }
    }

    // All public resource lookups keep their normal URLs. Only the compiler's ASM lookup requests a byte-backed URL.
    override fun getResource(name: String): URL? {
        if (name != decompiling.get()) return super.getResource(name)
        val inherited = parent.getResource(name)
        if (parent !== ClassLoader.getPlatformClassLoader()) project.unavailable()
        if (inherited != null) return inherited
        return when (val selection = project.lookup(name)) {
            is ProjectClassBytes.Payload -> selection.metadataResource
            is ProjectClassBytes.Selection.Ordinary -> selection.url
            ProjectClassBytes.Selection.Missing -> null
        }
    }

    override fun findClass(name: String): Class<*> {
        if (parent !== ClassLoader.getPlatformClassLoader()) project.unavailable()
        return when (val selection = project.lookup(classResource(name))) {
            is ProjectClassBytes.Payload -> defineProjectClass(name, selection)
            is ProjectClassBytes.Selection.Ordinary -> super.findClass(name)
            ProjectClassBytes.Selection.Missing -> throw ClassNotFoundException(name)
        }
    }

    /** Called for parent-defined classes that never passed through findClass. */
    protected fun recordDelegation(type: Class<*>) {
        val defining = type.classLoader
        if (defining === this && type !in defined) project.unavailable()
        if (parent !== ClassLoader.getPlatformClassLoader() && defining != null &&
            defining !== ClassLoader.getPlatformClassLoader() && defining !== this
        ) {
            project.unavailable()
        }
    }

    private fun defineProjectClass(name: String, payload: ProjectClassBytes.Payload): Class<*> {
        val packageName = name.substringBeforeLast('.', "")
        if (packageName.isNotEmpty()) ensurePackage(packageName, payload.manifest, payload.source.location)
        return defineClass(name, payload.bytes, 0, payload.bytes.size, payload.source).also(defined::add)
    }

    @Synchronized
    private fun ensurePackage(name: String, manifest: Manifest?, origin: URL) {
        val existing = getDefinedPackage(name)
        when {
            existing == null && manifest == null -> definePackage(name, null, null, null, null, null, null, null)
            existing == null -> definePackage(name, checkNotNull(manifest), origin)
            existing.isSealed -> if (!existing.isSealed(origin)) throw SecurityException("Sealed package: $name")
            manifest != null && sealsPackage(manifest, name, origin) ->
                throw SecurityException("Cannot seal previously defined package: $name")
        }
    }

    override fun close() {
        project.close()
        super.close()
    }

    private companion object {
        fun classResource(name: String) = name.replace('.', '/') + ".class"

        fun sealsPackage(manifest: Manifest, name: String, origin: URL): Boolean {
            val section = name.replace('.', '/') + "/"
            if (manifest.getAttributes(section) == null) {
                return manifest.mainAttributes.getValue(Attributes.Name.SEALED).equals("true", ignoreCase = true)
            }
            // URLClassLoader verifies signed per-package attributes before interpreting their sealing flag.
            return ManifestPackage().use { it.isSealed(name, manifest, origin) }
        }
    }

    private class ManifestPackage : URLClassLoader(emptyArray(), null) {
        fun isSealed(name: String, manifest: Manifest, origin: URL): Boolean =
            definePackage(name, manifest, origin).isSealed
    }
}
