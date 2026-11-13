// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceBrowserServiceTest {
    @TempDir
    lateinit var temporary: Path
    private val parserScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun stopParsers() {
        parserScope.cancel()
    }

    private val content = "package groovy.transform; public @interface CompileStatic { TypeCheckingMode value(); }"

    private fun document(registry: ExternalSourceRegistry, text: String = content, context: String = "context"): URI {
        val path = temporary.resolve(context + ".java")
        Files.writeString(path, text)
        return registry.register(
            SourceDocumentDescriptor(
                sourceDigest = "a".repeat(64),
                contentDigest = ExternalSourceRegistry.sha256(text.toByteArray()),
                entryName = "groovy/transform/CompileStatic.java",
                contentPath = path,
                origin = SourceOrigin.Bundled("groovy", URI.create("file:///server.jar")),
                context = SourceContextIdentity(context),
                displayName = "CompileStatic.java",
                languageId = "java",
            ),
        )
    }

    private fun located(name: String, context: String = "context"): SourceLookupResult.Located =
        SourceLookupResult.Located(
            URI.create("gvy-source://" + context + "/archive/" + name + ".java"),
            name,
            SourceDefinition(SourceSpan(0, 0, 4, 1), SourceSpan(1, 5, 1, 21)),
        )

    private fun browser(
        registry: ExternalSourceRegistry,
        locateType: suspend (String, SourceDocumentDescriptor) -> SourceLookupResult,
        buildIndex: (String, String) -> SourceTypeIndex = { _, text -> JavaSourceTypeIndex.build(text) },
    ): SourceBrowserService = SourceBrowserService(
        parserScope,
        registry,
        { descriptor -> SourceBrowserLookupSession({ name -> locateType(name, descriptor) }, { true }) },
        { language, text, _ -> buildIndex(language, text) },
    )

    @Test
    fun `the second source hop preserves its originating context and exact declaration ranges`() = runBlocking {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val uri = document(registry)
        val calls = mutableListOf<Pair<String, String>>()
        val service = browser(registry, { name, from ->
            calls.add(name to from.context.id)
            located(name)
        })
        val result = service.definition(uri, Position(0, content.indexOf("TypeCheckingMode")))
        assertEquals(listOf("groovy.transform.TypeCheckingMode" to "context"), calls)
        assertEquals(1, result.size)
        assertEquals(1, result.single().targetSelectionRange.start.line)
        assertEquals(5, result.single().targetSelectionRange.start.character)
        assertEquals(content.indexOf("TypeCheckingMode"), result.single().originSelectionRange.start.character)
    }

    @Test
    fun `identical source bytes share syntax indexing but never context dependent lookup results`() = runBlocking {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val a = document(registry, context = "one")
        val b = document(registry, context = "two")
        var indexes = 0
        val service = browser(
            registry,
            { name, from -> located(name, from.context.id) },
            { _, text ->
                indexes++
                JavaSourceTypeIndex.build(text)
            },
        )
        val position = Position(0, content.indexOf("TypeCheckingMode"))
        val first = service.definition(a, position).single()
        val second = service.definition(b, position).single()
        assertEquals(1, indexes)
        assertTrue(first.targetUri != second.targetUri)
        assertEquals(first.originSelectionRange, second.originSelectionRange)
    }

    @Test
    fun `ambiguous wildcard type candidates do not select the first result`() = runBlocking {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val text = "package sample; import a.*; import b.*; @interface C { Mode value(); }"
        val uri = document(registry, text)
        val service = browser(registry, { name, _ ->
            if (name == "a.Mode" || name == "b.Mode") {
                located(name)
            } else {
                SourceLookupResult.Unavailable(MissingSourceReason.BINARY_TYPE_NOT_PRESENT)
            }
        })
        assertTrue(service.definition(uri, Position(0, text.indexOf("Mode"))).isEmpty())
    }

    @Test
    fun `a retired source context remains content modified instead of ordinary no definition`() {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val uri = document(registry)
        val service = browser(registry, { _, _ -> SourceLookupResult.Stale })
        val failure = assertFailsWith<ResponseErrorException> {
            runBlocking { service.definition(uri, Position(0, content.indexOf("TypeCheckingMode"))) }
        }
        assertEquals(ResponseErrorCode.ContentModified.value, failure.responseError.code)
    }

    @Test
    fun `cancelling a source lookup does not become a successful empty response`() {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val uri = document(registry)
        val service = browser(registry, { _, _ -> throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> {
            runBlocking { service.definition(uri, Position(0, content.indexOf("TypeCheckingMode"))) }
        }
    }

    @Test
    fun `modified browsing buffers do not navigate using old source positions`() = runBlocking {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val uri = document(registry)
        var calls = 0
        val service = browser(registry, { name, _ ->
            calls++
            located(name)
        })
        registry.changed(uri)
        assertTrue(service.definition(uri, Position(0, content.indexOf("TypeCheckingMode"))).isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `a lookup cannot silently substitute another binary identity`() = runBlocking {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val uri = document(registry)
        val service = browser(registry, { _, _ -> located("unrelated.Type") })
        assertTrue(service.definition(uri, Position(0, content.indexOf("TypeCheckingMode"))).isEmpty())
    }

    @Test
    fun `portable edits during an origin lookup invalidate the pending location`() {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val uri = document(registry)
        val service = browser(registry, { name, _ ->
            registry.changed(uri)
            located(name)
        })
        val failure = assertFailsWith<ResponseErrorException> {
            runBlocking { service.definition(uri, Position(0, content.indexOf("TypeCheckingMode"))) }
        }
        assertEquals(ResponseErrorCode.ContentModified.value, failure.responseError.code)
    }

    @Test
    fun `same package binary without source cannot fall through to a java lang namesake`() = runBlocking {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val text = "package sample; @interface C { Mode value(); }"
        val uri = document(registry, text)
        val calls = mutableListOf<String>()
        val service = browser(registry, { name, _ ->
            calls.add(name)
            if (name == "sample.Mode") {
                SourceLookupResult.Unavailable(MissingSourceReason.NO_ATTACHMENT)
            } else {
                located(name)
            }
        })
        assertTrue(service.definition(uri, Position(0, text.indexOf("Mode"))).isEmpty())
        assertEquals(listOf("sample.Mode"), calls)
    }

    @Test
    fun `one wildcard candidate without source still prevents a competing located candidate winning`() = runBlocking {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val text = "package sample; import a.*; import b.*; @interface C { Mode value(); }"
        val uri = document(registry, text)
        val service = browser(registry, { name, _ ->
            when (name) {
                "a.Mode" -> SourceLookupResult.Unavailable(MissingSourceReason.TYPE_NOT_PRESENT)
                "b.Mode" -> located(name)
                else -> SourceLookupResult.Unavailable(MissingSourceReason.BINARY_TYPE_NOT_PRESENT)
            }
        })
        assertTrue(service.definition(uri, Position(0, text.indexOf("Mode"))).isEmpty())
    }

    @Test
    fun `one held session revalidates early located origins after another candidate suspends`() = runBlocking {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val text = "package sample; import a.*; import b.*; @interface C { Mode value(); }"
        val uri = document(registry, text)
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var current = true
        var sessions = 0
        val service = SourceBrowserService(parserScope, registry, {
            sessions++
            SourceBrowserLookupSession({ name ->
                when (name) {
                    "a.Mode" -> located(name)
                    "b.Mode" -> {
                        waiting.complete(Unit)
                        release.await()
                        SourceLookupResult.Unavailable(MissingSourceReason.BINARY_TYPE_NOT_PRESENT)
                    }
                    else -> SourceLookupResult.Unavailable(MissingSourceReason.BINARY_TYPE_NOT_PRESENT)
                }
            }, { current })
        })
        withTimeout(10_000) {
            supervisorScope {
                val pending = async { service.definition(uri, Position(0, text.indexOf("Mode"))) }
                waiting.await()
                current = false
                release.complete(Unit)
                val failure = assertFailsWith<ResponseErrorException> { pending.await() }
                assertEquals(ResponseErrorCode.ContentModified.value, failure.responseError.code)
                assertEquals(1, sessions)
            }
        }
    }

    @Test
    fun `final ordered input validation runs once after candidate lookup before returning a link`() {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val uri = document(registry)
        var validations = 0
        val service = SourceBrowserService(parserScope, registry, {
            SourceBrowserLookupSession(
                locateType = { name -> located(name) },
                isCurrent = { true },
                validateCurrent = {
                    validations++
                    false
                },
            )
        })
        val failure = assertFailsWith<ResponseErrorException> {
            runBlocking { service.definition(uri, Position(0, content.indexOf("TypeCheckingMode"))) }
        }
        assertEquals(ResponseErrorCode.ContentModified.value, failure.responseError.code)
        assertEquals(1, validations)
    }

    @Test
    fun `a qualified type package prefix cannot navigate to a competing same package type`() = runBlocking {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val text = "package sample; class C { other.lib.Foo value; }"
        val uri = document(registry, text)
        val calls = mutableListOf<String>()
        val service = browser(registry, { name, _ ->
            calls.add(name)
            if (name == "sample.other") {
                located(name)
            } else {
                SourceLookupResult.Unavailable(MissingSourceReason.BINARY_TYPE_NOT_PRESENT)
            }
        })
        assertTrue(service.definition(uri, Position(0, text.indexOf("other"))).isEmpty())
        assertTrue(calls.isEmpty())
    }
}
