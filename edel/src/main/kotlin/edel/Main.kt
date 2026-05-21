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
import edel.semantik.Resolver
import edel.semantik.Typpruefer
import java.io.File
import kotlin.system.exitProcess

const val VERSION = "0.1.0"

/** Ergebnis der statischen Analyse: Syntaxbaum, Symboltabelle und Diagnosen. */
class AnalyseErgebnis(
    val programm: Programm?,
    val symbole: GlobaleSymbole?,
    val diagnosen: List<Diagnose>,
) {
    val erfolgreich: Boolean get() = programm != null && diagnosen.isEmpty()
}

/**
 * Fuehrt die volle statische Pipeline aus: Lexer, Parser, Resolver, Typpruefer.
 * Lexer- und Parserfehler werden als einzelne Diagnose zurueckgegeben.
 */
fun analysiere(quelle: String): AnalyseErgebnis {
    val programm = try {
        val tokens = Lexer(quelle).zerlege()
        Parser(tokens).parse()
    } catch (fehler: QuellFehler) {
        return AnalyseErgebnis(null, null, listOf(fehler.diagnose))
    }
    val diagnosen = DiagnoseSammler()
    val symbole = Resolver(programm, diagnosen).auflösen()
    Typpruefer(programm, symbole, diagnosen).prüfe()
    return AnalyseErgebnis(programm, symbole, diagnosen.diagnosen)
}

/** Analysiert und interpretiert Quelltext; nuetzlich fuer Tests. */
fun interpretiere(quelle: String, ausgabe: (String) -> Unit) {
    val ergebnis = analysiere(quelle)
    if (!ergebnis.erfolgreich) {
        throw QuellFehler(ergebnis.diagnosen.firstOrNull()
            ?: Diagnose("Unbekannter Analysefehler", edel.fehler.Position(0, 0)))
    }
    Interpreter(ergebnis.programm!!, ausgabe).starte()
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

private fun befehlStarte(argumente: Array<String>) {
    val quelle = leseDatei(argumente)
    val ergebnis = analysiere(quelle)
    if (ergebnis.diagnosen.isNotEmpty()) {
        druckeDiagnosen(ergebnis.diagnosen)
        exitProcess(1)
    }
    val programm = ergebnis.programm!!
    val start = programm.deklarationen.filterIsInstance<FunktionDeklaration>()
        .firstOrNull { it.name == "start" }
    if (start == null) {
        System.err.println("Fehler: Das Programm hat keine Funktion 'start'.")
        exitProcess(1)
    }
    if (start.parameter.isNotEmpty()) {
        System.err.println("Fehler: Die Funktion 'start' darf keine Parameter haben.")
        exitProcess(1)
    }
    try {
        Interpreter(programm).starte()
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
    } else {
        druckeDiagnosen(ergebnis.diagnosen)
        exitProcess(1)
    }
}

private fun befehlÜbersetze(argumente: Array<String>) {
    val quelle = leseDatei(argumente)
    val ergebnis = analysiere(quelle)
    if (ergebnis.diagnosen.isNotEmpty()) {
        druckeDiagnosen(ergebnis.diagnosen)
        exitProcess(1)
    }
    val programm = ergebnis.programm!!
    val start = programm.deklarationen.filterIsInstance<FunktionDeklaration>()
        .firstOrNull { it.name == "start" }
    if (start == null || start.parameter.isNotEmpty()) {
        System.err.println("Fehler: Das Programm braucht eine parameterlose Funktion 'start'.")
        exitProcess(1)
    }
    val quelldatei = File(argumente[1]).absoluteFile
    val klassenname = quelldatei.nameWithoutExtension
    try {
        val bytes = Bytecodeerzeuger(programm, ergebnis.symbole!!, klassenname).kompiliere()
        val zieldatei = File(quelldatei.parentFile, "$klassenname.class")
        zieldatei.writeBytes(bytes)
        println("Erzeugt: ${zieldatei.path}")
        println("Ausfuehren mit:  java -cp \"${quelldatei.parent}\" $klassenname")
    } catch (fehler: NichtUnterstützt) {
        System.err.println(fehler.diagnose.formatiert())
        System.err.println(
            "Das Bytecode-Backend deckt erst den Sprachkern ab. " +
                "Dieses Programm laeuft weiterhin mit 'edel starte'.",
        )
        exitProcess(1)
    }
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
          edel version                  Versionsnummer anzeigen
          edel hilfe                     diese Hilfe anzeigen
        """.trimIndent(),
    )
}
