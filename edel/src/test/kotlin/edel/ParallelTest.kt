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

    // ---- fork/join unabhaengiger Teilausdruecke (Rekursion) -----------------

    private val FIB = "funktion fib(n: Ganzzahl): Ganzzahl { wenn n < 2 { zurück n } " +
        "zurück fib(n - 1) + fib(n - 2) }\n"

    private fun gabelungen(quelle: String): Int = analysiere(quelle).parallelplan!!.gabelAnzahl

    @Test
    fun erkenntGabelungUnabhaengigerRekursiverAufrufe() {
        assertEquals(1, gabelungen(FIB + "funktion start() { drucke(fib(10)) }"))
    }

    @Test
    fun parallelisierteRekursionLiefertExaktesErgebnis() {
        assertEquals(listOf("832040"), laufe(FIB + "funktion start() { drucke(fib(30)) }"))
    }

    @Test
    fun trivialerOperandWirdNichtGegabelt() {
        // 'fib(n-1) + 1': ein Operand ohne Aufruf -> kein Forken (nur Mehraufwand).
        val quelle = "funktion fib(n: Ganzzahl): Ganzzahl { wenn n < 2 { zurück n } " +
            "zurück fib(n - 1) + 1 }\n" +
            "funktion start() { drucke(fib(5)) }"
        assertEquals(0, gabelungen(quelle))
    }

    @Test
    fun unreinerTeilausdruckWirdNichtGegabelt() {
        val quelle = "funktion laut(n: Ganzzahl): Ganzzahl { drucke(n) zurück n }\n" +
            "funktion start() { drucke(laut(1) + laut(2)) }"
        assertEquals(0, gabelungen(quelle))
    }

    // ---- Streuschleifen (parallel map) --------------------------------------

    @Test
    fun erkenntStreuschleife() {
        val quelle = "funktion start() { ver xs = Liste(0, 0, 0, 0, 0) " +
            "für i von 0 bis 4 { xs.setze(i, i * i) } drucke(xs) }"
        assertEquals(1, analysiere(quelle).parallelplan!!.streuAnzahl)
    }

    @Test
    fun streuschleifeLiefertKorrektesErgebnis() {
        val quelle = "funktion start() { ver xs = Liste(0, 0, 0, 0, 0) " +
            "für i von 0 bis 4 { xs.setze(i, i * i) } drucke(xs) }"
        assertEquals(listOf("[0, 1, 4, 9, 16]"), laufe(quelle))
    }

    @Test
    fun gelesenesZielVerhindertStreuung() {
        // Liest 'xs' (das Streuziel) -> moegliche Wettlaufbedingung -> sequentiell.
        val quelle = "funktion start() { ver xs = Liste(0, 0, 0) " +
            "für i von 0 bis 2 { xs.setze(i, xs.länge()) } drucke(xs) }"
        assertEquals(0, analysiere(quelle).parallelplan!!.streuAnzahl)
    }

    // ---- Unabhaengige 'sei'-Gruppen -----------------------------------------

    @Test
    fun erkenntUnabhaengigeSeiGruppe() {
        val quelle = "funktion f(n: Ganzzahl): Ganzzahl { zurück n * n }\n" +
            "funktion start() { sei a = f(10) sei b = f(20) sei c = f(30) drucke(a + b + c) }"
        assertEquals(1, analysiere(quelle).parallelplan!!.gruppenAnzahl)
    }

    @Test
    fun seiGruppeLiefertExaktesErgebnis() {
        val quelle = "funktion f(n: Ganzzahl): Ganzzahl { zurück n * n }\n" +
            "funktion start() { sei a = f(10) sei b = f(20) sei c = f(30) drucke(a + b + c) }"
        assertEquals(listOf("1400"), laufe(quelle))
    }

    @Test
    fun abhaengigeSeiBindungWirdNichtGruppiert() {
        // 'b' liest 'a' -> die Bindungen sind nicht unabhaengig.
        val quelle = "funktion f(n: Ganzzahl): Ganzzahl { zurück n * n }\n" +
            "funktion start() { sei a = f(2) sei b = f(a) drucke(b) }"
        assertEquals(0, analysiere(quelle).parallelplan!!.gruppenAnzahl)
    }
}
