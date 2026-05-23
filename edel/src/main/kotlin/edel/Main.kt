package edel

import edel.codegen.Bytecodeerzeuger
import edel.fehler.Diagnose
import edel.fehler.DiagnoseSammler
import edel.fehler.LaufzeitFehler
import edel.fehler.NichtUnterstützt
import edel.fehler.QuellFehler
import edel.laufzeit.Interpreter
import edel.lexer.Lexer
import edel.parser.Bezeichner
import edel.parser.FunktionDeklaration
import edel.parser.Parser
import edel.parser.Programm
import edel.parser.SeiAnweisung
import edel.semantik.GlobaleSymbole
import edel.semantik.Typ
import edel.semantik.Parallelanalyse
import edel.semantik.Parallelplan
import edel.semantik.Resolver
import edel.semantik.Typpruefer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.system.exitProcess

const val VERSION = "0.1.0"

/** Ergebnis der statischen Analyse: Syntaxbaum, Symboltabelle, Parallelplan, Diagnosen. */
class AnalyseErgebnis(
    val programm: Programm?,
    val symbole: GlobaleSymbole?,
    val parallelplan: Parallelplan?,
    val diagnosen: List<Diagnose>,
    /** Statischer Typ jeder Namensverwendung (fuer Editor-Funktionen wie Hover). */
    val bezeichnerTypen: Map<Bezeichner, Typ> = emptyMap(),
    /** Statischer Typ jeder `sei`-/`ver`-Bindung. */
    val bindungsTypen: Map<SeiAnweisung, Typ> = emptyMap(),
) {
    val erfolgreich: Boolean get() = programm != null && diagnosen.isEmpty()
}

/**
 * Fuehrt die volle statische Pipeline fuer eine einzelne Quelle aus -- der
 * historische Einstieg fuer Tests und den Sprachserver. Die Quelle wird wie
 * ein Einstrich-Modul behandelt: kein `paket`, keine Importe.
 */
fun analysiere(quelle: String, datei: String? = null): AnalyseErgebnis =
    analysiereProjekt(mapOf((datei ?: "<inline>") to quelle), eintrag = datei ?: "<inline>")

/**
 * Mehrdatei-Pipeline: parst jedes Modul, qualifiziert Namen, fuehrt alle
 * Deklarationen zu einem einzigen [Programm] zusammen und laesst Resolver,
 * Typpruefer und Parallelanalyse darueber laufen. [eintrag] muss in
 * [quellen] vorhanden sein -- aus dieser Datei stammt die `start`-Funktion.
 */
fun analysiereProjekt(quellen: Map<String, String>, eintrag: String): AnalyseErgebnis {
    val diagnosen = DiagnoseSammler()
    val programme = LinkedHashMap<String, Programm>()
    for ((pfad, quelle) in quellen) {
        try {
            val tokens = Lexer(quelle, pfad).zerlege()
            programme[pfad] = Parser(tokens).parse()
        } catch (fehler: QuellFehler) {
            return AnalyseErgebnis(null, null, null, listOf(fehler.diagnose))
        }
    }
    val zusammen = Resolver.führeZusammen(programme, eintrag, diagnosen)
    val symbole = Resolver(zusammen, diagnosen).auflösen()
    val typpruefer = Typpruefer(zusammen, symbole, diagnosen)
    typpruefer.prüfe()
    val parallelplan = if (diagnosen.hatFehler) {
        Parallelplan(emptyMap())
    } else {
        Parallelanalyse(
            zusammen, symbole, typpruefer.bezeichnerTypen,
            typpruefer.binärTypen, typpruefer.bindungsTypen,
        ).analysiere()
    }
    return AnalyseErgebnis(
        zusammen, symbole, parallelplan, diagnosen.diagnosen,
        typpruefer.bezeichnerTypen, typpruefer.bindungsTypen,
    )
}

