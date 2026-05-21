package edel

import kotlin.test.Test
import kotlin.test.assertEquals

class InterpreterTest {

    /** Fuehrt Quelltext aus und liefert die Zeilen, die `drucke` erzeugt hat. */
    private fun laufe(quelle: String): List<String> {
        val ausgabe = mutableListOf<String>()
        interpretiere(quelle) { ausgabe.add(it) }
        return ausgabe
    }

    @Test
    fun drucktText() {
        assertEquals(listOf("hallo welt"), laufe("funktion start() { drucke(\"hallo welt\") }"))
    }

    @Test
    fun rechnetMitGanzzahlen() {
        assertEquals(listOf("14"), laufe("funktion start() { drucke(2 + 3 * 4) }"))
    }

    @Test
    fun ganzzahldivisionUndModulo() {
        assertEquals(listOf("3", "1"), laufe("funktion start() { drucke(7 / 2) drucke(7 % 2) }"))
    }

    @Test
    fun rekursionBerechnetFakultaet() {
        val quelle = "funktion fak(n: Ganzzahl): Ganzzahl { wenn n <= 1 { zurück 1 } zurück n * fak(n - 1) }\n" +
            "funktion start() { drucke(fak(6)) }"
        assertEquals(listOf("720"), laufe(quelle))
    }

    @Test
    fun schleifeMitBrich() {
        val quelle = "funktion start() { für i von 1 bis 100 { wenn i > 3 { brich } drucke(i) } }"
        assertEquals(listOf("1", "2", "3"), laufe(quelle))
    }

    @Test
    fun lambdaAlsWert() {
        val quelle = "funktion start() { sei q = (n: Ganzzahl) -> n * n drucke(q(9)) }"
        assertEquals(listOf("81"), laufe(quelle))
    }

    @Test
    fun listenMethoden() {
        val quelle = "funktion start() { sei xs = Liste(1, 2) xs.hinzufügen(3) drucke(xs.länge()) drucke(xs.holen(2)) }"
        assertEquals(listOf("3", "3"), laufe(quelle))
    }

    @Test
    fun waehleMustervergleich() {
        val quelle = "funktion stufe(n: Ganzzahl): Text { zurück wähle n { fall 1 -> \"eins\" fall 2 -> \"zwei\" sonst -> \"viele\" } }\n" +
            "funktion start() { drucke(stufe(1)) drucke(stufe(2)) drucke(stufe(9)) }"
        assertEquals(listOf("eins", "zwei", "viele"), laufe(quelle))
    }

    @Test
    fun klasseMitZustandUndMethoden() {
        val quelle = "klasse Konto { ver stand: Ganzzahl funktion einzahlen(b: Ganzzahl) { dies.stand = dies.stand + b } }\n" +
            "funktion start() { sei k = neu Konto(0) k.einzahlen(50) k.einzahlen(25) drucke(k.stand) }"
        assertEquals(listOf("75"), laufe(quelle))
    }

    @Test
    fun datensatzFelder() {
        val quelle = "datensatz Punkt(x: Ganzzahl, y: Ganzzahl)\n" +
            "funktion start() { sei p = neu Punkt(8, 9) drucke(p.x + p.y) }"
        assertEquals(listOf("17"), laufe(quelle))
    }
}
