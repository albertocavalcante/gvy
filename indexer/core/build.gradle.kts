plugins {
    kotlin("jvm")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(project(":parser:core"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