/** Analysiert und interpretiert Quelltext; nuetzlich fuer Tests. */
fun interpretiere(quelle: String, ausgabe: (String) -> Unit) {
    val ergebnis = analysiere(quelle)
    if (!ergebnis.erfolgreich) {
        throw QuellFehler(ergebnis.diagnosen.firstOrNull()
            ?: Diagnose("Unbekannter Analysefehler", edel.fehler.Position(0, 0)))
    }
    Interpreter(ergebnis.programm!!, ergebnis.parallelplan!!, ausgabe).starte()
}

fun main(argumente: Array<String>) {
    if (argumente.isEmpty()) {
        hilfe()
        exitProcess(1)
    }
    when (argumente[0]) {
        "starte" -> befehlStarte(argumente)
        "prüfe", "pruefe" -> befehlPrüfe(argumente)
        "übersetze", "uebersetze" -> befehlÜbersetze(argumente)
        "binär", "binaer" -> befehlBinär(argumente)
        "version" -> println("Edel $VERSION")
        "hilfe" -> hilfe()
        else -> {
            System.err.println("Unbekannter Befehl: '${argumente[0]}'")
            hilfe()
            exitProcess(1)
        }
    }
}

private fun leseEintragsdatei(argumente: Array<String>): File {
    if (argumente.size < 2) {
        System.err.println("Fehler: Es wurde keine Quelldatei angegeben.")
        exitProcess(1)
    }
    val datei = File(argumente[1])
    if (!datei.isFile) {
        System.err.println("Fehler: Datei nicht gefunden: '${argumente[1]}'")
        exitProcess(1)
    }
    return datei
}

/**
 * Sammelt alle Quelldateien des Projekts: die Einstiegsdatei plus jede Datei,
 * die ueber `importiere`-Direktiven transitiv erreicht wird. Andere `.edel`-
 * Dateien im Verzeichnisbaum bleiben unberuehrt -- so funktioniert eine
 * Einzeldatei in `beispiele/` weiter wie bisher.
 */
private fun sammleProjekt(eintrag: File): Map<String, String> {
    val absolut = eintrag.absoluteFile
    val eintragsProgramm = parsePaketUndImporte(absolut)
    val wurzel = quellwurzel(absolut, eintragsProgramm.paket)
    val gesammelt = LinkedHashMap<String, String>()
    gesammelt[absolut.path] = absolut.readText(Charsets.UTF_8)

    val zuLösen = ArrayDeque<Pair<String, File>>()
    for (fqn in eintragsProgramm.importe.values) zuLösen.addLast(fqn to absolut)

    while (zuLösen.isNotEmpty()) {
        val (fqn, bezug) = zuLösen.removeFirst()
        if (fqn.substringAfterLast('.') in Resolver.EINGEBAUTE_NAMEN) continue
        val datei = findeImportdatei(fqn, wurzel, bezug)
        if (datei.path !in gesammelt) {
            gesammelt[datei.path] = datei.readText(Charsets.UTF_8)
            val unter = parsePaketUndImporte(datei)
            for (weiter in unter.importe.values) zuLösen.addLast(weiter to datei)
        }
    }
    return gesammelt
}

/** Findet die `.edel`-Datei, die [fqn] deklariert (eindeutig, sonst Fehler). */
private fun findeImportdatei(fqn: String, wurzel: File, bezug: File): File {
    val paket = if ('.' in fqn) fqn.substringBeforeLast('.') else ""
    val kurzname = fqn.substringAfterLast('.')
    val verzeichnis = if (paket.isEmpty()) wurzel else File(wurzel, paket.replace('.', '/'))
    if (!verzeichnis.isDirectory) {
        System.err.println(
            "Fehler [${bezug.path}]: importierter Name '$fqn' nicht aufloesbar — " +
                "Verzeichnis '${verzeichnis.path}' fehlt",
        )
        exitProcess(1)
    }
    var gefunden: File? = null
    for (kand in verzeichnis.listFiles { f -> f.extension == "edel" } ?: emptyArray()) {
        val abs = kand.absoluteFile
        val info = parsePaketUndImporte(abs)
        if ((info.paket ?: "") != paket) continue
        if (info.deklarationen.none { it.name == kurzname }) continue
        if (gefunden != null && gefunden.path != abs.path) {
            System.err.println(
                "Fehler [${bezug.path}]: Import '$fqn' ist mehrdeutig — " +
                    "deklariert in '${gefunden.path}' und '${abs.path}'",
            )
            exitProcess(1)
        }
        gefunden = abs
    }
    if (gefunden == null) {
        System.err.println(
            "Fehler [${bezug.path}]: importierter Name '$fqn' wurde nicht gefunden",
        )
        exitProcess(1)
    }
    return gefunden
}

