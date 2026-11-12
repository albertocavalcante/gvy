// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

// TODO(#1603): Retire upstream Native bundle diagnostics before Gradle 10.
//   See: https://github.com/albertocavalcante/gvy/issues/1603
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    applyDefaultHierarchyTemplate {
        common {
            group("native") {
                group("posix") {
                    withMacos()
                    withLinux()
                }
            }
        }
    }
    listOf(macosArm64(), linuxX64(), mingwX64()).forEach { target ->
        target.binaries.executable {
            baseName = "gvy"
            entryPoint = "com.github.albertocavalcante.gvy.launcher.main"
        }
        val binary = target.binaries.getExecutable(NativeBuildType.RELEASE)
        val executable = binary.outputFile
        val payload =
            project(":gls")
                .layout.buildDirectory
                .file("editor-server/gls.jar")
                .get()
                .asFile
        val targetName = target.name
        tasks.register<Sync>("stage${target.name.replaceFirstChar { it.uppercase() }}Launcher") {
            group = "distribution"
            description = "Stage the ${target.name} launcher and current JVM payload for dogfooding."
            dependsOn(binary.linkTaskProvider, ":gls:stageEditorServer")
            from(executable) { rename { if (targetName == "mingwX64") "gvy.exe" else "gvy" } }
            from(payload) { rename { "gvy.jar" } }
            into(layout.buildDirectory.dir("install/${target.name}"))
            doFirst {
                check(executable.isFile && executable.canExecute()) {
                    "Missing $targetName executable; build the launcher on a compatible host."
                }
                check(payload.isFile && payload.length() > 0) { "Missing staged server JAR." }
            }
        }
    }
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

detekt {
    source.setFrom(fileTree("src") { include("**/*.kt") })
}
