import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    application
}

group = "edel"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
    // Kotlin 2.2.x erzeugt Bytecode bis JVM 24; das Jar laeuft auf JVM 24/25+.
    compilerOptions {
        jvmTarget = JvmTarget.JVM_24
    }
}

// Die (leere) Java-Kompilierung auf dasselbe Ziel wie Kotlin festlegen.
java {
    sourceCompatibility = JavaVersion.VERSION_24
    targetCompatibility = JavaVersion.VERSION_24
}

application {
    mainClass = "edel.MainKt"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Eigenstaendiges, ausfuehrbares Jar (ersetzt das Shadow-Plugin, damit der Build
// offline und ohne Drittanbieter-Plugin funktioniert).
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Baut ein eigenstaendiges edel.jar mit eingebetteter Kotlin-Standardbibliothek."
    archiveFileName = "edel.jar"
    destinationDirectory = layout.buildDirectory.dir("libs")
    manifest {
        attributes["Main-Class"] = "edel.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}

tasks.named("build") {
    dependsOn("fatJar")
}
