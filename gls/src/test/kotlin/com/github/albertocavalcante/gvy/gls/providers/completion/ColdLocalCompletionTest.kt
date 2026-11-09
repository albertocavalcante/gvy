package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.CompletionTriggerKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.eclipse.lsp4j.CompletionContext as LspCompletionContext

class ColdLocalCompletionTest {
    private val uri = URI.create("file:///CompletionDogfood.groovy")
    private val compilation = GroovyCompilationService()
    private val resolver = SemanticTypeResolver(compilation.classpathService.getTypeSolver())
    private val documents = DocumentProvider()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val declarations = """
        class Greeter {
            String greet(String name) { name }
        }
    """.trimIndent()
    private val memberSource = "$declarations\n\nnew Greeter().gr"

    @AfterEach
    fun close() {
        scope.cancel()
        compilation.clearCaches()
    }

    @Test
    fun `first and repeated member requests use local declarations without publication`(): Unit = runBlocking {
        assertUnpublished()

        val first = complete(memberSource)
        val second = complete(memberSource)

        assertUnpublished()
        assertHasGreet(first)
        assertHasGreet(second)
    }

    @Test
    fun `manual public completion resolves a cold buffer without a diagnostic job`(): Unit = runBlocking {
        documents.put(uri, memberSource, 1)
        val service = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )
        assertUnpublished()

        // No didOpen: no asynchronous diagnostic compilation can populate the index first.
        val result = service.completion(
            CompletionParams().apply {
                textDocument = TextDocumentIdentifier(uri.toString())
                position = cursor(memberSource)
                context = LspCompletionContext(CompletionTriggerKind.Invoked)
            },
        ).await()

