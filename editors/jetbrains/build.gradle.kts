// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

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
        intellijIdea("2025.3.1")
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

val testIde20262 by intellijPlatformTesting.testIde.registering {
    type = IntelliJPlatformType.IntellijIdea
    version = "2026.2.5"
    testFramework(TestFrameworkType.Platform)
}
