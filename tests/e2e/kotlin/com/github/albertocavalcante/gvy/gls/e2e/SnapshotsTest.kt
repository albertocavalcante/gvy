package com.github.albertocavalcante.gvy.gls.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.AssertionFailedError
import java.nio.file.Files
import java.nio.file.Path

class SnapshotsTest {

    @TempDir
    lateinit var tempDir: Path

    private val posix = WorkspaceForms.from(listOf("/private/var/folders/x/ws" to "file:///private/var/folders/x/ws/"))

    @Test
    fun `normalizes uri and path forms including the macOS realpath`() {
        val text = "a file:///private/var/folders/x/ws/src/A.groovy b /private/var/folders/x/ws/src " +
            "c file:///private/var/folders/x/ws"

        assertThat(Snapshots.normalize(text, posix))
            .isEqualTo("a {{workspace.uri}}src/A.groovy b {{workspace.path}}/src c {{workspace.uri}}")
    }

    @Test
    fun `workspace of a real directory also matches its realpath`() {
        val real = Files.createDirectories(tempDir.resolve("ws")).toRealPath()
        val forms = WorkspaceForms.of(real)

        assertThat(Snapshots.normalize("${real.toUri()}A.groovy $real/A.groovy", forms))
            .isEqualTo("{{workspace.uri}}A.groovy {{workspace.path}}/A.groovy")
    }

    @Test
    fun `normalizes Windows path and uri forms`() {
        val forms = WorkspaceForms.from(listOf("C:\\Users\\me\\ws" to "file:///C:/Users/me/ws/"))

        val text = "file:///C:/Users/me/ws/A.groovy file:///c%3A/Users/me/ws/B.groovy " +
            "C:\\Users\\me\\ws\\A.groovy C:/Users/me/ws/B"

        assertThat(Snapshots.normalize(text, forms)).isEqualTo(
            "{{workspace.uri}}A.groovy {{workspace.uri}}B.groovy {{workspace.path}}\\A.groovy {{workspace.path}}/B",
        )
    }

    @Test
    fun `does not touch a sibling directory sharing the prefix`() {
        assertThat(Snapshots.normalize("/private/var/folders/x/ws2/A", posix)).isEqualTo("/private/var/folders/x/ws2/A")
    }

    @Test
    fun `renders strings raw and objects as sorted pretty json with normalized keys`() {
        assertThat(Snapshots.render(JsonPrimitive("hi"), posix)).isEqualTo("hi\n")

        val node = Json.parseToJsonElement(
            """{"b":1,"a":{"file:///private/var/folders/x/ws/A":"/private/var/folders/x/ws"}}""",
        )

        assertThat(Snapshots.render(node, posix)).isEqualTo(
            """
            {
                "a": {
                    "{{workspace.uri}}A": "{{workspace.path}}"
                },
                "b": 1
            }

            """.trimIndent(),
        )
    }

    @Test
    fun `identity hash guard rejects hashes and accepts ordinary text`() {
        assertThatThrownBy { Snapshots.requireStable("n", "x Foo\$Bar@1a2b3c4d y") }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("Foo\$Bar@1a2b3c4d")
        Snapshots.requireStable("n", "user@example.com and abc@12 and Foo@zzzzzz")
    }

    @Test
    fun `hash guard also applies in update mode and writes nothing`() {
        assertThatThrownBy {
            Snapshots.verify(tempDir, "a.txt", JsonPrimitive("Obj@deadbeef"), posix, update = true)
        }.isInstanceOf(AssertionError::class.java)
        assertThat(Files.exists(tempDir.resolve("a.txt"))).isFalse()
    }

    @Test
    fun `diff marks removed and added lines`() {
        assertThat(Snapshots.diff("a\nb\nc\n", "a\nx\nc\n")).isEqualTo("  a\n- b\n+ x\n  c\n")
    }

    @Test
    fun `mismatch throws AssertionFailedError with expected actual and diff`() {
        Files.writeString(tempDir.resolve("s.md"), "old\n")

        val failure = org.junit.jupiter.api.Assertions.assertThrows(AssertionFailedError::class.java) {
            Snapshots.verify(tempDir, "s.md", JsonPrimitive("new"), posix, update = false)
        }

        assertThat(failure.message).contains("- old", "+ new", "updateSnapshots")
        assertThat(failure.expected.stringRepresentation).isEqualTo("old\n")
        assertThat(failure.actual.stringRepresentation).isEqualTo("new\n")
    }

    @Test
    fun `altering the snapshot file fails the check and update restores it`() {
        Snapshots.verify(tempDir, "d/s.md", JsonPrimitive("same"), posix, update = true)
        Snapshots.verify(tempDir, "d/s.md", JsonPrimitive("same"), posix, update = false)

        Files.writeString(tempDir.resolve("d/s.md"), "tampered\n")
        assertThatThrownBy { Snapshots.verify(tempDir, "d/s.md", JsonPrimitive("same"), posix, update = false) }
            .isInstanceOf(AssertionFailedError::class.java)

        Snapshots.verify(tempDir, "d/s.md", JsonPrimitive("same"), posix, update = true)
        assertThat(Files.readString(tempDir.resolve("d/s.md"))).isEqualTo("same\n")
    }

    @Test
    fun `CRLF in the stored file does not cause a mismatch`() {
        Files.writeString(tempDir.resolve("s.md"), "a\r\nb\r\n")

        Snapshots.verify(tempDir, "s.md", JsonPrimitive("a\nb"), posix, update = false)
    }

    @Test
    fun `missing snapshot fails with creation instructions`() {
        assertThatThrownBy { Snapshots.verify(tempDir, "none.md", JsonPrimitive("x"), posix, update = false) }
            .isInstanceOf(AssertionFailedError::class.java)
            .hasMessageContaining("not found")
    }

    @Test
    fun `snapshot names cannot escape the directory`() {
        listOf("../x", "/abs", "a/../../x", "", ".").forEach {
            assertThatThrownBy { Snapshots.resolve(tempDir, it) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `update mode is refused on GitHub Actions and under Bazel`() {
        assertThatThrownBy { Snapshots.requireUpdateAllowed(mapOf("GITHUB_ACTIONS" to "true")) { null } }
            .hasMessageContaining("GITHUB_ACTIONS")
        assertThatThrownBy { Snapshots.requireUpdateAllowed(mapOf("TEST_TARGET" to "//tests:e2e_test")) { null } }
            .hasMessageContaining("Bazel")
        assertThatThrownBy {
            Snapshots.requireUpdateAllowed(emptyMap()) {
                if (it ==
                    "${Snapshots.DIR_PROPERTY}.marker"
                ) {
                    "/x/README.md"
                } else {
                    null
                }
            }
        }.hasMessageContaining("Bazel")

        Snapshots.requireUpdateAllowed(mapOf("GITHUB_ACTIONS" to "")) { null }
    }

    @Test
    fun `resolveResourceDir prefers the direct property then the marker parent`() {
        val prop = "gvy.test.resourceDir"
        try {
            assertThat(resolveResourceDir(prop)).isNull()
            System.setProperty("$prop.marker", "/a/b/README.md")
            assertThat(resolveResourceDir(prop)).isEqualTo("/a/b")
            System.setProperty(prop, "/direct")
            assertThat(resolveResourceDir(prop)).isEqualTo("/direct")
        } finally {
            System.clearProperty(prop)
            System.clearProperty("$prop.marker")
        }
    }
}