        val items = if (result.isLeft) result.left else result.right.items
        assertUnpublished()
        assertHasGreet(items)
        assertEquals(memberSource, documents.get(uri))
        assertEquals(1, documents.getSnapshot(uri)?.version)
    }

    @Test
    fun `an incomplete constructor offers the class declared in the same buffer`(): Unit = runBlocking {
        val items = complete("$declarations\n\nnew Gr")

        assertUnpublished()
        val greeter = assertNotNull(
            items.singleOrNull { it.label == "Greeter" && it.kind == CompletionItemKind.Class },
            "Expected the current-buffer class after new Gr; found ${items.map { it.label to it.kind }}",
        )
        assertEquals("Greeter", greeter.insertText)
        assertTrue(items.none { it.label.contains(CompletionProvider.DUMMY_IDENTIFIER) })
    }

    @Test
    fun `member context excludes current scope class suggestions`(): Unit = runBlocking {
        val items = complete(memberSource)

        assertUnpublished()
        assertTrue(
            items.none { it.label == "Greeter" && it.kind == CompletionItemKind.Class },
            "An instance member request must not suggest the enclosing module's class",
        )
    }

    @Test
    fun `published control retains local methods and signature distinct grep overloads`(): Unit = runBlocking {
        val published = compilation.compile(uri, "$declarations\n\nnew Greeter().greet('Groovy')")
        assertTrue(published.isSuccess)
        assertNotNull(compilation.getSymbolStorage(uri))

        val items = complete(memberSource)

        assertHasGreet(items)
        val grep = items.filter { it.label == "grep" && it.kind == CompletionItemKind.Method }
        assertEquals(2, grep.size, "Both grep overloads must survive member filtering")
        assertEquals(2, grep.map { it.detail to it.insertText }.distinct().size)
        assertTrue(grep.any { it.insertText == "grep()" })
        assertTrue(grep.any { it.detail?.contains("Object") == true && it.insertText != "grep()" })
    }

    @Test
    fun `unsaved local declarations supersede the indexed direct members`(): Unit = runBlocking {
        val previous = "class Greeter { String outdated() { 'old' } }"
        assertTrue(compilation.compile(uri, previous).isSuccess)

        val items = complete(memberSource)

        assertHasGreet(items)
        assertTrue(items.none { it.label == "outdated" })
        assertEquals(previous, assertNotNull(compilation.getCompiledSnapshot(uri)).sourceText)
    }

    @Test
    fun `local superclass members remain available without publication`(): Unit = runBlocking {
        val source = """
            class Parent { String inherited(String value) { value } }
            class Greeter extends Parent {}
            new Greeter().in
        """.trimIndent()

        val items = complete(source)

        assertUnpublished()
        assertTrue(items.any { it.label == "inherited" && it.kind == CompletionItemKind.Method })
    }

    @Test
    fun `qualified local parameter types preserve separate inherited overloads`(): Unit = runBlocking {
        val source = """
            class Parent { String choose(java.util.Date value) { 'util' } }
            class Greeter extends Parent { String choose(java.sql.Date value) { 'sql' } }
            new Greeter().ch
        """.trimIndent()

        val items = complete(source)

        assertUnpublished()
        val overloads = items.filter { it.label == "choose" && it.kind == CompletionItemKind.Method }
        assertEquals(2, overloads.size, "Different qualified Date types are different signatures")
        assertEquals(listOf("String choose(Date value)"), overloads.map { it.detail }.distinct())
        assertEquals(
            setOf("Method: Greeter.choose(java.sql.Date)", "Method: Parent.choose(java.util.Date)"),
            overloads.map { it.documentation.left }.toSet(),
            "Documentation keeps the qualified identity that tells the overloads apart",
        )
    }

    @Test
    fun `local override of an indexed superclass is offered once`(): Unit = runBlocking {
        val parentUri = URI.create("file:///Parent.groovy")
        val parent = "class Parent { String greet(String name) { name } }"
        assertTrue(compilation.compile(parentUri, parent).isSuccess)
        val source = """
            class Greeter extends Parent { String greet(String name) { name } }
            new Greeter().gr
        """.trimIndent()

        val items = complete(source)

        val greet = items.filter { it.label == "greet" && it.kind == CompletionItemKind.Method }
        assertEquals(1, greet.size, "A local override must replace the exact indexed superclass signature")
        assertEquals(parent, assertNotNull(compilation.getCompiledSnapshot(parentUri)).sourceText)
        assertNull(compilation.getCompiledSnapshot(uri))
    }

    @Test
    fun `indexed superclass override identity survives AST eviction`(): Unit = runBlocking {
        val parentUri = URI.create("file:///Parent.groovy")
        assertTrue(compilation.compile(parentUri, "class Parent { String greet(String name) { name } }").isSuccess)
        compilation.compilationCacheService.invalidate(parentUri)
        assertNull(compilation.getCompiledSnapshot(parentUri))
        assertTrue(compilation.getWorkspaceSymbolIndex()?.implementationSnapshot?.containsKey(parentUri) == true)
        val source = """
            class Greeter extends Parent { String greet(String name) { name } }
            new Greeter().gr
        """.trimIndent()

        val items = complete(source)

        val greet = items.filter { it.label == "greet" && it.kind == CompletionItemKind.Method }
        assertEquals(1, greet.size, "Detached qualified facts must retain the exact override after AST eviction")
        assertNull(compilation.getCompiledSnapshot(parentUri))
        assertNull(compilation.getCompiledSnapshot(uri))
    }

    @Test
    fun `local override of a binary superclass is offered once`(): Unit = runBlocking {
        val source = """
            class Greeter { boolean equals(Object other) { true } }
            new Greeter().eq
        """.trimIndent()

        val items = complete(source)

        assertUnpublished()
        val equals = items.filter { it.label == "equals" && it.kind == CompletionItemKind.Method }
        assertEquals(1, equals.size, "The exact Object override must replace its reflected superclass method")
    }

    @Test
    fun `local method shadows only its exact GDK overload`(): Unit = runBlocking {
        val source = """
            class Greeter { Object grep(Object filter) { filter } }
            new Greeter().gr
        """.trimIndent()

        val items = complete(source)

        assertUnpublished()
        val grep = items.filter { it.label == "grep" && it.kind == CompletionItemKind.Method }
        assertEquals(2, grep.size, "Local Object grep must replace its extension counterpart, preserving noarg grep")
        assertTrue(
            grep.any {
                it.detail == "Object grep(Object filter)" && it.insertText == "grep(\${1:Object filter})"
            },
        )
        assertTrue(grep.any { it.insertText == "grep()" })
    }

    @Test
    fun `dependency receiver methods survive member context filtering`(): Unit = runBlocking {
        val items = complete("String value = 'text'\nvalue.sub")

        assertUnpublished()
        assertTrue(items.any { it.label == "substring" && it.kind == CompletionItemKind.Method })
    }

    @Test
    fun `explicit this offers enclosing class members with named parameters`(): Unit = runBlocking {
        val source = "class Greeter { String greet(String n){n}; void m(){ this.gr } }"

        val items = complete(source, Position(0, source.indexOf(" } }")))

        assertUnpublished()
        val greet = items.filter { it.label == "greet" && it.kind == CompletionItemKind.Method }
        assertEquals(listOf("greet(\${1:String n})"), greet.map { it.insertText }, "found ${labels(items)}")
        assertEquals("String greet(String n)", greet.single().detail)
    }

    @Test
    fun `explicit this at script level offers script methods`(): Unit = runBlocking {
        val items = complete("String greet(String n) { n }\nthis.gr")

        val greet = items.filter { it.label == "greet" && it.kind == CompletionItemKind.Method }
        assertEquals(listOf("greet(\${1:String n})"), greet.map { it.insertText }, "found ${labels(items)}")
    }

    @Test
    fun `explicit super offers superclass members but not the subclass declarations`(): Unit = runBlocking {
        val source = """
            class Parent { String greet(String n) { n } }
            class Greeter extends Parent { String own() { '' }; void m() { super.gr } }
        """.trimIndent()
        val lines = source.lines()

        val items = complete(source, Position(1, lines[1].indexOf(" } }")))

        assertUnpublished()
        val greet = items.filter { it.label == "greet" && it.kind == CompletionItemKind.Method }
        assertEquals(listOf("greet(\${1:String n})"), greet.map { it.insertText }, "found ${labels(items)}")
        assertTrue(items.none { it.label == "own" }, "super must not offer the subclass's own members")
    }

    @Test
    fun `local receiver displays simple parameter types with names`(): Unit = runBlocking {
        val items = complete(memberSource)

        val greet = items.single { it.label == "greet" && it.kind == CompletionItemKind.Method }
        assertEquals("greet(\${1:String name})", greet.insertText)
        assertEquals("String greet(String name)", greet.detail)
        assertTrue(items.none { it.detail?.contains("java.lang.") == true }, "found ${labels(items)}")
    }

    @Test
    fun `external receiver keeps base display and offers each overload once`(): Unit = runBlocking {
        val items = complete("String value = 'text'\nvalue.sub")

        val substring = items.filter { it.label == "substring" && it.kind == CompletionItemKind.Method }
        assertEquals(
            setOf("substring(\${1:int})", "substring(\${1:int}, \${2:int})"),
            substring.map { it.insertText }.toSet(),
        )
        assertEquals(substring.size, substring.map { it.insertText }.distinct().size, "found ${labels(items)}")
    }

    @Test
    fun `overloads differing only by qualified type both appear once with simple display`(): Unit = runBlocking {
        val source = """
            class Greeter {
                String choose(java.util.Date value) { 'util' }
                String choose(java.sql.Date value) { 'sql' }
            }
            new Greeter().ch
        """.trimIndent()

        val items = complete(source)

        val choose = items.filter { it.label == "choose" && it.kind == CompletionItemKind.Method }
        assertEquals(2, choose.size, "found ${labels(items)}")
        assertTrue(choose.all { it.insertText == "choose(\${1:Date value})" })
        assertTrue(choose.all { it.detail == "String choose(Date value)" })
    }

    private fun labels(items: List<CompletionItem>) = items.map { "${it.label}|${it.insertText}|${it.detail}" }

    private suspend fun complete(source: String, position: Position = cursor(source)): List<CompletionItem> =
        CompletionProvider.getContextualCompletions(
            uri = uri.toString(),
            line = position.line,
            character = position.character,
            compilationService = compilation,
            semanticResolver = resolver,
            content = source,
        )

    private fun cursor(source: String): Position {
        val lines = source.lines()
        return Position(lines.lastIndex, lines.last().length)
    }

    private fun assertHasGreet(items: List<CompletionItem>) {
        assertTrue(
            items.any { it.label == "greet" && it.kind == CompletionItemKind.Method },
            "Expected the local greet method on the first request; found ${items.map { it.label to it.detail }}",
        )
    }

    private fun assertUnpublished() {
        assertNull(compilation.getCompiledSnapshot(uri))
        assertTrue(compilation.getAllSymbolStorages().isEmpty())
        assertTrue(compilation.getWorkspaceSymbolIndex()?.implementationSnapshot?.containsKey(uri) != true)
    }
}
