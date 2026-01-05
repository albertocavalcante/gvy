plugins {
    kotlin("jvm")
}

tasks.jar {
    archiveBaseName.set("groovy-semantics-core")
}

dependencies {
    // Groovy AST for SemanticDocumentBuilder
    implementation(libs.groovy.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
