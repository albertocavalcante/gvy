// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.ResolvedArtifactCoordinates
import com.github.albertocavalcante.gvy.build.SourceArtifactResolver
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactRequest
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactResolver
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactResult
import com.github.javaparser.JavaParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Public source behavior: no inferred coordinates, stale results, wrong versions or fabricated declaration ranges. */
class SourceNavigationServiceTest {
    @TempDir lateinit var directory: Path

    @Test
    @EnabledIf("hasJdkSources")
    fun `legacy JDK method navigation selects the method declaration`() = runBlocking {
        val className = "java.util.HashMap"
        val classUri = URI.create("jrt:/java.base/java/util/HashMap.class")
        val jdkSources = JdkSourceResolver(directory.resolve("jdk-sources"))
        SourceNavigationService(
            sourceCacheRoot = directory.resolve("jdk-navigation"),
            jdkSourceResolver = jdkSources,
        ).use { navigation ->
            val classLocation = assertIs<SourceNavigator.SourceResult.SourceLocation>(
                navigation.navigateToSource(classUri, className),
            )
            val methodLocation = assertIs<SourceNavigator.SourceResult.SourceLocation>(
                navigation.navigateToMethodSource(classUri, className, "clear"),
            )
            val definition = assertNotNull(methodLocation.definition)
            val methodLineNumber = assertNotNull(methodLocation.lineNumber)
            val classLineNumber = assertNotNull(classLocation.lineNumber)
            val methodLine = Files.readString(Path.of(methodLocation.uri)).lines()[definition.nameRange.startLine]

            assertEquals(
                "clear",
                methodLine.substring(definition.nameRange.startCharacter, definition.nameRange.endCharacter),
            )
            assertEquals(definition.nameRange.startLine + 1, methodLineNumber)
            assertNotEquals(classLineNumber, methodLineNumber)
        }
    }

    @Test
    fun `verified producer sources locate the exact annotation name`() = runBlocking {
        val source = sourceJar(directory.resolve("repo/library-sources.jar"), JAVA_SOURCE)
        val resolver = RecordingResolver { VerifiedSourceArtifactResult.Available(source, digest(source)) }
        val symbol = binary("selected/library.jar")
        service(resolver).use { navigation ->
            val located = assertIs<SourceLookupResult.Located>(navigation.locate(symbol, context()))
            val lines = Files.readString(Path.of(located.uri)).lines()
            val name = located.definition.nameRange
            assertEquals("Anno", lines[name.startLine].substring(name.startCharacter, name.endCharacter))
            assertEquals(1, resolver.calls.get())
            assertEquals(COORDINATES, resolver.lastCoordinates)
            assertEquals(digest((symbol.origin as SourceOrigin.Archive).binaryJar), resolver.lastBinaryDigest)
            assertTrue(located.definition.declarationRange.contains(name))
        }
    }

    @Test
    fun `Java member navigation selects fields enum constants and methods by exact name`(): Unit = runBlocking {
        val content = """
            package sample;
            public enum Anno {
                READY;
                public static final int FIRST = 1, SECOND = 2;
                public static int value() { return FIRST; }
                public static int select() { return FIRST; }
                public static int select(int input) { return input; }
            }
        """.trimIndent()
        val source = sourceJar(directory.resolve("repo/library-sources.jar"), content)
        val resolver = RecordingResolver { VerifiedSourceArtifactResult.Available(source, digest(source)) }
        val owner = binary("selected/library.jar")
        service(resolver).use { navigation ->
            for (member in listOf("READY", "SECOND", "value")) {
                val symbol = owner.copy(memberName = member)
                val located = assertIs<SourceLookupResult.Located>(navigation.locate(symbol, context()))
                val span = located.definition.nameRange
                val line = Files.readString(Path.of(located.uri)).lines()[span.startLine]
                assertEquals(member, line.substring(span.startCharacter, span.endCharacter))
                assertEquals(located, navigation.locate(symbol, context()))
            }
            assertIs<SourceLookupResult.FileOnly>(navigation.locate(owner.copy(memberName = "select"), context()))
        }
    }

