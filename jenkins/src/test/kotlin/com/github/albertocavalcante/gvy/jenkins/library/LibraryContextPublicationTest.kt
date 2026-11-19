// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.library

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LibraryContextPublicationTest {
    private val document = LibraryDocumentIdentity(URI("file:///workspace/"), URI("file:///workspace/Jenkinsfile"))
    private val request = LibraryContextRequest(
        document,
        documentGeneration = 1,
        LibraryProfileIdentity("team", 1, "private-team", 1),
        LibraryAnalysisIdentity("native-v1", "java21-policy1", "index-v1"),
    )
    private val inputs = LibraryInputSnapshot(emptyList(), emptyList(), LibraryClosureStatus.UNKNOWN)

    @Test
    fun `only a current successful attempt publishes and current reads require the full request`() {
        val publication = LibraryContextPublication(document)
        val attempt = publication.begin(request)
        assertThat(publication.publishedFor(request)).isNull()
        assertThat(publication.publish(attempt, inputs)).isTrue()

        val snapshot = requireNotNull(publication.publishedFor(request))
        assertThat(snapshot.identity).isEqualTo(LibraryContextIdentity(request, inputs.fingerprint))
        assertThat(snapshot.inputs).isSameAs(inputs)
        assertThat(publication.publish(attempt, inputs)).isFalse()
        changedRequests().forEach { assertThat(publication.publishedFor(it)).isNull() }
    }

    @Test
    fun `late work cannot replace or clear a newer result even after an equal request returns`() {
        val publication = LibraryContextPublication(document)
        val old = publication.begin(request)
        publication.begin(request.copy(documentGeneration = 2))
        val current = publication.begin(request)
        assertThat(publication.publish(current, inputs)).isTrue()
        assertThat(publication.publish(old, inputs)).isFalse()
        publication.cancel(old)
        assertThat(publication.publishedFor(request)).isNotNull()
    }

    @Test
    fun `configuration authorization and analysis replacements fence old attempts`() {
        changedRequests().filter { it.document == document }.forEach { replacement ->
            val publication = LibraryContextPublication(document)
            val old = publication.begin(request)
            publication.begin(replacement)
            assertThat(publication.publish(old, inputs)).isFalse()
            assertThat(publication.publishedFor(request)).isNull()
        }
    }

    @Test
    fun `cancellation before or after publication revokes the current result`() {
        val publication = LibraryContextPublication(document)
        val canceled = publication.begin(request)
        publication.cancel(canceled)
        assertThat(publication.publish(canceled, inputs)).isFalse()
        assertThat(publication.publishedFor(request)).isNull()

        val completed = publication.begin(request)
        assertThat(publication.publish(completed, inputs)).isTrue()
        publication.cancel(completed)
        assertThat(publication.publishedFor(request)).isNull()
        assertThat(publication.publish(completed, inputs)).isFalse()
    }

    @Test
    fun `a new attempt clears old API until success and foreign attempts cannot publish`() {
        val publication = LibraryContextPublication(document)
        assertThat(publication.publish(publication.begin(request), inputs)).isTrue()
        publication.begin(request)
        assertThat(publication.publishedFor(request)).isNull()

        val foreign = LibraryContextPublication(document).begin(request)
        assertThat(publication.publish(foreign, inputs)).isFalse()
        assertThat(publication.publishedFor(request)).isNull()
    }

    @Test
    fun `workspace and document identities are mandatory boundaries`() {
        val publication = LibraryContextPublication(document)
        val otherDocuments = changedRequests().filter { it.document != document }
        otherDocuments.forEach { other ->
            assertThrows<IllegalArgumentException> { publication.begin(other) }
            assertThat(publication.publishedFor(other)).isNull()
        }
    }

    @Test
    fun `identities reject placeholder scope values and negative generations`() {
        val invalidIdentities: List<() -> Any> = listOf(
            { document.copy(workspaceRoot = URI("relative-root")) },
            { document.copy(documentUri = URI("relative-document")) },
            { request.copy(documentGeneration = -1) },
            { request.profile.copy(profileId = " ") },
            { request.profile.copy(configurationGeneration = -1) },
            { request.profile.copy(authorizationScopeId = "") },
            { request.profile.copy(authorizationGeneration = -1) },
            { request.analysis.copy(engine = "") },
            { request.analysis.copy(target = " ") },
            { request.analysis.copy(indexSchema = "") },
        )
        invalidIdentities.forEach { create -> assertThrows<IllegalArgumentException> { create() } }
    }

    @Test
    fun `deferred reads require the published content identity as well as the request`() {
        val publication = LibraryContextPublication(document)
        val original = LibraryContextIdentity(request, inputs.fingerprint)
        assertThat(publication.publish(publication.begin(request), inputs)).isTrue()
        assertThat(publication.current(original)).isNotNull()

        val changed = LibraryInputSnapshot(emptyList(), emptyList(), LibraryClosureStatus.PARTIAL)
        assertThat(publication.publish(publication.begin(request), changed)).isTrue()
        assertThat(publication.current(original)).isNull()
        assertThat(publication.current(LibraryContextIdentity(request, changed.fingerprint))).isNotNull()
    }

    @Test
    fun `the same alias in separate document profiles retains separate input identities`() {
        val otherDocument = document.copy(documentUri = URI("file:///workspace/SecondJenkinsfile"))
        val otherRequest = request.copy(
            document = otherDocument,
            profile = request.profile.copy(profileId = "other-team", authorizationScopeId = "other-scope"),
        )
        val firstInputs = libraryInputs("main", "first")
        val secondInputs = libraryInputs("release", "second")
        val first = LibraryContextPublication(document)
        val second = LibraryContextPublication(otherDocument)
        assertThat(first.publish(first.begin(request), firstInputs)).isTrue()
        assertThat(second.publish(second.begin(otherRequest), secondInputs)).isTrue()

        assertThat(requireNotNull(first.publishedFor(request)).identity)
            .isNotEqualTo(requireNotNull(second.publishedFor(otherRequest)).identity)
        assertThat(first.publishedFor(otherRequest)).isNull()
        assertThat(second.publishedFor(request)).isNull()
    }

    @Test
    fun `a delayed worker cannot overwrite a concurrently published current result`() {
        val publication = LibraryContextPublication(document)
        val old = publication.begin(request)
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val delayed = executor.submit<Boolean> {
                ready.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                publication.publish(old, inputs)
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            val replacement = request.copy(profile = request.profile.copy(authorizationGeneration = 2))
            val current = publication.begin(replacement)
            assertThat(publication.publish(current, inputs)).isTrue()
            release.countDown()

            assertThat(delayed.get(5, TimeUnit.SECONDS)).isFalse()
            assertThat(requireNotNull(publication.publishedFor(replacement)).request).isEqualTo(replacement)
            assertThat(publication.publishedFor(request)).isNull()
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun changedRequests(): List<LibraryContextRequest> = listOf(
        request.copy(document = document.copy(documentUri = URI("file:///workspace/Other.groovy"))),
        request.copy(document = document.copy(workspaceRoot = URI("file:///other-root/"))),
        request.copy(documentGeneration = 2),
        request.copy(profile = request.profile.copy(profileId = "other-team")),
        request.copy(profile = request.profile.copy(configurationGeneration = 2)),
        request.copy(profile = request.profile.copy(authorizationScopeId = "other-scope")),
        request.copy(profile = request.profile.copy(authorizationGeneration = 2)),
        request.copy(analysis = request.analysis.copy(engine = "openrewrite-v1")),
        request.copy(analysis = request.analysis.copy(target = "java17-policy1")),
        request.copy(analysis = request.analysis.copy(indexSchema = "index-v2")),
    )

    private fun libraryInputs(revision: String, content: String): LibraryInputSnapshot {
        val sources = LibrarySourceSnapshot(mapOf("vars/kitchen.groovy" to content.toByteArray()))
        val library = LibraryInputIdentity("kitchen-lib", "manual-profile", revision, sources.fingerprint)
        return LibraryInputSnapshot(listOf(library), emptyList(), LibraryClosureStatus.UNKNOWN)
    }
}