/** Klettert vom Verzeichnis der Einstiegsdatei so viele Ebenen hoch wie [paket] tief ist. */
private fun quellwurzel(eintrag: File, paket: String?): File {
    var dir = eintrag.parentFile ?: File(".").absoluteFile
    if (paket.isNullOrEmpty()) return dir
    val teile = paket.split('.').reversed()
    for (teil in teile) {
        if (dir.name != teil) {
            System.err.println(
                "Fehler: Datei '${eintrag.path}' liegt nicht im erwarteten Pfad fuer " +
                    "paket '$paket' (erwartet wurde '.../${paket.replace('.', '/')}/${eintrag.name}')",
            )
            exitProcess(1)
        }
        dir = dir.parentFile
            ?: error("Wurzelverzeichnis ist nicht zugaenglich (paket '$paket')")
    }
    return dir
}

/** Parst nur so viel, wie zum Sammeln noetig ist: `paket` und `importiere`. */
private fun parsePaketUndImporte(datei: File): Programm {
    val tokens = Lexer(datei.readText(Charsets.UTF_8), datei.path).zerlege()
    return try {
        Parser(tokens).parse()
    } catch (fehler: QuellFehler) {
        System.err.println(fehler.diagnose.formatiert())
        exitProcess(1)
    }
}

/** Bundle of CLI prep results: parsed/typed project plus entry-metadata. */
private class ProjektAnalyse(
    val ergebnis: AnalyseErgebnis,
    val eintrag: File,
    val eintragspaket: String?,
) {
    val eintragsStart: String =
        if (eintragspaket.isNullOrEmpty()) "start" else "$eintragspaket.start"
}

/** Sammelt das Projekt, analysiert es und beendet bei Fehlern. */
private fun analysiereDateiOderBeende(argumente: Array<String>): ProjektAnalyse {
    val eintrag = leseEintragsdatei(argumente).absoluteFile
    val quellen = sammleProjekt(eintrag)
    val eintragsPaket = parsePaketUndImporte(eintrag).paket
    val ergebnis = analysiereProjekt(quellen, eintrag.path)
    if (ergebnis.diagnosen.isNotEmpty()) {
        druckeDiagnosen(ergebnis.diagnosen)
        exitProcess(1)
    }
    return ProjektAnalyse(ergebnis, eintrag, eintragsPaket)
}

/** Stellt sicher, dass die Einstiegsfunktion [startFqn] parameterlos vorhanden ist. */
private fun prüfeStartVorhanden(programm: Programm, startFqn: String) {
    val start = programm.deklarationen.filterIsInstance<FunktionDeklaration>()
        .firstOrNull { it.name == startFqn }
    if (start == null || start.parameter.isNotEmpty()) {
        System.err.println(
            "Fehler: Das Programm braucht eine parameterlose Funktion 'start' " +
                "(erwartet als '${startFqn}').",
        )
        exitProcess(1)
    }
}

private fun befehlStarte(argumente: Array<String>) {
    val analyse = analysiereDateiOderBeende(argumente)
    prüfeStartVorhanden(analyse.ergebnis.programm!!, analyse.eintragsStart)
    try {
        Interpreter(
            analyse.ergebnis.programm,
            analyse.ergebnis.parallelplan!!,
            eintrag = analyse.eintragsStart,
        ).starte()
    } catch (fehler: LaufzeitFehler) {
        System.err.println("Laufzeitfehler: ${fehler.message}")
        exitProcess(1)
    }
}

