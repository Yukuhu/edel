package edel

import edel.laufzeit.Interpreter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests des Modulsystems: `paket`/`importiere` ueber mehrere Dateien hinweg.
 * Jeder Test laesst eine kleine Quellsammlung durch [analysiereProjekt] laufen
 * und prueft entweder Diagnosen oder die Ausgabe des Interpreters.
 */
class ModulTest {

    /** Liefert die `start`-Funktion (FQN [eintrag]) und gibt ihre Ausgabe zurueck. */
    private fun starteProjekt(quellen: Map<String, String>, eintrag: String, startFqn: String): String {
        val ergebnis = analysiereProjekt(quellen, eintrag)
        assertEquals(emptyList(), ergebnis.diagnosen, "Projekt sollte fehlerfrei sein")
        val ausgabe = StringBuilder()
        Interpreter(
            ergebnis.programm!!, ergebnis.parallelplan!!, { ausgabe.appendLine(it) },
            eintrag = startFqn,
        ).starte()
        return ausgabe.toString()
    }

    @Test
    fun importierterDatensatzWirdAufgeloest() {
        val quellen = mapOf(
            "main.edel" to """
                importiere geometrie.Punkt
                funktion start() {
                    sei p = neu Punkt(3, 4)
                    drucke(p.x + p.y)
                }
            """.trimIndent(),
            "geometrie/punkt.edel" to """
                paket geometrie
                datensatz Punkt(x: Ganzzahl, y: Ganzzahl)
            """.trimIndent(),
        )
        assertEquals("7\n", starteProjekt(quellen, eintrag = "main.edel", startFqn = "start"))
    }

    @Test
    fun importierteFunktionWirdAufgeloest() {
        val quellen = mapOf(
            "main.edel" to """
                importiere mathe.verdopple
                funktion start() { drucke(verdopple(21)) }
            """.trimIndent(),
            "mathe/funktionen.edel" to """
                paket mathe
                funktion verdopple(n: Ganzzahl): Ganzzahl { zurück n * 2 }
            """.trimIndent(),
        )
        assertEquals("42\n", starteProjekt(quellen, eintrag = "main.edel", startFqn = "start"))
    }

    @Test
    fun mehrfacheImporteAusGleichemPaket() {
        val quellen = mapOf(
            "main.edel" to """
                importiere geometrie.Punkt
                importiere geometrie.verschiebe
                funktion start() {
                    sei a = neu Punkt(1, 2)
                    sei b = verschiebe(a, 10, 20)
                    drucke(b.x)
                    drucke(b.y)
                }
            """.trimIndent(),
            "geometrie/punkt.edel" to """
                paket geometrie
                datensatz Punkt(x: Ganzzahl, y: Ganzzahl)
                funktion verschiebe(p: Punkt, dx: Ganzzahl, dy: Ganzzahl): Punkt {
                    zurück neu Punkt(p.x + dx, p.y + dy)
                }
            """.trimIndent(),
        )
        assertEquals("11\n22\n", starteProjekt(quellen, eintrag = "main.edel", startFqn = "start"))
    }

    @Test
    fun paketDeklarationVerschiebtStartFqn() {
        val quellen = mapOf(
            "anwendung/main.edel" to """
                paket anwendung
                funktion start() { drucke("hallo") }
            """.trimIndent(),
        )
        assertEquals(
            "hallo\n",
            starteProjekt(quellen, eintrag = "anwendung/main.edel", startFqn = "anwendung.start"),
        )
    }

    @Test
    fun importDerNichtExistiert_meldetDiagnose() {
        val quellen = mapOf(
            "main.edel" to """
                importiere mathe.fehlt
                funktion start() { drucke(0) }
            """.trimIndent(),
        )
        val ergebnis = analysiereProjekt(quellen, "main.edel")
        assertTrue(
            ergebnis.diagnosen.any { "mathe.fehlt" in it.meldung },
            "Erwarte Diagnose ueber unbekannten Import, erhielt: ${ergebnis.diagnosen}",
        )
    }

    @Test
    fun gleicherFqnInZweiDateien_meldetKollision() {
        val quellen = mapOf(
            "a.edel" to """
                paket gemein
                funktion hilf(): Ganzzahl { zurück 1 }
            """.trimIndent(),
            "b.edel" to """
                paket gemein
                funktion hilf(): Ganzzahl { zurück 2 }
            """.trimIndent(),
            "main.edel" to """
                importiere gemein.hilf
                funktion start() { drucke(hilf()) }
            """.trimIndent(),
        )
        // Beide Dateien tauchen im Map auf, also fuehrt Resolver.fuehreZusammen sie zusammen.
        val ergebnis = analysiereProjekt(quellen, "main.edel")
        assertTrue(
            ergebnis.diagnosen.any { "gemein.hilf" in it.meldung },
            "Erwarte Kollisions-Diagnose, erhielt: ${ergebnis.diagnosen}",
        )
    }

    @Test
    fun beispielModuleLaeuftMitGoldenerAusgabe() {
        val mainDatei = File("beispiele/module/main.edel")
        val punktDatei = File("beispiele/module/geometrie/punkt.edel")
        val aus = File("beispiele/module/main.aus")
        assertTrue(mainDatei.isFile && punktDatei.isFile && aus.isFile, "Beispiel-Dateien fehlen")
        val ergebnis = analysiereProjekt(
            mapOf(
                mainDatei.path to mainDatei.readText(),
                punktDatei.path to punktDatei.readText(),
            ),
            mainDatei.path,
        )
        assertEquals(emptyList(), ergebnis.diagnosen)
        val programm = assertNotNull(ergebnis.programm)
        val ausgabe = StringBuilder()
        Interpreter(programm, ergebnis.parallelplan!!, { ausgabe.appendLine(it) }).starte()
        assertEquals(aus.readText(), ausgabe.toString())
    }
}
