package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.dsl.completion.completions
import com.github.albertocavalcante.gvy.gls.providers.codelens.TestCodeLensProvider
import com.github.albertocavalcante.gvy.testing.registry.TestFrameworkRegistry
import com.github.albertocavalcante.gvy.testing.spock.SpockTestDetector
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/** Characterizes the LSP4J adapter; packaged-server transports need separate coverage. */
class DynamicPayloadWireContractTest {
    interface FixtureService {
        @JsonRequest("fixture/completion")
        fun completion(): CompletableFuture<List<CompletionItem>>

        @JsonRequest("fixture/codeLens")
        fun codeLens(): CompletableFuture<List<CodeLens>>
    }

    @Test
    fun `dynamic completion and test command data retain their wire shapes`(): Unit = runBlocking {
        val completion = completions {
            method("café", "String", listOf("String name"), signature = "café(java.lang.String)")
        }
        val lenses = testLenses()
        val service = object : FixtureService {
            override fun completion() = CompletableFuture.completedFuture(completion)
            override fun codeLens() = CompletableFuture.completedFuture(lenses)
        }
        val responses = sendRequests(service)
        val item = responses.getValue(1)["result"].asJsonArray.single().asJsonObject
        assertEquals(JsonParser.parseString("""{"signature":"café(java.lang.String)"}"""), item["data"])
        val commands = responses.getValue(2)["result"].asJsonArray.map { it.asJsonObject["command"].asJsonObject }
        val runAll = commands.single { it["title"].asString == "\$(play) Run All" }
        assertEquals(
            JsonParser.parseString(
                """[{"uri":"file:///MySpec.groovy","suite":"com.example.MySpec",
                "test":"*","debug":false}]""",
            ),
            runAll["arguments"],
        )
        val run = commands.single { it["title"].asString == "\$(play) Run" }
        val debug = commands.single { it["title"].asString == "\$(debug-alt) Debug" }
        assertEquals("groovy.test.run", run["command"].asString)
        assertEquals(
            JsonParser.parseString(
                """[{"uri":"file:///MySpec.groovy","suite":"com.example.MySpec",
                "test":"should work","debug":false,"framework":"SPOCK"}]""",
            ),
            run["arguments"],
        )
        assertEquals("groovy.test.debug", debug["command"].asString)
        assertEquals(
            JsonParser.parseString(
                """[{"uri":"file:///MySpec.groovy","suite":"com.example.MySpec",
                "test":"should work","debug":true,"framework":"SPOCK"}]""",
            ),
            debug["arguments"],
        )
    }
    private suspend fun testLenses(): List<CodeLens> {
        val uri = URI.create("file:///MySpec.groovy")
        val source = """
            package com.example
            import spock.lang.Specification
            class MySpec extends Specification {
                def "should work"() {
                    expect: true
                }
            }
        """.trimIndent()
        val compiler = GroovyCompilationService()
        compiler.compile(uri, source, compilePhase = Phases.CONVERSION)
        val compilation = mockk<GroovyCompilationService>()
        every { compilation.getParseResult(uri) } returns compiler.getParseResult(uri)
        val registry = TestFrameworkRegistry().apply { register(SpockTestDetector()) }
        val lenses = TestCodeLensProvider(compilation, registry).provideCodeLenses(uri)
        return lenses
    }

    private fun sendRequests(service: FixtureService): Map<Int, com.google.gson.JsonObject> {
        val requests = listOf(
            """{"jsonrpc":"2.0","id":1,"method":"fixture/completion"}""",
            """{"jsonrpc":"2.0","id":2,"method":"fixture/codeLens"}""",
        )
        val frames = requests.joinToString("") { body ->
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n$body"
        }
        val output = ByteArrayOutputStream()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val launcher = Launcher.Builder<LanguageClient>()
                .setLocalService(service)
                .setRemoteInterface(LanguageClient::class.java)
                .setInput(ByteArrayInputStream(frames.toByteArray(Charsets.UTF_8)))
                .setOutput(output)
                .setExecutorService(executor)
                .create()
            launcher.startListening().get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        val bytes = output.toByteArray()
        val wire = bytes.toString(Charsets.UTF_8)
        val headers = Regex("Content-Length: (\\d+)\\r\\n\\r\\n").findAll(wire).toList()
        assertEquals(2, headers.size)
        return headers.mapIndexed { index, header ->
            val end = headers.getOrNull(index + 1)?.range?.first ?: wire.length
            val body = wire.substring(header.range.last + 1, end)
            assertEquals(header.groupValues[1].toInt(), body.toByteArray(Charsets.UTF_8).size)
            JsonParser.parseString(body).asJsonObject
        }.associateBy { it["id"].asInt }
    }
}
