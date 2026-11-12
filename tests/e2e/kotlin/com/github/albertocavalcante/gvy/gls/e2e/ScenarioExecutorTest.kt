// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ScenarioExecutorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `invalid inline files remove the materialized workspace and name the scenario`() {
        val root = Files.createDirectories(tempDir.resolve("ws"))

        assertThatThrownBy { writeInlineFilesOrCleanUp(root, "my-scenario", mapOf("../A.groovy" to "x")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("my-scenario")
        assertThat(Files.exists(root)).isFalse()
    }

    @Test
    fun `IO failures while writing inline files remove the materialized workspace`() {
        val root = Files.createDirectories(tempDir.resolve("ws"))
        // A fixture file where an inline file needs a directory.
        Files.writeString(root.resolve("a"), "fixture")

        assertThatThrownBy { writeInlineFilesOrCleanUp(root, "my-scenario", mapOf("a/B.groovy" to "x")) }
            .isInstanceOf(java.io.IOException::class.java)
        assertThat(Files.exists(root)).isFalse()
    }

    @Test
    fun `valid inline files keep the workspace and return markers`() {
        val root = Files.createDirectories(tempDir.resolve("ws"))

        val markers = writeInlineFilesOrCleanUp(root, "my-scenario", mapOf("A.groovy" to "|^a|x"))

        assertThat(markers).isEqualTo(mapOf("A.groovy" to listOf(CursorMarker("a", 0, 0))))
        assertThat(Files.readString(root.resolve("A.groovy"))).isEqualTo("x")
    }
}
