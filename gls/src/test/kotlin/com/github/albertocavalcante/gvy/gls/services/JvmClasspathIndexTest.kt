// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.services.ClassFileFixtures.ACC_MODULE
import com.github.albertocavalcante.gvy.gls.services.ClassFileFixtures.ACC_PUBLIC_SUPER
import com.github.albertocavalcante.gvy.gls.services.ClassFileFixtures.ACC_SYNTHETIC
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JvmClasspathIndexTest {

    @TempDir
    lateinit var tempDir: Path

    private fun jarOf(vararg entries: Pair<String, ByteArray>): Path {
        val jar = Files.createTempFile(tempDir, "lib", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return jar
    }

    private fun classOf(name: String, flags: Int = ACC_PUBLIC_SUPER) = ClassFileFixtures.minimal(name, flags)

    private fun index(vararg entries: Path): List<IndexedClass> = JvmClasspathIndex().index(
        entries.map {
            it.toString()
        },
    )

    private fun List<IndexedClass>.names() = map { it.fullName }

    @Test
    fun `indexes JDK classes when classpath entries are provided`() {
        val results = JvmClasspathIndex().index(listOf(tempDir.toString()))

        assertThat(results).anyMatch { it.fullName == "java.lang.String" }
    }

    @Test
    fun `lists JDK nested classes by their own name and includes Object`() {
        val results = JvmClasspathIndex().index(emptyList())

        assertThat(results).contains(
            IndexedClass("Entry", "java.util.Map\$Entry"),
            IndexedClass("Object", "java.lang.Object"),
        )
        assertThat(results.names()).doesNotContain("java.util.ArrayList\$Itr")
    }

    @Test
    fun `indexes the public classes of a jar by simple and qualified name`() {
        val jar = jarOf(
            "demo/Shown.class" to classOf("demo/Shown"),
            "demo/Outer\$Inner.class" to classOf("demo/Outer\$Inner"),
            "demo/Hidden.class" to classOf("demo/Hidden", flags = 0),
            "demo/Generated.class" to classOf("demo/Generated", ACC_PUBLIC_SUPER or ACC_SYNTHETIC),
            "demo/Descriptor.class" to classOf("demo/Descriptor", ACC_PUBLIC_SUPER or ACC_MODULE),
            "demo/Broken.class" to "not a class".toByteArray(),
            "demo/readme.txt" to "text".toByteArray(),
        )

        val results = index(jar)

        assertThat(results).contains(IndexedClass("Shown", "demo.Shown"), IndexedClass("Inner", "demo.Outer\$Inner"))
        assertThat(results.names()).doesNotContain("demo.Hidden", "demo.Generated", "demo.Descriptor", "demo.Broken")
    }

    @Test
    fun `skips entries whose path is not the class's own name`() {
        val jar = jarOf(
            "BOOT-INF/classes/com/acme/Foo.class" to classOf("com/acme/Foo"),
            "classes/x/Bar.class" to classOf("x/Bar"),
            "/x/Baz.class" to classOf("x/Baz"),
            "demo/Liar.class" to classOf("demo/Other"),
            "demo/Honest.class" to classOf("demo/Honest"),
        )

        val names = index(jar).names()

        assertThat(names).contains("demo.Honest")
        assertThat(names).noneMatch { it.startsWith("BOOT-INF") || it.startsWith("classes.") || it.startsWith(".") }
        assertThat(names).doesNotContain("com.acme.Foo", "x.Bar", "x.Baz")
        assertThat(names).doesNotContain("demo.Liar", "demo.Other")
    }

    @Test
    fun `lists classes that only exist under a supported multi-release directory`() {
        val jar = jarOf(
            "META-INF/versions/9/demo/Only9.class" to classOf("demo/Only9"),
            "META-INF/versions/9/module-info.class" to classOf("module-info"),
            "META-INF/versions/99/demo/Future.class" to classOf("demo/Future"),
            "META-INF/versions/9/demo/Liar.class" to classOf("demo/Other"),
            "META-INF/services/demo.Service" to "demo.Impl".toByteArray(),
        )

        val names = index(jar).names()

        assertThat(names).contains("demo.Only9")
        assertThat(names).doesNotContain("demo.Future", "demo.Liar", "module-info")
    }

    @Test
    fun `indexes a class directory by relative path`() {
        val directory = Files.createDirectories(tempDir.resolve("classes/demo"))
        Files.write(directory.resolve("FromDirectory.class"), classOf("demo/FromDirectory"))

        val results = index(tempDir.resolve("classes"))

        assertThat(results).contains(IndexedClass("FromDirectory", "demo.FromDirectory"))
    }

    @Test
    fun `skips an oversized class file without reading it`() {
        val size = (JvmClasspathIndex.MAX_CLASS_FILE_BYTES + 1).toInt()
        val jar = jarOf("demo/Big.class" to ByteArray(size), "demo/Small.class" to classOf("demo/Small"))

        val names = index(jar).names()

        assertThat(names).contains("demo.Small")
        assertThat(names).doesNotContain("demo.Big")
    }

    @Test
    fun `a corrupt archive and a missing entry do not stop the index`() {
        val good = jarOf("demo/Good.class" to classOf("demo/Good"))
        val garbage = Files.write(tempDir.resolve("garbage.jar"), "this is not a zip".toByteArray())
        val truncated = tempDir.resolve("truncated.jar").also {
            Files.write(it, Files.readAllBytes(good).copyOf(Files.size(good).toInt() / 2))
        }
        val missing = tempDir.resolve("missing.jar")

        val results = index(garbage, truncated, missing, good)

        assertThat(results.names()).contains("demo.Good", "java.lang.String")
    }

    @Test
    fun `a corrupt entry inside an archive does not hide its good entries`() {
        val payload = ByteArray(4096) { (it * 31).toByte() } // incompressible enough to be stored deflated
        val jar = jarOf("demo/Rotten.class" to payload, "demo/Good.class" to classOf("demo/Good"))
        val bytes = Files.readAllBytes(jar)
        val start = String(bytes, Charsets.ISO_8859_1).indexOf("demo/Rotten.class") + "demo/Rotten.class".length
        for (i in start until start + 64) bytes[i] = (bytes[i].toInt() xor 0x5A).toByte()
        Files.write(jar, bytes)

        assertThat(index(jar).names()).contains("demo.Good")
    }

    @Test
    fun `a later copy is used when the first copy of a name is unreadable or not public`() {
        val first = jarOf("demo/Dup.class" to "garbage".toByteArray())
        val second = jarOf("demo/Dup.class" to classOf("demo/Dup"))

        assertThat(index(first, second).names()).contains("demo.Dup")
    }

    @Test
    fun `lists each qualified name once with the first usable entry winning`() {
        val first = jarOf("demo/Same.class" to classOf("demo/Same"))
        val second = jarOf("demo/Same.class" to classOf("demo/Same"))

        assertThat(index(first, second).filter { it.fullName == "demo.Same" }).hasSize(1)
    }

    @Test
    fun `skips an entry that does not exist`() {
        assertThat(index(tempDir.resolve("missing.jar")).names()).contains("java.lang.String")
    }
}
