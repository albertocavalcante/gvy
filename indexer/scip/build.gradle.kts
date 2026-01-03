plugins {
    kotlin("jvm")
    // alias(libs.plugins.wire) // Disabled due to Gradle 9 incompatibility
}

val wireCompiler = configurations.create("wireCompiler")

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.wire.runtime)
    implementation(project(":indexer:core"))
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
    val protoFile = protoDir.file("scip.proto")

    inputs.dir(protoDir)
    outputs.dir(outputDir)

    classpath = wireCompiler
    mainClass.set("com.squareup.wire.WireCompiler")

    args(
        "--proto_path=${protoDir.asFile.absolutePath}",
        "--kotlin_out=${outputDir.get().asFile.absolutePath}",
        protoFile.asFile.name, // Source file relative to proto_path
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
