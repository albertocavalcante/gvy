plugins {
    kotlin("jvm")
}

tasks.jar {
    archiveBaseName.set("groovy-semantics-core")
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
