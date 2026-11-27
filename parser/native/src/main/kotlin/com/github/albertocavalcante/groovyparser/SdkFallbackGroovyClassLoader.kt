// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.NativeCompilerSdkIdentity
import groovy.lang.GroovyClassLoader
import java.net.URL
import java.util.Collections
import java.util.Enumeration

private const val CLASS_FILE_SUFFIX = ".class"

/** Project bytecode and source lookup completes before a narrowly enumerated process SDK fallback is considered. */
internal open class SdkFallbackGroovyClassLoader(parent: ClassLoader) : GroovyClassLoader(parent) {
    private var fallback: RuntimeGroovySdk? = null
    private val classFileVersions = ClassFileVersions()
    val compilerSdk: NativeCompilerSdkIdentity? get() = fallback?.identity

    fun selectSdkFallback() {
        val projectSdk = listOf("class", "groovy", "java").any { extension ->
            super.getResource("groovy/lang/GroovyObject.$extension") != null
        }
        fallback = if (projectSdk) null else RuntimeGroovySdk.current()
    }

    override fun loadClass(
        name: String,
        lookupScriptFiles: Boolean,
        preferClassOverScript: Boolean,
        resolve: Boolean,
    ): Class<*> = try {
        super.loadClass(name, lookupScriptFiles, preferClassOverScript, resolve)
    } catch (missing: ClassNotFoundException) {
        fallback?.load(name) ?: throw missing
    }

    override fun getResource(name: String): URL? {
        if (name == AST_TRANSFORMATION_SERVICE_FILE) return null
        val resource = super.getResource(name) ?: fallback?.resource(name) ?: return null
        // The compiler remembers a class file by URL for the life of the process; see ClassFileVersions.
        return if (name.endsWith(CLASS_FILE_SUFFIX)) classFileVersions.versioned(resource) else resource
    }

    override fun getResources(name: String): Enumeration<URL> {
        if (name == AST_TRANSFORMATION_SERVICE_FILE) return Collections.emptyEnumeration()
        val normal = super.getResources(name)
        if (normal.hasMoreElements()) return normal
        return Collections.enumeration(listOfNotNull(fallback?.resource(name)))
    }
}
