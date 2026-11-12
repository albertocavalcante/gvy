package com.github.albertocavalcante.gvy.gls.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.Charset

@Isolated("Captures the process output streams")
class Utf8StdoutTest {
    @Test
    fun `JSON payload bytes stay UTF-8 through a legacy stdout encoding`() {
        val json = """{"message":"hello 世界 😀","name":"déjà λ"}"""
        val text = json + System.lineSeparator()
        val originalOut = System.out
        val originalErr = System.err
        val bytes = ByteArrayOutputStream()
        val capturedOut = PrintStream(bytes, true, Charset.forName("windows-1252"))
        System.setOut(capturedOut)
        try {
            writeUtf8Stdout(text)
            assertThat(System.out).isSameAs(capturedOut)
            assertThat(System.err).isSameAs(originalErr)
        } finally {
            System.setOut(originalOut)
            capturedOut.close()
        }

        assertThat(bytes.toByteArray()).containsExactly(*text.toByteArray(Charsets.UTF_8))
    }
}
