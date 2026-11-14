// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.sources.ExternalSourceRegistry
import com.github.albertocavalcante.gvy.gls.sources.SourceDefinitionParams
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DeleteFile
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagedSourceDocumentServiceTest {
    @TempDir
    lateinit var temporary: Path

    private fun registry(): ExternalSourceRegistry = ExternalSourceRegistry(
        temporary.resolve("registry"),
        virtualDocumentsEnabled = { true },
        managedRoots = listOf(temporary.resolve("content")),
    )

    private fun delegate(calls: MutableList<String>): TextDocumentService = Proxy.newProxyInstance(
        TextDocumentService::class.java.classLoader,
        arrayOf(TextDocumentService::class.java),
    ) { _, method, _ ->
        calls.add(method.name)
        if (method.returnType == CompletableFuture::class.java) CompletableFuture.completedFuture<Any?>(null) else null
    } as TextDocumentService

    /** A newly added URI-bearing LSP method must be intercepted; Kotlin-generated delegation is not sufficient. */
    private fun scopedMethods(): List<Method> = TextDocumentService::class.java.methods.filter { method ->
        method.parameterCount == 1 && scopeGetter(method.parameterTypes.single()) != null
    }

    private fun scopeGetter(parameter: Class<*>): Method? = parameter.methods.firstOrNull {
        it.name == "getUri" && it.parameterCount == 0 && it.returnType == String::class.java
    } ?: parameter.methods.firstOrNull { getter ->
        getter.parameterCount == 0 && getter.name in setOf("getTextDocument", "getItem") &&
            getter.returnType.methods.any { it.name == "getUri" && it.returnType == String::class.java }
    }

    private fun params(method: Method, uri: String): Any {
        val parameter = method.parameterTypes.single().getConstructor().newInstance()
        val getter = requireNotNull(scopeGetter(parameter.javaClass))
        if (getter.name == "getUri") {
            parameter.javaClass.getMethod("setUri", String::class.java).invoke(parameter, uri)
            return parameter
        }
        val identifier = if (getter.returnType == TypeHierarchyItem::class.java) {
            TypeHierarchyItem(
                "Test",
                SymbolKind.Class,
                uri,
                Range(Position(0, 0), Position(0, 0)),
                Range(Position(0, 0), Position(0, 0)),
            )
        } else {
            getter.returnType.getConstructor().newInstance()
        }
        identifier.javaClass.getMethod("setUri", String::class.java).invoke(identifier, uri)
        identifier.javaClass.methods.firstOrNull { it.name == "setText" }?.invoke(identifier, "")
        parameter.javaClass.getMethod(getter.name.replaceFirst("get", "set"), getter.returnType)
            .invoke(parameter, identifier)
        parameter.javaClass.methods.firstOrNull { it.name == "setPosition" }?.invoke(parameter, Position(0, 0))
        return parameter
    }

    @Test
    fun `all current URI scoped interface methods protect managed docs even with missing descriptors`() {
        val calls = mutableListOf<String>()
        val service = ManagedSourceDocumentService(
            delegate(calls),
            registry(),
            sourceDefinitions = { CompletableFuture.completedFuture(emptyList()) },
        )
        val methods = scopedMethods()
        assertTrue(
            methods.size >= 35,
            "Coverage must include document notifications, edits, analysis and item hierarchies",
        )
        val uris = listOf(
            "gvy-source://missing/archive/Unknown.java",
            temporary.resolve("registry/views/lost/Unknown.groovy").toUri().toString(),
            temporary.resolve("content/digest/Unknown.java").toUri().toString(),
        )
        for (uri in uris) {
            for (method in methods) {
                val response = method.invoke(service, params(method, uri))
                if (response is CompletableFuture<*>) response.get(1, TimeUnit.SECONDS)
            }
        }
        assertEquals(emptyList(), calls, "Managed views must never reach compiler, workspace indexes or edit providers")
    }

    @Test
    fun `ordinary workspace methods still reach the original service`() {
        val calls = mutableListOf<String>()
        var browserCalls = 0
        val service = ManagedSourceDocumentService(delegate(calls), registry(), {
            browserCalls++
            CompletableFuture.completedFuture(emptyList())
        })
        val methods = scopedMethods()
        for (method in methods) {
            val response = method.invoke(service, params(method, "file:///workspace/Money.groovy"))
            if (response is CompletableFuture<*>) response.get(1, TimeUnit.SECONDS)
        }
        assertEquals(methods.map { it.name }.sorted(), calls.sorted())
        assertEquals(0, browserCalls)
    }

    @Test
    fun `managed definitions preserve links and use selected names for legacy clients`() {
        val calls = mutableListOf<String>()
        val link = LocationLink().apply {
            targetUri = "gvy-source://target/archive/Mode.java"
            targetRange = Range(Position(1, 0), Position(4, 1))
            targetSelectionRange = Range(Position(1, 5), Position(1, 9))
            originSelectionRange = Range(Position(2, 0), Position(2, 4))
        }
        val captured = mutableListOf<SourceDefinitionParams>()
        var linkSupport = true
        val service = ManagedSourceDocumentService(delegate(calls), registry(), {
            captured.add(it)
            CompletableFuture.completedFuture(listOf(link))
        }, { linkSupport })
        val params = DefinitionParams(TextDocumentIdentifier("gvy-source://context/archive/A.java"), Position(2, 1))
        assertEquals(listOf(link), service.definition(params).get().right)
        linkSupport = false
        val locations = service.definition(params).get().left
        assertEquals(link.targetUri, locations.single().uri)
        assertEquals(link.targetSelectionRange, locations.single().range)
        assertEquals(listOf(params.position, params.position), captured.map { it.position })
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `cancelling managed definition cancels its underlying browser request`() {
        val browser = CompletableFuture<List<LocationLink>>()
        val service = ManagedSourceDocumentService(delegate(mutableListOf()), registry(), { browser })
        val response = service.definition(
            DefinitionParams(
                TextDocumentIdentifier("gvy-source://context/archive/A.java"),
                Position(0, 0),
            ),
        )
        assertFalse(browser.isDone)
        response.cancel(true)
        assertTrue(browser.isCancelled)
    }

    @Test
    fun `an opened managed ancestor alias is guarded until its document closes`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privilege is not assumed")
        val managed = Files.createDirectories(temporary.resolve("content"))
        Files.writeString(managed.resolve("Mode.groovy"), "class Mode {}")
        val alias = Files.createSymbolicLink(temporary.resolve("alias"), managed).resolve("Mode.groovy").toUri()
        val registry = registry()
        val calls = mutableListOf<String>()
        val service = ManagedSourceDocumentService(
            delegate(calls),
            registry,
            sourceDefinitions = { CompletableFuture.completedFuture(emptyList()) },
        )
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(alias.toString(), "groovy", 1, "class Mode {}")))
        assertFalse(registry.isManaged(alias), "Document admission must not alter workspace namespace classification")
        service.hover(HoverParams(TextDocumentIdentifier(alias.toString()), Position(0, 0))).get()
        service.definition(DefinitionParams(TextDocumentIdentifier(alias.toString()), Position(0, 0))).get()
        service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(alias.toString(), 2),
                listOf(TextDocumentContentChangeEvent("modified managed viewing copy")),
            ),
        )
        service.definition(DefinitionParams(TextDocumentIdentifier(alias.toString()), Position(0, 0))).get()
        service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(alias.toString())))
        assertTrue(calls.isEmpty())
        assertFalse(registry.isManaged(alias), "Alias observations belong only to the open document lifetime")
    }

    @Test
    fun `parent traversal through a link is guarded for open and edit targets`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privilege is not assumed")
        val managed = Files.createDirectories(temporary.resolve("content"))
        val child = Files.createDirectory(managed.resolve("child"))
        val workspace = Files.createDirectory(temporary.resolve("workspace"))
        val link = Files.createSymbolicLink(workspace.resolve("link"), child)
        val traversed = link.resolve("../Mode.groovy").toUri().toString()
        val calls = mutableListOf<String>()
        val edit = TextEdit(Range(Position(0, 0), Position(0, 0)), "edited")
        val original = object : TextDocumentService by delegate(calls) {
            override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> =
                CompletableFuture.completedFuture(WorkspaceEdit(mapOf(traversed to listOf(edit))))
        }
        val service = ManagedSourceDocumentService(
            original,
            registry(),
            sourceDefinitions = { CompletableFuture.completedFuture(emptyList()) },
        )
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(traversed, "groovy", 1, "class Mode {}")))
        assertTrue(calls.isEmpty())
        val rename = RenameParams().apply {
            textDocument = TextDocumentIdentifier(workspace.resolve("Ordinary.groovy").toUri().toString())
        }
        assertEquals(null, service.rename(rename).get())
    }

    @Test
    fun `a missing ordinary file remains an editable unsaved document`() {
        val missing = temporary.resolve("not-created/New.groovy").toUri()
        val registry = registry()
        val calls = mutableListOf<String>()
        val service = ManagedSourceDocumentService(
            delegate(calls),
            registry,
            sourceDefinitions = { CompletableFuture.completedFuture(emptyList()) },
        )
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(missing.toString(), "groovy", 1, "class New {}")))
        assertFalse(registry.isManaged(missing))
        service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(missing.toString())))
        assertEquals(listOf("didOpen", "didClose"), calls)
    }

    @Test
    fun `unresolvable aliases decline analysis without pretending to be ordinary missing files`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privilege is not assumed")
        val broken = Files.createSymbolicLink(temporary.resolve("broken"), temporary.resolve("absent"))
            .resolve("Mode.groovy").toUri()
        val registry = registry()
        val calls = mutableListOf<String>()
        val service = ManagedSourceDocumentService(
            delegate(calls),
            registry,
            sourceDefinitions = { CompletableFuture.completedFuture(emptyList()) },
        )
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(broken.toString(), "groovy", 1, "class Mode {}")))
        assertFalse(registry.isManaged(broken), "An unavailable document is not a new managed namespace")
        service.hover(HoverParams(TextDocumentIdentifier(broken.toString()), Position(0, 0))).get()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a managed open completing after close cannot delegate a stale document`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), sourceAdmission = { _, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        })
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val service = ManagedSourceDocumentService(
            delegate(calls),
            registry,
            sourceDefinitions = { CompletableFuture.completedFuture(emptyList()) },
        )
        val uri = temporary.resolve("alias/Mode.groovy").toUri().toString()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit {
                service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, "old managed buffer")))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
            release.countDown()
            pending.get(5, TimeUnit.SECONDS)
            assertTrue(calls.isEmpty())
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun `a late ordinary open cannot replace a newer guarded document incarnation`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), sourceAdmission = { _, _ ->
            if (requests.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                false
            } else {
                true
            }
        })
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val service = ManagedSourceDocumentService(
            delegate(calls),
            registry,
            sourceDefinitions = { CompletableFuture.completedFuture(emptyList()) },
        )
        val uri = temporary.resolve("alias/Mode.groovy").toUri().toString()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit {
                service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, "old ordinary buffer")))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, "new managed buffer")))
            release.countDown()
            pending.get(5, TimeUnit.SECONDS)
            service.hover(HoverParams(TextDocumentIdentifier(uri), Position(0, 0))).get()
            assertTrue(calls.isEmpty())
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun `ordinary full sync updates and save survive admission IO with bounded latest state`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), sourceAdmission = { _, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            false
        })
        val observed = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Any>>())
        val original = Proxy.newProxyInstance(
            TextDocumentService::class.java.classLoader,
            arrayOf(TextDocumentService::class.java),
        ) { _, method, args ->
            observed.add(method.name to args.single())
            null
        } as TextDocumentService
        val service = ManagedSourceDocumentService(
            original,
            registry,
            sourceDefinitions = { CompletableFuture.completedFuture(emptyList()) },
        )
        val uri = temporary.resolve("ordinary/New.groovy").toUri().toString()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit {
                service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, "initial text")))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, 2),
                    listOf(TextDocumentContentChangeEvent("latest text")),
                ),
            )
            service.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 3), emptyList()))
            service.didSave(DidSaveTextDocumentParams(TextDocumentIdentifier(uri)))
            release.countDown()
            pending.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("didOpen", "didChange", "didSave"), observed.map { it.first })
            val change = observed[1].second as DidChangeTextDocumentParams
            assertEquals(3, change.textDocument.version)
            assertEquals("latest text", change.contentChanges.single().text)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun `reentrant reopen defers filesystem admission until close unlocks and keeps new edits`() {
        val observed = mutableListOf<String>()
        var inClose = false
        var admissions = 0
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), sourceAdmission = { _, _ ->
            assertFalse(inClose, "Reentrant filesystem admission must not run under the delegate commit monitor")
            admissions++
            false
        })
        val uri = temporary.resolve("ordinary/Reopen.groovy").toUri().toString()
        lateinit var service: ManagedSourceDocumentService
        val original = Proxy.newProxyInstance(
            TextDocumentService::class.java.classLoader,
            arrayOf(TextDocumentService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "didOpen" -> observed.add("open:" + (args.single() as DidOpenTextDocumentParams).textDocument.text)
                "didChange" -> observed.add(
                    "change:" + (args.single() as DidChangeTextDocumentParams).contentChanges.single().text,
                )
                "didClose" -> {
                    observed.add("close")
                    inClose = true
                    try {
                        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, "new text")))
                        service.didChange(
                            DidChangeTextDocumentParams(
                                VersionedTextDocumentIdentifier(uri, 2),
                                listOf(TextDocumentContentChangeEvent("new edited text")),
                            ),
                        )
                    } finally {
                        inClose = false
                    }
                }
            }
            null
        } as TextDocumentService
        service = ManagedSourceDocumentService(
            original,
            registry,
            sourceDefinitions = { CompletableFuture.completedFuture(emptyList()) },
        )
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, "old text")))
        service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
        assertEquals(3, admissions, "A buffered edit receives a fresh admission after the reopen")
        assertEquals(listOf("open:old text", "close", "open:new text", "change:new edited text"), observed)
    }

    @Test
    fun `ordinary alias retargeted into managed storage retires native state before reload`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privilege is not assumed")
        val ordinary = Files.createDirectories(temporary.resolve("workspace"))
        val managed = Files.createDirectories(temporary.resolve("content"))
        Files.writeString(ordinary.resolve("Mode.groovy"), "class Ordinary {}")
        Files.writeString(managed.resolve("Mode.groovy"), "class Managed {}")
        val link = Files.createSymbolicLink(temporary.resolve("link"), ordinary)
        val uri = link.resolve("Mode.groovy").toUri().toString()
        val calls = mutableListOf<String>()
        val service = ManagedSourceDocumentService(
            delegate(calls),
            registry(),
            sourceDefinitions = { CompletableFuture.completedFuture(emptyList()) },
        )
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, "class Ordinary {}")))
        Files.delete(link)
        Files.createSymbolicLink(link, managed)
        repeat(2) { version ->
            service.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, version + 2),
                    listOf(TextDocumentContentChangeEvent("class Managed {}")),
                ),
            )
        }
        service.didSave(DidSaveTextDocumentParams(TextDocumentIdentifier(uri)))
        service.hover(HoverParams(TextDocumentIdentifier(uri), Position(0, 0))).get()
        service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
        assertEquals(
            listOf("didOpen", "didClose"),
            calls,
            "The native document is retired exactly once; no managed text is compiled",
        )
    }

    @Test
    fun `formatting checks aliases before entry and again before its pending reply`() {
        var guarded = false
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), sourceAdmission = { _, _ -> guarded })
        val pending = CompletableFuture<List<TextEdit>>()
        var calls = 0
        val original = object : TextDocumentService by delegate(mutableListOf()) {
            override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
                calls++
                return pending
            }
        }
        val service = ManagedSourceDocumentService(
            original,
            registry,
            sourceDefinitions = { CompletableFuture.completedFuture(emptyList()) },
        )
        val params = DocumentFormattingParams().apply {
            textDocument = TextDocumentIdentifier(temporary.resolve("alias.groovy").toUri().toString())
        }
        val response = service.formatting(params)
        guarded = true
        pending.complete(listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "edited")))
        assertTrue(response.get().isEmpty(), "An edit produced before retarget must not escape after retarget")
        assertTrue(service.formatting(params).get().isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun `cancelling ordinary formatting preserves upstream request cancellation`() {
        val pending = CompletableFuture<List<TextEdit>>()
        val original = object : TextDocumentService by delegate(mutableListOf()) {
            override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> = pending
        }
        val service = ManagedSourceDocumentService(
            original,
            registry(),
            sourceDefinitions = { CompletableFuture.completedFuture(emptyList()) },
        )
        val response = service.formatting(
            DocumentFormattingParams().apply {
                textDocument = TextDocumentIdentifier(temporary.resolve("ordinary.groovy").toUri().toString())
            },
        )
        response.cancel(true)
        assertTrue(pending.isCancelled)
    }

    @Test
    fun `rename and code actions reject managed targets and cache ancestor resource operations`() {
        val ordinary = temporary.resolve("workspace/Original.groovy").toUri().toString()
        val managed = temporary.resolve("content/A.groovy").toUri().toString()
        val edit = TextEdit(Range(Position(0, 0), Position(0, 0)), "edited")
        var produced = WorkspaceEdit(mapOf(managed to listOf(edit)))
        val original = object : TextDocumentService by delegate(mutableListOf()) {
            override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> =
                CompletableFuture.completedFuture(produced)
            override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> =
                CompletableFuture.completedFuture(
                    listOf(Either.forRight(CodeAction("Fix").apply { this.edit = produced })),
                )
        }
        val service = ManagedSourceDocumentService(
            original,
            registry(),
            sourceDefinitions = { CompletableFuture.completedFuture(emptyList()) },
        )
        val rename = RenameParams().apply { textDocument = TextDocumentIdentifier(ordinary) }
        val action = CodeActionParams().apply { textDocument = TextDocumentIdentifier(ordinary) }
        assertEquals(null, service.rename(rename).get())
        assertTrue(service.codeAction(action).get().isEmpty())
        produced = WorkspaceEdit().apply {
            documentChanges = listOf(Either.forRight(DeleteFile(temporary.toUri().toString())))
        }
        assertEquals(null, service.rename(rename).get(), "Removing an ancestor would remove immutable stores too")
        produced = WorkspaceEdit(mapOf(temporary.resolve("workspace/Copy.groovy").toUri().toString() to listOf(edit)))
        assertEquals(produced, service.rename(rename).get(), "A user-owned copy remains editable")
        produced = WorkspaceEdit(mapOf("untitled:New.groovy" to listOf(edit)))
        assertEquals(
            produced,
            service.rename(rename).get(),
            "Ordinary unsaved text documents are valid text edit targets",
        )
    }

    @Test
    fun `late reload admission cannot retire a newer ordinary incarnation`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val admissions = AtomicInteger()
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), sourceAdmission = { _, _ ->
            if (admissions.incrementAndGet() == 2) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                true
            } else {
                false
            }
        })
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val service = ManagedSourceDocumentService(
            delegate(calls),
            registry,
            sourceDefinitions = { CompletableFuture.completedFuture(emptyList()) },
        )
        val uri = temporary.resolve("ordinary/Reopen.groovy").toUri().toString()
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, "old")))
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit {
                service.didChange(
                    DidChangeTextDocumentParams(
                        VersionedTextDocumentIdentifier(uri, 2),
                        listOf(TextDocumentContentChangeEvent("retargeted")),
                    ),
                )
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, "new")))
            release.countDown()
            pending.get(5, TimeUnit.SECONDS)
            service.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, 2),
                    listOf(TextDocumentContentChangeEvent("new edited")),
                ),
            )
            assertEquals(listOf("didOpen", "didClose", "didOpen", "didChange"), calls)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }
}
