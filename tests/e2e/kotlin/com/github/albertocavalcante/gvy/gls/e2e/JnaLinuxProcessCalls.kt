// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/** Linux-only ABI adapter. Construct in a dedicated supervisor, never arm a subreaper in the test JVM. */
internal class JnaLinuxProcessCalls : LinuxProcessCalls {
    init {
        val architecture = System.getProperty("os.arch", "").lowercase()
        if (!System.getProperty("os.name", "").equals("Linux", ignoreCase = true) ||
            architecture !in setOf("amd64", "x86_64", "aarch64", "arm64")
        ) {
            throw LinuxOwnershipFailure("Native process ownership requires Linux x86_64 or aarch64")
        }
    }

    private val libc: LinuxLibc by lazy { Native.load("c", LinuxLibc::class.java) }
    override val currentPid: Int get() = Math.toIntExact(ProcessHandle.current().pid())

    override fun armSubreaper(): LinuxNativeCall = nativeCall(
        libc.prctl(PR_SET_CHILD_SUBREAPER, NativeLong(1), ZERO, ZERO, ZERO).toLong(),
    )

    override fun openPidfd(pid: Int): LinuxNativeCall = nativeCall(
        libc.syscall(NativeLong(SYS_PIDFD_OPEN), NativeLong(pid.toLong()), ZERO, ZERO, ZERO).toLong(),
    )

    override fun signalPidfd(descriptor: Int): LinuxNativeCall = nativeCall(
        libc.syscall(
            NativeLong(SYS_PIDFD_SEND_SIGNAL),
            NativeLong(descriptor.toLong()),
            NativeLong(SIGKILL),
            ZERO,
            ZERO,
        )
            .toLong(),
    )

    override fun pollPidfd(descriptor: Int, timeoutMillis: Int): LinuxNativeCall = Memory(POLLFD_BYTES).use { memory ->
        memory.setInt(POLLFD_DESCRIPTOR_OFFSET, descriptor)
        memory.setShort(POLLFD_EVENTS_OFFSET, POLLIN)
        memory.setShort(POLLFD_RETURNED_EVENTS_OFFSET, 0)
        val result = libc.poll(memory, NativeLong(1), timeoutMillis)
        val error = if (result < 0) Native.getLastError() else 0
        // Capture errno before reading native memory, which itself crosses JNA's native boundary.
        LinuxNativeCall(result, error, memory.getShort(POLLFD_RETURNED_EVENTS_OFFSET).toInt())
    }

    override fun waitChild(pid: Int): LinuxNativeCall {
        val status = IntByReference()
        val result = libc.waitpid(pid, status, WNOHANG)
        val error = if (result < 0) Native.getLastError() else 0
        return LinuxNativeCall(result, error, status.value)
    }

    override fun closeDescriptor(descriptor: Int): LinuxNativeCall = nativeCall(libc.close(descriptor).toLong())

    private fun nativeCall(result: Long): LinuxNativeCall =
        LinuxNativeCall(Math.toIntExact(result), if (result < 0) Native.getLastError() else 0)
}

internal interface LinuxLibc : Library {
    fun prctl(option: Int, second: NativeLong, third: NativeLong, fourth: NativeLong, fifth: NativeLong): Int
    fun syscall(
        number: NativeLong,
        first: NativeLong,
        second: NativeLong,
        third: NativeLong,
        fourth: NativeLong,
    ): NativeLong
    fun poll(descriptors: Pointer, count: NativeLong, timeoutMillis: Int): Int
    fun waitpid(pid: Int, status: IntByReference, options: Int): Int
    fun close(descriptor: Int): Int
}

// Linux UAPI syscall numbers shared by the explicitly supported x86_64 and aarch64 ABIs.
private const val SYS_PIDFD_SEND_SIGNAL = 424L
private const val SYS_PIDFD_OPEN = 434L
private const val PR_SET_CHILD_SUBREAPER = 36
private const val SIGKILL = 9L
private const val WNOHANG = 1
private const val POLLIN: Short = 1
private const val POLLFD_BYTES = 8L
private const val POLLFD_DESCRIPTOR_OFFSET = 0L
private const val POLLFD_EVENTS_OFFSET = 4L
private const val POLLFD_RETURNED_EVENTS_OFFSET = 6L
private val ZERO = NativeLong(0)
