import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    application
    // Offizielles GraalVM-Plugin: liefert die Aufgaben nativeCompile / nativeRun
    // und uebersetzt das Werkzeug aus dem echten Klassenpfad zu einem Binaerprogramm.
    id("org.graalvm.buildtools.native") version "1.1.0"
}

group = "edel"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Eigener Quellsatz fuer den Sprachserver (LSP). Er haengt vom Kompilat des
// Hauptquellsatzes ab, wird aber getrennt uebersetzt und verpackt -- so geraet
// die lsp4j-Abhaengigkeit nicht in das native edel-Werkzeug.
sourceSets {
    create("lsp") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Eclipse LSP4J: die Standard-Bibliothek fuer das Language Server Protocol.
    "lspImplementation"("org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0")
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

// ---------------------------------------------------------------------------
// Eigenstaendiges, ausfuehrbares Jar
// ---------------------------------------------------------------------------
// Ein Uber-Jar mit eingebetteter Kotlin-Standardbibliothek (ohne Shadow-Plugin,
// damit keine weitere Drittanbieter-Abhaengigkeit noetig ist). Edel besitzt
// keine SPI-Dienste, daher genuegt es, Signaturen und module-info auszuschliessen.
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Baut ein eigenstaendiges edel.jar mit eingebetteter Kotlin-Standardbibliothek."
    archiveFileName = "edel.jar"
    destinationDirectory = layout.buildDirectory.dir("libs")
    manifest {
        attributes(
            "Main-Class" to "edel.MainKt",
            "Implementation-Title" to "Edel",
            "Implementation-Version" to project.version.toString(),
        )
    }
    // Reproduzierbare, stabile Jars (gleiche Eingabe -> gleiche Bytes).
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Signaturdateien und Modul-Deskriptoren der Abhaengigkeiten wuerden das
    // zusammengefuehrte Jar ungueltig machen.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/SIG-*")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
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

// ---------------------------------------------------------------------------
// Sprachserver (LSP) als eigenstaendiges Jar
// ---------------------------------------------------------------------------
// Buendelt Compiler-Kern, Sprachserver und lsp4j zu edel-lsp.jar. Der Server
// laeuft als JVM-Prozess (`java -jar edel-lsp.jar`) und spricht ueber stdin/stdout.
tasks.register<Jar>("lspJar") {
    group = "build"
    description = "Baut den Edel-Sprachserver (LSP) als eigenstaendiges edel-lsp.jar."
    archiveFileName = "edel-lsp.jar"
    destinationDirectory = layout.buildDirectory.dir("libs")
    manifest {
        attributes(
            "Main-Class" to "edel.lsp.SprachserverKt",
            "Implementation-Title" to "Edel Language Server",
            "Implementation-Version" to project.version.toString(),
        )
    }
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/SIG-*")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    from(sourceSets["main"].output)
    from(sourceSets["lsp"].output)
    dependsOn(configurations["lspRuntimeClasspath"])
    from({
        configurations["lspRuntimeClasspath"]
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}

// ---------------------------------------------------------------------------
// Natives Binaerprogramm (GraalVM Native Build Tools)
// ---------------------------------------------------------------------------
// Das Plugin stellt die Aufgaben `nativeCompile` (Binaerprogramm bauen) und
// `nativeRun` (bauen und starten) bereit. Das Ergebnis liegt unter
// build/native/nativeCompile/edel.
graalvmNative {
    // Die Gradle-JVM ist bereits ein GraalVM-JDK (GRAALVM_HOME ist gesetzt);
    // es muss daher keine separate Toolchain gesucht werden.
    toolchainDetection = false
    // Edel haengt nur von der Kotlin-Standardbibliothek ab; es werden keine
    // Erreichbarkeits-Metadaten aus dem GraalVM-Repository benoetigt.
    metadataRepository {
        enabled = false
    }
    binaries {
        named("main") {
            imageName = "edel"
            mainClass = "edel.MainKt"
            buildArgs.add("--no-fallback")
        }
    }
}
