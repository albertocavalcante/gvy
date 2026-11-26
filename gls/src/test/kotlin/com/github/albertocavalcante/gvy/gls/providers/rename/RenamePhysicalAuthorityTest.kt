// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenamePhysicalAuthorityTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `root and primary parent retargets invalidate the captured receipt`() {
        val first = Files.createDirectory(directory.resolve("first"))
        val second = Files.createDirectory(directory.resolve("second"))
        Files.writeString(first.resolve("A.groovy"), "same")
        Files.writeString(second.resolve("A.groovy"), "same")
        val root = Files.createSymbolicLink(directory.resolve("root"), first)
        val primary = root.resolve("A.groovy")
        val receipt = RenamePhysicalAuthority(listOf(root), primary.toUri())
        assertTrue(receipt.contains(primary.toUri(), false))
        assertTrue(receipt.current())
        Files.delete(root)
        Files.createSymbolicLink(root, second)
        assertFalse(receipt.current())
    }

    @Test
    fun `links are followed before dot dot and missing descendants`() {
        val root = Files.createDirectory(directory.resolve("root"))
        val nested = Files.createDirectories(root.resolve("actual/nested"))
        val link = Files.createSymbolicLink(root.resolve("alias"), nested)
        val primary = link.resolve("../missing/A.groovy")
        val receipt = RenamePhysicalAuthority(listOf(root), primary.toUri())
        assertTrue(receipt.contains(primary.toUri(), false))
        assertTrue(receipt.current())
        val outside = Files.createDirectory(directory.resolve("outside"))
        val escape = Files.createSymbolicLink(root.resolve("escape"), outside)
        assertFalse(receipt.contains(escape.resolve("missing/A.groovy").toUri(), false))
    }
}
