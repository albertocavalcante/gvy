plugins {
    kotlin("jvm")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.slf4j.api)
    implementation(project(":parser:core"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
