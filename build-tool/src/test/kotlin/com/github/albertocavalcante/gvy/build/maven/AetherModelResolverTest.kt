// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.maven

import io.mockk.every
import io.mockk.mockk
import org.apache.maven.model.Repository
import org.apache.maven.model.resolution.InvalidRepositoryException
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.model.resolution.UnresolvableModelException
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.ArtifactResolutionException
import org.eclipse.aether.resolution.ArtifactResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AetherModelResolverTest {

    @TempDir
    lateinit var tempDir: Path

    private val system = mockk<RepositorySystem>()
    private val session = mockk<RepositorySystemSession>()
    private val seen = mutableListOf<List<RemoteRepository>>()

    private fun resolver(vararg initial: RemoteRepository): AetherModelResolver {
        val pom = tempDir.resolve("some.pom").also { it.writeText("<project/>") }
        every { system.resolveArtifact(any(), any<ArtifactRequest>()) } answers {
            val request = secondArg<ArtifactRequest>()
            seen.add(request.repositories)
            ArtifactResult(request).setArtifact(request.artifact.setFile(pom.toFile()))
        }
        return AetherModelResolver(system, session, initial.toList())
    }

    private fun central() = RemoteRepository.Builder("central", "default", "https://repo.example/central").build()

    private fun repository(id: String, url: String?) = Repository().apply {
        this.id = id
        this.url = url
    }

    /** Resolves one model and reports the repositories the request carried. */
    private fun ModelResolver.repositoriesUsed(): List<RemoteRepository> {
        resolveModel("g", "a", "1")
        return seen.last()
    }

    @Test
    fun `an added repository is appended after the existing ones`() {
        val resolver = resolver(central())

        resolver.addRepository(repository("extra", "https://repo.example/extra"))

        assertEquals(
            listOf("central" to "https://repo.example/central", "extra" to "https://repo.example/extra"),
            resolver.repositoriesUsed().map { it.id to it.url },
        )
    }

    @Test
    fun `a repository with a known id is kept unless replace is requested`() {
        val resolver = resolver(central())

        resolver.addRepository(repository("central", "https://other.example/central"), false)
        assertEquals(listOf("https://repo.example/central"), resolver.repositoriesUsed().map { it.url })

        resolver.addRepository(repository("central", "https://other.example/central"), true)
        assertEquals(listOf("https://other.example/central"), resolver.repositoriesUsed().map { it.url })
    }

    @Test
    fun `a repository without a URL is rejected instead of failing later`() {
        val resolver = resolver(central())

        assertThrows<InvalidRepositoryException> { resolver.addRepository(repository("broken", null)) }
        assertThrows<InvalidRepositoryException> { resolver.addRepository(repository("blank", " "), true) }

        assertEquals(listOf("central"), resolver.repositoriesUsed().map { it.id })
    }

    @Test
    fun `a copy does not share later repositories with the original`() {
        val resolver = resolver(central())
        val copy = resolver.newCopy()

        copy.addRepository(repository("only-copy", "https://repo.example/copy"))

        assertEquals(listOf("central"), resolver.repositoriesUsed().map { it.id })
        assertEquals(listOf("central", "only-copy"), copy.repositoriesUsed().map { it.id })
    }

    @Test
    fun `an unresolvable artifact surfaces as an unresolvable model`() {
        val resolver = resolver(central())
        every { system.resolveArtifact(any(), any<ArtifactRequest>()) } answers {
            throw ArtifactResolutionException(listOf(ArtifactResult(secondArg<ArtifactRequest>())))
        }

        val failure = assertThrows<UnresolvableModelException> { resolver.resolveModel("g", "bom", "2") }

        assertTrue(failure.artifactId == "bom" && failure.version == "2", failure.message)
    }
}
