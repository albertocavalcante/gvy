// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import com.github.albertocavalcante.groovyparser.SdkFallbackGroovyClassLoader
import groovy.lang.GroovyClassLoader

/** Per-loader policy for JVM signature resolution, which can re-enter the defining loader. */
class NativeBinaryReadScope internal constructor() {
    private val reading = ThreadLocal<Boolean>()
    internal val active: Boolean get() = reading.get() == true

    /** Close on the entering thread. Coroutine hosts enter and leave around each resumed segment. */
    fun enter(): AutoCloseable {
        if (active) return nested
        reading.set(true)
        return AutoCloseable { reading.remove() }
    }

    private companion object {
        val nested = AutoCloseable { }
    }
}

/** Only the native parser's loader supports scoped suppression of implicit Groovy source resolution. */
fun GroovyClassLoader.nativeBinaryReadScope(): NativeBinaryReadScope? = (this as? SdkFallbackGroovyClassLoader)?.binaryReads