    @Test
    fun `a sibling sources jar without verified provenance is not trusted`() = runBlocking {
        val sibling = sourceJar(directory.resolve("selected/library-sources.jar"), JAVA_SOURCE)
        val resolver = RecordingResolver()
        val symbol = binary("selected/library.jar")

        service(resolver).use { navigation ->
            assertTrue(Files.isRegularFile(sibling))
            assertEquals(
                SourceLookupResult.Unavailable(MissingSourceReason.SOURCE_NOT_PUBLISHED),
                navigation.locate(symbol, context()),
            )
            assertEquals(1, resolver.calls.get())
        }
    }

    @Test
    fun `unknown coordinates do not become a filename or Gradle-cache guess`() = runBlocking {
        val resolver = RecordingResolver()
        val symbol = binary(
            ".gradle/caches/modules-2/files-2.1/com.acme/library/1.0/abcdef/library-1.0.jar",
            coordinates = null,
        )
        service(resolver).use { navigation ->
            assertEquals(
                SourceLookupResult.Unavailable(MissingSourceReason.UNKNOWN_ORIGIN),
                navigation.locate(symbol, context()),
            )
            assertEquals(0, resolver.calls.get())
        }
    }

    @Test
    fun `verified download uses exact producer coordinates and warm navigation performs no additional work`() =
        runBlocking {
            val source = sourceJar(directory.resolve("download.jar"), JAVA_SOURCE)
            val resolver = RecordingResolver { VerifiedSourceArtifactResult.Available(source, digest(source)) }
            val symbol = binary("selected/library.jar")
            service(resolver).use { navigation ->
                val first = assertIs<SourceLookupResult.Located>(navigation.locate(symbol, context(download = true)))
                repeat(3) { assertEquals(first, navigation.locate(symbol, context(download = true))) }
                assertEquals(COORDINATES, resolver.lastCoordinates)
                assertEquals(digest((symbol.origin as SourceOrigin.Archive).binaryJar), resolver.lastBinaryDigest)
                assertEquals(1, resolver.calls.get())
                assertEquals(
                    mapOf(
                        "archiveImports" to 1L,
                        "archiveIndexes" to 1L,
                        "sourceMaterializations" to 1L,
                        "declarationInspections" to 1L,
                    ),
                    navigation.getStatistics(),
                )
            }
        }

    @Test
    fun `warm verified input cache is invalidated when binary bytes change without a stamp change`() = runBlocking {
        val firstSource = sourceJar(directory.resolve("download-one.jar"), "$JAVA_SOURCE\n// artifact one")
        val secondSource = sourceJar(directory.resolve("download-two.jar"), "$JAVA_SOURCE\n// artifact two")
        val resolver = RecordingResolver { call ->
            val source = if (call == 1) firstSource else secondSource
            VerifiedSourceArtifactResult.Available(source, digest(source))
        }
        val symbol = binary("selected/library.jar")
        val binary = (symbol.origin as SourceOrigin.Archive).binaryJar
        val originalTime = Files.getLastModifiedTime(binary)
        val originalSize = Files.size(binary)

        service(resolver).use { navigation ->
            val first = assertIs<SourceLookupResult.Located>(navigation.locate(symbol, context(download = true)))
            assertTrue(Files.readString(Path.of(first.uri)).contains("artifact one"))

            // Rewrite the same file, then restore the exact timestamp and size.
            Files.writeString(binary, "changed binary!")
            Files.setLastModifiedTime(binary, originalTime)
            assertEquals(originalSize, Files.size(binary))
            assertEquals(originalTime, Files.getLastModifiedTime(binary))

            val second = assertIs<SourceLookupResult.Located>(navigation.locate(symbol, context(download = true)))
            assertTrue(Files.readString(Path.of(second.uri)).contains("artifact two"))
            assertEquals(2, resolver.calls.get())
            assertEquals(digest(binary), resolver.lastBinaryDigest)
        }
    }

