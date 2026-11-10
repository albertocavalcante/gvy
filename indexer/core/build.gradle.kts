plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

tasks.jar {
    archiveBaseName.set("groovy-indexer-core")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.logging)
    implementation(libs.kotlin.serialization.json)
    implementation(project(":parser:core"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // kotlin-logging needs SLF4J impl at test runtime
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
}
