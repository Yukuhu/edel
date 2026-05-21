package edel

import edel.codegen.Bytecodeerzeuger
import edel.fehler.NichtUnterstützt
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests des Bytecode-Backends: jedes Programm wird zu echtem JVM-Bytecode
 * uebersetzt, die `.class` in den Testprozess geladen und ausgefuehrt. Damit
 * deckt der Test zugleich ab, dass die erzeugten Klassen vom JVM-Verifizierer
 * akzeptiert werden.
 */
class CodegenTest {

    private class ByteKlassenlader : ClassLoader() {
        fun definiere(name: String, bytes: ByteArray): Class<*> =
            defineClass(name, bytes, 0, bytes.size)
    }

    /** Uebersetzt Quelltext, fuehrt die erzeugte `start`-Methode aus, liefert deren Ausgabe. */
    private fun kompiliereUndLaufe(klassenname: String, quelle: String): String {
        val ergebnis = analysiere(quelle)
        assertEquals(emptyList(), ergebnis.diagnosen, "Quelle sollte fehlerfrei sein")
        val bytes = Bytecodeerzeuger(
            ergebnis.programm!!, ergebnis.symbole!!, klassenname, ergebnis.parallelplan!!,
        ).kompiliere()
        val klasse = ByteKlassenlader().definiere(klassenname, bytes)

        val puffer = ByteArrayOutputStream()
        val vorher = System.out
        System.setOut(PrintStream(puffer, true, "UTF-8"))
        try {
            klasse.getMethod("start").invoke(null)
        } finally {
            System.setOut(vorher)
        }
        return puffer.toString("UTF-8")
    }

    @Test
    fun uebersetztSchleifeUndArithmetik() {
        val quelle = "funktion start() { ver s = 0 für i von 1 bis 5 { s = s + i } drucke(s) }"
        assertEquals("15\n", kompiliereUndLaufe("ProbeSchleife", quelle))
    }

    @Test
    fun uebersetztRekursion() {
        val quelle = "funktion fak(n: Ganzzahl): Ganzzahl { wenn n <= 1 { zurück 1 } " +
            "zurück n * fak(n - 1) }\n" +
            "funktion start() { drucke(fak(10)) }"
        assertEquals("3628800\n", kompiliereUndLaufe("ProbeRekursion", quelle))
    }

    @Test
    fun uebersetztWähleUndWahrheitswerte() {
        val quelle = "funktion ampel(n: Ganzzahl): Text { zurück wähle n { " +
            "fall 0 -> \"rot\" fall 1 -> \"gelb\" sonst -> \"grün\" } }\n" +
            "funktion start() { drucke(ampel(0)) drucke(ampel(1)) drucke(ampel(2)) " +
            "drucke(1 < 2 und 2 < 3) }"
        assertEquals("rot\ngelb\ngrün\nwahr\n", kompiliereUndLaufe("ProbeWähle", quelle))
    }

    @Test
    fun uebersetztKommazahlen() {
        val quelle = "funktion start() { sei x = 7.0 / 2.0 drucke(x) drucke(3 + 1.5) }"
        assertEquals("3.5\n4.5\n", kompiliereUndLaufe("ProbeKomma", quelle))
    }

    @Test
    fun uebersetztParalleleSummenreduktion() {
        // Wird zu LongStream.rangeClosed(..).parallel().map(..).sum() uebersetzt.
        val quelle = "funktion start() { ver s = 0 für i von 1 bis 1000000 { s = s + i } drucke(s) }"
        assertEquals("500000500000\n", kompiliereUndLaufe("ProbeParallelSumme", quelle))
    }

    @Test
    fun uebersetztParalleleProduktreduktion() {
        val quelle = "funktion start() { ver p = 1 für i von 1 bis 20 { p = p * i } drucke(p) }"
        assertEquals("2432902008176640000\n", kompiliereUndLaufe("ProbeParallelProdukt", quelle))
    }

    @Test
    fun lehntNichtUnterstuetzteProgrammeAb() {
        val quelle = "datensatz Punkt(x: Ganzzahl, y: Ganzzahl)\nfunktion start() { }"
        val ergebnis = analysiere(quelle)
        assertEquals(emptyList(), ergebnis.diagnosen)
        assertFailsWith<NichtUnterstützt> {
            Bytecodeerzeuger(ergebnis.programm!!, ergebnis.symbole!!, "ProbeAblehnung").kompiliere()
        }
    }
}
