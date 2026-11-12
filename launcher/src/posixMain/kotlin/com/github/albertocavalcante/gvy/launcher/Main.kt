// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.github.albertocavalcante.gvy.launcher

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.ENOENT
import platform.posix.R_OK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.access
import platform.posix.errno
import platform.posix.execvp
import platform.posix.fputs
import platform.posix.free
import platform.posix.getenv
import platform.posix.realpath
import platform.posix.stat
import platform.posix.stderr
import platform.posix.strerror
import kotlin.system.exitProcess

private const val EXECUTION_FAILED = 126
private const val EXECUTABLE_NOT_FOUND = 127

fun main(arguments: Array<String>) {
    val jar = environment("GVY_JAR") ?: "${executablePath().substringBeforeLast('/')}/gvy.jar"
    if (!readableJar(jar)) fail("Set GVY_JAR to a readable server JAR, or place gvy.jar beside the executable.")
    val javaHome = selectedJavaHome()
    val command = javaCommand(arguments.toList(), javaHome, jar)
    memScoped {
        val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
        command.forEachIndexed { index, argument -> argv[index] = argument.cstr.ptr }
        argv[command.size] = null
        // Replace this process: no shell, stdio proxy, supervisor, or signal-forwarding race.
        execvp(command.first(), argv)
        val failure = errno
        val reason = strerror(failure)?.toKString() ?: "errno $failure"
        fail(
            "Cannot execute ${command.first()}: $reason",
            if (failure == ENOENT) EXECUTABLE_NOT_FOUND else EXECUTION_FAILED,
        )
    }
}

private fun selectedJavaHome(): String? {
    for (name in listOf("GVY_JAVA_HOME", "JAVA_HOME")) {
        val value = environment(name) ?: continue
        if (value.isBlank()) fail("$name is empty; set a Java home or unset it to use the next runtime source.")
        return value
    }
    return null
}

private fun environment(name: String): String? = getenv(name)?.toKString()

private fun readableJar(path: String): Boolean = memScoped {
    val info = alloc<stat>()
    path.isNotBlank() && stat(path, info.ptr) == 0 &&
        (info.st_mode.toInt() and S_IFMT) == S_IFREG && access(path, R_OK) == 0
}

internal fun canonicalPath(path: String): String {
    val resolved = realpath(path, null) ?: fail("Cannot resolve the executable path.")
    return try {
        resolved.toKString()
    } finally {
        free(resolved)
    }
}

internal expect fun executablePath(): String

internal fun fail(message: String, status: Int = 1): Nothing {
    fputs("gvy launcher: $message\n", stderr)
    exitProcess(status)
}
