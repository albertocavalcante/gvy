// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

class LinuxOwnedChildReaderTest {
    @TempDir
    lateinit var tasks: Path

    @Test
    fun `all supervisor threads contribute unique owned child IDs`() {
        children(FIRST_TASK, "101 102\n")
        children(SECOND_TASK, "102 103 ")

        assertThat(
            LinuxOwnedChildReader(tasks).read(),
        ).containsExactlyInAnyOrder(FIRST_CHILD, SECOND_CHILD, THIRD_CHILD)
    }

    @Test
    fun `malformed observation never returns a partial list as trustworthy ownership`() {
        children(FIRST_TASK, "101 unknown")

        assertThatThrownBy { LinuxOwnedChildReader(tasks).read() }
            .isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("unconfirmed")
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "+2", "2147483648"])
    fun `invalid or nonpositive native PID cannot become a process signal target`(pid: String) {
        children(FIRST_TASK, pid)

        assertThatThrownBy { LinuxOwnedChildReader(tasks).read() }
            .isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("unconfirmed")
    }

    @Test
    fun `oversized native observation fails before decoding or allocating an unbounded list`() {
        val file = children(FIRST_TASK, "")
        Files.write(file, ByteArray(MAX_LINUX_CHILD_LIST_BYTES + 1) { ' '.code.toByte() })

        assertThatThrownBy { LinuxOwnedChildReader(tasks).read() }
            .isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("unconfirmed")
    }

    @Test
    fun `missing observation for an existing supervisor thread is an infrastructure failure`() {
        Files.createDirectory(tasks.resolve(FIRST_TASK.toString()))

        assertThatThrownBy { LinuxOwnedChildReader(tasks).read() }
            .isInstanceOf(LinuxOwnershipFailure::class.java).hasMessageContaining("unconfirmed")
    }

    @Test
    fun `a thread retired after enumeration is safely ignored without certifying process cleanup`() {
        val childFile = children(FIRST_TASK, "")
        val thread = childFile.parent
        val reader = LinuxOwnedChildReader(tasks) { path ->
            Files.delete(childFile)
            Files.delete(thread)
            throw NoSuchFileException(path.toString())
        }

        assertThat(reader.read()).isEmpty()
        assertThat(Files.exists(thread)).isFalse()
    }

    private fun children(task: Int, content: String): Path {
        val directory = Files.createDirectory(tasks.resolve(task.toString()))
        return Files.writeString(directory.resolve("children"), content)
    }

    private companion object {
        const val FIRST_TASK = 1
        const val SECOND_TASK = 2
        const val FIRST_CHILD = 101
        const val SECOND_CHILD = 102
        const val THIRD_CHILD = 103
    }
}
