plugins {
    kotlin("jvm")
    // alias(libs.plugins.wire) // Disabled due to Gradle 9 incompatibility
}

val wireCompiler = configurations.create("wireCompiler")

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.wire.runtime)
    wireCompiler(libs.wire.compiler)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Manual Wire generation task
val generateProtos by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generates Kotlin code from Proto files using Wire"

    val outputDir = layout.buildDirectory.dir("generated/source/wire")
    val protoDir = layout.projectDirectory.dir("src/main/proto")

    inputs.dir(protoDir)
    outputs.dir(outputDir)

    classpath = wireCompiler
    mainClass.set("com.squareup.wire.WireCompiler")

    args(
        "--proto_path=${protoDir.asFile.absolutePath}",
        "--kotlin_out=${outputDir.get().asFile.absolutePath}",
        "com/github/albertocavalcante/reports/coverage.proto",
        "com/github/albertocavalcante/reports/results.proto",
    )
}

sourceSets.main {
    kotlin.srcDir(generateProtos)
}

// Ensure compilation depends on generation
tasks.compileKotlin {
    dependsOn(generateProtos)
}

tasks.test {
    useJUnitPlatform()
}

// Wire-generated protobuf models should not count toward coverage.
kover {
    reports {
        filters {
            excludes {
                classes("com.github.albertocavalcante.schemas.*")
            }
        }
    }
}

base {
    archivesName.set("schemas")
}
