package edel

import edel.codegen.Bytecodeerzeuger
import edel.fehler.Diagnose
import edel.fehler.DiagnoseSammler
import edel.fehler.LaufzeitFehler
import edel.fehler.NichtUnterstützt
import edel.fehler.QuellFehler
import edel.laufzeit.Interpreter
import edel.lexer.Lexer
import edel.parser.FunktionDeklaration
import edel.parser.Parser
import edel.parser.Programm
import edel.semantik.GlobaleSymbole
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
) {
    val erfolgreich: Boolean get() = programm != null && diagnosen.isEmpty()
}

/**
 * Fuehrt die volle statische Pipeline aus: Lexer, Parser, Resolver, Typpruefer
 * und Parallelanalyse. Lexer-/Parserfehler werden als einzelne Diagnose gemeldet.
 */
fun analysiere(quelle: String): AnalyseErgebnis {
    val programm = try {
        val tokens = Lexer(quelle).zerlege()
        Parser(tokens).parse()
    } catch (fehler: QuellFehler) {
        return AnalyseErgebnis(null, null, null, listOf(fehler.diagnose))
    }
    val diagnosen = DiagnoseSammler()
    val symbole = Resolver(programm, diagnosen).auflösen()
    val typpruefer = Typpruefer(programm, symbole, diagnosen)
    typpruefer.prüfe()
    val parallelplan = if (diagnosen.hatFehler) {
        Parallelplan(emptyMap())
    } else {
        Parallelanalyse(programm, symbole, typpruefer.bezeichnerTypen).analysiere()
    }
    return AnalyseErgebnis(programm, symbole, parallelplan, diagnosen.diagnosen)
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

private fun leseDatei(argumente: Array<String>): String {
    if (argumente.size < 2) {
        System.err.println("Fehler: Es wurde keine Quelldatei angegeben.")
        exitProcess(1)
    }
    val datei = File(argumente[1])
    if (!datei.isFile) {
        System.err.println("Fehler: Datei nicht gefunden: '${argumente[1]}'")
        exitProcess(1)
    }
    return datei.readText(Charsets.UTF_8)
}

/** Analysiert die angegebene Datei und beendet das Programm bei Fehlern. */
private fun analysiereDateiOderBeende(argumente: Array<String>): AnalyseErgebnis {
    val ergebnis = analysiere(leseDatei(argumente))
    if (ergebnis.diagnosen.isNotEmpty()) {
        druckeDiagnosen(ergebnis.diagnosen)
        exitProcess(1)
    }
    return ergebnis
}

/** Stellt sicher, dass eine parameterlose Funktion 'start' vorhanden ist. */
private fun prüfeStartVorhanden(programm: Programm) {
    val start = programm.deklarationen.filterIsInstance<FunktionDeklaration>()
        .firstOrNull { it.name == "start" }
    if (start == null || start.parameter.isNotEmpty()) {
        System.err.println("Fehler: Das Programm braucht eine parameterlose Funktion 'start'.")
        exitProcess(1)
    }
}

private fun befehlStarte(argumente: Array<String>) {
    val ergebnis = analysiereDateiOderBeende(argumente)
    prüfeStartVorhanden(ergebnis.programm!!)
    try {
        Interpreter(ergebnis.programm, ergebnis.parallelplan!!).starte()
    } catch (fehler: LaufzeitFehler) {
        System.err.println("Laufzeitfehler: ${fehler.message}")
        exitProcess(1)
    }
}

private fun befehlPrüfe(argumente: Array<String>) {
    val quelle = leseDatei(argumente)
    val ergebnis = analysiere(quelle)
    if (ergebnis.diagnosen.isEmpty()) {
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
    } else {
        druckeDiagnosen(ergebnis.diagnosen)
        exitProcess(1)
    }
}

/** Uebersetzt die Quelldatei zu JVM-Bytecode und liefert (Quelldatei, Klassenbytes). */
private fun kompiliereZuBytecode(argumente: Array<String>): Pair<File, ByteArray> {
    val ergebnis = analysiereDateiOderBeende(argumente)
    prüfeStartVorhanden(ergebnis.programm!!)
    val quelldatei = File(argumente[1]).absoluteFile
    val klassenname = quelldatei.nameWithoutExtension
    val bytes = try {
        Bytecodeerzeuger(
            ergebnis.programm, ergebnis.symbole!!, klassenname, ergebnis.parallelplan!!,
        ).kompiliere()
    } catch (fehler: NichtUnterstützt) {
        System.err.println(fehler.diagnose.formatiert())
        System.err.println(
            "Das Bytecode-Backend deckt erst den Sprachkern ab. " +
                "Dieses Programm laeuft weiterhin mit 'edel starte'.",
        )
        exitProcess(1)
    }
    return quelldatei to bytes
}

private fun befehlÜbersetze(argumente: Array<String>) {
    val (quelldatei, bytes) = kompiliereZuBytecode(argumente)
    val klassenname = quelldatei.nameWithoutExtension
    val zieldatei = File(quelldatei.parentFile, "$klassenname.class")
    zieldatei.writeBytes(bytes)
    println("Erzeugt: ${zieldatei.path}")
    println("Ausfuehren mit:  java -cp \"${quelldatei.parent}\" $klassenname")
}

private fun befehlBinär(argumente: Array<String>) {
    val (quelldatei, bytes) = kompiliereZuBytecode(argumente)
    val klassenname = quelldatei.nameWithoutExtension
    val arbeitsverzeichnis = Files.createTempDirectory("edel-binaer").toFile()
    try {
        File(arbeitsverzeichnis, "$klassenname.class").writeBytes(bytes)
        val zieldatei = File(quelldatei.parentFile, klassenname)
        val nativeImage = findeNativeImage()
        println("Uebersetze '${quelldatei.name}' mit GraalVM native-image ...")
        val exitcode = try {
            ProcessBuilder(
                nativeImage,
                "--no-fallback",
                "-cp", arbeitsverzeichnis.absolutePath,
                klassenname,
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
