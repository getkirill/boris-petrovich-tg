plugins {
    kotlin("jvm") version "2.2.10"
    id("com.gradleup.shadow") version "9.2.2"
    application
}

group = "dev.kraskaska"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("dev.inmo:tgbotapi:29.0.0")
    implementation("org.postgresql:postgresql:42.7.8")
    implementation("org.slf4j:slf4j-jdk14:2.0.17")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}
application {
    mainClass = "dev.kraskaska.boris.MainKt"
}