plugins {
    kotlin("jvm") version "2.2.10"
}

group = "dev.kraskaska"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("dev.inmo:tgbotapi:29.0.0")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}