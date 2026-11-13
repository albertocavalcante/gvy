// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.utils

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class SourcePathAdmissionTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `missing ordinary files and ordinary copies remain eligible outside the owned namespace`() {
        val managed = root.resolve("managed")
        val ordinary = Files.createDirectories(root.resolve("workspace"))
        val excluded: (Path) -> Boolean = { it.startsWith(managed) }

        assertEquals(
            SourcePathAdmission.MANAGED,
            classifySourcePath(managed.resolve("not-created/Mode.groovy"), excluded),
        )
        assertEquals(SourcePathAdmission.ORDINARY, classifySourcePath(ordinary.resolve("new/Mode.groovy"), excluded))
        assertEquals(
            SourcePathAdmission.ORDINARY,
            classifySourcePath(root.resolve("managed-copy/Mode.groovy"), excluded),
        )
    }

    @Test
    fun `linked ancestors resolve into the managed namespace including missing descendants`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val managed = Files.createDirectory(root.resolve("managed"))
        val ordinary = Files.createDirectory(root.resolve("workspace"))
        val alias = Files.createSymbolicLink(ordinary.resolve("cache"), managed)
        val excluded: (Path) -> Boolean = { it.startsWith(managed) }

        assertEquals(SourcePathAdmission.MANAGED, classifySourcePath(alias.resolve("future/Mode.groovy"), excluded))
        assertEquals(SourcePathAdmission.ORDINARY, classifySourcePath(ordinary.resolve("Mode.groovy"), excluded))
    }

    @Test
    fun `broken or non-directory ancestors cannot be admitted as ordinary files`() {
        val file = Files.writeString(root.resolve("file"), "content")
        assertEquals(SourcePathAdmission.UNAVAILABLE, classifySourcePath(file.resolve("Mode.groovy")) { false })
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val broken = Files.createSymbolicLink(root.resolve("broken"), root.resolve("missing"))
        assertEquals(SourcePathAdmission.UNAVAILABLE, classifySourcePath(broken.resolve("Mode.groovy")) { false })
    }
}