    @Test
    fun `same filename and FQN in two origins never reuse the other version`() = runBlocking {
        val first = binary("one/library.jar", ResolvedArtifactCoordinates("producer.one", "library", "1.0"))
        val second = binary("two/library.jar", ResolvedArtifactCoordinates("producer.two", "library", "2.0"))
        val firstSource = sourceJar(directory.resolve("repository/one-sources.jar"), JAVA_SOURCE + "\n// version one")
        val secondSource = sourceJar(directory.resolve("repository/two-sources.jar"), JAVA_SOURCE + "\n// version two")
        val resolver = RecordingResolver { call ->
            val source = if (call == 1) firstSource else secondSource
            VerifiedSourceArtifactResult.Available(source, digest(source))
        }
        service(resolver).use { navigation ->
            val one = assertIs<SourceLookupResult.Located>(navigation.locate(first, context("one")))
            val two = assertIs<SourceLookupResult.Located>(navigation.locate(second, context("two")))
            assertNotEquals(one.uri, two.uri)
            assertTrue(Files.readString(Path.of(one.uri)).contains("version one"))
            assertTrue(Files.readString(Path.of(two.uri)).contains("version two"))
            assertEquals(2, resolver.calls.get())
        }
    }

    @Test
    fun `wrong package yields file-only result rather than a simple-name declaration guess`(): Unit = runBlocking {
        val symbol = binary("selected/library.jar")
        val source = sourceJar(
            directory.resolve("repository/library-sources.jar"),
            "package other; public @interface Anno {}",
        )
        val resolver = RecordingResolver { VerifiedSourceArtifactResult.Available(source, digest(source)) }
        service(resolver).use { navigation ->
            assertIs<SourceLookupResult.FileOnly>(navigation.locate(symbol, context()))
        }
    }

    @Test
    fun `identical bytes retain independent Java and Groovy declaration results in both lookup orders`(): Unit =
        runBlocking {
            val content = "package sample\nclass Anno { def value }\n"
            for ((order, languages) in listOf(listOf("java", "groovy"), listOf("groovy", "java")).withIndex()) {
                val archives = languages.map { language ->
                    sourceJar(directory.resolve("language-$order/$language.jar"), content, "sample/Anno.$language")
                }
                val resolver = RecordingResolver { call ->
                    val archive = archives[call - 1]
                    VerifiedSourceArtifactResult.Available(archive, digest(archive))
                }
                service(resolver).use { navigation ->
                    val symbols = languages.map { language -> binary("language-$order/$language/library.jar") }
                    val results = symbols.map { symbol -> navigation.locate(symbol, context()) }
                    for ((language, result) in languages.zip(results)) {
                        if (language == "java") {
                            assertIs<SourceLookupResult.FileOnly>(result)
                        } else {
                            val located = assertIs<SourceLookupResult.Located>(result)
                            val span = located.definition.nameRange
                            val selected = Files.readString(Path.of(located.uri)).lines()[span.startLine]
                                .substring(span.startCharacter, span.endCharacter)
                            assertEquals("Anno", selected)
                        }
                    }
                    for ((symbol, result) in symbols.zip(results)) {
                        assertEquals(result, navigation.locate(symbol, context()))
                    }
                    assertEquals(2L, navigation.getStatistics()["declarationInspections"])
                    assertEquals(2, resolver.calls.get())
                }
            }
        }

