// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    kotlin("jvm")
}

kotlin {
    explicitApi()
}

// The Kotlin compiler at the project's Kotlin version, used only by the compile-fail check below.
val compileFailCompiler =
    configurations.register("compileFailCompiler") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    add(compileFailCompiler.name, libs.kotlin.compiler.embeddable)
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Compiles a well-typed control and a file that mixes coordinate units or version kinds against the main classes,
 * as a separate module (so internal constructors are invisible). The control must compile; the
 * mixed file must fail with an error on every line marked `// <n>:` and on no other line.
 */
abstract class VerifyCompileFailures
    @Inject
    constructor(
        private val execOperations: ExecOperations,
    ) : DefaultTask() {
        @get:Classpath
        abstract val compilerClasspath: ConfigurableFileCollection

        @get:Classpath
        abstract val consumerClasspath: ConfigurableFileCollection

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val wellTyped: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val mixedFile: RegularFileProperty

        @get:Nested
        abstract val launcher: Property<JavaLauncher>

        @get:OutputDirectory
        abstract val workDirectory: DirectoryProperty

        @TaskAction
        fun verify() {
            val work = workDirectory.get().asFile
            work.deleteRecursively()
            work.mkdirs()

            val control = compile(wellTyped.get().asFile, work.resolve("Well.kt"), work.resolve("well"))
            if (control.exitCode != 0) {
                throw GradleException("The well-typed control must compile:\n${control.output}")
            }

            val mixedSource = mixedFile.get().asFile
            val mixed = compile(mixedSource, work.resolve("Mixed.kt"), work.resolve("mixed"))
            if (mixed.exitCode == 0) throw GradleException("The mixed file compiled; the types it mixes can be mixed")

            val expectedLines =
                mixedSource
                    .readLines()
                    .withIndex()
                    .filter { (_, text) -> Regex("""// \d+:""").containsMatchIn(text) }
                    .map { (index, _) -> index + 1 }
                    .toSortedSet()
            // kotlinc prints "Mixed.kt:12:5: error: ..."; the embedded CLI prints "e: file:///.../Mixed.kt:12:5 ...".
            val location = Regex("""Mixed\.kt:(\d+):\d+""")
            val errorLines =
                mixed.output
                    .lineSequence()
                    .filter { it.startsWith("e: ") || it.contains(": error: ") }
                    .mapNotNull { line -> location.find(line)?.let { match -> match.groupValues[1].toInt() } }
                    .toSortedSet()
            if (expectedLines.isEmpty() || errorLines != expectedLines) {
                throw GradleException(
                    "Expected errors on lines $expectedLines of ${mixedSource.name}, got $errorLines:\n${mixed.output}",
                )
            }
            logger.lifecycle("Compile-fail check: ${expectedLines.size} statements fail, the control compiles")
        }

        private class Compilation(
            val exitCode: Int,
            val output: String,
        )

        private fun compile(
            source: java.io.File,
            copy: java.io.File,
            destination: java.io.File,
        ): Compilation {
            source.copyTo(copy, overwrite = true)
            val output = ByteArrayOutputStream()
            val java = launcher.get().executablePath.asFile
            val result =
                execOperations.javaexec {
                    executable = java.absolutePath
                    classpath = compilerClasspath
                    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
                    maxHeapSize = "512m"
                    args(
                        "-no-stdlib",
                        "-no-reflect",
                        "-nowarn",
                        "-classpath",
                        consumerClasspath.asPath,
                        "-d",
                        destination.absolutePath,
                        copy.absolutePath,
                    )
                    standardOutput = output
                    errorOutput = output
                    isIgnoreExitValue = true
                }
            return Compilation(result.exitValue, output.toString(Charsets.UTF_8))
        }
    }

val verifyCoordinateUnitCompileFailures =
    tasks.register<VerifyCompileFailures>("verifyCoordinateUnitCompileFailures") {
        description = "Prove that coordinate units, bases and raw Ints do not mix at compile time"
        group = "verification"
        compilerClasspath.from(compileFailCompiler)
        consumerClasspath.from(sourceSets.main.map { it.output }, configurations.named("compileClasspath"))
        wellTyped.set(layout.projectDirectory.file("src/test/resources/compile-fail/WellTypedCoordinates.kt.txt"))
        mixedFile.set(layout.projectDirectory.file("src/test/resources/compile-fail/MixedCoordinateUnits.kt.txt"))
        launcher.set(javaToolchains.launcherFor(java.toolchain))
        workDirectory.set(layout.buildDirectory.dir("compile-fail"))
    }

val verifyVersionKindCompileFailures =
    tasks.register<VerifyCompileFailures>("verifyVersionKindCompileFailures") {
        description = "Prove that document versions, generations and epochs do not mix at compile time"
        group = "verification"
        compilerClasspath.from(compileFailCompiler)
        consumerClasspath.from(sourceSets.main.map { it.output }, configurations.named("compileClasspath"))
        wellTyped.set(layout.projectDirectory.file("src/test/resources/compile-fail/WellTypedVersions.kt.txt"))
        mixedFile.set(layout.projectDirectory.file("src/test/resources/compile-fail/MixedVersionKinds.kt.txt"))
        launcher.set(javaToolchains.launcherFor(java.toolchain))
        workDirectory.set(layout.buildDirectory.dir("compile-fail-versions"))
    }

tasks.check {
    dependsOn(verifyCoordinateUnitCompileFailures, verifyVersionKindCompileFailures)
}
