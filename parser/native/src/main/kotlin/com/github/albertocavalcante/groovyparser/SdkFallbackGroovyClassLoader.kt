// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.NativeBinaryReadScope
import com.github.albertocavalcante.nativeapi.NativeCompilerSdkIdentity
import com.github.albertocavalcante.nativeapi.NativeSourceMembership
import com.github.albertocavalcante.nativeapi.SourceMembershipRecorder
import groovy.lang.GroovyClassLoader
import groovy.lang.GroovyResourceLoader
import java.net.URL
import java.nio.file.Path
import java.util.Collections
import java.util.Enumeration

/** Project bytecode and source lookup completes before a narrowly enumerated process SDK fallback is considered. */
internal open class SdkFallbackGroovyClassLoader(parent: ClassLoader) : GroovyClassLoader(parent) {
    val binaryReads = NativeBinaryReadScope()
    private val noSource = GroovyResourceLoader { null }
    private var sourceSearch: SourceMembershipRecorder? = null
    private var fallback: RuntimeGroovySdk? = null
    val compilerSdk: NativeCompilerSdkIdentity? get() = fallback?.identity

    init {
        // Keep the default extension probe inside any later workspace filters. An explicit filter may
        // deliberately exclude a present source; a failed default probe establishes no such decision.
        val original = super.getResourceLoader()
        super.setResourceLoader(GroovyResourceLoader { name ->
            val recorder = sourceSearch
            if (recorder == null) original.loadGroovySource(name) else recorder.scriptSearch { original.loadGroovySource(name) }
        })
    }

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
        super.loadClass(name, lookupScriptFiles && !binaryReads.active, preferClassOverScript, resolve)
    } catch (missing: ClassNotFoundException) {
        fallback?.load(name) ?: throw missing
    }

    // The compiler's ClassNodeResolver asks the resource loader directly, outside loadClass.
    override fun getResourceLoader(): GroovyResourceLoader =
        if (binaryReads.active) noSource else super.getResourceLoader()

    /** Install after source filters, and release the mutable recorder as soon as compilation returns. */
    fun beginSourceSearch(sourceRoots: List<Path>) {
        val directories = getURLs().filter { it.protocol == "file" && it.path.endsWith("/") }.map { Path.of(it.toURI()) }
        val recorder = SourceMembershipRecorder(
            (directories + sourceRoots).map { it.toAbsolutePath().normalize() }.distinct(),
            localLoader = parent == null || parent === ClassLoader.getPlatformClassLoader(),
        )
        sourceSearch = recorder
    }

    fun finishSourceSearch(): NativeSourceMembership? = sourceSearch?.finish().also { sourceSearch = null }

    override fun getResource(name: String): URL? {
        if (name == AST_TRANSFORMATION_SERVICE_FILE) return null
        val recorder = sourceSearch ?: return findNormalResource(name)
        return recorder.resource(name) { findNormalResource(name) }
    }

    private fun findNormalResource(name: String): URL? = super.getResource(name) ?: fallback?.resource(name)

    override fun getResources(name: String): Enumeration<URL> {
        if (name == AST_TRANSFORMATION_SERVICE_FILE) return Collections.emptyEnumeration()
        val normal = super.getResources(name)
        if (normal.hasMoreElements()) return normal
        return Collections.enumeration(listOfNotNull(fallback?.resource(name)))
    }
}
