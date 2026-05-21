package edel

import edel.fehler.LaufzeitFehler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests der Nullsicherheit nach Kotlin-Vorbild: nicht-nullbare Typen,
 * nullbare Typen `T?`, die Operatoren `?.`/`?:`/`!!` sowie Smart Casts.
 */
class NullsicherheitTest {

    private fun fehler(quelle: String) = analysiere(quelle).diagnosen

    private fun laufe(quelle: String): List<String> {
        val ausgabe = mutableListOf<String>()
        interpretiere(quelle) { ausgabe.add(it) }
        return ausgabe
    }

    @Test
    fun nichtsPasstNichtInNichtNullbarenTyp() {
        assertTrue(fehler("funktion start() { sei x: Ganzzahl = nichts }").isNotEmpty())
    }

    @Test
    fun nichtsPasstInNullbarenTyp() {
        assertEquals(emptyList(), fehler("funktion start() { sei x: Ganzzahl? = nichts drucke(x) }"))
    }

    @Test
    fun nullbarerWertKannNichtDirektVerwendetWerden() {
        val quelle = "funktion start() { sei x: Text? = \"a\" drucke(x.länge()) }"
        assertTrue(fehler(quelle).any { it.meldung.contains("nichts") })
    }

    @Test
    fun sichererAufrufLiefertNullbaresErgebnis() {
        // 'x?.länge()' ist Ganzzahl? und passt daher nicht in Ganzzahl.
        val quelle = "funktion f(x: Text?): Ganzzahl { zurück x?.länge() }\nfunktion start() { }"
        assertTrue(fehler(quelle).isNotEmpty())
    }

    @Test
    fun elvisLiefertNichtNullbarenTyp() {
        val quelle = "funktion f(x: Ganzzahl?): Ganzzahl { zurück x ?: 0 }\nfunktion start() { }"
        assertEquals(emptyList(), fehler(quelle))
    }

    @Test
    fun nichtNullZusicherungEntnulltDenTyp() {
        val quelle = "funktion f(x: Ganzzahl?): Ganzzahl { zurück x!! }\nfunktion start() { }"
        assertEquals(emptyList(), fehler(quelle))
    }

    @Test
    fun elvisZurLaufzeit() {
        val quelle = "funktion start() { sei a: Ganzzahl? = 7 sei b: Ganzzahl? = nichts " +
            "drucke(a ?: 99) drucke(b ?: 99) }"
        assertEquals(listOf("7", "99"), laufe(quelle))
    }

    @Test
    fun sichererAufrufKurzschliesst() {
        assertEquals(
            listOf("nichts"),
            laufe("funktion start() { sei x: Text? = nichts drucke(x?.länge()) }"),
        )
    }

    @Test
    fun nichtNullZusicherungWirftBeiNichts() {
        assertFailsWith<LaufzeitFehler> {
            laufe("funktion start() { sei x: Ganzzahl? = nichts drucke(x!!) }")
        }
    }

    @Test
    fun smartCastNachNullpruefung() {
        val quelle = "funktion start() { sei x: Text? = \"hallo\" " +
            "wenn x != nichts { drucke(x.länge()) } }"
        assertEquals(emptyList(), fehler(quelle))
        assertEquals(listOf("5"), laufe(quelle))
    }

    @Test
    fun smartCastNachFrueherRueckkehr() {
        val quelle = "funktion f(x: Text?): Ganzzahl { wenn x == nichts { zurück 0 } zurück x.länge() }\n" +
            "funktion start() { drucke(f(\"abc\")) }"
        assertEquals(emptyList(), fehler(quelle))
        assertEquals(listOf("3"), laufe(quelle))
    }

    @Test
    fun smartCastInUndKette() {
        val quelle = "funktion start() { sei x: Text? = \"abcd\" " +
            "wenn x != nichts und x.länge() > 2 { drucke(\"lang\") } }"
        assertEquals(emptyList(), fehler(quelle))
        assertEquals(listOf("lang"), laufe(quelle))
    }

    @Test
    fun veraenderlicheVariableWirdNichtVerfeinert() {
        // 'ver'-Bindungen sind nicht stabil -> kein Smart Cast.
        val quelle = "funktion start() { ver x: Text? = \"a\" " +
            "wenn x != nichts { drucke(x.länge()) } }"
        assertTrue(fehler(quelle).isNotEmpty())
    }
}
