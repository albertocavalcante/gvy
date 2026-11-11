package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InlineFilesTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `files are written with markers stripped and LF line endings`() {
        val markers = InlineFiles.write(
            root,
            mapOf(
                "src/a/Greeter.groovy" to "class |^decl|Greeter {\r\n    String hi() { 'hi' }\r\n}\r\n",
                "Main.groovy" to "new Greeter().|^call|hi()\n",
            ),
        )

        assertThat(Files.readString(root.resolve("src/a/Greeter.groovy")))
            .isEqualTo("class Greeter {\n    String hi() { 'hi' }\n}\n")
        assertThat(Files.readString(root.resolve("Main.groovy"))).isEqualTo("new Greeter().hi()\n")
        assertThat(markers).isEqualTo(
            mapOf(
                "src/a/Greeter.groovy" to listOf(CursorMarker("decl", 0, 6)),
                "Main.groovy" to listOf(CursorMarker("call", 0, 14)),
            ),
        )
    }

    @Test
    fun `inline files overwrite fixture files`() {
        Files.writeString(root.resolve("Existing.groovy"), "old")

        InlineFiles.write(root, mapOf("Existing.groovy" to "new"))

        assertThat(Files.readString(root.resolve("Existing.groovy"))).isEqualTo("new")
    }

    @Test
    fun `files are written as UTF-8`() {
        InlineFiles.write(root, mapOf("U.groovy" to "def s = 'ação 😀'"))

        assertThat(Files.readAllBytes(root.resolve("U.groovy")))
            .isEqualTo("def s = 'ação 😀'".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `absolute paths are rejected`() {
        listOf("/etc/A.groovy", "\\A.groovy", "C:/A.groovy", "C:\\A.groovy").forEach { path ->
            assertThatThrownBy { InlineFiles.write(root, mapOf(path to "x")) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(path)
                .hasMessageContaining("relative")
        }
    }

    @Test
    fun `parent segments are rejected and nothing is written`() {
        listOf("../A.groovy", "src/../../A.groovy", "src\\..\\A.groovy", "..").forEach { path ->
            assertThatThrownBy { InlineFiles.write(root, mapOf("Ok.groovy" to "x", path to "x")) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(path)
                .hasMessageContaining("..")
        }
        assertThat(Files.exists(root.resolve("Ok.groovy"))).isFalse()
    }

    @Test
    fun `blank paths are rejected`() {
        assertThatThrownBy { InlineFiles.write(root, mapOf(" " to "x")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the same marker name in two inline files is rejected`() {
        assertThatThrownBy { InlineFiles.write(root, mapOf("A.groovy" to "|^x|a", "B.groovy" to "|^x|b")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("|^x|")
            .hasMessageContaining("A.groovy")
            .hasMessageContaining("B.groovy")
    }

    @Test
    fun `marker errors name the inline file`() {
        assertThatThrownBy { InlineFiles.write(root, mapOf("A.groovy" to "|^uri|a")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("A.groovy")
            .hasMessageContaining("reserved")
    }
}
