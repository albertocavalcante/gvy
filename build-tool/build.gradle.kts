// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Kotlin
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation(libs.kotlin.stdlib)

    // Logging
    implementation(libs.kotlin.logging)

    // Coroutines
    implementation(libs.kotlin.coroutines.core)

    // Gradle Tooling API
    implementation(libs.gradle.tooling.api)

    // Build Server Protocol (for Bazel, sbt, Mill support)
    implementation(libs.bsp4j)

    // JSON parsing for BSP connection files
    implementation(libs.kotlin.serialization.json)

    // Maven Embedder (for programmatic dependency resolution)
    implementation(libs.maven.embedder)
    implementation(libs.maven.compat)
    implementation(libs.maven.resolver.connector.basic)
    implementation(libs.maven.resolver.transport.http)
    implementation(libs.maven.resolver.supplier)

    // Testing
    testImplementation(libs.groovy.core)
    testImplementation(libs.groovy.ant)
    testImplementation("org.apache.groovy:groovy-xml:${libs.versions.groovy.get()}")
    testImplementation(libs.slf4j.api)
    testCompileOnly(libs.logback.classic)
    testCompileOnly("ch.qos.logback:logback-core:${libs.versions.logback.get()}")
    testRuntimeOnly(libs.logback.classic)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)

    // Detekt Formatting
    detektPlugins(libs.detekt.formatting)
}

// Exclude BSP client from coverage - requires real BSP server (integration test)
// NOTE: BspConnectionDetails and BspBuildTool are tested via unit tests.
// BspClient requires a running BSP server process to test properly.
kover {
    reports {
        filters {
            excludes {
                classes("*BspClient*")
            }
        }
    }
}

// The standalone Maven BSP server. MavenBspEquivalenceTest launches it as a child process over stdio, so the test
// crosses the same JSON-RPC boundary an editor does. The server never depends on build-tool, so there is no cycle.
val mavenBspServer =
    configurations.create("mavenBspServer") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "gvy-maven-bsp-server"))
        }
    }
dependencies {
    add(mavenBspServer.name, project(":bsp:maven-bsp"))
}

/** The server JAR and the reused fixtures are inputs by content; their absolute paths stay out of cache keys. */
abstract class MavenBspEquivalenceArguments : CommandLineArgumentProvider {
    @get:Classpath
    abstract val serverJar: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtures: DirectoryProperty

    override fun asArguments(): List<String> =
        listOf(
            "-Dgvy.mavenBsp.serverJar=${serverJar.singleFile.absolutePath}",
            "-Dgvy.mavenBsp.fixtures=${fixtures.get().asFile.absolutePath}",
        )
}

/** Machine-specific paths for the real Tooling API test; internal so they never affect cache keys. */
abstract class ScopedGradleTestArguments : CommandLineArgumentProvider {
    @get:Internal
    abstract val installation: DirectoryProperty

    @get:Internal
    abstract val userHome: DirectoryProperty

    override fun asArguments(): List<String> =
        listOfNotNull(
            installation.orNull?.let { "-Dgvy.gradle.scoped.test.installation=${it.asFile.absolutePath}" },
            "-Dgvy.gradle.scoped.test.home=${userHome.get().asFile.absolutePath}",
        )
}

// This policy is test-only; it is not packaged in the reusable library.
sourceSets.named("test") {
    resources.srcDir(rootProject.file("tools/test-logging/src/test/resources"))
}

tasks.test {
    useJUnitPlatform()
    // The older-Gradle check reads this variable; as an input it keeps a skipped run's cached result from
    // standing in for a run that should have tested those versions (#1376)
    inputs.property(
        "scopedGradleVersions",
        providers.environmentVariable("GVY_SCOPED_TEST_GRADLE_VERSIONS").orElse(""),
    )
    jvmArgumentProviders.add(
        objects.newInstance<ScopedGradleTestArguments>().apply {
            gradle.gradleHomeDir?.let { installation.set(it) }
            userHome.set(layout.buildDirectory.dir("scoped-gradle-test-home"))
        },
    )
    jvmArgumentProviders.add(
        objects.newInstance<MavenBspEquivalenceArguments>().apply {
            serverJar.from(mavenBspServer)
            fixtures.set(layout.projectDirectory.dir("../bsp/maven-bsp/src/test/resources/fixtures"))
        },
    )
}
