// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.github.albertocavalcante.gvy.launcher

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.osx.PROC_PIDPATHINFO_MAXSIZE
import platform.osx.proc_pidpath
import platform.posix.getpid

internal actual fun executablePath(): String = memScoped {
    val buffer = allocArray<ByteVar>(PROC_PIDPATHINFO_MAXSIZE)
    if (proc_pidpath(getpid(), buffer, PROC_PIDPATHINFO_MAXSIZE.convert()) <= 0) {
        fail("Cannot determine executable path with proc_pidpath.")
    }
    canonicalPath(buffer.toKString())
}
