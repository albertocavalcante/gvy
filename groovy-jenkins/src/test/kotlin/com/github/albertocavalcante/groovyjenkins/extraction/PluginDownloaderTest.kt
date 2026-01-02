package com.github.albertocavalcante.groovyjenkins.extraction

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name

class PluginDownloaderTest {

    @Test
    fun `download fetches plugin from releases`(@TempDir cacheDir: Path) = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel("mock-content"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/java-archive"),
            )
        }
        val client = HttpClient(mockEngine)
        client.use {
            val downloader = PluginDownloader(cacheDir, it)
            val path = downloader.download("workflow-basic-steps", "2.18")

            assertTrue(path.exists())
            assertEquals("workflow-basic-steps-2.18.hpi", path.name)
        }
    }
}
