package edel

import edel.codegen.Bytecodeerzeuger
import edel.laufzeit.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
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

    /** Laedt FQN-Klassen aus einem byte-Map -- Klassennamen koennen Punkte enthalten. */
    private class ByteKlassenlader(private val klassen: Map<String, ByteArray>) : ClassLoader() {
        private val geladen = HashMap<String, Class<*>>()
        override fun findClass(name: String): Class<*> {
            geladen[name]?.let { return it }
            val bytes = klassen[name] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size).also { geladen[name] = it }
        }
    }

    /** Uebersetzt das Projekt zu Bytecode, laedt die Hauptklasse und ruft `main([])` auf. */
    private fun kompiliereUndLaufe(
        quellen: Map<String, String>,
        eintrag: String,
        hauptklasse: String,
        startFqn: String,
    ): String {
        val ergebnis = analysiereProjekt(quellen, eintrag)
        assertEquals(emptyList(), ergebnis.diagnosen, "Projekt sollte fehlerfrei sein")
        val klassen = Bytecodeerzeuger(
            ergebnis.programm!!, ergebnis.symbole!!, hauptklasse, ergebnis.parallelplan!!,
            eintragsStart = startFqn,
        ).kompiliere()
        val klasse = ByteKlassenlader(klassen).loadClass(hauptklasse)
        val puffer = ByteArrayOutputStream()
        val vorher = System.out
        System.setOut(PrintStream(puffer, true, "UTF-8"))
        try {
            // `main(String[])` ruft intern die Edel-Einstiegsfunktion auf.
            klasse.getMethod("main", Array<String>::class.java).invoke(null, emptyArray<String>())
        } finally {
            System.setOut(vorher)
        }
        return puffer.toString("UTF-8")
    }

    @Test
    fun bytecodeUebersetztMehrdateiProjekt() {
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
        assertEquals(
            "11\n22\n",
            kompiliereUndLaufe(
                quellen, eintrag = "main.edel",
                hauptklasse = "main", startFqn = "start",
            ),
        )
    }

    @Test
    fun bytecodeUebersetztPaketiertesEinstiegsprogramm() {
        val quellen = mapOf(
            "anwendung/main.edel" to """
                paket anwendung
                funktion verdopple(n: Ganzzahl): Ganzzahl { zurück n * 2 }
                funktion start() { drucke(verdopple(21)) }
            """.trimIndent(),
        )
        assertEquals(
            "42\n",
            kompiliereUndLaufe(
                quellen, eintrag = "anwendung/main.edel",
                hauptklasse = "anwendung.main", startFqn = "anwendung.start",
            ),
        )
    }

    @Test
    fun bytecodeUebersetztDatensaetzeAusFremdemPaket() {
        // Cross-Paket-Aufruf: `neu Punkt(...)` und Feldzugriff `.x` ueber Modulgrenze.
        val quellen = mapOf(
            "haupt.edel" to """
                importiere zahlen.Paar2
                importiere zahlen.summe
                funktion start() {
                    sei p = neu Paar2(7, 8)
                    drucke(summe(p))
                }
            """.trimIndent(),
            "zahlen/paar.edel" to """
                paket zahlen
                datensatz Paar2(a: Ganzzahl, b: Ganzzahl)
                funktion summe(p: Paar2): Ganzzahl { zurück p.a + p.b }
            """.trimIndent(),
        )
        assertEquals(
            "15\n",
            kompiliereUndLaufe(
                quellen, eintrag = "haupt.edel",
                hauptklasse = "haupt", startFqn = "start",
            ),
        )
    }

    @Test
    fun importierteReineFunktionWirdAlsParallelisierbarErkannt() {
        // Regression: vor dem Fix hat aufrufVerletzt den Kurznamen `quadrat`
        // gegen die FQN-indizierte `reineFunktionen`-Menge geprueft -- der
        // Lookup ging immer fehl und die Schleife wurde nicht parallelisiert.
        val quellen = mapOf(
            "main.edel" to """
                importiere mathe.quadrat
                funktion start() {
                    ver s = 0
                    für i von 1 bis 1000 { s = s + quadrat(i) }
                    drucke(s)
                }
            """.trimIndent(),
            "mathe/funktionen.edel" to """
                paket mathe
                funktion quadrat(n: Ganzzahl): Ganzzahl { zurück n * n }
            """.trimIndent(),
        )
        val ergebnis = analysiereProjekt(quellen, "main.edel")
        assertEquals(emptyList(), ergebnis.diagnosen)
        assertTrue(
            ergebnis.parallelplan!!.anzahl >= 1,
            "Reduktion ueber importierte reine Funktion sollte parallelisiert werden",
        )
    }

    @Test
    fun importierteUnreineFunktionVerhindertParallelisierung() {
        // Regression: vor dem Fix hat funktionIstUnrein den Kurznamen `meld`
        // gegen die FQN-indizierte `unrein`-Menge geprueft -- transitive
        // Unreinheit ueber Modulgrenzen wurde nicht erkannt, die Schleife
        // waere faelschlich parallelisiert worden (mit verschraenkter Ausgabe).
        val quellen = mapOf(
            "main.edel" to """
                importiere io.meld
                funktion start() {
                    ver s = 0
                    für i von 1 bis 10 {
                        meld(i)
                        s = s + i
                    }
                    drucke(s)
                }
            """.trimIndent(),
            "io/druckhilfe.edel" to """
                paket io
                funktion meld(n: Ganzzahl) { drucke(n) }
            """.trimIndent(),
        )
        val ergebnis = analysiereProjekt(quellen, "main.edel")
        assertEquals(emptyList(), ergebnis.diagnosen)
        assertEquals(
            0, ergebnis.parallelplan!!.anzahl,
            "Schleife, die transitiv `drucke` aufruft, darf nicht parallelisiert werden",
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
