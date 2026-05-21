package edel

import edel.fehler.QuellFehler
import edel.lexer.Lexer
import edel.lexer.TokenTyp.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LexerTest {

    private fun typen(quelle: String) = Lexer(quelle).zerlege().map { it.typ }

    @Test
    fun zerlegtSchluesselwoerterUndOperatoren() {
        assertEquals(
            listOf(SEI, BEZEICHNER, ZUWEISUNG, GANZZAHL_LITERAL, PLUS, GANZZAHL_LITERAL, DATEI_ENDE),
            typen("sei x = 42 + 8"),
        )
    }

    @Test
    fun erkenntUmlauteInBezeichnernUndSchluesselwoertern() {
        val tokens = Lexer("für fakultät").zerlege()
        assertEquals(FÜR, tokens[0].typ)
        assertEquals(BEZEICHNER, tokens[1].typ)
        assertEquals("fakultät", tokens[1].text)
    }

    @Test
    fun liestTextUndKommazahlliterale() {
        val tokens = Lexer("\"hallo\" 3.5").zerlege()
        assertEquals("hallo", tokens[0].literal)
        assertEquals(3.5, tokens[1].literal)
    }

    @Test
    fun liestMehrzeichenOperatoren() {
        assertEquals(
            listOf(KLEINER_GLEICH, GLEICH, UNGLEICH, PFEIL, GRÖSSER_GLEICH, DATEI_ENDE),
            typen("<= == != -> >="),
        )
    }

    @Test
    fun ueberspringtZeilenUndBlockkommentare() {
        assertEquals(listOf(SEI, DATEI_ENDE), typen("// Zeile\n/* Block */ sei"))
    }

    @Test
    fun meldetNichtAbgeschlossenenText() {
        assertFailsWith<QuellFehler> { Lexer("\"offen").zerlege() }
    }

    @Test
    fun meldetUnbekanntesZeichen() {
        assertFailsWith<QuellFehler> { Lexer("§").zerlege() }
    }
}
