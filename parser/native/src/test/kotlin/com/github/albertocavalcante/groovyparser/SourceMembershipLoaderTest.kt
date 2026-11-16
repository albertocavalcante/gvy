// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.SourcePathAdmissionPolicy
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.messages.SimpleMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceMembershipLoaderTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `trailing lambda remains the workspace class locator`() {
        val request = ParseRequest(root.resolve("Uses.groovy").toUri(), "") { listOf(root.resolve(it)) }
        assertEquals(listOf(root.resolve("Dependency")), request.workspaceClassLocator?.invoke("Dependency"))
    }

    @Test
    fun `legacy positional workspace locator remains source compatible`() {
        val locator: (String) -> List<Path> = { listOf(root.resolve(it)) }
        val request = ParseRequest(
            root.resolve("Uses.groovy").toUri(),
            "",
            emptyList(),
            emptyList(),
            emptyList(),
            emptyMap(),
            emptySet(),
            emptySet(),
            Phases.CONVERSION,
            ParseMode.WORKSPACE,
            {},
            locator,
        )

        assertSame(locator, request.workspaceClassLocator)
        assertNull(request.sourcePathAdmission)
    }

    @Test
    fun `locator buffer retains annotation source identity`() {
        val disk = Files.writeString(root.resolve("Different.groovy"), "class Placeholder {}")
        val alias = Files.createSymbolicLink(root.resolve("Open.groovy"), disk)
        val result = GroovyParserFacade().parseWithSourceFilter(
            ParseRequest(
                uri = root.resolve("Uses.groovy").toUri(),
                content = "@Mark class Uses {}",
                parseMode = ParseMode.MINIMAL,
                compilePhase = Phases.CANONICALIZATION,
                workspaceClassLocator = { if (it == "Mark") listOf(disk) else emptyList() },
            ),
            sourceFilter = { true },
            locatorBuffers = mapOf(alias.toUri() to "@interface Mark {}"),
        )
        assertEquals(alias.toUri(), result.sourceAnnotations.sourceUri(alias.toUri().path))
        assertNotNull(result.sourceAnnotations.foreignDeclaration(alias.toUri().path, "Mark"))
    }

    @Test
    fun `local jar admission receives unnormalized localhost path and fails closed on invalid path`() {
        val seen = mutableListOf<Path>()
        fun parse(url: URL) = GroovyParserFacade(
            GroovyParserFacade::class.java.classLoader,
            { parent ->
                SdkFallbackGroovyClassLoader(parent).apply {
                    resourceLoader = groovy.lang.GroovyResourceLoader { name ->
                        if (name == "Dependency") url else null
                    }
                }
            },
            null,
        ).parse(
            ParseRequest(
                uri = root.resolve("Uses.groovy").toUri(),
                content = "class Uses { Dependency value }",
                compilePhase = Phases.CANONICALIZATION,
                sourcePathAdmission = SourcePathAdmissionPolicy { path ->
                    seen.add(path)
                    false
                },
            ),
        )
        val rawPath = root.resolve("missing/../sources.jar")
        val result = parse(URL("jar:file://localhost${rawPath.toUri().rawPath}!/Dependency.groovy"))
        assertTrue(seen.contains(rawPath), "admission must see the path before normalization: $seen")
        assertTrue(result.compilationUnit.ast.classes.none { it.name == "Dependency" })
        seen.clear()
        val invalid = parse(URL("jar:file://remote-host/path.jar!/Dependency.groovy"))
        assertTrue(seen.isEmpty())
        assertTrue(invalid.compilationUnit.ast.classes.none { it.name == "Dependency" })
    }

    @Test
    fun `on demand lookup uses unsaved dependency through symlink alias`() {
        val sourceRoot = Files.createDirectories(root.resolve("src"))
        val dependencyRoot = Files.createDirectories(root.resolve("dependency"))
        val disk = Files.writeString(dependencyRoot.resolve("Different.groovy"), "class Dependency { void bad( }")
        val alias = Files.createSymbolicLink(root.resolve("OpenDependency.groovy"), disk)
        val uri = sourceRoot.resolve("Uses.groovy").toUri()
        val result = GroovyParserFacade().parseWithSourceFilter(
            ParseRequest(
                uri = uri,
                content = "class Uses { Dependency value }",
                sourceRoots = listOf(sourceRoot),
                compilePhase = Phases.CANONICALIZATION,
                workspaceClassLocator = { name -> if (name == "Dependency") listOf(disk) else emptyList() },
            ),
            sourceFilter = { true },
            locatorBuffers = mapOf(alias.toUri() to "class Dependency { String unsaved() { 'ok' } }"),
        )
        assertNull(result.outcome.fallback, "lookup must compile the unsaved buffer rather than broken disk")
        assertTrue(result.diagnostics.isEmpty(), "unsaved dependency should resolve: ${result.diagnostics}")
        val dependency = result.compilationUnit.ast.classes.single { it.name == "Dependency" }
        assertTrue(dependency.methods.any { it.name == "unsaved" })
    }

    @Test
    fun `broken on demand alias buffer is excluded from recovery`() {
        val sourceRoot = Files.createDirectories(root.resolve("src"))
        val dependencyRoot = Files.createDirectories(root.resolve("dependency"))
        val disk = Files.writeString(
            dependencyRoot.resolve("Different.groovy"),
            "class Dependency { def diskOnly() {} }",
        )
        val alias = Files.createSymbolicLink(root.resolve("OpenDependency.groovy"), disk)
        val uri = sourceRoot.resolve("Uses.groovy").toUri()
        val result = GroovyParserFacade().parseWithSourceFilter(
            ParseRequest(
                uri = uri,
                content = "class Uses { Dependency value }",
                sourceRoots = listOf(sourceRoot),
                compilePhase = Phases.CANONICALIZATION,
                workspaceClassLocator = { name -> if (name == "Dependency") listOf(disk) else emptyList() },
            ),
            sourceFilter = { true },
            locatorBuffers = mapOf(alias.toUri() to "class Dependency { void bad( }"),
        )
        assertTrue(result.outcome.excludedSources.any { it.source == alias.toUri().toString() })
        assertTrue(result.compilationUnit.ast.classes.none { it.methods.any { method -> method.name == "diskOnly" } })
    }

    @Test
    fun `source loader rechecks dynamic path admission`() {
        val sourceRoot = Files.createDirectories(root.resolve("src"))
        val dependency = Files.writeString(root.resolve("Dependency.groovy"), "class Dependency {}")
        val admittedPaths = mutableListOf<Path>()
        val request = ParseRequest(
            uri = sourceRoot.resolve("Uses.groovy").toUri(),
            content = "class Uses { Dependency value }",
            sourceRoots = listOf(root),
            sourcePathAdmission = SourcePathAdmissionPolicy { path ->
                admittedPaths.add(path)
                false
            },
            compilePhase = Phases.CANONICALIZATION,
        )
        val result = GroovyParserFacade().parse(request.copy(), setOf(dependency))

        assertTrue(
            admittedPaths.contains(dependency.toAbsolutePath().normalize()),
            "the loader checks the discovered path",
        )
        assertNull(result.outcome.fallback, "a denied source remains unresolved without retrying")
        assertTrue(result.compilationUnit.ast.classes.none { it.name == "Dependency" })
    }

    @Test
    fun `source loader excludes real path when source root uses symlink`() {
        val actualRoot = Files.createDirectories(root.resolve("actual"))
        val disk = Files.writeString(actualRoot.resolve("Broken.groovy"), "class Broken { void bad( }")
        val aliasRoot = Files.createSymbolicLink(root.resolve("alias"), actualRoot)
        val uri = root.resolve("Uses.groovy").toUri()
        val result = GroovyParserFacade().parseWithSourceFilter(
            ParseRequest(
                uri = uri,
                content = "class Uses { Broken value }",
                sourceRoots = listOf(aliasRoot),
                excludedSources = setOf(disk),
                compilePhase = Phases.CANONICALIZATION,
            ),
            sourceFilter = { true },
        )
        assertNull(result.outcome.fallback, "excluded disk source must not return through a symlink root")
        assertTrue(result.diagnostics.isNotEmpty())
    }

    @Test
    fun `unattributed error cannot reload a dropped eager buffer from disk`() {
        val sourceRoot = Files.createDirectories(root.resolve("src"))
        val disk = Files.writeString(root.resolve("Different.groovy"), "class Dependency { def diskOnly() {} }")
        val locatorDisk = Files.writeString(root.resolve("Another.groovy"), "class Other { def locatorDiskOnly() {} }")
        val locatorAlias = Files.createSymbolicLink(root.resolve("OpenOther.groovy"), locatorDisk)
        val uri = sourceRoot.resolve("Uses.groovy").toUri()
        var attempts = 0
        val parser = GroovyParserFacade(
            GroovyParserFacade::class.java.classLoader,
            ::SdkFallbackGroovyClassLoader,
        ) { unit ->
            if (attempts++ == 0) unit.errorCollector.addErrorAndContinue(SimpleMessage("ownerless test error", null))
        }
        val result = parser.parseWithSourceFilter(
            ParseRequest(
                uri = uri,
                content = "class Uses { Dependency value; Other other }",
                sourceRoots = listOf(sourceRoot),
                openSources = mapOf(disk.toUri() to "class Dependency { def unsaved() {} }"),
                workspaceSources = listOf(disk),
                compilePhase = Phases.CANONICALIZATION,
                workspaceClassLocator = { name ->
                    when (name) {
                        "Dependency" -> listOf(disk)
                        "Other" -> listOf(locatorDisk)
                        else -> emptyList()
                    }
                },
            ),
            sourceFilter = { true },
            locatorBuffers = mapOf(locatorAlias.toUri() to "class Other { def unsavedOther() {} }"),
        )
        assertTrue(attempts >= 2, "the ownerless error must trigger a real retry")
        val dropped = result.outcome.excludedSources.single { it.source == disk.toUri().toString() }
        assertTrue(dropped.reason.contains("no source"), dropped.reason)
        assertTrue(result.outcome.excludedSources.any { it.source == locatorAlias.toUri().toString() })
        assertTrue(
            result.compilationUnit.ast.classes.none { cls ->
                cls.methods.any { method -> method.name == "diskOnly" || method.name == "locatorDiskOnly" }
            },
        )
    }

    @Test
    fun `source loader refuses excluded broken source`() {
        val broken = root.resolve("Broken.groovy").also { it.toFile().writeText("class Broken { void m( }") }
        val uri = root.resolve("Uses.groovy").toUri()
        val result = GroovyParserFacade().parse(
            ParseRequest(
                uri = uri,
                content = "class Uses { Broken value }",
                sourceRoots = listOf(root),
                locatorCandidates = setOf(uri.toString(), uri.path),
                compilePhase = Phases.CANONICALIZATION,
            ),
            emptySet(),
        )
        assertNull(result.outcome.fallback, "excluded source must not be loaded: $broken")
        assertTrue(result.diagnostics.isNotEmpty(), "the missing type remains visible")
    }

    @Test
    fun `source loader resolves included Groovy sibling`() {
        val sibling = root.resolve("Sibling.groovy").also { it.toFile().writeText("class Sibling {}") }
        val uri = root.resolve("Uses.groovy").toUri()
        val result = GroovyParserFacade().parse(
            ParseRequest(
                uri = uri,
                content = "class Uses { Sibling value }",
                sourceRoots = listOf(root),
                locatorCandidates = setOf(uri.toString(), uri.path),
                compilePhase = Phases.CANONICALIZATION,
            ),
            setOf(sibling),
        )
        assertTrue(result.diagnostics.isEmpty(), "included Groovy source must resolve: ${result.diagnostics}")
        assertNull(result.outcome.fallback)
    }

    @Test
    fun `membership does not hide Groovy sources in dependency jars`() {
        val sourceRoot = root.resolve("src").also { it.toFile().mkdirs() }
        val jar = root.resolve("dependency.jar")
        ZipOutputStream(jar.toFile().outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Dependency.groovy"))
            zip.write("class Dependency {}".toByteArray())
            zip.closeEntry()
        }
        val uri = sourceRoot.resolve("Uses.groovy").toUri()
        val result = GroovyParserFacade().parse(
            ParseRequest(
                uri = uri,
                content = "class Uses { Dependency value }",
                classpath = listOf(jar),
                sourceRoots = listOf(sourceRoot),
                locatorCandidates = setOf(uri.toString(), uri.path),
                compilePhase = Phases.CANONICALIZATION,
            ),
            emptySet(),
        )
        assertTrue(result.diagnostics.isEmpty(), "dependency source must resolve: ${result.diagnostics}")
        assertNull(result.outcome.fallback)
    }

    @Test
    fun `source admission checks the local jar containing a Groovy source`() {
        val sourceRoot = Files.createDirectories(root.resolve("src"))
        val managedRoot = Files.createDirectories(root.resolve("managed"))
        val jar = managedRoot.resolve("sources.jar")
        ZipOutputStream(jar.toFile().outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Dependency.groovy"))
            zip.write("class Dependency {}".toByteArray())
            zip.closeEntry()
        }
        val admittedPaths = mutableListOf<Path>()
        val result = GroovyParserFacade().parse(
            ParseRequest(
                uri = sourceRoot.resolve("Uses.groovy").toUri(),
                content = "class Uses { Dependency value }",
                classpath = listOf(jar),
                sourceRoots = listOf(sourceRoot),
                sourcePathAdmission = SourcePathAdmissionPolicy { path ->
                    admittedPaths.add(path)
                    !path.startsWith(managedRoot)
                },
                compilePhase = Phases.CANONICALIZATION,
            ),
            emptySet(),
        )

        assertTrue(admittedPaths.contains(jar.toAbsolutePath().normalize()), "admission checks the JAR path")
        assertTrue(result.compilationUnit.ast.classes.none { it.name == "Dependency" })
    }

    @Test
    fun `source loader preserves legacy behavior for included Java sibling`() {
        val java = root.resolve("Sibling.java").also { it.toFile().writeText("public class Sibling {}") }
        val uri = root.resolve("Uses.groovy").toUri()
        val request = ParseRequest(
            uri = uri,
            content = "class Uses { Sibling value }",
            sourceRoots = listOf(root),
            locatorCandidates = setOf(uri.toString(), uri.path),
            compilePhase = Phases.CANONICALIZATION,
        )
        val parser = GroovyParserFacade()
        val legacy = parser.parse(request)
        val scoped = parser.parse(request, setOf(java))
        assertEquals(legacy.diagnostics.map { it.message }, scoped.diagnostics.map { it.message })
        assertEquals(legacy.outcome.fallback, scoped.outcome.fallback)
    }
}
