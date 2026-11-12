@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.github.albertocavalcante.gvy.launcher

import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.posix.memset
import platform.windows.CTRL_BREAK_EVENT
import platform.windows.CTRL_C_EVENT
import platform.windows.CloseHandle
import platform.windows.CreateJobObjectW
import platform.windows.CreateProcessW
import platform.windows.DUPLICATE_SAME_ACCESS
import platform.windows.DWORDVar
import platform.windows.DeleteProcThreadAttributeList
import platform.windows.DuplicateHandle
import platform.windows.ERROR_FILE_NOT_FOUND
import platform.windows.ERROR_PATH_NOT_FOUND
import platform.windows.EXTENDED_STARTUPINFO_PRESENT
import platform.windows.ExitProcess
import platform.windows.GetCurrentProcess
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import platform.windows.GetStdHandle
import platform.windows.HANDLE
import platform.windows.HANDLEVar
import platform.windows.INFINITE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.InitializeProcThreadAttributeList
import platform.windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION
import platform.windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
import platform.windows.JobObjectExtendedLimitInformation
import platform.windows.PROCESS_INFORMATION
import platform.windows.SIZE_TVar
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOEXW
import platform.windows.STD_ERROR_HANDLE
import platform.windows.STD_INPUT_HANDLE
import platform.windows.STD_OUTPUT_HANDLE
import platform.windows.SetConsoleCtrlHandler
import platform.windows.SetInformationJobObject
import platform.windows.UpdateProcThreadAttribute
import platform.windows.WAIT_OBJECT_0
import platform.windows.WaitForSingleObject

// Win10 SDK ProcThreadAttributeJobList (13 | INPUT), unavailable in K/N's Win7 platform headers.
private const val JOB_LIST_ATTRIBUTE = 0x0002000D
private const val HANDLE_LIST_ATTRIBUTE = 0x00020002

fun main() {
    val jar = windowsEnvironment("GVY_JAR") ?: "${windowsExecutablePath().substringBeforeLast('\\')}\\gvy.jar"
    if (!windowsReadableFile(jar)) {
        windowsFail("Set GVY_JAR to a readable server JAR, or place gvy.jar beside the executable.")
    }
    val home = listOf("GVY_JAVA_HOME", "JAVA_HOME").firstNotNullOfOrNull { name ->
        windowsEnvironment(name)?.also {
            if (it.isBlank()) windowsFail("$name is empty; set a Java home or unset it to use the next runtime source.")
        }
    }
    val command = javaCommand(windowsArguments(), home, jar, windows = true).toMutableList()
    command[0] = resolveJava(command.first(), home != null)
    launchWindows(command)
}

