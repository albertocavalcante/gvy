// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceDefinition
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.sources.SourceSpan
import groovy.transform.CompileStatic
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnnotationDefinitionNavigationTest {
    @TempDir
    lateinit var workspace: Path
    private val uri = URI.create("file:///Money.groovy")

    @Test
    fun `annotation heads and imports reach one external type without self resolution`(): Unit = runBlocking {
        val service = GroovyCompilationService(parentClassLoader = CompileStatic::class.java.classLoader)
        val calls = mutableListOf<String>()
        val navigator = sources { name -> calls.add(name) }
        val provider = DefinitionProvider(service, navigator)
        val source = """
            import groovy.transform.Immutable as Value
            import groovy.transform.CompileStatic
            @Value
            @CompileStatic
            class Money { BigDecimal amount }
        """.trimIndent()
        try {
            assertTrue(service.compile(uri, source).isSuccess)
            val importTarget = provider.provideDefinitions(uri.toString(), Position(0, 30)).toList().single()
            val annotationTarget = provider.provideDefinitions(uri.toString(), Position(2, 1)).toList().single()
            val staticTarget = provider.provideDefinitions(uri.toString(), Position(3, 1)).toList().single()

            assertEquals(importTarget.uri, annotationTarget.uri)
            assertEquals("file:///sources/groovy/transform/Immutable.groovy", annotationTarget.uri)
            assertEquals("file:///sources/groovy/transform/CompileStatic.java", staticTarget.uri)
            assertEquals(
                listOf("groovy.transform.Immutable", "groovy.transform.Immutable", "groovy.transform.CompileStatic"),
                calls,
            )
        } finally {
            service.clearCaches()
        }
    }

    @Test
    fun `annotation links use the written alias name range in UTF16`(): Unit = runBlocking {
        val service = GroovyCompilationService(parentClassLoader = CompileStatic::class.java.classLoader)
        val provider = DefinitionProvider(service, sources {})
        try {
            val source =
                "import groovy.transform.Immutable as Value\r\n" +
                    "/*😀*/\t@Value class Money { BigDecimal amount }"
            assertTrue(service.compile(uri, source).isSuccess)

            val link = provider.provideDefinitionLinks(uri.toString(), Position(1, 8)).toList().single()

            assertEquals(Range(Position(1, 8), Position(1, 13)), link.originSelectionRange)
            assertEquals("file:///sources/groovy/transform/Immutable.groovy", link.targetUri)
        } finally {
            service.clearCaches()
        }
    }

    @Test
    fun `a resolved annotation type is not replaced by an unrelated workspace class with the same simple name`(): Unit =
        runBlocking {
            val service = GroovyCompilationService(parentClassLoader = CompileStatic::class.java.classLoader)
            val calls = mutableListOf<String>()
            val provider = DefinitionProvider(service, sources { calls.add(it) })
            try {
                assertTrue(
                    service.compile(
                        URI.create("file:///other/CompileStatic.groovy"),
                        "package other\n@interface CompileStatic {}",
                    ).isSuccess,
                )
                assertTrue(
                    service.compile(
                        uri,
                        "import groovy.transform.CompileStatic\n@CompileStatic class Money {}",
                    ).isSuccess,
                )

                val target = provider.provideDefinitions(uri.toString(), Position(1, 1)).toList().single()
                val importTarget = provider.provideDefinitions(uri.toString(), Position(0, 27)).toList().single()

                assertEquals("file:///sources/groovy/transform/CompileStatic.java", target.uri)
                assertEquals(target.uri, importTarget.uri)
                assertEquals(listOf("groovy.transform.CompileStatic", "groovy.transform.CompileStatic"), calls)
            } finally {
                service.clearCaches()
            }
        }

    @Test
    fun `an indexed declaration outside compile inputs cannot replace a bound SDK annotation`(): Unit = runBlocking {
        val service = GroovyCompilationService(parentClassLoader = CompileStatic::class.java.classLoader)
        val calls = mutableListOf<String>()
        val provider = DefinitionProvider(service, sources { calls.add(it) })
        try {
            assertTrue(
                service.compile(
                    URI.create("file:///outside/CompileStatic.groovy"),
                    "package groovy.transform\n@interface CompileStatic {}",
                ).isSuccess,
            )
            val source = "import groovy.transform.CompileStatic\n@CompileStatic class Money {}"
            assertTrue(service.compile(uri, source).isSuccess)
            val held = kotlin.test.assertNotNull(service.getCompiledSnapshot(uri)).parseResult
            assertFalse(held.outcome.isDegraded)
            assertEquals(
                null,
                held.ast?.imports?.single()?.type?.redirect()?.module,
                "The compiler must have selected the SDK binary",
            )

            val annotation = provider.provideDefinitions(uri.toString(), Position(1, 1)).toList().single()
            val import = provider.provideDefinitions(uri.toString(), Position(0, 27)).toList().single()

            assertEquals("file:///sources/groovy/transform/CompileStatic.java", annotation.uri)
            assertEquals(annotation.uri, import.uri)
            assertEquals(listOf("groovy.transform.CompileStatic", "groovy.transform.CompileStatic"), calls)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `the held compilation's source annotation beats an SDK binary`(): Unit = runBlocking {
        val documents = DocumentProvider()
        val selected = URI.create("file:///workspace/groovy/transform/CompileStatic.groovy")
        val service = GroovyCompilationService(
            parentClassLoader = CompileStatic::class.java.classLoader,
            documentProvider = documents,
        )
        val calls = mutableListOf<String>()
        val provider = DefinitionProvider(service, sources { calls.add(it) })
        val source = "import groovy.transform.CompileStatic\n@CompileStatic class Money {}"
        try {
            val declarationLine = "/*😀*/ @interface CompileStatic {}"
            documents.put(selected, "package groovy.transform\r$declarationLine", 1)
            documents.put(uri, source, 1)
            assertTrue(service.compile(uri, source).isSuccess)
            val held = kotlin.test.assertNotNull(service.getCompiledSnapshot(uri)).parseResult
            assertFalse(held.outcome.isDegraded)
            assertEquals(
                selected.path,
                held.ast?.imports?.single()?.type?.redirect()?.module?.context?.name,
                "The fixture must actually bind the open workspace source",
            )

            val annotation = provider.provideDefinitions(uri.toString(), Position(1, 1)).toList().single()
            val import = provider.provideDefinitions(uri.toString(), Position(0, 27)).toList().single()

            assertEquals(selected.toString(), annotation.uri)
            assertEquals(selected.toString(), import.uri)
            assertTrue(calls.isEmpty(), "A proven source binding must not be replaced by binary navigation")
            val link = provider.provideDefinitionLinks(uri.toString(), Position(1, 1)).toList().single()
            val column = declarationLine.indexOf("CompileStatic")
            assertEquals(
                Range(Position(1, column), Position(1, column + "CompileStatic".length)),
                link.targetSelectionRange,
            )
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `import navigation and origin selections use frozen CR and UTF16 source ranges`(): Unit = runBlocking {
        val service = GroovyCompilationService(parentClassLoader = CompileStatic::class.java.classLoader)
        val provider = DefinitionProvider(service, sources {})
        val importLine = "/*😀*/\timport groovy./* type */transform.CompileStatic as StaticModel"
        val source = "import java.util.List\r$importLine\r@StaticModel class Money {}"
        try {
            assertTrue(service.compile(uri, source).isSuccess)
            val column = importLine.indexOf("CompileStatic")

            val link = provider.provideDefinitionLinks(uri.toString(), Position(1, column + 1)).toList().single()
            val keyword = provider
                .provideDefinitions(uri.toString(), Position(1, importLine.indexOf("import") + 1))
                .toList()
                .single()

            assertEquals("file:///sources/groovy/transform/CompileStatic.java", link.targetUri)
            assertEquals(link.targetUri, keyword.uri)
            assertEquals(
                Range(Position(1, column), Position(1, column + "CompileStatic".length)),
                link.originSelectionRange,
            )
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `an on-disk annotation resolves from its held unit without a target cache lookup`(): Unit = runBlocking {
        val model = workspace.resolve("p/Model.groovy")
        Files.createDirectories(model.parent)
        Files.writeString(model, "package p\n@interface Model {}")
        val main = workspace.resolve("Money.groovy").toUri()
        val service = GroovyCompilationService(parentClassLoader = CompileStatic::class.java.classLoader)
        val provider = DefinitionProvider(service)
        try {
            service.workspaceManager.updateWorkspaceModel(workspace, emptyList(), listOf(workspace))
            assertTrue(service.compile(main, "import p.Model\n@Model class Money {}").isSuccess)
            assertEquals(null, service.getAst(model.toUri()), "Only the requesting document is cached separately")

            val annotation = provider.provideDefinitions(main.toString(), Position(1, 1)).toList().single()
            val import = provider.provideDefinitions(main.toString(), Position(0, 10)).toList().single()

            assertEquals(model.toUri().toString(), annotation.uri)
            assertEquals(annotation.uri, import.uri)
            assertEquals(null, service.getAst(model.toUri()), "Navigation must not reacquire a newer target parse")
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `a nested source annotation import targets the annotation instead of its owner`(): Unit = runBlocking {
        val source = """
            package p
            import p.Outer.Mark
            class Outer { @interface Mark {} }
            @Mark class Money {}
        """.trimIndent()
        val service = GroovyCompilationService(parentClassLoader = CompileStatic::class.java.classLoader)
        val provider = DefinitionProvider(service)
        try {
            val compilation = service.compile(uri, source)
            assertTrue(compilation.isSuccess, compilation.diagnostics.joinToString { it.message })
            val head = provider.provideDefinitionLinks(uri.toString(), Position(3, 1)).toList().single()
            val imported = provider
                .provideDefinitionLinks(uri.toString(), Position(1, source.lines()[1].indexOf("Mark") + 1))
                .toList()
                .single()
            val column = source.lines()[2].indexOf("Mark")
            val selected = Range(Position(2, column), Position(2, column + "Mark".length))

            assertEquals(uri.toString(), head.targetUri)
            assertEquals(selected, head.targetSelectionRange)
            assertEquals(head.targetUri, imported.targetUri)
            assertEquals(head.targetSelectionRange, imported.targetSelectionRange)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `an unproven overloaded static import does not invent an owner target`(): Unit = runBlocking {
        val source = """
            package p
            import static p.Outer.make
            class Outer {
                static Object make() { null }
                static Object make(int count) { count }
            }
            class Money { Object value = make() }
        """.trimIndent()
        val service = GroovyCompilationService(parentClassLoader = CompileStatic::class.java.classLoader)
        val provider = DefinitionProvider(service)
        try {
            assertTrue(service.compile(uri, source).isSuccess)
            assertTrue(
                provider.provideDefinitions(uri.toString(), Position(1, source.lines()[1].indexOf("make") + 1))
                    .toList()
                    .isEmpty(),
            )
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `unique source static imports navigate to their member declarations`(): Unit = runBlocking {
        val source = """
            package p
            import static p.Outer.ANSWER
            import static p.Outer.make
            class Outer {
                static int ANSWER = 42
                static int make() { 42 }
            }
            class Money { int value = ANSWER + make() }
        """.trimIndent()
        val service = GroovyCompilationService(parentClassLoader = CompileStatic::class.java.classLoader)
        val provider = DefinitionProvider(service)
        try {
            assertTrue(service.compile(uri, source).isSuccess)
            val lines = source.lines()
            val field = provider.provideDefinitionLinks(
                uri.toString(),
                Position(1, lines[1].indexOf("ANSWER") + 1),
            ).toList().single()
            val method = provider.provideDefinitionLinks(
                uri.toString(),
                Position(2, lines[2].indexOf("make") + 1),
            ).toList().single()

            assertEquals(uri.toString(), field.targetUri)
            assertEquals(4, field.targetRange.start.line)
            assertEquals(uri.toString(), method.targetUri)
            assertEquals(5, method.targetRange.start.line)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `a static field and method sharing an import name remain ambiguous`(): Unit = runBlocking {
        val source = """
            package p
            import static p.Outer.value
            class Outer {
                static int value = 1
                static int value() { 2 }
            }
            class Money {}
        """.trimIndent()
        val service = GroovyCompilationService(parentClassLoader = CompileStatic::class.java.classLoader)
        val provider = DefinitionProvider(service)
        try {
            assertTrue(service.compile(uri, source).isSuccess)
            assertTrue(
                provider.provideDefinitions(uri.toString(), Position(1, source.lines()[1].indexOf("value") + 1))
                    .toList()
                    .isEmpty(),
            )
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `custom collector use reaches its source annotation declaration name`(): Unit = runBlocking {
        val service = GroovyCompilationService(parentClassLoader = CompileStatic::class.java.classLoader)
        val provider = DefinitionProvider(service)
        try {
            val source = """
                import groovy.transform.AnnotationCollector
                import groovy.transform.CompileStatic
                @AnnotationCollector([CompileStatic])
                @interface Model {}
                @Model class Money {}
            """.trimIndent()
            assertTrue(service.compile(uri, source).isSuccess)

            val link = provider.provideDefinitionLinks(uri.toString(), Position(4, 1)).toList().single()

            assertEquals(uri.toString(), link.targetUri)
            assertEquals(Range(Position(3, 11), Position(3, 16)), link.targetSelectionRange)
            assertEquals(Range(Position(4, 1), Position(4, 6)), link.originSelectionRange)
        } finally {
            service.clearCaches()
        }
    }

    @Test
    fun `a document revision changed during source acquisition cannot publish the old annotation target`(): Unit =
        runBlocking {
            val documents = DocumentProvider()
            val service = GroovyCompilationService(
                parentClassLoader = CompileStatic::class.java.classLoader,
                documentProvider = documents,
            )
            val provider = DefinitionProvider(
                service,
                sources { documents.update(uri, "class Replacement {}", 2) },
            )
            val source = "import groovy.transform.CompileStatic\n@CompileStatic class Money {}"
            try {
                documents.put(uri, source, 1)
                assertTrue(service.compile(uri, source).isSuccess)

                assertFailsWith<ResponseErrorException> {
                    provider.provideDefinitions(uri.toString(), Position(1, 1)).toList()
                }
            } finally {
                service.clearCaches()
            }
        }

    @Test
    fun `definition and link requests preserve source cancellation and protocol failures`(): Unit = runBlocking {
        val service = GroovyCompilationService(parentClassLoader = CompileStatic::class.java.classLoader)
        try {
            assertTrue(
                service.compile(uri, "import groovy.transform.CompileStatic\n@CompileStatic class Money {}").isSuccess,
            )
            val failures = listOf(
                CancellationException("source lookup cancelled"),
                ResponseErrorException(
                    ResponseError(ResponseErrorCode.ContentModified, "source context retired", null),
                ),
                ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, "source integrity failed", null)),
            )
            for (expected in failures) {
                val provider = DefinitionProvider(service, sources { throw expected })
                assertSame(
                    expected,
                    assertFailsWith<RuntimeException> {
                        provider.provideDefinitions(uri.toString(), Position(1, 1)).toList()
                    },
                )
                assertSame(
                    expected,
                    assertFailsWith<RuntimeException> {
                        provider.provideDefinitionLinks(uri.toString(), Position(1, 1)).toList()
                    },
                )
            }
        } finally {
            service.clearCaches()
        }
    }

    private fun sources(onLookup: (String) -> Unit): SourceNavigator = object : SourceNavigator {
        override suspend fun navigateToSource(classpathUri: URI, className: String): SourceNavigator.SourceResult {
            onLookup(className)
            val extension = if (className.endsWith("Immutable")) "groovy" else "java"
            return SourceNavigator.SourceResult.SourceLocation(
                URI.create("file:///sources/${className.replace('.', '/')}.$extension"),
                className,
                lineNumber = 10,
                definition = SourceDefinition(
                    SourceSpan(8, 0, 12, 0),
                    SourceSpan(9, 18, 9, 18 + className.substringAfterLast('.').length),
                ),
            )
        }

        override suspend fun navigateToMethodSource(
            classpathUri: URI,
            className: String,
            methodName: String,
        ): SourceNavigator.SourceResult = error("Annotation heads do not request method source")
    }
}