private fun befehlPrüfe(argumente: Array<String>) {
    val ergebnis = analysiereDateiOderBeende(argumente).ergebnis
    println("Keine Fehler gefunden.")
    val plan = ergebnis.parallelplan
    if (plan != null && plan.anzahl > 0) {
        println()
        val wort = if (plan.anzahl == 1) "Schleife" else "Schleifen"
        println("${plan.anzahl} $wort werden automatisch parallelisiert:")
        for ((schleife, reduktion) in plan.reduktionen) {
            val akkus = reduktion.akkumulatoren.joinToString(", ") {
                "${it.name} (${if (it.operator == edel.lexer.TokenTyp.STERN) "*" else "+"})"
            }
            println("  [${schleife.position}] Reduktion ueber $akkus")
        }
    }
    if (plan != null && plan.streuAnzahl > 0) {
        println()
        val wort = if (plan.streuAnzahl == 1) "Streuschleife" else "Streuschleifen"
        println("${plan.streuAnzahl} $wort werden automatisch parallelisiert:")
        for ((schleife, streuung) in plan.streuungen) {
            println("  [${schleife.position}] parallel map -> ${streuung.zielListen.joinToString(", ")}")
        }
    }
    if (plan != null && plan.gabelAnzahl > 0) {
        println()
        val wort = if (plan.gabelAnzahl == 1) "Ausdruck" else "Ausdruecke"
        println("${plan.gabelAnzahl} unabhaengige(r) $wort werden per fork/join parallelisiert:")
        for (ausdruck in plan.gabeln.keys) {
            println("  [${ausdruck.position}] unabhaengige Operanden")
        }
    }
    if (plan != null && plan.gruppenAnzahl > 0) {
        println()
        val wort = if (plan.gruppenAnzahl == 1) "sei-Gruppe" else "sei-Gruppen"
        println("${plan.gruppenAnzahl} unabhaengige $wort werden nebenlaeufig berechnet:")
        for (gruppe in plan.gruppen.values) {
            val namen = gruppe.bindungen.joinToString(", ") { it.anweisung.name }
            println("  [${gruppe.bindungen.first().anweisung.position}] $namen")
        }
    }
}

/** Ergebnis der Bytecode-Uebersetzung: erzeugte Klassen samt FQN-Metadaten. */
private class Übersetzung(
    val quelldatei: File,
    /** Wurzelverzeichnis (entry-dir, ggf. um die paket-Tiefe hochgeklettert). */
    val quellwurzel: File,
    /** FQN der Hauptklasse, z. B. `app.main` oder `fibonacci`. */
    val hauptklasse: String,
    /** Erzeugte Klassen, FQN -> Bytes. */
    val klassen: Map<String, ByteArray>,
)

/** Uebersetzt das Projekt zu JVM-Bytecode -- inklusive Mehrdatei-Modulen. */
private fun kompiliereZuBytecode(argumente: Array<String>): Übersetzung {
    val analyse = analysiereDateiOderBeende(argumente)
    prüfeStartVorhanden(analyse.ergebnis.programm!!, analyse.eintragsStart)

    val quelldatei = analyse.eintrag
    val quellwurzel = quellwurzel(quelldatei, analyse.eintragspaket)
    val hauptklasse = if (analyse.eintragspaket.isNullOrEmpty()) {
        quelldatei.nameWithoutExtension
    } else {
        "${analyse.eintragspaket}.${quelldatei.nameWithoutExtension}"
    }
    val klassen = try {
        Bytecodeerzeuger(
            analyse.ergebnis.programm, analyse.ergebnis.symbole!!,
            hauptklasse, analyse.ergebnis.parallelplan!!,
            eintragsStart = analyse.eintragsStart,
        ).kompiliere()
    } catch (fehler: NichtUnterstützt) {
        System.err.println(fehler.diagnose.formatiert())
        System.err.println(
            "Das Bytecode-Backend deckt noch nicht den vollen Sprachumfang ab. " +
                "Dieses Programm laeuft weiterhin mit 'edel starte'.",
        )
        exitProcess(1)
    }
    return Übersetzung(quelldatei, quellwurzel, hauptklasse, klassen)
}