    @Test
    @Timeout(10)
    fun `closing navigation while its presentation waits prevents a late source result`(): Unit = runBlocking {
        val source = sourceJar(directory.resolve("download.jar"), JAVA_SOURCE)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val navigation = SourceNavigationService(
            sourceResolver = RecordingResolver { VerifiedSourceArtifactResult.Available(source, digest(source)) },
            sourceCacheRoot = directory.resolve("managed"),
            sourcePresentation = SourcePresentation { descriptor ->
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS)) { "The test must release source presentation" }
                descriptor.contentPath.toUri()
            },
        )
        val request = async { navigation.locate(binary("selected/library.jar"), context()) }
        try {
            entered.await()
            navigation.close()
            release.countDown()
            assertFailsWith<CancellationException> { request.await() }
        } finally {
            release.countDown()
            navigation.close()
            request.cancelAndJoin()
        }
    }

    @Test
    @Timeout(10)
    fun `a store finishing initialization after close is closed instead of published`(): Unit = runBlocking {
        val source = sourceJar(directory.resolve("download.jar"), JAVA_SOURCE)
        val initialized = CompletableDeferred<SourceContentStore>()
        val release = CountDownLatch(1)
        val creations = AtomicInteger()
        val symbol = binary("selected/library.jar")
        val navigation = SourceNavigationService(
            sourceResolver = RecordingResolver { VerifiedSourceArtifactResult.Available(source, digest(source)) },
            sourceCacheRoot = directory.resolve("managed"),
            contentStoreFactory = { root ->
                creations.incrementAndGet()
                val candidate = SourceContentStore(root)
                var returned = false
                try {
                    initialized.complete(candidate)
                    check(release.await(5, TimeUnit.SECONDS)) { "The test must release source store initialization" }
                    returned = true
                    candidate
                } finally {
                    if (!returned) candidate.close()
                }
            },
        )
        var initializedStore: SourceContentStore? = null
        val request = async { navigation.locate(symbol, context()) }
        try {
            val candidate = initialized.await()
            initializedStore = candidate
            navigation.close()
            release.countDown()
            assertFailsWith<CancellationException> { request.await() }
            assertFailsWith<CancellationException> { candidate.importArchive(SourceArchiveInput.Local(source)) }
            navigation.close()
            assertFailsWith<CancellationException> { navigation.locate(symbol, context()) }
            assertEquals(1, creations.get(), "A closed navigator must not create another store")
        } finally {
            release.countDown()
            navigation.close()
            request.cancelAndJoin()
            initializedStore?.close()
        }
    }

    @Test
    fun `closing an initialized navigator closes its published store and rejects later requests`(): Unit = runBlocking {
        val source = sourceJar(directory.resolve("download.jar"), JAVA_SOURCE)
        val creations = AtomicInteger()
        val initialized = CompletableDeferred<SourceContentStore>()
        val navigation = SourceNavigationService(
            sourceResolver = RecordingResolver { VerifiedSourceArtifactResult.Available(source, digest(source)) },
            sourceCacheRoot = directory.resolve("managed"),
            contentStoreFactory = { root ->
                creations.incrementAndGet()
                SourceContentStore(root).also { initialized.complete(it) }
            },
        )
        try {
            val symbol = binary("selected/library.jar")
            assertIs<SourceLookupResult.Located>(navigation.locate(symbol, context()))
            val storage = initialized.await()
            navigation.close()
            navigation.close()
            assertFailsWith<CancellationException> { storage.importArchive(SourceArchiveInput.Local(source)) }
            assertFailsWith<CancellationException> { navigation.locate(symbol, context()) }
            assertEquals(1, creations.get())
        } finally {
            navigation.close()
        }
    }

    @Test
    fun `fresh context validation after presentation rejects a retired binary`(): Unit = runBlocking {
        val source = sourceJar(directory.resolve("download.jar"), JAVA_SOURCE)
        val valid = AtomicBoolean(true)
        val validations = AtomicInteger()
        val held = SourceLookupContext(
            SourceContextIdentity("held"),
            validateCurrent = {
                validations.incrementAndGet()
                valid.get()
            },
        ) { true }
        SourceNavigationService(
            sourceResolver = RecordingResolver { VerifiedSourceArtifactResult.Available(source, digest(source)) },
            sourceCacheRoot = directory.resolve("managed"),
            sourcePresentation = SourcePresentation { descriptor ->
                valid.set(false)
                descriptor.contentPath.toUri()
            },
        ).use { navigation ->
            assertEquals(SourceLookupResult.Stale, navigation.locate(binary("selected/library.jar"), held))
            assertEquals(1, validations.get())
        }
    }

    @Test
    fun `fresh validation distinguishes a retired context from a current presentation failure`(): Unit = runBlocking {
        val source = sourceJar(directory.resolve("download.jar"), JAVA_SOURCE)
        for (stillCurrent in listOf(false, true)) {
            val valid = AtomicBoolean(true)
            val validations = AtomicInteger()
            val held = SourceLookupContext(
                SourceContextIdentity("failed-presentation-$stillCurrent"),
                validateCurrent = {
                    validations.incrementAndGet()
                    valid.get()
                },
            ) { true }
            SourceNavigationService(
                sourceResolver = RecordingResolver { VerifiedSourceArtifactResult.Available(source, digest(source)) },
                sourceCacheRoot = directory.resolve("failed-presentation-$stillCurrent"),
                sourcePresentation = SourcePresentation {
                    valid.set(stillCurrent)
                    throw IOException("Presentation failed")
                },
            ).use { navigation ->
                val expected = if (stillCurrent) {
                    SourceLookupResult.Failed(SourceFailure.IO_FAILURE)
                } else {
                    SourceLookupResult.Stale
                }
                assertEquals(expected, navigation.locate(binary("selected/library.jar"), held))
                assertEquals(1, validations.get())
            }
        }
    }

    @Test
    fun `cancellation during final context validation remains cancellation`(): Unit = runBlocking {
        val source = sourceJar(directory.resolve("download.jar"), JAVA_SOURCE)
        val cancelled = CancellationException("Final validation cancelled")
        val held = SourceLookupContext(
            SourceContextIdentity("cancelled-validation"),
            validateCurrent = { throw cancelled },
        ) { true }
        val resolver = RecordingResolver { VerifiedSourceArtifactResult.Available(source, digest(source)) }
        service(resolver).use { navigation ->
            val failure = assertFailsWith<CancellationException> {
                navigation.locate(binary("selected/library.jar"), held)
            }
            assertTrue(failure === cancelled)
        }
    }

    @Test
    fun `local-only policy is passed through and explicit retry bypasses missing-source cache`() = runBlocking {
        val resolver = RecordingResolver { VerifiedSourceArtifactResult.NotAvailable }
        val symbol = binary("selected/library.jar")
        service(resolver).use { navigation ->
            repeat(2) { assertIs<SourceLookupResult.Unavailable>(navigation.locate(symbol, context())) }
            assertEquals(1, resolver.calls.get())
            assertEquals(false, resolver.lastAllowDownload)
            navigation.retrySource(symbol, context(download = true))
            assertEquals(2, resolver.calls.get())
            assertEquals(true, resolver.lastAllowDownload)
        }
    }

    @Test
    fun `retry does not join an older pending request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = sourceJar(directory.resolve("download.jar"), JAVA_SOURCE)
        val resolver = RecordingResolver { call ->
            if (call == 1) {
                started.complete(Unit)
                release.await()
                VerifiedSourceArtifactResult.NotAvailable
            } else {
                VerifiedSourceArtifactResult.Available(source, digest(source))
            }
        }
        val symbol = binary("selected/library.jar")
        service(resolver).use { navigation ->
            val older = async { navigation.locate(symbol, context(download = true)) }
            started.await()
            val retried = assertIs<SourceLookupResult.Located>(navigation.retrySource(symbol, context(download = true)))
            release.complete(Unit)
            older.await()
            assertEquals(retried, navigation.locate(symbol, context(download = true)))
            assertEquals(2, resolver.calls.get())
        }
    }

    @Test
    fun `transport failures are not cached as source absence`() = runBlocking {
        val resolver = RecordingResolver { VerifiedSourceArtifactResult.Failed }
        service(resolver).use { navigation ->
            val symbol = binary("selected/library.jar")
            repeat(2) {
                assertEquals(
                    SourceLookupResult.Failed(SourceFailure.IO_FAILURE),
                    navigation.locate(symbol, context(download = true)),
                )
            }
            assertEquals(2, resolver.calls.get())
        }
    }

    @Test
    fun `context retired during lookup cannot publish a location`() = runBlocking {
        var current = true
        val resolver = RecordingResolver {
            current = false
            VerifiedSourceArtifactResult.NotAvailable
        }
        service(resolver).use { navigation ->
            val held = SourceLookupContext(
                SourceContextIdentity("held"),
                SourceLookupPolicy.ALLOW_VERIFIED_DOWNLOADS,
            ) { current }
            assertEquals(SourceLookupResult.Stale, navigation.locate(binary("selected/library.jar"), held))
        }
    }

    @Test
    fun `concurrent requesters share verified acquisition indexing extraction and declaration parsing`() = runBlocking {
        val source = sourceJar(directory.resolve("download.jar"), JAVA_SOURCE)
        val resolver = RecordingResolver { VerifiedSourceArtifactResult.Available(source, digest(source)) }
        val symbol = binary("selected/library.jar")
        service(resolver).use { navigation ->
            val results = List(12) { async { navigation.locate(symbol, context(download = true)) } }.awaitAll()
            results.forEach { assertIs<SourceLookupResult.Located>(it) }
            assertEquals(1, resolver.calls.get())
            assertEquals(1L, navigation.getStatistics()["declarationInspections"])
            assertEquals(1L, navigation.getStatistics()["sourceMaterializations"])
            assertEquals(1L, navigation.getStatistics()["archiveImports"])
        }
    }

    @Test
    fun `local attachment survives service restart but rejects changed binary content`() = runBlocking {
        val symbol = binary("selected/library.jar", coordinates = null)
        val attached = sourceJar(directory.resolve("chosen-sources.zip"), JAVA_SOURCE)
        service().use { assertIs<SourceLookupResult.Located>(it.attachSource(symbol, context(), attached, null)) }
        service().use { assertIs<SourceLookupResult.Located>(it.locate(symbol, context())) }
        Files.writeString((symbol.origin as SourceOrigin.Archive).binaryJar, "changed binary")
        service().use { navigation ->
            assertEquals(
                SourceLookupResult.Failed(SourceFailure.INTEGRITY_MISMATCH),
                navigation.locate(symbol, context()),
            )
        }
    }

    @Test
    fun `attachment expected digest is enforced before malformed archive parsing`(): Unit = runBlocking {
        val symbol = binary("selected/library.jar")
        val malformed = directory.resolve("chosen.jar").also { Files.writeString(it, "not an archive") }
        service().use { navigation ->
            assertEquals(
                SourceLookupResult.Failed(SourceFailure.INTEGRITY_MISMATCH),
                navigation.attachSource(symbol, context(), malformed, "0".repeat(64)),
            )
            assertIs<SourceLookupResult.Unavailable>(navigation.locate(symbol, context()))
        }
    }

    @Test
    fun `downloaded verified sources are immediately reusable by ordinary local-only navigation`() = runBlocking {
        val source = sourceJar(directory.resolve("download.jar"), JAVA_SOURCE)
        val resolver = RecordingResolver { call ->
            if (call == 1) {
                VerifiedSourceArtifactResult.NotAvailable
            } else {
                VerifiedSourceArtifactResult.Available(source, digest(source))
            }
        }
        val symbol = binary("selected/library.jar")
        service(resolver).use { navigation ->
            assertIs<SourceLookupResult.Unavailable>(navigation.locate(symbol, context()))
            val downloaded = assertIs<SourceLookupResult.Located>(
                navigation.retrySource(symbol, context(download = true)),
            )
            assertEquals(downloaded, navigation.locate(symbol, context()))
            assertEquals(2, resolver.calls.get())
        }
    }

    @Test
    fun `selected binary mismatch remains explicit source absence without parsing public source bytes`() = runBlocking {
        val resolver = RecordingResolver { VerifiedSourceArtifactResult.BinaryMismatch }
        service(resolver).use { navigation ->
            assertEquals(
                SourceLookupResult.Unavailable(MissingSourceReason.BINARY_ORIGIN_MISMATCH),
                navigation.locate(binary("selected/library.jar"), context(download = true)),
            )
            assertEquals(0L, navigation.getStatistics()["archiveIndexes"])
        }
    }

    @Test
    fun `retired context takes precedence over a delayed transport failure`() = runBlocking {
        var current = true
        val resolver = RecordingResolver {
            current = false
            VerifiedSourceArtifactResult.Failed
        }
        val held = SourceLookupContext(
            SourceContextIdentity("held"),
            SourceLookupPolicy.ALLOW_VERIFIED_DOWNLOADS,
        ) { current }
        service(resolver).use { navigation ->
            assertEquals(SourceLookupResult.Stale, navigation.locate(binary("selected/library.jar"), held))
        }
    }

    @Test
    fun `privately changed same-version core binary never uses the canonical bundled sources`() = runBlocking {
        val runtime = Path.of(groovy.lang.GroovyObject::class.java.protectionDomain.codeSource.location.toURI())
        val binary = directory.resolve("private/groovy-4.0.33.jar")
        Files.createDirectories(binary.parent)
        Files.copy(runtime, binary)
        Files.write(binary, byteArrayOf(1), java.nio.file.StandardOpenOption.APPEND)
        val resolver = RecordingResolver { VerifiedSourceArtifactResult.BinaryMismatch }
        service(resolver).use { navigation ->
            val symbol = ResolvedExternalSymbol(
                "groovy.transform.CompileStatic",
                SourceOrigin.Archive(binary, COORDINATES),
            )
            assertEquals(
                SourceLookupResult.Unavailable(MissingSourceReason.BINARY_ORIGIN_MISMATCH),
                navigation.locate(symbol, context(download = true)),
            )
            assertEquals(digest(binary), resolver.lastBinaryDigest)
            assertEquals(0L, navigation.getStatistics()["archiveIndexes"])
        }
    }

    @Test
    fun `invalid UTF8 in an otherwise valid Java comment fails before parsing or publishing a source URI`() =
        runBlocking {
            val symbol = binary("selected/library.jar")
            val malformed = "package sample; /* ".toByteArray(Charsets.UTF_8) +
                byteArrayOf(0xC3.toByte(), 0x28) + " */ public @interface Anno {}".toByteArray(Charsets.UTF_8)
            val source = sourceJar(directory.resolve("repository/library-sources.jar"), malformed)
            val parsers = AtomicInteger()
            val presentations = AtomicInteger()
            SourceNavigationService(
                sourceResolver = RecordingResolver { VerifiedSourceArtifactResult.Available(source, digest(source)) },
                sourceCacheRoot = directory.resolve("managed"),
                javaSourceInspector = JavaSourceInspector {
                    parsers.incrementAndGet()
                    JavaParser()
                },
                sourcePresentation = SourcePresentation {
                    presentations.incrementAndGet()
                    error("Malformed UTF8 must never publish a source view")
                },
            ).use { navigation ->
                repeat(2) {
                    assertEquals(
                        SourceLookupResult.Failed(SourceFailure.IO_FAILURE),
                        navigation.locate(symbol, context()),
                    )
                }
                assertEquals(0L, navigation.getStatistics()["declarationInspections"])
            }
            assertEquals(0, parsers.get())
            assertEquals(0, presentations.get())
        }

    private fun service(resolver: SourceArtifactResolver = RecordingResolver()) = SourceNavigationService(
        sourceResolver = resolver,
        sourceCacheRoot = directory.resolve("managed"),
    )

    private fun binary(
        relative: String,
        coordinates: ResolvedArtifactCoordinates? = COORDINATES,
    ): ResolvedExternalSymbol {
        val path = directory.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, "selected binary")
        return ResolvedExternalSymbol("sample.Anno", SourceOrigin.Archive(path, coordinates))
    }

    private fun sourceJar(path: Path, content: String, entryName: String = "sample/Anno.java"): Path =
        sourceJar(path, content.toByteArray(Charsets.UTF_8), entryName)

    private fun sourceJar(path: Path, content: ByteArray, entryName: String = "sample/Anno.java"): Path {
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(JarEntry(entryName))
            jar.write(content)
            jar.closeEntry()
        }
        return path
    }

    private fun context(id: String = "context", download: Boolean = false) = SourceLookupContext(
        SourceContextIdentity(id),
        if (download) SourceLookupPolicy.ALLOW_VERIFIED_DOWNLOADS else SourceLookupPolicy.LOCAL_ONLY,
    ) { true }

    private fun digest(path: Path): String = Files.newInputStream(path).use { copyAndDigest(it, 64L * 1024 * 1024) }

    private class RecordingResolver(
        private val resolve: suspend (Int) -> VerifiedSourceArtifactResult = {
            VerifiedSourceArtifactResult.NotAvailable
        },
    ) : SourceArtifactResolver,
        VerifiedSourceArtifactResolver {
        override val cacheDir: Path = Path.of("unused-test-resolver-cache")
        val calls = AtomicInteger()
        var lastCoordinates: ResolvedArtifactCoordinates? = null
        var lastAllowDownload: Boolean? = null
        var lastBinaryDigest: String? = null
        override suspend fun resolveVerifiedSourceArtifact(
            request: VerifiedSourceArtifactRequest,
        ): VerifiedSourceArtifactResult {
            lastCoordinates = request.coordinates
            lastBinaryDigest = request.selectedBinarySha256
            lastAllowDownload = request.allowDownload
            return resolve(calls.incrementAndGet())
        }
        override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? =
            error("Legacy guessed-coordinate resolver must not run")
        override fun isSourcesCached(groupId: String, artifactId: String, version: String) = false
    }

    companion object {
        @JvmStatic
        fun hasJdkSources(): Boolean = JdkSourceResolver().findSrcZip() != null

        private val COORDINATES = ResolvedArtifactCoordinates("producer.exact", "artifact", "7.3")
        private const val JAVA_SOURCE = "package sample;\n@Deprecated\npublic @interface Anno {}"
    }
}
