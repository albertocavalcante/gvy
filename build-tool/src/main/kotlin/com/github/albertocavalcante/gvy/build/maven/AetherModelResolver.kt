// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.maven

import org.apache.maven.model.Dependency
import org.apache.maven.model.Parent
import org.apache.maven.model.Repository
import org.apache.maven.model.building.FileModelSource
import org.apache.maven.model.building.ModelSource2
import org.apache.maven.model.resolution.InvalidRepositoryException
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.model.resolution.UnresolvableModelException
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactRequest
import org.eclipse.aether.resolution.ArtifactResolutionException

/**
 * Resolves parent POMs and import-scope BOMs for the embedded Maven model builder through Aether, so the
 * effective model carries the same managed versions Maven would apply.
 */
internal class AetherModelResolver(
    private val system: RepositorySystem,
    private val session: RepositorySystemSession,
    repositories: List<RemoteRepository>,
) : ModelResolver {
    private val repositories = repositories.toMutableList()

    override fun resolveModel(groupId: String, artifactId: String, version: String): ModelSource2 {
        val artifact = DefaultArtifact(groupId, artifactId, "", "pom", version)
        val request = ArtifactRequest(artifact, repositories.toList(), null)
        val file = try {
            system.resolveArtifact(session, request).artifact?.file
        } catch (e: ArtifactResolutionException) {
            throw UnresolvableModelException(e.message, groupId, artifactId, version, e)
        }
        if (file == null) throw UnresolvableModelException("No POM file for $artifact", groupId, artifactId, version)
        return FileModelSource(file)
    }

    override fun resolveModel(parent: Parent): ModelSource2 =
        resolveModel(parent.groupId, parent.artifactId, parent.version)

    override fun resolveModel(dependency: Dependency): ModelSource2 =
        resolveModel(dependency.groupId, dependency.artifactId, dependency.version)

    override fun addRepository(repository: Repository) {
        addRepository(repository, false)
    }

    @Throws(InvalidRepositoryException::class)
    override fun addRepository(repository: Repository, replace: Boolean) {
        val existing = repositories.indexOfFirst { it.id == repository.id }
        if (existing >= 0 && !replace) return
        val remote = RemoteRepository.Builder(repository.id, repository.layout ?: "default", repository.url).build()
        if (existing >= 0) repositories[existing] = remote else repositories.add(remote)
    }

    override fun newCopy(): ModelResolver = AetherModelResolver(system, session, repositories)
}