/** Schreibt eine Klasse `a.b.Foo` als `<wurzel>/a/b/Foo.class` (mit mkdirs). */
private fun schreibeKlasse(wurzel: File, fqn: String, bytes: ByteArray) {
    val ziel = File(wurzel, fqn.replace('.', '/') + ".class")
    ziel.parentFile?.mkdirs()
    ziel.writeBytes(bytes)
}

private fun befehlÜbersetze(argumente: Array<String>) {
    val ü = kompiliereZuBytecode(argumente)
    for ((fqn, bytes) in ü.klassen) schreibeKlasse(ü.quellwurzel, fqn, bytes)
    println("Erzeugt: ${ü.klassen.keys.joinToString(", ") { it.replace('.', '/') + ".class" }}")
    println("Ausfuehren mit:  java -cp \"${ü.quellwurzel.path}\" ${ü.hauptklasse}")
}

private fun befehlBinär(argumente: Array<String>) {
    val ü = kompiliereZuBytecode(argumente)
    val arbeitsverzeichnis = Files.createTempDirectory("edel-binaer").toFile()
    try {
        for ((fqn, bytes) in ü.klassen) schreibeKlasse(arbeitsverzeichnis, fqn, bytes)
        // Die Binaerdatei nimmt den Kurznamen der Einstiegsdatei -- ohne Punkte
        // bleibt der Pfad bedienbar.
        val zieldatei = File(ü.quelldatei.parentFile, ü.quelldatei.nameWithoutExtension)
        val nativeImage = findeNativeImage()
        println("Uebersetze '${ü.quelldatei.name}' mit GraalVM native-image ...")
        val exitcode = try {
            ProcessBuilder(
                nativeImage,
                "--no-fallback",
                "-cp", arbeitsverzeichnis.absolutePath,
                ü.hauptklasse,
                "-o", zieldatei.absolutePath,
            ).inheritIO().start().waitFor()
        } catch (fehler: IOException) {
            System.err.println("Fehler: 'native-image' konnte nicht gestartet werden (${fehler.message}).")
            System.err.println(
                "Bitte GraalVM mit 'native-image' installieren oder die Umgebungsvariable " +
                    "EDEL_NATIVE_IMAGE auf den Pfad setzen.",
            )
            exitProcess(1)
        }
        if (exitcode != 0) {
            System.err.println("Fehler: native-image schlug fehl (Exitcode $exitcode).")
            exitProcess(1)
        }
        println()
        println("Erzeugt: ${zieldatei.path}")
        println("Ausfuehren mit:  ${zieldatei.path}")
    } finally {
        arbeitsverzeichnis.deleteRecursively()
    }
}

/** Sucht das ausfuehrbare 'native-image' der installierten GraalVM. */
private fun findeNativeImage(): String {
    val kandidaten = listOfNotNull(
        System.getenv("EDEL_NATIVE_IMAGE"),
        System.getProperty("java.home")?.takeIf { it.isNotEmpty() }?.let { "$it/bin/native-image" },
        System.getenv("GRAALVM_HOME")?.let { "$it/bin/native-image" },
    )
    for (kandidat in kandidaten) {
        if (File(kandidat).canExecute()) return kandidat
    }
    return "native-image" // ueber den PATH aufloesen
}

private fun druckeDiagnosen(diagnosen: List<Diagnose>) {
    for (diagnose in diagnosen) {
        System.err.println(diagnose.formatiert())
    }
    val anzahl = diagnosen.size
    System.err.println(if (anzahl == 1) "1 Fehler." else "$anzahl Fehler.")
}

private fun hilfe() {
    println(
        """
        Edel $VERSION - eine Programmiersprache mit deutschen Schluesselwoertern.

        Aufruf:
          edel starte    <datei.edel>   Programm typpruefen und ausfuehren
          edel prüfe     <datei.edel>   Programm nur typpruefen
          edel übersetze <datei.edel>   Programm zu einer JVM-.class-Datei uebersetzen
          edel binär     <datei.edel>   Programm mit GraalVM zu einem nativen Programm uebersetzen
          edel version                  Versionsnummer anzeigen
          edel hilfe                     diese Hilfe anzeigen
        """.trimIndent(),
    )
}
