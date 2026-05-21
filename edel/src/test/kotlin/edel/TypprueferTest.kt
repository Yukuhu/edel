package edel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypprueferTest {

    private fun diagnosen(quelle: String) = analysiere(quelle).diagnosen

    private fun istFehlerfrei(quelle: String) = diagnosen(quelle).isEmpty()

    @Test
    fun korrektesProgrammHatKeineFehler() {
        assertTrue(istFehlerfrei("funktion start() { sei x = 1 + 2 drucke(x) }"))
    }

    @Test
    fun meldetTypkonfliktBeiAnnotation() {
        assertTrue(diagnosen("funktion start() { sei x: Ganzzahl = wahr }").isNotEmpty())
    }

    @Test
    fun meldetZuweisungAnUnveraenderlicheBindung() {
        val fehler = diagnosen("funktion start() { sei x = 1 x = 2 }")
        assertTrue(fehler.any { it.meldung.contains("unveraenderlich") })
    }

    @Test
    fun erlaubtZuweisungAnVeraenderlicheBindung() {
        assertTrue(istFehlerfrei("funktion start() { ver x = 1 x = 2 }"))
    }

    @Test
    fun meldetUnbekanntenNamen() {
        assertTrue(diagnosen("funktion start() { drucke(unbekannt) }").isNotEmpty())
    }

    @Test
    fun bedingungMussWahrheitSein() {
        assertTrue(diagnosen("funktion start() { wenn 1 { } }").isNotEmpty())
    }

    @Test
    fun meldetFalscheArgumentanzahl() {
        val quelle = "funktion f(n: Ganzzahl): Ganzzahl { zurück n }\n" +
            "funktion start() { drucke(f(1, 2)) }"
        assertTrue(diagnosen(quelle).isNotEmpty())
    }

    @Test
    fun pruueftSchnittstellenkonformitaet() {
        val quelle = "schnittstelle Form { funktion fläche(): Kommazahl }\n" +
            "klasse Leer erfüllt Form { }\n" +
            "funktion start() { }"
        assertTrue(diagnosen(quelle).any { it.meldung.contains("fläche") })
    }

    @Test
    fun textverkettungMitZahlIstErlaubt() {
        assertTrue(istFehlerfrei("funktion start() { drucke(\"Zahl: \" + 7) }"))
    }

    @Test
    fun fehlerTraegtPositionsangabe() {
        val fehler = diagnosen("funktion start() {\n    sei x: Ganzzahl = wahr\n}")
        assertEquals(1, fehler.size)
        assertEquals(2, fehler[0].position.zeile)
    }
}
