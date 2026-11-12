@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.github.albertocavalcante.gvy.launcher

import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.value
import platform.posix.fputs
import platform.posix.stderr
import platform.windows.BY_HANDLE_FILE_INFORMATION
import platform.windows.CloseHandle
import platform.windows.CommandLineToArgvW
import platform.windows.CreateFileW
import platform.windows.ERROR_ENVVAR_NOT_FOUND
import platform.windows.ExitProcess
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.FILE_FLAG_BACKUP_SEMANTICS
import platform.windows.FILE_SHARE_DELETE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GENERIC_READ
import platform.windows.GetCommandLineW
import platform.windows.GetEnvironmentVariableW
import platform.windows.GetFileInformationByHandle
import platform.windows.GetFinalPathNameByHandleW
import platform.windows.GetFullPathNameW
import platform.windows.GetLastError
import platform.windows.GetModuleFileNameW
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.LocalFree
import platform.windows.OPEN_EXISTING

private const val LEGACY_PATH_LIMIT = 260
internal const val WINDOWS_BUFFER_SIZE = 32768
internal const val WINDOWS_EXECUTION_FAILED = 126u
internal const val WINDOWS_JAVA_NOT_FOUND = 127u

internal fun windowsArguments(): List<String> = memScoped {
    val count = alloc<kotlinx.cinterop.IntVar>()
    val values =
        CommandLineToArgvW(GetCommandLineW()?.toKStringFromUtf16(), count.ptr)
            ?: windowsFail("Cannot read command line.")
    try {
        (1 until count.value).map { values[it]?.toKStringFromUtf16() ?: windowsFail("Invalid argument.") }
    } finally {
        LocalFree(values)
    }
}

internal fun windowsEnvironment(name: String): String? = memScoped {
    val size = GetEnvironmentVariableW(name, null, 0u)
    if (size == 0u) {
        if (GetLastError() == ERROR_ENVVAR_NOT_FOUND.toUInt()) return null
        return ""
    }
    val buffer = allocArray<UShortVar>(size.toInt())
    val copied = GetEnvironmentVariableW(name, buffer, size)
    if (copied == 0u || copied >= size) windowsFail("Cannot read $name.")
    buffer.toKStringFromUtf16()
}

internal fun windowsExecutablePath(): String = memScoped {
    val buffer = allocArray<UShortVar>(WINDOWS_BUFFER_SIZE)
    val copied = GetModuleFileNameW(null, buffer, WINDOWS_BUFFER_SIZE.toUInt())
    if (copied == 0u || copied >= WINDOWS_BUFFER_SIZE.toUInt()) windowsFail("Cannot locate executable.")
    val file =
        CreateFileW(
            extendedPath(buffer.toKStringFromUtf16()),
            0u,
            (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).toUInt(),
            null,
            OPEN_EXISTING.toUInt(),
            0u,
            null,
        )
    if (file == INVALID_HANDLE_VALUE) windowsFail("Cannot resolve executable.")
    try {
        val length = GetFinalPathNameByHandleW(file, buffer, WINDOWS_BUFFER_SIZE.toUInt(), 0u)
        if (length == 0u || length >= WINDOWS_BUFFER_SIZE.toUInt()) windowsFail("Cannot resolve executable.")
        processPath(buffer.toKStringFromUtf16())
    } finally {
        CloseHandle(file)
    }
}

internal fun windowsReadableFile(path: String): Boolean = memScoped {
    if (path.isBlank()) return false
    val file = CreateFileW(
        extendedPath(windowsAbsolutePath(path)),
        GENERIC_READ,
        (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).toUInt(),
        null,
        OPEN_EXISTING.toUInt(),
        FILE_FLAG_BACKUP_SEMANTICS.toUInt(),
        null,
    )
    if (file == INVALID_HANDLE_VALUE) return false
    try {
        val info = alloc<BY_HANDLE_FILE_INFORMATION>()
        GetFileInformationByHandle(file, info.ptr) != 0 &&
            (info.dwFileAttributes and FILE_ATTRIBUTE_DIRECTORY.toUInt()) == 0u
    } finally {
        CloseHandle(file)
    }
}

internal fun windowsAbsolutePath(path: String): String = memScoped {
    val buffer = allocArray<UShortVar>(WINDOWS_BUFFER_SIZE)
    val length = GetFullPathNameW(path, WINDOWS_BUFFER_SIZE.toUInt(), buffer, null)
    if (length == 0u || length >= WINDOWS_BUFFER_SIZE.toUInt()) windowsFail("Cannot resolve path.")
    val absolute = buffer.toKStringFromUtf16()
    if (path.startsWith("\\\\?\\")) absolute else processPath(extendedPath(absolute))
}

internal fun windowsFail(message: String, status: UInt = 1u): Nothing {
    fputs("gvy launcher: $message\n", stderr)
    ExitProcess(status)
    error("ExitProcess returned")
}

private fun extendedPath(path: String): String = when {
    path.startsWith("\\\\?\\") -> path
    path.startsWith("\\\\") -> "\\\\?\\UNC\\${path.removePrefix("\\\\")}"
    else -> "\\\\?\\$path"
}

private fun processPath(path: String): String {
    val ordinary = if (path.startsWith("\\\\?\\UNC\\")) {
        "\\\\${path.removePrefix("\\\\?\\UNC\\")}"
    } else {
        path.removePrefix("\\\\?\\")
    }
    val requiresPrefix = ordinary.length >= LEGACY_PATH_LIMIT ||
        ordinary.split('\\').any { it.endsWith('.') || it.endsWith(' ') }
    return if (requiresPrefix) path else ordinary
}
