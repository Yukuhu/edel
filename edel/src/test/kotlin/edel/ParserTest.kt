package edel

import edel.fehler.QuellFehler
import edel.lexer.Lexer
import edel.lexer.TokenTyp
import edel.parser.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class ParserTest {

    private fun parse(quelle: String): Programm = Parser(Lexer(quelle).zerlege()).parse()

    @Test
    fun parstFunktionMitParameternUndRueckgabe() {
        val programm = parse("funktion f(n: Ganzzahl): Ganzzahl { zurück n }")
        val funktion = assertIs<FunktionDeklaration>(programm.deklarationen[0])
        assertEquals("f", funktion.name)
        assertEquals(1, funktion.parameter.size)
        assertEquals("n", funktion.parameter[0].name)
    }

    @Test
    fun parstDatensatz() {
        val programm = parse("datensatz Punkt(x: Ganzzahl, y: Ganzzahl)")
        val datensatz = assertIs<DatensatzDeklaration>(programm.deklarationen[0])
        assertEquals(listOf("x", "y"), datensatz.felder.map { it.name })
    }

    @Test
    fun beachtetOperatorpraezedenz() {
        val programm = parse("funktion start() { sei x = 1 + 2 * 3 }")
        val funktion = programm.deklarationen[0] as FunktionDeklaration
        val sei = funktion.körper.anweisungen[0] as SeiAnweisung
        val wurzel = assertIs<BinärAusdruck>(sei.initialwert)
        assertEquals(TokenTyp.PLUS, wurzel.operator)
        val rechts = assertIs<BinärAusdruck>(wurzel.rechts)
        assertEquals(TokenTyp.STERN, rechts.operator)
    }

    @Test
    fun parstLambdaStattGruppierung() {
        val programm = parse("funktion start() { sei f = (n: Ganzzahl) -> n * 2 }")
        val funktion = programm.deklarationen[0] as FunktionDeklaration
        val sei = funktion.körper.anweisungen[0] as SeiAnweisung
        assertIs<LambdaAusdruck>(sei.initialwert)
    }

    @Test
    fun parstWennSonstWenn() {
        val programm = parse("funktion start() { wenn wahr { } sonst wenn falsch { } sonst { } }")
        val funktion = programm.deklarationen[0] as FunktionDeklaration
        val wenn = assertIs<WennAnweisung>(funktion.körper.anweisungen[0])
        assertIs<WennAnweisung>(wenn.sonst)
    }

    @Test
    fun zurueckOhneWertVorBlockende() {
        val programm = parse("funktion start() { zurück }")
        val funktion = programm.deklarationen[0] as FunktionDeklaration
        val zurück = assertIs<ZurückAnweisung>(funktion.körper.anweisungen[0])
        assertNull(zurück.wert)
    }

    @Test
    fun meldetSyntaxfehler() {
        assertFailsWith<QuellFehler> { parse("funktion { }") }
    }
}
