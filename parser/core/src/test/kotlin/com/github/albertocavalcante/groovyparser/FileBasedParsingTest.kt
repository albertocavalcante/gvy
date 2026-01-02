package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class FileBasedParsingTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parse from File`() {
        val file = tempDir.resolve("Test.groovy").toFile()
        file.writeText(
            """
            package com.example
            class FileTest {
                String name
            }
            """.trimIndent(),
        )

        val unit = StaticGroovyParser.parse(file)

        assertThat(unit.packageDeclaration.isPresent).isTrue()
        assertThat(unit.packageDeclaration.get().name).isEqualTo("com.example")
        assertThat(unit.types).hasSize(1)
        assertThat((unit.types[0] as ClassDeclaration).name).isEqualTo("FileTest")
    }

    @Test
    fun `parse from Path`() {
        val path = tempDir.resolve("PathTest.groovy")
        path.toFile().writeText("class PathTest { def run() {} }")

        val unit = StaticGroovyParser.parse(path)

        assertThat(unit.types).hasSize(1)
        assertThat((unit.types[0] as ClassDeclaration).name).isEqualTo("PathTest")
        assertThat((unit.types[0] as ClassDeclaration).methods).hasSize(1)
    }

    @Test
    fun `parse from InputStream`() {
        val code = "class StreamTest { int count = 42 }"
        val inputStream = ByteArrayInputStream(code.toByteArray(StandardCharsets.UTF_8))

        val unit = StaticGroovyParser.parse(inputStream)

        assertThat(unit.types).hasSize(1)
        assertThat((unit.types[0] as ClassDeclaration).name).isEqualTo("StreamTest")
    }

    @Test
    fun `parse from InputStream with encoding`() {
        val code = "class EncodingTest { String message = 'héllo' }"
        val inputStream = ByteArrayInputStream(code.toByteArray(StandardCharsets.UTF_8))

        val unit = StaticGroovyParser.parse(inputStream, StandardCharsets.UTF_8)

        assertThat(unit.types).hasSize(1)
        assertThat((unit.types[0] as ClassDeclaration).name).isEqualTo("EncodingTest")
    }

    @Test
    fun `parse from Reader`() {
        val code = "class ReaderTest { void process() {} }"
        val reader = StringReader(code)

        val unit = StaticGroovyParser.parse(reader)

        assertThat(unit.types).hasSize(1)
        assertThat((unit.types[0] as ClassDeclaration).name).isEqualTo("ReaderTest")
    }

    @Test
    fun `parseResult from File returns ParseResult`() {
        val file = tempDir.resolve("ResultTest.groovy").toFile()
        file.writeText("class ResultTest {}")

        val result = StaticGroovyParser.parseResult(file)

        assertThat(result.isSuccessful).isTrue()
        assertThat(result.result.isPresent).isTrue()
        assertThat((result.result.get().types[0] as ClassDeclaration).name).isEqualTo("ResultTest")
    }

    @Test
    fun `parseResult from Path returns ParseResult`() {
        val path = tempDir.resolve("PathResultTest.groovy")
        path.toFile().writeText("class PathResultTest {}")

        val result = StaticGroovyParser.parseResult(path)

        assertThat(result.isSuccessful).isTrue()
    }

    @Test
    fun `parseResult returns problems for invalid code`() {
        val result = StaticGroovyParser.parseResult("class { invalid }")

        assertThat(result.isSuccessful).isFalse()
        assertThat(result.problems).isNotEmpty()
    }

    @Test
    fun `parse respects configuration encoding`() {
        val file = tempDir.resolve("Utf8Test.groovy").toFile()
        file.writeText("class Utf8Test { String café = 'naïve' }", StandardCharsets.UTF_8)

        StaticGroovyParser.setConfiguration(
            ParserConfiguration().setCharacterEncoding(StandardCharsets.UTF_8),
        )

        val unit = StaticGroovyParser.parse(file)

        assertThat(unit.types).hasSize(1)
        assertThat((unit.types[0] as ClassDeclaration).name).isEqualTo("Utf8Test")
    }

    @Test
    fun `parse multiple files from directory`() {
        // Create test files
        tempDir.resolve("A.groovy").toFile().writeText("class A {}")
        tempDir.resolve("B.groovy").toFile().writeText("class B {}")
        tempDir.resolve("C.groovy").toFile().writeText("class C {}")

        val units = tempDir.toFile()
            .listFiles { _, name -> name.endsWith(".groovy") }
            ?.map { StaticGroovyParser.parse(it) }
            ?: emptyList()

        assertThat(units).hasSize(3)
        val classNames = units.flatMap { it.types }.map { (it as ClassDeclaration).name }
        assertThat(classNames).containsExactlyInAnyOrder("A", "B", "C")
    }
}
