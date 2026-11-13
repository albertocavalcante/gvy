// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceManagedPathsTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `immutable stores and missing metadata paths remain protected`() {
        val root = temporary.resolve("content-v2")
        val paths = SourceManagedPaths(listOf(root))
        assertTrue(paths.contains(root.resolve("content/digest/A.java").toUri()))
        assertTrue(paths.destinationIsManaged(root.resolve("archives/digest.jar").toUri()))
        assertFalse(paths.contains(temporary.resolve("workspace/A.java").toUri()))
        assertFalse(paths.destinationIsManaged(temporary.resolve("workspace/A.java").toUri()))
    }

    @Test
    fun `explicit destinations resolve aliases to managed stores including new descendants`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privilege is not assumed on Windows")
        val root = Files.createDirectory(temporary.resolve("content-v2"))
        val alias = Files.createSymbolicLink(temporary.resolve("alias"), root)
        val paths = SourceManagedPaths(listOf(root))
        assertTrue(paths.destinationIsManaged(alias.resolve("not-created/A.java").toUri()))
        val ordinary = Files.createDirectory(temporary.resolve("workspace"))
        val ordinaryAlias = Files.createSymbolicLink(temporary.resolve("ordinary-alias"), ordinary)
        assertFalse(paths.destinationIsManaged(ordinaryAlias.resolve("A.java").toUri()))
    }

    @Test
    fun `a future managed root under an aliased ancestor has a canonical namespace`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privilege is not assumed on Windows")
        val base = Files.createDirectory(temporary.resolve("real"))
        val alias = Files.createSymbolicLink(temporary.resolve("alias"), base)
        val paths = SourceManagedPaths(listOf(alias.resolve("content-v2")))
        assertTrue(paths.destinationIsManaged(base.resolve("content-v2/A.java").toUri()))
    }

    @Test
    fun `broken aliases fail closed before any write`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privilege is not assumed on Windows")
        val broken = Files.createSymbolicLink(temporary.resolve("broken"), temporary.resolve("missing"))
        val paths = SourceManagedPaths(emptyList())
        assertTrue(paths.destinationIsManaged(broken.toUri()))
    }

    @Test
    fun `resource operations protect managed roots and their containing directories`() {
        val root = temporary.resolve("managed/content-v2")
        val paths = SourceManagedPaths(listOf(root))
        assertTrue(paths.resourceOperationIsManaged(root.toUri()))
        assertTrue(paths.resourceOperationIsManaged(root.parent.toUri()))
        assertTrue(paths.resourceOperationIsManaged(root.resolve("A.java").toUri()))
        assertFalse(paths.resourceOperationIsManaged(temporary.resolve("workspace").toUri()))
        assertFalse(
            paths.destinationIsManaged(root.parent.resolve("copied.java").toUri()),
            "Ordinary copies beside a cache remain editable",
        )
    }

    @Test
    fun `resource operation ancestors resolve directory aliases`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privilege is not assumed on Windows")
        val base = Files.createDirectories(temporary.resolve("base"))
        val root = Files.createDirectories(base.resolve("managed"))
        val alias = Files.createSymbolicLink(temporary.resolve("alias"), base)
        assertTrue(SourceManagedPaths(listOf(root)).resourceOperationIsManaged(alias.toUri()))
    }

    @Test
    fun `case aliases on a case insensitive filesystem remain managed destinations`() {
        val root = Files.createDirectory(temporary.resolve("CaseManaged"))
        val alias = temporary.resolve("casemanaged")
        assumeTrue(Files.exists(alias), "Case-insensitive filesystem required")
        val paths = SourceManagedPaths(listOf(root))
        assertTrue(paths.destinationIsManaged(alias.resolve("A.java").toUri()))
    }
}
