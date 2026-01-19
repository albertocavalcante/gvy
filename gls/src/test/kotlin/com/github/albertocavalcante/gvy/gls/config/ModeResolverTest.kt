package com.github.albertocavalcante.gvy.gls.config

import com.github.albertocavalcante.gvy.gls.project.JenkinsCapabilities
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

class ModeResolverTest {
    private val jenkinsFileUri = URI.create("file:///workspace/Jenkinsfile")
    private val groovyFileUri = URI.create("file:///workspace/src/Foo.groovy")

    @Test
    fun `resolveMode returns GROOVY when configured as GROOVY`() {
        val resolver = ModeResolver(configuredMode = GroovyMode.GROOVY)

        assertThat(resolver.resolveMode(jenkinsFileUri)).isEqualTo(GroovyMode.GROOVY)
        assertThat(resolver.resolveMode(groovyFileUri)).isEqualTo(GroovyMode.GROOVY)
    }

    @Test
    fun `resolveMode returns JENKINS when configured as JENKINS`() {
        val resolver = ModeResolver(configuredMode = GroovyMode.JENKINS)

        assertThat(resolver.resolveMode(jenkinsFileUri)).isEqualTo(GroovyMode.JENKINS)
        assertThat(resolver.resolveMode(groovyFileUri)).isEqualTo(GroovyMode.JENKINS)
    }

    @Test
    fun `resolveMode auto-detects Jenkins file when AUTO`() {
        val jenkinsCapabilities = mockk<JenkinsCapabilities> {
            every { isJenkinsFile(jenkinsFileUri) } returns true
            every { isJenkinsFile(groovyFileUri) } returns false
        }
        val resolver = ModeResolver(
            configuredMode = GroovyMode.AUTO,
            jenkinsCapabilities = jenkinsCapabilities,
        )

        assertThat(resolver.resolveMode(jenkinsFileUri)).isEqualTo(GroovyMode.JENKINS)
        assertThat(resolver.resolveMode(groovyFileUri)).isEqualTo(GroovyMode.GROOVY)
    }

    @Test
    fun `resolveMode returns GROOVY when AUTO and jenkinsCapabilities is null`() {
        val resolver = ModeResolver(
            configuredMode = GroovyMode.AUTO,
            jenkinsCapabilities = null,
        )

        assertThat(resolver.resolveMode(jenkinsFileUri)).isEqualTo(GroovyMode.GROOVY)
        assertThat(resolver.resolveMode(groovyFileUri)).isEqualTo(GroovyMode.GROOVY)
    }

    @Test
    fun `resolveMode returns GROOVY when AUTO and isJenkinsFile returns false`() {
        val jenkinsCapabilities = mockk<JenkinsCapabilities> {
            every { isJenkinsFile(any()) } returns false
        }
        val resolver = ModeResolver(
            configuredMode = GroovyMode.AUTO,
            jenkinsCapabilities = jenkinsCapabilities,
        )

        assertThat(resolver.resolveMode(jenkinsFileUri)).isEqualTo(GroovyMode.GROOVY)
        assertThat(resolver.resolveMode(groovyFileUri)).isEqualTo(GroovyMode.GROOVY)
    }

    @Test
    fun `isJenkinsModeEnabled returns true when mode resolves to JENKINS`() {
        val resolver = ModeResolver(configuredMode = GroovyMode.JENKINS)

        assertThat(resolver.isJenkinsModeEnabled(jenkinsFileUri)).isTrue()
        assertThat(resolver.isJenkinsModeEnabled(groovyFileUri)).isTrue()
    }

    @Test
    fun `isJenkinsModeEnabled returns false when mode resolves to GROOVY`() {
        val resolver = ModeResolver(configuredMode = GroovyMode.GROOVY)

        assertThat(resolver.isJenkinsModeEnabled(jenkinsFileUri)).isFalse()
        assertThat(resolver.isJenkinsModeEnabled(groovyFileUri)).isFalse()
    }

