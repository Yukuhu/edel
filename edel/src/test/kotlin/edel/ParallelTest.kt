package edel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests der automatischen Parallelisierung: die Analyse erkennt unabhaengige
 * Reduktionsschleifen, und der Interpreter berechnet sie nebenlaeufig mit einem
 * Ergebnis, das bitgleich zur sequentiellen Ausfuehrung ist.
 */
class ParallelTest {

    private fun paralleleSchleifen(quelle: String): Int =
        analysiere(quelle).parallelplan!!.anzahl

    private fun laufe(quelle: String): List<String> {
        val ausgabe = mutableListOf<String>()
        interpretiere(quelle) { ausgabe.add(it) }
        return ausgabe
    }

    @Test
    fun erkenntEinfacheReduktion() {
        val quelle = "funktion start() { ver s = 0 für i von 1 bis 10 { s = s + i } drucke(s) }"
        assertEquals(1, paralleleSchleifen(quelle))
    }

    @Test
    fun summenreduktionLiefertExaktesErgebnis() {
        val quelle = "funktion start() { ver s = 0 für i von 1 bis 1000000 { s = s + i } drucke(s) }"
        assertEquals(listOf("500000500000"), laufe(quelle))
    }

    @Test
    fun produktreduktionLiefertExaktesErgebnis() {
        val quelle = "funktion start() { ver p = 1 für i von 1 bis 20 { p = p * i } drucke(p) }"
        assertEquals(listOf("2432902008176640000"), laufe(quelle))
    }

    @Test
    fun mehrereAkkumulatorenInEinerSchleife() {
        val quelle = "funktion start() { ver s = 0 ver p = 1 " +
            "für i von 1 bis 10 { s = s + i p = p * i } drucke(s) drucke(p) }"
        assertEquals(1, paralleleSchleifen(quelle))
        assertEquals(listOf("55", "3628800"), laufe(quelle))
    }

    @Test
    fun fürInReduktionÜberListe() {
        val quelle = "funktion start() { ver s = 0 " +
            "für x in Liste(10, 20, 30, 40) { s = s + x } drucke(s) }"
        assertEquals(1, paralleleSchleifen(quelle))
        assertEquals(listOf("100"), laufe(quelle))
    }

    @Test
    fun schleifeMitAusgabeWirdNichtParallelisiert() {
        val quelle = "funktion start() { ver s = 0 " +
            "für i von 1 bis 10 { s = s + i drucke(s) } }"
        assertEquals(0, paralleleSchleifen(quelle))
    }

    @Test
    fun schreibenEinerFremdenVariableWirdNichtParallelisiert() {
        val quelle = "funktion start() { ver s = 0 ver x = 0 " +
            "für i von 1 bis 10 { s = s + i x = i } drucke(s) drucke(x) }"
        assertEquals(0, paralleleSchleifen(quelle))
    }

    @Test
    fun lesenDesAkkumulatorsWirdNichtParallelisiert() {
        val quelle = "funktion start() { ver s = 0 " +
            "für i von 1 bis 10 { sei t = s s = s + i drucke(t) } }"
        assertEquals(0, paralleleSchleifen(quelle))
    }

    @Test
    fun kommazahlAkkumulatorWirdNichtParallelisiert() {
        // Gleitkomma-Addition ist nicht assoziativ -> bleibt sequentiell.
        val quelle = "funktion start() { ver s = 0.0 " +
            "für i von 1 bis 10 { s = s + 1.5 } drucke(s) }"
        assertEquals(0, paralleleSchleifen(quelle))
    }

    @Test
    fun unreineFunktionVerhindertParallelisierung() {
        val quelle = "funktion meldung(n: Ganzzahl): Ganzzahl { drucke(n) zurück n }\n" +
            "funktion start() { ver s = 0 für i von 1 bis 10 { s = s + meldung(i) } }"
        assertEquals(0, paralleleSchleifen(quelle))
    }

    @Test
    fun reineFunktionImRumpfBleibtParallelisierbar() {
        val quelle = "funktion doppelt(n: Ganzzahl): Ganzzahl { zurück n * 2 }\n" +
            "funktion start() { ver s = 0 für i von 1 bis 100 { s = s + doppelt(i) } drucke(s) }"
        assertEquals(1, paralleleSchleifen(quelle))
        assertEquals(listOf("10100"), laufe(quelle))
    }
}
