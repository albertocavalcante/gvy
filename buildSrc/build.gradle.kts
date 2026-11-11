plugins {
    kotlin("jvm") version embeddedKotlinVersion
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation("org.snakeyaml:snakeyaml-engine:2.7")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation("io.gitlab.arturbosch.detekt:detekt-core:${libs.versions.detekt.get()}")
    testRuntimeOnly(libs.detekt.formatting)
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    compilerOptions.freeCompilerArgs.add("-Xjdk-release=17")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    maxHeapSize = "512m"
    systemProperty("gvy.detekt.sourceConfig", file("../tools/lint/detekt.yml").absolutePath)
    inputs.file("../tools/lint/detekt.yml")
}

// Every consumer compiles this build logic before configuration, including ordinary CI lint/build invocations.
tasks.jar {
    finalizedBy(tasks.test)
}
