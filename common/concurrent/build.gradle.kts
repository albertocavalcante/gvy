// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // With only a JVM target, common code may call JVM APIs. A second target makes the compiler reject them in
    // commonMain. These are the launcher's targets, so a native consumer can use the module; CI compiles and tests
    // them in the launcher job, the one job that provisions Kotlin/Native.
    macosArm64()
    linuxX64()
    mingwX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

// The plain detekt task scans only src/main and src/test by default; Kotlin Multiplatform sources are elsewhere.
detekt {
    source.setFrom(fileTree("src") { include("**/*.kt") })
}
