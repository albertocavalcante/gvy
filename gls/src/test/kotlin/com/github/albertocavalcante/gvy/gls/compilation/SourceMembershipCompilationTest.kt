// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.DependencyRole
import com.github.albertocavalcante.gvy.build.DependencyTarget
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import com.github.albertocavalcante.gvy.build.TargetDependencySnapshot
import com.github.albertocavalcante.gvy.build.TargetDependencyState
import com.github.albertocavalcante.gvy.common.ast.isStubSourceName
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceMembershipCompilationTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `plain workspace compilation leaves source model flags inapplicable`(): Unit = runBlocking {
        val src = Files.createDirectories(root.resolve("src"))
        val file = Files.writeString(src.resolve("Plain.groovy"), "class Plain {}")
        val compilation = GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined)
        compilation.updateWorkspaceModel(root, emptyList(), listOf(src))
        val result = compilation.compile(file.toUri(), "class Plain {}")
        assertNull(result.sourceMembershipKnown)
        assertNull(result.sourceVisibilityKnown)
        assertNull(result.toSourceModelDiagnostic())
    }

    @Test
    fun `failed Gradle observation produces an explicit source model limitation`(): Unit = runBlocking {
        val src = Files.createDirectories(root.resolve("src"))
        val file = Files.writeString(src.resolve("Example.groovy"), "class Example {}")
        val compilation = GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined)
        compilation.updateWorkspaceModel(
            root,
            emptyList(),
            listOf(src),
            TargetDependencyEvidence.Scoped(emptyList(), enumerationComplete = false),
        )
        val result = compilation.compile(file.toUri(), "class Example {}")
        assertTrue(result.sourceMembershipKnown == false)
        assertTrue(result.sourceVisibilityKnown == false)
        assertNotNull(result.toSourceModelDiagnostic())
    }

    @Test
    fun `excluded open buffer does not enter a member compile`(): Unit = runBlocking {
        val sources = Files.createDirectories(root.resolve("src"))
        val main = Files.writeString(sources.resolve("Main.groovy"), "class Main {}")
        val excluded = Files.writeString(sources.resolve("Broken.groovy"), "class Broken {}")
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined, documentProvider = documents)
        val target = TargetDependencySnapshot(
            DependencyTarget(URI.create("test:main"), root, "main"),
            DependencyRole.COMPILE,
            DependencyProvenance("test", 1),
            TargetDependencyState.COMPLETE,
            emptyList(),
            listOf(sources),
            listOf(main),
        )
        compilation.updateWorkspaceModel(
            root,
            emptyList(),
            listOf(sources),
            TargetDependencyEvidence.Scoped(listOf(target), enumerationComplete = true),
        )
        documents.put(excluded.toUri(), "class Broken { void m( }")
        documents.put(main.toUri(), "class Main {}")
        compilation.compile(main.toUri(), "class Main {}")
        val snapshot = assertNotNull(compilation.getCompiledSnapshot(main.toUri()))
        assertTrue(snapshot.parseResult.outcome.excludedSources.isEmpty())
        assertTrue(snapshot.parseResult.outcome.fallback == null)
        assertTrue(
            snapshot.parseResult.compilationUnit.iterator().asSequence().none {
                it.name.contains("Broken.groovy")
            },
        )
    }

    @Test
    fun `excluded and newly created documents compile independently`(): Unit = runBlocking {
        val sources = Files.createDirectories(root.resolve("src"))
        val member = Files.writeString(sources.resolve("Member.groovy"), "class Member {}")
        val excluded = Files.writeString(sources.resolve("Excluded.groovy"), "class Excluded {}")
        val newFile = sources.resolve("New.groovy")
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined, documentProvider = documents)
        val target = TargetDependencySnapshot(
            DependencyTarget(URI.create("test:main"), root, "main"),
            DependencyRole.COMPILE,
            DependencyProvenance("test", 1),
            TargetDependencyState.COMPLETE,
            emptyList(),
            listOf(sources),
            listOf(member),
        )
        compilation.updateWorkspaceModel(
            root,
            emptyList(),
            listOf(sources),
            TargetDependencyEvidence.Scoped(listOf(target), enumerationComplete = true),
        )
        val brokenMember = "class Member { void m( }"
        documents.put(member.toUri(), brokenMember)
        for (file in listOf(excluded, newFile)) {
            val content = "class ${file.fileName.toString().removeSuffix(".groovy")} {}"
            documents.put(file.toUri(), content)
            val result = compilation.compile(file.toUri(), content)
            assertTrue(result.sourceMembershipKnown == false)
            val snapshot = assertNotNull(compilation.getCompiledSnapshot(file.toUri()))
            assertTrue(snapshot.parseResult.outcome.excludedSources.isEmpty())
            assertTrue(snapshot.parseResult.outcome.fallback == null)
            assertTrue(
                snapshot.parseResult.compilationUnit.iterator().asSequence().none {
                    it.name.contains("Member.groovy")
                },
            )
        }
    }

    @Test
    fun `new source remains unconfirmed through file events until compiler membership refreshes`(): Unit = runBlocking {
        val sources = Files.createDirectories(root.resolve("src"))
        val member = Files.writeString(sources.resolve("Member.groovy"), "class Member {}")
        val added = sources.resolve("Added.groovy")
        val content = "class Added {}"
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined, documentProvider = documents)
        fun evidence(files: List<Path>) = TargetDependencyEvidence.Scoped(
            listOf(
                TargetDependencySnapshot(
                    DependencyTarget(URI.create("test:main"), root, "main"),
                    DependencyRole.COMPILE,
                    DependencyProvenance("test", 1),
                    TargetDependencyState.COMPLETE,
                    emptyList(),
                    listOf(sources),
                    files,
                ),
            ),
            enumerationComplete = true,
        )
        compilation.updateWorkspaceModel(root, emptyList(), listOf(sources), evidence(listOf(member)))
        documents.put(added.toUri(), content)
        assertTrue(compilation.compile(added.toUri(), content).sourceMembershipKnown == false)
        assertTrue(compilation.workspaceManager.current().sourceScopeFor(added).mode == SourceMode.INDEPENDENT)

        Files.writeString(added, content)
        compilation.onWorkspaceSourcesChanged(listOf(added), emptyList())
        assertTrue(compilation.compile(added.toUri(), content).sourceMembershipKnown == false)
        assertTrue(compilation.workspaceManager.current().sourceScopeFor(added).mode == SourceMode.INDEPENDENT)

        compilation.updateWorkspaceModel(root, emptyList(), listOf(sources), evidence(listOf(member, added)))
        assertTrue(compilation.compile(added.toUri(), content).sourceMembershipKnown == true)
        assertTrue(compilation.workspaceManager.current().sourceScopeFor(added).mode == SourceMode.SELECTED)
    }

    @Test
    fun `task-added source outside source roots participates in compilation`(): Unit = runBlocking {
        val sources = Files.createDirectories(root.resolve("src"))
        val extra = Files.createDirectories(root.resolve("extra"))
        val main = Files.writeString(sources.resolve("Main.groovy"), "class Main { Extra value }")
        val sibling = Files.writeString(extra.resolve("Extra.groovy"), "class Extra {}")
        val compilation = GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined)
        val target = TargetDependencySnapshot(
            DependencyTarget(URI.create("test:main"), root, "main"),
            DependencyRole.COMPILE,
            DependencyProvenance("test", 1),
            TargetDependencyState.COMPLETE,
            emptyList(),
            listOf(sources),
            listOf(main, sibling),
        )
        compilation.updateWorkspaceModel(
            root,
            emptyList(),
            listOf(sources),
            TargetDependencyEvidence.Scoped(listOf(target), enumerationComplete = true),
        )
        assertTrue(sibling in compilation.workspaceManager.getWorkspaceSources())
        compilation.compile(main.toUri(), "class Main { Extra value }")
        val snapshot = assertNotNull(compilation.getCompiledSnapshot(main.toUri()))
        assertTrue(snapshot.parseResult.diagnostics.none { it.message.contains("unable to resolve class Extra") })
    }

    @Test
    fun `test source resolves unbuilt main source without loading excluded sibling`(): Unit = runBlocking {
        val mainRoot = Files.createDirectories(root.resolve("src/main/groovy"))
        val testRoot = Files.createDirectories(root.resolve("src/test/groovy"))
        val main = Files.writeString(mainRoot.resolve("Main.groovy"), "class Main {}")
        Files.writeString(mainRoot.resolve("Broken.groovy"), "class Broken { void m( }")
        val test = Files.writeString(testRoot.resolve("Use.groovy"), "class Use { Main value }")
        val compilation = GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined)
        fun target(id: String, dir: Path, files: List<Path>) = TargetDependencySnapshot(
            DependencyTarget(URI.create("test:$id"), root, id),
            DependencyRole.COMPILE,
            DependencyProvenance("test", 1),
            TargetDependencyState.COMPLETE,
            emptyList(),
            listOf(dir),
            files,
        )
        compilation.updateWorkspaceModel(
            root,
            emptyList(),
            listOf(mainRoot, testRoot),
            TargetDependencyEvidence.Scoped(
                listOf(target("main", mainRoot, listOf(main)), target("test", testRoot, listOf(test))),
                enumerationComplete = true,
            ),
        )
        val result = compilation.compile(test.toUri(), "class Use { Main value }")
        assertTrue(result.diagnostics.none { it.message.contains("unable to resolve class Main") })
        assertTrue(result.sourceVisibilityKnown == false)
        val snapshot = assertNotNull(compilation.getCompiledSnapshot(test.toUri()))
        assertTrue(snapshot.parseResult.outcome.excludedSources.isEmpty())
    }

    @Test
    fun `unbuilt project dependency source remains available as unknown visibility`(): Unit = runBlocking {
        val appRoot = Files.createDirectories(root.resolve("app/src/main/groovy"))
        val libRoot = Files.createDirectories(root.resolve("lib/src/main/groovy"))
        val app = Files.writeString(appRoot.resolve("App.groovy"), "class App { Lib value }")
        val lib = Files.writeString(libRoot.resolve("Lib.groovy"), "class Lib {}")
        val compilation = GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined)
        fun target(id: String, project: Path, dir: Path, files: List<Path>) = TargetDependencySnapshot(
            DependencyTarget(URI.create("test:$id"), project, "main"),
            DependencyRole.COMPILE,
            DependencyProvenance("test", 1),
            TargetDependencyState.COMPLETE,
            emptyList(),
            listOf(dir),
            files,
        )
        compilation.updateWorkspaceModel(
            root,
            listOf(root.resolve("lib/build/libs/lib.jar")),
            listOf(appRoot, libRoot),
            TargetDependencyEvidence.Scoped(
                listOf(
                    target("app", root.resolve("app"), appRoot, listOf(app)),
                    target("lib", root.resolve("lib"), libRoot, listOf(lib)),
                ),
                enumerationComplete = true,
            ),
        )
        val result = compilation.compile(app.toUri(), "class App { Lib value }")
        assertTrue(result.diagnostics.none { it.message.contains("unable to resolve class Lib") })
        assertTrue(result.sourceVisibilityKnown == false)
    }

    @Test
    fun `unknown dependency uses its unsaved buffer only when located`(): Unit = runBlocking {
        val mainRoot = Files.createDirectories(root.resolve("src/main/groovy"))
        val testRoot = Files.createDirectories(root.resolve("src/test/groovy"))
        val main = Files.writeString(mainRoot.resolve("Main.groovy"), "class Main {}")
        val test = Files.writeString(testRoot.resolve("Use.groovy"), "class Use extends Main.Fresh {}")
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined, documentProvider = documents)
        fun target(id: String, dir: Path, files: List<Path>) = TargetDependencySnapshot(
            DependencyTarget(URI.create("test:$id"), root, id),
            DependencyRole.COMPILE,
            DependencyProvenance("test", 1),
            TargetDependencyState.COMPLETE,
            emptyList(),
            listOf(dir),
            files,
        )
        compilation.updateWorkspaceModel(
            root,
            emptyList(),
            listOf(mainRoot, testRoot),
            TargetDependencyEvidence.Scoped(
                listOf(
                    target("main", mainRoot, listOf(main)),
                    target("test", testRoot, listOf(test)),
                ),
                enumerationComplete = true,
            ),
        )
        val buffer = "class Main { static class Fresh {} }"
        documents.put(main.toUri(), buffer)
        compilation.compile(main.toUri(), buffer)
        val result = compilation.compile(test.toUri(), "class Use extends Main.Fresh {}")
        assertTrue(result.diagnostics.none { it.message.contains("unable to resolve class Main.Fresh") })
    }

    @Test
    fun `unknown dependency buffer is not injected until referenced`(): Unit = runBlocking {
        val mainRoot = Files.createDirectories(root.resolve("src/main/groovy"))
        val testRoot = Files.createDirectories(root.resolve("src/test/groovy"))
        val main = Files.writeString(mainRoot.resolve("Main.groovy"), "class Main {}")
        val test = Files.writeString(testRoot.resolve("Use.groovy"), "class Use {}")
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined, documentProvider = documents)
        fun target(id: String, dir: Path, file: Path) = TargetDependencySnapshot(
            DependencyTarget(URI.create("test:$id"), root, id),
            DependencyRole.COMPILE,
            DependencyProvenance("test", 1),
            TargetDependencyState.COMPLETE,
            emptyList(),
            listOf(dir),
            listOf(file),
        )
        compilation.updateWorkspaceModel(
            root,
            emptyList(),
            listOf(mainRoot, testRoot),
            TargetDependencyEvidence.Scoped(
                listOf(target("main", mainRoot, main), target("test", testRoot, test)),
                enumerationComplete = true,
            ),
        )
        documents.put(main.toUri(), "class Main { void broken( }")
        documents.put(test.toUri(), "class Use {}")
        val result = compilation.compile(test.toUri(), "class Use {}")
        assertTrue(result.diagnostics.none { it.message.contains("Main.groovy") })
        val snapshot = assertNotNull(compilation.getCompiledSnapshot(test.toUri()))
        assertTrue(snapshot.parseResult.outcome.excludedSources.isEmpty())
    }

    @Test
    fun `ambiguous owners never inject a proven excluded open buffer`(): Unit = runBlocking {
        val sources = Files.createDirectories(root.resolve("src"))
        val shared = Files.writeString(sources.resolve("Shared.groovy"), "class Shared {}")
        val broken = Files.writeString(sources.resolve("Broken.groovy"), "class Broken {}")
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined, documentProvider = documents)
        fun target(id: String) = TargetDependencySnapshot(
            DependencyTarget(URI.create("test:$id"), root, id),
            DependencyRole.COMPILE,
            DependencyProvenance("test", 1),
            TargetDependencyState.COMPLETE,
            emptyList(),
            listOf(sources),
            listOf(shared),
        )
        compilation.updateWorkspaceModel(
            root,
            emptyList(),
            listOf(sources),
            TargetDependencyEvidence.Scoped(listOf(target("one"), target("two")), enumerationComplete = true),
        )
        documents.put(broken.toUri(), "class Broken { void broken( }")
        documents.put(shared.toUri(), "class Shared {}")
        val result = compilation.compile(shared.toUri(), "class Shared {}")
        assertTrue(result.diagnostics.none { it.message.contains("Broken.groovy") })
        val snapshot = assertNotNull(compilation.getCompiledSnapshot(shared.toUri()))
        assertTrue(snapshot.parseResult.outcome.excludedSources.isEmpty())

        // Force Groovy to ask the loader for the excluded name as well as keeping its buffer open.
        val referencing = "class Shared { Broken value }"
        documents.put(shared.toUri(), referencing)
        compilation.compile(shared.toUri(), referencing)
        val afterLookup = assertNotNull(compilation.getCompiledSnapshot(shared.toUri()))
        assertTrue(
            afterLookup.parseResult.compilationUnit.iterator().asSequence().none {
                !isStubSourceName(it.name) && it.name.contains("Broken.groovy")
            },
        )
        assertTrue("Broken" in afterLookup.parseResult.outcome.stubbedClasses)
        assertTrue(afterLookup.parseResult.outcome.excludedSources.isEmpty())
    }

    @Test
    fun `strategy-owned Jenkins source retains legacy unsaved buffer recovery`(): Unit = runBlocking {
        val sources = Files.createDirectories(root.resolve("src"))
        val jenkins = Files.writeString(sources.resolve("Jenkinsfile.groovy"), "class Build extends Helper.Fresh {}")
        val helper = Files.writeString(sources.resolve("Helper.groovy"), "class Helper {}")
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined, documentProvider = documents)
        val target = TargetDependencySnapshot(
            DependencyTarget(URI.create("test:main"), root, "main"),
            DependencyRole.COMPILE,
            DependencyProvenance("test", 1),
            TargetDependencyState.COMPLETE,
            emptyList(),
            listOf(sources),
            emptyList(),
        )
        compilation.updateWorkspaceModel(
            root,
            emptyList(),
            listOf(sources),
            TargetDependencyEvidence.Scoped(listOf(target), enumerationComplete = true),
        )
        val strategy = object : ProjectStrategy {
            override val id = "jenkins-test"
            override val displayName = "Jenkins test"
            override fun canHandle(workspaceRoot: Path, config: ServerConfiguration) = true
            override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null
            override fun overridesClasspath(uri: URI) = uri == jenkins.toUri()
            override fun getClasspathForFile(uri: URI, content: String, projectDependencies: List<Path>) =
                projectDependencies
        }
        val registry = ProjectStrategyRegistry(listOf(strategy))
        registry.selectStrategies(root, ServerConfiguration())
        compilation.workspaceManager.setStrategyRegistry(registry)
        documents.put(helper.toUri(), "class Helper { static class Fresh {} }")
        documents.put(jenkins.toUri(), "class Build extends Helper.Fresh {}")
        val result = compilation.compile(jenkins.toUri(), "class Build extends Helper.Fresh {}")
        assertTrue(result.diagnostics.none { it.message.contains("unable to resolve class Helper.Fresh") })
        assertNull(result.sourceMembershipKnown)
        assertNull(result.sourceVisibilityKnown)
        assertNull(result.toSourceModelDiagnostic())
    }
}
