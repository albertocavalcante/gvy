plugins {
    kotlin("jvm")
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":source"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
