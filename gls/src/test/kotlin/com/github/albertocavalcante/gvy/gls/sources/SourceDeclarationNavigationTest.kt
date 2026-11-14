// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.ResolvedArtifactCoordinates
import com.github.albertocavalcante.gvy.build.SourceArtifactResolver
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactRequest
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactResolver
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Verified source selection and exact declaration spans for Java and Groovy archives. */
class SourceDeclarationNavigationTest {
    @TempDir lateinit var directory: Path

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
    fun `verified Groovy source locates a unique method and leaves overloads file-only`() = runBlocking {
        val content = """
            package sample
            class Anno {
                String convert(String value) { value }
                String select() { "select()" }
                String select(String value) { value }
            }
        """.trimIndent()
        val source = sourceJar(
            directory.resolve("repo/library-sources.jar"),
            content,
            "sample/Anno.groovy",
        )
        val resolver = RecordingResolver { VerifiedSourceArtifactResult.Available(source, digest(source)) }
        val owner = binary("selected/library.jar")
        service(resolver).use { navigation ->
            val located = assertIs<SourceLookupResult.Located>(
                navigation.locate(owner.copy(memberName = "convert"), context()),
            )
            val name = located.definition.nameRange
            val lines = Files.readString(Path.of(located.uri)).lines()
            assertEquals("convert", lines[name.startLine].substring(name.startCharacter, name.endCharacter))
            assertTrue(located.definition.declarationRange.contains(name))

            assertIs<SourceLookupResult.FileOnly>(navigation.locate(owner.copy(memberName = "select"), context()))
        }
    }

    @Test
    fun `ambiguous Groovy static field and method remain file-only`() = runBlocking {
        val content = """
            package sample
            class Anno {
                static int select = 1
                static int select() { 2 }
            }
        """.trimIndent()
        val source = sourceJar(
            directory.resolve("repo/library-sources.jar"),
            content,
            "sample/Anno.groovy",
        )
        val resolver = RecordingResolver { VerifiedSourceArtifactResult.Available(source, digest(source)) }
        val owner = binary("selected/library.jar")

        service(resolver).use { navigation ->
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
        var lastBinaryDigest: String? = null
        override suspend fun resolveVerifiedSourceArtifact(
            request: VerifiedSourceArtifactRequest,
        ): VerifiedSourceArtifactResult {
            lastCoordinates = request.coordinates
            lastBinaryDigest = request.selectedBinarySha256
            return resolve(calls.incrementAndGet())
        }
        override suspend fun resolveSourceJar(groupId: String, artifactId: String, version: String): Path? =
            error("Legacy guessed-coordinate resolver must not run")
        override fun isSourcesCached(groupId: String, artifactId: String, version: String) = false
    }

    companion object {
        private val COORDINATES = ResolvedArtifactCoordinates("producer.exact", "artifact", "7.3")
        private const val JAVA_SOURCE = "package sample;\n@Deprecated\npublic @interface Anno {}"
    }
}
