// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import javax.tools.ToolProvider
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class RotatingMemberBinary(temp: Path, val archive: Boolean) {
    private val original = build(temp.resolve("before"), "String", "Alpha")
    private val replacement = build(temp.resolve("after"), "Double", "Bravo")
    val root: Path = if (archive) original.resolve("dep.jar") else original
    private val target = if (archive) root else root.resolve("dep/Parent.class")

    fun replace() {
        val next = if (archive) replacement.resolve("dep.jar") else replacement.resolve("dep/Parent.class")
        val size = Files.size(target)
        val time = Files.getLastModifiedTime(target)
        val old = digest(target)
        val newer = digest(next)
        assertEquals(size, Files.size(next))
        assertFalse(old.contentEquals(newer))
        val sibling = Files.createTempFile(target.parent, "rotation", ".tmp")
        try {
            Files.copy(next, sibling, StandardCopyOption.REPLACE_EXISTING)
            Files.setLastModifiedTime(sibling, time)
            Files.move(sibling, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(sibling)
        }
        assertEquals(size, Files.size(target))
        assertEquals(time, Files.getLastModifiedTime(target))
        assertContentEquals(newer, digest(target))
        URLClassLoader(arrayOf(root.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            assertBinding(loader.loadClass("dep.Base"), replacement = true)
        }
    }

    fun assertBinding(base: Class<*>, replacement: Boolean) {
        val owner = base.superclass
        assertEquals("dep.Base", base.name)
        assertEquals("dep.Parent", owner.name)
        val member = base.getMethod("receiptCall", String::class.java)
        assertSame(owner, member.declaringClass)
        assertEquals(if (replacement) "Double" else "String", member.returnType.simpleName)
        val completion = if (replacement) "receiptBravo" else "receiptAlpha"
        assertSame(owner, base.getMethod(completion).declaringClass)
        assertTrue(Files.isSameFile(root, Path.of(owner.protectionDomain.codeSource.location.toURI())))
    }

    private fun build(dir: Path, type: String, suffix: String): Path {
        val source = Files.createDirectories(dir.resolve("src"))
        val output = Files.createDirectories(dir.resolve("bin"))
        val parent = Files.writeString(
            source.resolve("Parent.java"),
            """package dep;
                |public class Parent {
                |    public $type receiptCall(String arg) { return null; }
                |    public void receipt$suffix() {}
                |}
            """.trimMargin(),
        )
        val base = Files.writeString(source.resolve("Base.java"), "package dep; public class Base extends Parent {}")
        val javac = checkNotNull(ToolProvider.getSystemJavaCompiler())
        val code = javac.run(null, null, null, "-g:none", "-d", output.toString(), parent.toString(), base.toString())
        assertEquals(0, code)
        if (archive) {
            JarOutputStream(Files.newOutputStream(output.resolve("dep.jar"))).use { jar ->
                for (name in listOf("dep/Base.class", "dep/Parent.class")) {
                    val bytes = Files.readAllBytes(output.resolve(name))
                    val entry = JarEntry(name).apply {
                        method = ZipEntry.STORED
                        time = 1_000_000_000_000L
                        size = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    }
                    jar.putNextEntry(entry)
                    jar.write(bytes)
                    jar.closeEntry()
                }
            }
        }
        return output
    }

    private fun digest(file: Path): ByteArray = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
}