private fun resolveJava(java: String, explicit: Boolean): String {
    if (explicit) return windowsAbsolutePath(java)
    val entries = windowsEnvironment("PATH")?.split(';') ?: emptyList()
    return entries.map { "${it.trim('"').ifEmpty { "." }.trimEnd('\\', '/')}\\java.exe" }
        .firstOrNull { windowsReadableFile(it) }
        ?.let(::windowsAbsolutePath)
        ?: windowsFail("Cannot find java.exe in PATH.", WINDOWS_JAVA_NOT_FOUND)
}

private fun launchWindows(command: List<String>): Nothing = memScoped {
    val line = command.joinToString(" ", transform = ::quoteWindowsArgument)
    if (line.length >= WINDOWS_BUFFER_SIZE - 1) {
        windowsFail("Command line exceeds the Windows limit.", WINDOWS_EXECUTION_FAILED)
    }
    val job = createJavaJob()
    val duplicates = listOf(STD_INPUT_HANDLE, STD_OUTPUT_HANDLE, STD_ERROR_HANDLE).map { duplicateStandardHandle(it) }
    val startup = configureStartup(job, duplicates)
    // Java receives the shared console event directly; the supervisor waits for its actual exit.
    // Clear an inherited IGNORE_CTRL_C flag before spawning Java; a custom handler alone does not clear it.
    if (SetConsoleCtrlHandler(null, 0) == 0) {
        windowsFail("Cannot restore console cancellation.", WINDOWS_EXECUTION_FAILED)
    }
    if (SetConsoleCtrlHandler(staticCFunction(::handleConsoleEvent), 1) == 0) {
        windowsFail("Cannot configure console cancellation.", WINDOWS_EXECUTION_FAILED)
    }
    val process = alloc<PROCESS_INFORMATION> { memset(ptr, 0, sizeOf<PROCESS_INFORMATION>().convert()) }
    val inheritHandles = if (duplicates.any { it != null }) 1 else 0
    val created = CreateProcessW(
        command.first(),
        line.wcstr.ptr,
        null,
        null,
        inheritHandles,
        EXTENDED_STARTUPINFO_PRESENT.toUInt(),
        null,
        null,
        startup.ptr.reinterpret(),
        process.ptr,
    )
    val failure = GetLastError()
    DeleteProcThreadAttributeList(startup.lpAttributeList)
    duplicates.filterNotNull().forEach { CloseHandle(it) }
    if (created == 0) {
        windowsFail("Cannot execute ${command.first()}: Windows error $failure", executionFailureStatus(failure))
    }
    CloseHandle(process.hThread)
    val status = waitForJava(process)
    CloseHandle(process.hProcess)
    CloseHandle(job)
    ExitProcess(status)
    error("ExitProcess returned")
}

private fun handleConsoleEvent(event: UInt): Int = when (event) {
    CTRL_C_EVENT.toUInt(), CTRL_BREAK_EVENT.toUInt() -> 1
    else -> 0
}

private fun executionFailureStatus(error: UInt): UInt = when (error) {
    ERROR_FILE_NOT_FOUND.toUInt(), ERROR_PATH_NOT_FOUND.toUInt() -> WINDOWS_JAVA_NOT_FOUND
    else -> WINDOWS_EXECUTION_FAILED
}

private fun MemScope.waitForJava(process: PROCESS_INFORMATION): UInt {
    if (WaitForSingleObject(process.hProcess, INFINITE) != WAIT_OBJECT_0) {
        windowsFail("Cannot wait for Java.", WINDOWS_EXECUTION_FAILED)
    }
    val status = alloc<DWORDVar>()
    if (GetExitCodeProcess(process.hProcess, status.ptr) == 0) {
        windowsFail("Cannot read Java exit status.", WINDOWS_EXECUTION_FAILED)
    }
    return status.value
}

private fun duplicateStandardHandle(identifier: UInt): HANDLE? = memScoped {
    val original = GetStdHandle(identifier)
    if (original == null || original == INVALID_HANDLE_VALUE) return null
    val duplicate = alloc<HANDLEVar>()
    if (DuplicateHandle(
            GetCurrentProcess(),
            original,
            GetCurrentProcess(),
            duplicate.ptr,
            0u,
            1,
            DUPLICATE_SAME_ACCESS.toUInt(),
        ) ==
        0
    ) {
        windowsFail("Cannot inherit standard stream.", WINDOWS_EXECUTION_FAILED)
    }
    duplicate.value
}

private fun MemScope.createJavaJob(): HANDLE {
    val job = CreateJobObjectW(null, null) ?: windowsFail("Cannot create Java process job.", WINDOWS_EXECUTION_FAILED)
    val limits =
        alloc<JOBOBJECT_EXTENDED_LIMIT_INFORMATION> {
            memset(ptr, 0, sizeOf<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>().convert())
        }
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE.toUInt()
    if (SetInformationJobObject(
            job,
            JobObjectExtendedLimitInformation,
            limits.ptr,
            sizeOf<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>().toUInt(),
        ) ==
        0
    ) {
        windowsFail("Cannot configure Java process job.", WINDOWS_EXECUTION_FAILED)
    }
    return job
}

private fun MemScope.configureStartup(job: HANDLE, duplicates: List<HANDLE?>): STARTUPINFOEXW {
    // A private job is assigned atomically during creation. Killing the launcher cannot orphan Java.
    val jobs = alloc<HANDLEVar>().apply { value = job }
    val inherited = duplicates.filterNotNull()
    val handles = allocArray<HANDLEVar>(inherited.size)
    inherited.forEachIndexed { index, handle -> handles[index] = handle }
    val bytes = alloc<SIZE_TVar>()
    val attributeCount = if (inherited.isEmpty()) 1u else 2u
    InitializeProcThreadAttributeList(null, attributeCount, 0u, bytes.ptr)
    val attributes = allocArray<kotlinx.cinterop.ByteVar>(bytes.value.toInt())
    if (InitializeProcThreadAttributeList(attributes.reinterpret(), attributeCount, 0u, bytes.ptr) ==
        0
    ) {
        windowsFail("Cannot initialize process attributes.", WINDOWS_EXECUTION_FAILED)
    }
    if (UpdateProcThreadAttribute(
            attributes.reinterpret(),
            0u,
            JOB_LIST_ATTRIBUTE.convert(),
            jobs.ptr,
            sizeOf<HANDLEVar>().convert(),
            null,
            null,
        ) ==
        0
    ) {
        windowsFail("Cannot assign Java process job; Windows 10 or newer is required.", WINDOWS_EXECUTION_FAILED)
    }
    if (inherited.isNotEmpty() &&
        UpdateProcThreadAttribute(
            attributes.reinterpret(),
            0u,
            HANDLE_LIST_ATTRIBUTE.convert(),
            handles,
            (
                sizeOf<HANDLEVar>() *
                    inherited.size
                ).convert(),
            null,
            null,
        ) ==
        0
    ) {
        windowsFail("Cannot configure inherited stdio.", WINDOWS_EXECUTION_FAILED)
    }
    val startup = alloc<STARTUPINFOEXW> { memset(ptr, 0, sizeOf<STARTUPINFOEXW>().convert()) }
    startup.StartupInfo.cb = sizeOf<STARTUPINFOEXW>().toUInt()
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES.toUInt()
    startup.StartupInfo.hStdInput = duplicates[0]
    startup.StartupInfo.hStdOutput = duplicates[1]
    startup.StartupInfo.hStdError = duplicates[2]
    startup.lpAttributeList = attributes.reinterpret()
    return startup
}
