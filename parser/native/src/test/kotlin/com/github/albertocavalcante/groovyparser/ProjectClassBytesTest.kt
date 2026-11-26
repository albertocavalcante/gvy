// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.NativeSourceMembership.Status
import com.github.albertocavalcante.nativeapi.nativeBinaryInputs
import jdk.security.jarsigner.JarSigner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectClassBytesTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `same bytes at a newly selected earlier root retire the owned class`() {
        val earlier = Files.createDirectory(temp.resolve("earlier"))
        val dependency = jar("later.jar", mapOf(OBJECT_ENTRY to bytes(OBJECT_ENTRY)))
        val receipt = SdkFallbackGroovyClassLoader(ClassLoader.getPlatformClassLoader()).use { loader ->
            loader.addClasspath(earlier.toString())
            loader.addClasspath(dependency.toString())
            val loaded = loader.loadClass(OBJECT_NAME, false, true)
            assertEquals(dependency.toUri(), loaded.protectionDomain.codeSource.location.toURI())
            assertNotNull(loader.nativeBinaryInputs()).also { assertEquals(Status.CURRENT, it.check {}) }
        }
        val child = earlier.resolve(OBJECT_ENTRY)
        Files.createDirectories(child.parent)
        Files.write(child, bytes(OBJECT_ENTRY))
        assertEquals(Status.CHANGED, receipt.check {})
    }

    @Test
    fun `removing a selected multi-release entry changes the receipt even when base bytes match`() {
        val versioned = "META-INF/versions/17/$OBJECT_ENTRY"
        val content = bytes(OBJECT_ENTRY)
        val dependency = jar("multi.jar", mapOf(OBJECT_ENTRY to content, versioned to content), multi = true)
        val receipt = SdkFallbackGroovyClassLoader(ClassLoader.getPlatformClassLoader()).use { loader ->
            loader.addClasspath(dependency.toString())
            assertTrue(versioned in assertNotNull(loader.getResource(OBJECT_ENTRY)).toExternalForm())
            assertEquals(dependency.toUri(), loader.loadClass(OBJECT_NAME).protectionDomain.codeSource.location.toURI())
            assertNotNull(loader.nativeBinaryInputs()).also { assertEquals(Status.CURRENT, it.check {}) }
        }
        val plain = jar("without-version.jar", mapOf(OBJECT_ENTRY to content), multi = true)
        Files.move(plain, dependency, StandardCopyOption.REPLACE_EXISTING)
        assertEquals(Status.CHANGED, receipt.check {})
    }

    @Test
    fun `signed sealed project classes preserve the JDK package code source and signers`() {
        val unsigned = jar("unsigned.jar", mapOf(OBJECT_ENTRY to bytes(OBJECT_ENTRY)), sealed = true)
        val signed = sign(unsigned)
        val other = jar("other.jar", mapOf("groovy/lang/Range.class" to bytes("groovy/lang/Range.class")))
        val platform = ClassLoader.getPlatformClassLoader()
        URLClassLoader(arrayOf(signed.toUri().toURL(), other.toUri().toURL()), platform).use { reference ->
            val ordinary = reference.loadClass(OBJECT_NAME)
            val expected = assertNotNull(ordinary.protectionDomain.codeSource.codeSigners).toList()
            assertTrue(expected.isNotEmpty())
            assertTrue(ordinary.`package`.isSealed)
            assertFailsWith<SecurityException> { reference.loadClass("groovy.lang.Range") }
            SdkFallbackGroovyClassLoader(platform).use { loader ->
                loader.addClasspath(signed.toString())
                loader.addClasspath(other.toString())
                val selected = loader.loadClass(OBJECT_NAME, false, true)
                val source = selected.protectionDomain.codeSource
                assertEquals(expected, assertNotNull(source.codeSigners).toList())
                assertEquals(signed.toUri(), source.location.toURI())
                assertTrue(selected.`package`.isSealed(source.location))
                assertEquals(ordinary.`package`.implementationVersion, selected.`package`.implementationVersion)
                assertEquals(Status.CURRENT, assertNotNull(loader.nativeBinaryInputs()).check {})
                assertFailsWith<SecurityException> { loader.loadClass("groovy.lang.Range", false, true) }
            }
        }
    }

    private fun jar(
        name: String,
        entries: Map<String, ByteArray>,
        multi: Boolean = false,
        sealed: Boolean = false,
    ): Path {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MULTI_RELEASE] = multi.toString()
            mainAttributes[Attributes.Name.SEALED] = sealed.toString()
            mainAttributes[Attributes.Name.IMPLEMENTATION_VERSION] = "3.2.1"
        }
        val result = temp.resolve(name)
        JarOutputStream(Files.newOutputStream(result), manifest).use { output ->
            entries.forEach { (entry, content) ->
                output.putNextEntry(JarEntry(entry))
                output.write(content)
                output.closeEntry()
            }
        }
        return result
    }

    private fun sign(unsigned: Path): Path {
        val store = temp.resolve("fixture.p12")
        val password = "fixture-only"
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool")
        val log = temp.resolve("keytool.log")
        val process = ProcessBuilder(
            keytool.toString(), "-J-Xmx64m", "-genkeypair", "-alias", "fixture",
            "-dname", "CN=gvy test", "-keyalg", "EC", "-storetype", "PKCS12",
            "-keystore", store.toString(), "-storepass", password, "-noprompt",
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), Files.readString(log))
        } finally {
            process.destroyForcibly()
        }
        val keys = KeyStore.getInstance(store.toFile(), password.toCharArray())
        val key = keys.getEntry("fixture", KeyStore.PasswordProtection(password.toCharArray()))
        val signer = JarSigner.Builder(key as KeyStore.PrivateKeyEntry).build()
        val result = temp.resolve("signed.jar")
        ZipFile(unsigned.toFile()).use { input -> Files.newOutputStream(result).use { signer.sign(input, it) } }
        return result
    }

    private fun bytes(name: String): ByteArray =
        assertNotNull(groovy.lang.GroovyObject::class.java.classLoader.getResourceAsStream(name)).use { it.readBytes() }

    private companion object {
        const val OBJECT_NAME = "groovy.lang.GroovyObject"
        const val OBJECT_ENTRY = "groovy/lang/GroovyObject.class"
    }
}
