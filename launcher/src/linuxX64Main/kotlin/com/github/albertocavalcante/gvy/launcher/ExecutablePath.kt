@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.github.albertocavalcante.gvy.launcher

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.readlink

internal actual fun executablePath(): String = memScoped {
    val buffer = allocArray<ByteVar>(PATH_MAX)
    val size = readlink("/proc/self/exe", buffer, PATH_MAX.convert()).toInt()
    if (size <= 0 || size >= PATH_MAX) fail("Cannot determine executable path from /proc/self/exe.")
    buffer[size] = 0
    canonicalPath(buffer.toKString())
}
