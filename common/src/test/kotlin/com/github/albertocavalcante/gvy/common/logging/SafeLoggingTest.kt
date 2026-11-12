package com.github.albertocavalcante.gvy.common.logging

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeLoggingTest {
    @Test
    fun `local rendering redacts known credentials and escapes controls`() {
        val result = SafeLogging.localText(
            "token=secret123 password=hunter2 Authorization: Bearer abc123\nnext\u0000",
            256,
        )
        assertFalse(result.text.contains("secret123"))
        assertFalse(result.text.contains("hunter2"))
        assertFalse(result.text.contains("abc123"))
        assertTrue(result.text.contains("[REDACTED]"))
        assertTrue(result.text.contains("\\n"))
        assertTrue(result.text.contains("\\u0000"))
        val quoted = SafeLogging.localText("password=\"two words\" Authorization: Bearer  key-value\u2028end", 256)
        assertFalse(quoted.text.contains("two words"))
        assertFalse(quoted.text.contains("key-value"))
        assertTrue(quoted.text.contains("\\u2028"))
        val urlText = SafeLogging.localText("connect https://user:pw@repo.example/private?token=abc", 256)
        assertEquals("connect https://repo.example", urlText.text)
    }

    @Test
    fun `basic authorization is fully redacted without hiding the next field`() {
        val basic = SafeLogging.localText("Authorization: Basic dXNlcjpwYXNz next=visible", 256)
        assertFalse(basic.text.contains("dXNlcjpwYXNz"))
        assertTrue(basic.text.contains("next=visible"))
    }

    @Test
    fun `escaped quoted credentials are fully redacted without hiding the next field`() {
        val quoted = SafeLogging.localText("password=\"one\\\"two secret\" next=visible", 256)
        assertFalse(quoted.text.contains("two secret"))
        assertTrue(quoted.text.contains("next=visible"))
    }

    @Test
    fun `JSON-style secret keys are redacted without hiding other fields`() {
        val result = SafeLogging.localText("{\"password\":\"json-secret\",\"ok\":\"visible\"}", 256)
        assertFalse(result.text.contains("json-secret"))
        assertTrue(result.text.contains("visible"))
    }

    @Test
    fun `UTF-8 limit includes the truncation marker and does not split code points`() {
        for (budget in 0..48) {
            val result = SafeLogging.localText("é🙂".repeat(100), budget)
            val bytes = result.text.toByteArray(Charsets.UTF_8)
            assertTrue(bytes.size <= budget, "budget=$budget actual=${bytes.size}")
            assertEquals(result.text, bytes.toString(Charsets.UTF_8))
            assertTrue(result.truncated)
            if (budget in 1..10) assertTrue(result.text.endsWith("~"))
        }
        val result = SafeLogging.localText("é🙂".repeat(100), 48)
        assertTrue(result.text.contains("[truncated]"))
    }

    @Test
    fun `exact UTF-8 limit stays intact and malformed characters remain valid UTF-8`() {
        val exact = SafeLogging.localText("é🙂", 6)
        assertEquals("é🙂", exact.text)
        assertFalse(exact.truncated)
        val malformed = SafeLogging.localText("\uD800", 16)
        assertEquals("\uFFFD", malformed.text)
        assertEquals(malformed.text, malformed.text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertFailsWith<IllegalArgumentException> { SafeLogging.localText("x", -1) }
    }

    @Test
    fun `large input has bounded scan and explicit omission`() {
        val result = SafeLogging.localText("a".repeat(1_000_000), 512)
        assertTrue(result.truncated)
        assertTrue(result.text.endsWith("[truncated]"))
        assertTrue(result.text.toByteArray(Charsets.UTF_8).size <= 512)
        assertTrue(result.examinedChars <= 16_384)
        assertEquals(1_000_000 - result.examinedChars, result.unscannedChars)
        assertTrue(result.omittedRenderedBytes > 0)
    }

    @Test
    fun `shared text vectors preserve the declared UTF-8 contract`() {
        val resource = checkNotNull(javaClass.getResourceAsStream("/logging/safe-rendering-vectors.tsv"))
        resource.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val fields = line.split('\t')
                assertEquals(4, fields.size)
                val input = fields[1].replace("\\n", "\n").replace("\\uD800", "\uD800")
                val actual = SafeLogging.localText(input, fields[0].toInt())
                assertEquals(fields[2], actual.text)
                assertEquals(fields[3].toBooleanStrict(), actual.truncated)
            }
        }
    }

    @Test
    fun `URL touching scan cutoff is treated as incomplete rather than revealing userinfo`() {
        val apparentOrigin = "https://SECRET"
        val prefix = "x".repeat(16_384 - apparentOrigin.length)
        val result = SafeLogging.localText(prefix + apparentOrigin + "@repo.example/path", 65_536)
        assertFalse(result.text.contains("SECRET"))
        assertTrue(result.text.contains("<incomplete-url>"))
        assertTrue(result.truncated)

        val queryOrigin = "https://repo.example"
        val queryPrefix = "x".repeat(16_384 - queryOrigin.length)
        val query = SafeLogging.localText(queryPrefix + queryOrigin + "?token=secret", 65_536)
        assertTrue(query.text.contains("<incomplete-url>"))
        assertFalse(query.text.contains("secret"))

        val complete = SafeLogging.localText(queryOrigin, 65_536)
        assertEquals(queryOrigin, complete.text)
        assertFalse(complete.truncated)
    }

    @Test
    fun `repository identity omits credentials paths and queries`() {
        assertEquals(
            "https://repo.example:8443",
            SafeLogging.repositoryIdentity("https://user:secret@repo.example:8443/private?token=abc#frag"),
        )
        assertEquals("<invalid-url>", SafeLogging.repositoryIdentity("https://secret@%bad/path?token=abc"))
        assertEquals("<invalid-url>", SafeLogging.repositoryIdentity("https://" + "x".repeat(10_000)))
        val metadata = SafeLogging.launchMetadata(2)
        assertEquals(2, metadata.argumentCount)
        assertFalse(metadata.toString().contains("secret"))
    }

    @Test
    fun `paths are lexically normalized and never reveal external locations`() {
        assertEquals(
            "<workspace>/src/Probe.groovy",
            SafeLogging.workspacePath("/project/a/../src/Probe.groovy", "/project"),
        )
        assertEquals("<external-path>", SafeLogging.workspacePath("/project/../secret", "/project"))
        assertEquals("<external-path>", SafeLogging.workspacePath("relative/secret", "/project"))
        assertEquals("<external-path>", SafeLogging.workspacePath("/project/\u0000", "/project"))
    }

    @Test
    fun `enabled raw detail can use its explicit byte budget`() {
        val input = "x".repeat(20_000)
        val captured = checkNotNull(RawDetailPolicy.Enabled(32_768).capture(input))
        assertEquals(input, captured.text)
        assertFalse(captured.truncated)
        assertEquals(0, captured.unscannedChars)
    }

    @Test
    fun `session detail is opt-in bounded and never becomes a support event`() {
        assertEquals(null, LoggingSession().rawDetail.capture("token=secret"))
        val enabled = RawDetailPolicy.Enabled(32)
        val captured = checkNotNull(enabled.capture("sensitive-value".repeat(100)))
        assertTrue(captured.potentiallySensitive)
        assertFalse(captured.toString().contains("sensitive-value"))
        assertTrue(captured.text.toByteArray(Charsets.UTF_8).size <= 32)
        val event = SupportEvent(SupportOperation.BUILD, SupportOutcome.FAILURE, 5, 1)
        assertEquals(SupportOutcome.FAILURE, event.outcome)
        assertFalse(event.toString().contains("secret"))
        assertFailsWith<IllegalArgumentException> {
            SupportEvent(SupportOperation.BUILD, SupportOutcome.FAILURE, -1, 1)
        }
        assertFailsWith<IllegalArgumentException> { RawDetailPolicy.Enabled(65_537) }
    }
}
