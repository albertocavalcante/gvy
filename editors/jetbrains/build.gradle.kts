// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import groovy.json.JsonSlurper
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.20.1"
    id("com.diffplug.spotless") version "8.11.0"
}

group = "org.gvy"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("gvyLocalIde").orNull
        if (localIde == null) {
            intellijIdea("2025.3.1")
        } else {
            val supplied = file(localIde)
            val app =
                if (supplied.name == "Contents" && supplied.parentFile.name.endsWith(".app")) {
                    supplied.parentFile
                } else {
                    supplied
                }
            val infoFile =
                if (app.name.endsWith(".app")) {
                    app.resolve("Contents/Resources/product-info.json")
                } else {
                    app.resolve("product-info.json")
                }
            val info = JsonSlurper().parse(infoFile) as? Map<*, *>
            val productCode = info?.get("productCode")
            val buildNumber = info?.get("buildNumber")
            require(productCode == "IU" && buildNumber == "253.29346.138") {
                "gvyLocalIde must identify IntelliJ IDEA 2025.3.1 build 253.29346.138"
            }
            local(app)
        }
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        languageVersion = KotlinVersion.KOTLIN_2_2
        apiVersion = KotlinVersion.KOTLIN_2_2
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253.29346.138"
            untilBuild = "262.*"
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2025.3.1")
            create(IntelliJPlatformType.IntellijIdea, "2026.1.4")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.5")
        }
    }
}

// This build is not a root subproject, so it owns its own formatter targets.
spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.7.1")
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.7.1")
    }
}

intellijPlatformTesting.testIde.register("testIde20262") {
    type = IntelliJPlatformType.IntellijIdea
    version = "2026.2.5"
    testFramework(TestFrameworkType.Platform)
}

// Dynamic unloading changes application-global state. Each task owns a fresh JVM and sandbox, and selects only
// the reflection-only acceptance fixture. Supply local SDKs to avoid provisioning another IDE distribution.
val nativeUnloadTasks = mapOf("testNativeUnload253" to "253", "testNativeUnload262" to "262")
val productionOutput = sourceSets.main.get().output
nativeUnloadTasks.forEach { (taskName, baseline) ->
    intellijPlatformTesting.testIde.register(taskName) {
        type = IntelliJPlatformType.IntellijIdea
        version = if (baseline == "253") "2025.3.1" else "2026.2.5"
        providers.gradleProperty("gvyNativeUnloadIde$baseline").orNull?.let { localPath = file(it) }
        testFramework(TestFrameworkType.Platform)
        task {
            include("org/gvy/jetbrains/qa/GvyDynamicUnloadTest.class")
            maxParallelForks = 1
            forkEvery = 1
            systemProperty("gvy.test.nativeUnload", "true")
            systemProperty("gvy.test.nativeUnloadBaseline", baseline)
            systemProperty("idea.force.use.core.classloader", "true")
            systemProperty("idea.use.core.classloader.for.plugin.path", "false")
            doFirst {
                // Bundled classpath descriptors need the core loader. GVY is discovered from the sandbox instead.
                // Apply after the platform Gradle plugin has assembled its flattened test classpath.
                systemProperty("idea.force.use.core.classloader", "true")
                systemProperty("idea.use.core.classloader.for.plugin.path", "false")
                // The sandbox provider advertises the same plugin through two discovery paths. In 262 these
                // become distinct descriptor identities, so unloading one leaves the other in the module graph.
                // Keep directory discovery through idea.plugins.path and override the duplicate explicit path last.
                jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dplugin.path=") })
                val pluginRoot =
                    pluginDirectory
                        .get()
                        .asFile
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                val productionPaths = productionOutput.files.map { it.toPath().toAbsolutePath().normalize() }.toSet()
                val providerClass = "org/gvy/jetbrains/GvyServerSupportProvider.class"
                classpath =
                    classpath.filter { entry ->
                        val path = entry.toPath().toAbsolutePath().normalize()
                        when {
                            path.startsWith(pluginRoot) || path in productionPaths -> false
                            entry.isDirectory -> !entry.resolve(providerClass).exists()
                            entry.extension == "jar" -> ZipFile(entry).use { it.getEntry(providerClass) == null }
                            else -> true
                        }
                    }
                require(
                    listOf("serverJar", "serverSha256", "serverCommit").all {
                        !systemProperties["gvy.test.$it"]?.toString().isNullOrBlank()
                    },
                ) {
                    "Native unload acceptance requires the pinned real-server JAR, SHA-256 and source commit."
                }
            }
        }
    }
}

// Optional locally; CI supplies all three and the test validates the JAR's embedded source identity.
val realServerProperties =
    mapOf(
        "serverJar" to providers.gradleProperty("gvyTestServerJar").orNull,
        "serverSha256" to providers.gradleProperty("gvyTestServerSha256").orNull,
        "serverCommit" to providers.gradleProperty("gvyTestServerCommit").orNull,
    )
require(realServerProperties.values.all { it == null } || realServerProperties.values.all { it != null }) {
    "Supply gvyTestServerJar, gvyTestServerSha256 and gvyTestServerCommit together."
}
tasks.withType<Test>().configureEach {
    if (name !in nativeUnloadTasks) exclude("org/gvy/jetbrains/qa/GvyDynamicUnloadTest*")
    realServerProperties.forEach { (name, value) ->
        if (value != null) systemProperty("gvy.test.$name", value)
    }
}
