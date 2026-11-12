package com.github.albertocavalcante.gvy.gls.e2e

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

internal const val MAX_LINUX_CHILD_LIST_BYTES = 64 * 1024

/** Enumerates the supervisor's kernel-owned children; final native ECHILD, not this snapshot, certifies release. */
internal class LinuxOwnedChildReader(
    private val taskRoot: Path = Path.of("/proc/self/task"),
    private val readChildFile: (Path) -> ByteArray = { path ->
        Files.newInputStream(path).use { it.readNBytes(MAX_LINUX_CHILD_LIST_BYTES + 1) }
    },
) {
    fun read(): List<Int> = try {
        val children = linkedSetOf<Int>()
        Files.newDirectoryStream(taskRoot).use { tasks ->
            for (task in tasks) children.addAll(taskChildren(task))
        }
        children.toList()
    } catch (failure: IOException) {
        throw LinuxOwnershipFailure(
            "Linux CLI ownership unconfirmed: cannot read the supervisor's child lists",
            failure,
        )
    } catch (failure: DirectoryIteratorException) {
        throw LinuxOwnershipFailure("Linux CLI ownership unconfirmed: supervisor task enumeration failed", failure)
    }

    private fun taskChildren(task: Path): List<Int> {
        val bytes = readTaskFile(task) ?: return emptyList()
        if (bytes.size > MAX_LINUX_CHILD_LIST_BYTES) {
            throw LinuxOwnershipFailure(
                "Linux CLI ownership unconfirmed: native child observation exceeds its byte budget",
            )
        }
        val content = bytes.toString(Charsets.US_ASCII).trim()
        if (content.isEmpty()) return emptyList()
        return content.split(PID_DELIMITER).map { token ->
            val pid = token.takeIf { it.all { character -> character in '0'..'9' } }?.toIntOrNull()
            if (pid == null || pid <= 0) {
                throw LinuxOwnershipFailure(
                    "Linux CLI ownership unconfirmed: native child observation contains an invalid PID",
                )
            }
            pid
        }
    }

    private fun readTaskFile(task: Path): ByteArray? = try {
        readChildFile(task.resolve("children"))
    } catch (failure: NoSuchFileException) {
        // A retired JVM thread's children move to another live thread; final ECHILD still remains mandatory.
        if (Files.notExists(task)) null else throw failure
    }
}

private val PID_DELIMITER = Regex("\\s+")