    @Test
    fun `isJenkinsModeEnabled respects auto-detection`() {
        val jenkinsCapabilities = mockk<JenkinsCapabilities> {
            every { isJenkinsFile(jenkinsFileUri) } returns true
            every { isJenkinsFile(groovyFileUri) } returns false
        }
        val resolver = ModeResolver(
            configuredMode = GroovyMode.AUTO,
            jenkinsCapabilities = jenkinsCapabilities,
        )

        assertThat(resolver.isJenkinsModeEnabled(jenkinsFileUri)).isTrue()
        assertThat(resolver.isJenkinsModeEnabled(groovyFileUri)).isFalse()
    }

    @Test
    fun `isGroovyOnlyMode returns true when GROOVY configured`() {
        val resolver = ModeResolver(configuredMode = GroovyMode.GROOVY)

        assertThat(resolver.isGroovyOnlyMode()).isTrue()
    }

    @Test
    fun `isGroovyOnlyMode returns false when JENKINS configured`() {
        val resolver = ModeResolver(configuredMode = GroovyMode.JENKINS)

        assertThat(resolver.isGroovyOnlyMode()).isFalse()
    }

    @Test
    fun `isGroovyOnlyMode returns false when AUTO configured`() {
        val resolver = ModeResolver(configuredMode = GroovyMode.AUTO)

        assertThat(resolver.isGroovyOnlyMode()).isFalse()
    }

    @Test
    fun `fromConfig creates resolver with correct mode`() {
        val jenkinsCapabilities = mockk<JenkinsCapabilities>()
        val config = mockk<ServerConfiguration> {
            every { groovyMode } returns GroovyMode.JENKINS
        }

        val resolver = ModeResolver.fromConfig(config, jenkinsCapabilities)

        assertThat(resolver.resolveMode(jenkinsFileUri)).isEqualTo(GroovyMode.JENKINS)
    }

    @Test
    fun `fromConfig creates resolver with null jenkinsCapabilities`() {
        val config = mockk<ServerConfiguration> {
            every { groovyMode } returns GroovyMode.GROOVY
        }

        val resolver = ModeResolver.fromConfig(config, null)

        assertThat(resolver.resolveMode(jenkinsFileUri)).isEqualTo(GroovyMode.GROOVY)
    }

    @Test
    fun `fromConfig creates resolver with AUTO mode and jenkinsCapabilities`() {
        val jenkinsCapabilities = mockk<JenkinsCapabilities> {
            every { isJenkinsFile(jenkinsFileUri) } returns true
        }
        val config = mockk<ServerConfiguration> {
            every { groovyMode } returns GroovyMode.AUTO
        }

        val resolver = ModeResolver.fromConfig(config, jenkinsCapabilities)

        assertThat(resolver.resolveMode(jenkinsFileUri)).isEqualTo(GroovyMode.JENKINS)
    }

    @Test
    fun `default constructor uses AUTO mode`() {
        val resolver = ModeResolver()

        // Without jenkinsCapabilities, AUTO defaults to GROOVY
        assertThat(resolver.resolveMode(jenkinsFileUri)).isEqualTo(GroovyMode.GROOVY)
    }

    @Test
    fun `multiple files can be resolved with same resolver`() {
        val jenkinsCapabilities = mockk<JenkinsCapabilities> {
            every { isJenkinsFile(jenkinsFileUri) } returns true
            every { isJenkinsFile(groovyFileUri) } returns false
        }
        val resolver = ModeResolver(
            configuredMode = GroovyMode.AUTO,
            jenkinsCapabilities = jenkinsCapabilities,
        )

        val file1Uri = URI.create("file:///workspace/Jenkinsfile")
        val file2Uri = URI.create("file:///workspace/src/App.groovy")
        val file3Uri = URI.create("file:///workspace/vars/myPipeline.groovy")

        every { jenkinsCapabilities.isJenkinsFile(file1Uri) } returns true
        every { jenkinsCapabilities.isJenkinsFile(file2Uri) } returns false
        every { jenkinsCapabilities.isJenkinsFile(file3Uri) } returns true

        assertThat(resolver.resolveMode(file1Uri)).isEqualTo(GroovyMode.JENKINS)
        assertThat(resolver.resolveMode(file2Uri)).isEqualTo(GroovyMode.GROOVY)
        assertThat(resolver.resolveMode(file3Uri)).isEqualTo(GroovyMode.JENKINS)
    }
}
