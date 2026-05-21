package edel.parser

import edel.fehler.Diagnose
import edel.fehler.Position
import edel.fehler.QuellFehler
import edel.lexer.Token
import edel.lexer.TokenTyp
import edel.lexer.TokenTyp.*

/**
 * Recursive-descent-Parser mit Praezedenzkletterung fuer Ausdruecke.
 * Neuezeilen sind bedeutungslos; Anweisungen werden anhand ihres
 * Anfangstokens unterschieden.
 */
class Parser(private val tokens: List<Token>) {
    private var i = 0

    fun parse(): Programm {
        val deklarationen = mutableListOf<Deklaration>()
        while (!amEnde()) {
            deklarationen.add(deklaration())
        }
        return Programm(deklarationen)
    }

    // ---- Tokenstrom-Hilfen --------------------------------------------------

    private fun amEnde(): Boolean = aktuell().typ == DATEI_ENDE
    private fun aktuell(): Token = tokens[i]
    private fun vorherig(): Token = tokens[i - 1]
    private fun prüfe(typ: TokenTyp): Boolean = aktuell().typ == typ

    private fun passt(vararg typen: TokenTyp): Boolean {
        if (aktuell().typ in typen) {
            i++
            return true
        }
        return false
    }

    private fun erwarte(typ: TokenTyp, was: String): Token {
        if (prüfe(typ)) return tokens[i++]
        fehler("Erwartet: $was, gefunden: '${aktuell().text.ifEmpty { "Dateiende" }}'", aktuell().position)
    }

    private fun fehler(meldung: String, position: Position): Nothing =
        throw QuellFehler(Diagnose(meldung, position))

    // ---- Deklarationen ------------------------------------------------------

    private fun deklaration(): Deklaration {
        var statisch = false
        var sichtbarkeit = Sichtbarkeit.STANDARD
        while (true) {
            when (aktuell().typ) {
                STATISCH -> { i++; statisch = true }
                ÖFFENTLICH -> { i++; sichtbarkeit = Sichtbarkeit.ÖFFENTLICH }
                PRIVAT -> { i++; sichtbarkeit = Sichtbarkeit.PRIVAT }
                GESCHÜTZT -> { i++; sichtbarkeit = Sichtbarkeit.GESCHÜTZT }
                else -> break
            }
        }
        return when (aktuell().typ) {
            FUNKTION -> funktionDeklaration(statisch, sichtbarkeit)
            DATENSATZ -> datensatzDeklaration()
            KLASSE -> klasseDeklaration()
            AUFZÄHLUNG -> aufzählungDeklaration()
            SCHNITTSTELLE -> schnittstelleDeklaration()
            IMPORTIERE -> {
                val pos = tokens[i++].position
                ImportDeklaration(erwarte(BEZEICHNER, "Modulname").text, pos)
            }
            PAKET -> {
                val pos = tokens[i++].position
                PaketDeklaration(erwarte(BEZEICHNER, "Paketname").text, pos)
            }
            else -> fehler(
                "Erwartet wurde eine Deklaration (funktion, datensatz, klasse, " +
                    "aufzählung, schnittstelle), gefunden: '${aktuell().text}'",
                aktuell().position,
            )
        }
    }

    private fun funktionDeklaration(statisch: Boolean, sichtbarkeit: Sichtbarkeit): FunktionDeklaration {
        val pos = erwarte(FUNKTION, "'funktion'").position
        val name = erwarte(BEZEICHNER, "Funktionsname").text
        val parameter = parameterListe()
        val rückgabetyp = if (passt(DOPPELPUNKT)) typausdruck() else null
        val körper = block()
        return FunktionDeklaration(name, parameter, rückgabetyp, körper, statisch, sichtbarkeit, pos)
    }

    private fun parameterListe(): List<Parameter> {
        erwarte(KLAMMER_AUF, "'('")
        val parameter = mutableListOf<Parameter>()
        if (!prüfe(KLAMMER_ZU)) {
            do {
                parameter.add(parameter())
            } while (passt(KOMMA))
        }
        erwarte(KLAMMER_ZU, "')'")
        return parameter
    }

    private fun parameter(): Parameter {
        val nameTok = erwarte(BEZEICHNER, "Parametername")
        erwarte(DOPPELPUNKT, "':' mit Typangabe")
        return Parameter(nameTok.text, typausdruck(), nameTok.position)
    }

    private fun datensatzDeklaration(): DatensatzDeklaration {
        val pos = erwarte(DATENSATZ, "'datensatz'").position
        val name = erwarte(BEZEICHNER, "Datensatzname").text
        val felder = parameterListe()
        return DatensatzDeklaration(name, felder, pos)
    }

    private fun klasseDeklaration(): KlasseDeklaration {
        val pos = erwarte(KLASSE, "'klasse'").position
        val name = erwarte(BEZEICHNER, "Klassenname").text
        val oberklasse = if (passt(ERWEITERT)) erwarte(BEZEICHNER, "Oberklassenname").text else null
        val schnittstellen = mutableListOf<String>()
        if (passt(ERFÜLLT)) {
            do {
                schnittstellen.add(erwarte(BEZEICHNER, "Schnittstellenname").text)
            } while (passt(KOMMA))
        }
        erwarte(GESCHWEIFT_AUF, "'{'")
        val felder = mutableListOf<FeldDeklaration>()
        val methoden = mutableListOf<FunktionDeklaration>()
        while (!prüfe(GESCHWEIFT_ZU) && !amEnde()) {
            var statisch = false
            var sichtbarkeit = Sichtbarkeit.STANDARD
            while (true) {
                when (aktuell().typ) {
                    STATISCH -> { i++; statisch = true }
                    ÖFFENTLICH -> { i++; sichtbarkeit = Sichtbarkeit.ÖFFENTLICH }
                    PRIVAT -> { i++; sichtbarkeit = Sichtbarkeit.PRIVAT }
                    GESCHÜTZT -> { i++; sichtbarkeit = Sichtbarkeit.GESCHÜTZT }
                    else -> break
                }
            }
            when (aktuell().typ) {
                FUNKTION -> methoden.add(funktionDeklaration(statisch, sichtbarkeit))
                SEI, VER -> felder.add(feldDeklaration())
                else -> fehler(
                    "Erwartet wurde ein Feld ('sei'/'ver') oder eine Methode ('funktion'), " +
                        "gefunden: '${aktuell().text}'",
                    aktuell().position,
                )
            }
        }
        erwarte(GESCHWEIFT_ZU, "'}'")
        return KlasseDeklaration(name, oberklasse, schnittstellen, felder, methoden, pos)
    }

    private fun feldDeklaration(): FeldDeklaration {
        val wandelbar = aktuell().typ == VER
        val pos = tokens[i++].position // sei oder ver
        val name = erwarte(BEZEICHNER, "Feldname").text
        erwarte(DOPPELPUNKT, "':' mit Typangabe")
        val typ = typausdruck()
        val initialwert = if (passt(ZUWEISUNG)) ausdruck() else null
        return FeldDeklaration(name, typ, initialwert, wandelbar, pos)
    }

    private fun aufzählungDeklaration(): AufzählungDeklaration {
        val pos = erwarte(AUFZÄHLUNG, "'aufzählung'").position
        val name = erwarte(BEZEICHNER, "Aufzählungsname").text
        erwarte(GESCHWEIFT_AUF, "'{'")
        val varianten = mutableListOf<String>()
        if (!prüfe(GESCHWEIFT_ZU)) {
            do {
                varianten.add(erwarte(BEZEICHNER, "Variantenname").text)
            } while (passt(KOMMA))
        }
        erwarte(GESCHWEIFT_ZU, "'}'")
        return AufzählungDeklaration(name, varianten, pos)
    }

    private fun schnittstelleDeklaration(): SchnittstelleDeklaration {
        val pos = erwarte(SCHNITTSTELLE, "'schnittstelle'").position
        val name = erwarte(BEZEICHNER, "Schnittstellenname").text
        erwarte(GESCHWEIFT_AUF, "'{'")
        val methoden = mutableListOf<MethodenSignatur>()
        while (!prüfe(GESCHWEIFT_ZU) && !amEnde()) {
            val mpos = erwarte(FUNKTION, "'funktion'").position
            val mname = erwarte(BEZEICHNER, "Methodenname").text
            val parameter = parameterListe()
            val rückgabetyp = if (passt(DOPPELPUNKT)) typausdruck() else null
            methoden.add(MethodenSignatur(mname, parameter, rückgabetyp, mpos))
        }
        erwarte(GESCHWEIFT_ZU, "'}'")
        return SchnittstelleDeklaration(name, methoden, pos)
    }

    // ---- Typausdruecke ------------------------------------------------------

    private fun typausdruck(): Typausdruck {
        if (prüfe(KLAMMER_AUF)) {
            val pos = tokens[i++].position
            val parameter = mutableListOf<Typausdruck>()
            if (!prüfe(KLAMMER_ZU)) {
                do {
                    parameter.add(typausdruck())
                } while (passt(KOMMA))
            }
            erwarte(KLAMMER_ZU, "')'")
            erwarte(PFEIL, "'->' fuer den Rueckgabetyp")
            return FunktionsTypausdruck(parameter, typausdruck(), pos)
        }
        val nameTok = erwarte(BEZEICHNER, "Typname")
        val typargumente = mutableListOf<Typausdruck>()
        if (passt(KLEINER)) {
            do {
                typargumente.add(typausdruck())
            } while (passt(KOMMA))
            erwarte(GRÖSSER, "'>'")
        }
        return EinfacherTypausdruck(nameTok.text, typargumente, nameTok.position)
    }

    // ---- Anweisungen --------------------------------------------------------

    private fun block(): Block {
        val pos = erwarte(GESCHWEIFT_AUF, "'{'").position
        val anweisungen = mutableListOf<Anweisung>()
        while (!prüfe(GESCHWEIFT_ZU) && !amEnde()) {
            anweisungen.add(anweisung())
        }
        erwarte(GESCHWEIFT_ZU, "'}'")
        return Block(anweisungen, pos)
    }

    private fun anweisung(): Anweisung = when (aktuell().typ) {
        SEI -> seiAnweisung(wandelbar = false)
        VER -> seiAnweisung(wandelbar = true)
        WENN -> wennAnweisung()
        SOLANGE -> solangeAnweisung()
        FÜR -> fürAnweisung()
        ZURÜCK -> zurückAnweisung()
        BRICH -> BrichAnweisung(tokens[i++].position)
        WEITER -> WeiterAnweisung(tokens[i++].position)
        GESCHWEIFT_AUF -> block()
        else -> ausdruckOderZuweisung()
    }

    private fun seiAnweisung(wandelbar: Boolean): Anweisung {
        val pos = tokens[i++].position // sei / ver
        val name = erwarte(BEZEICHNER, "Variablenname").text
        val typannotation = if (passt(DOPPELPUNKT)) typausdruck() else null
        erwarte(ZUWEISUNG, "'=' mit Anfangswert")
        val initialwert = ausdruck()
        return SeiAnweisung(name, typannotation, initialwert, wandelbar, pos)
    }

    private fun wennAnweisung(): WennAnweisung {
        val pos = erwarte(WENN, "'wenn'").position
        val bedingung = ausdruck()
        val dann = block()
        var sonst: Anweisung? = null
        if (passt(SONST)) {
            sonst = if (prüfe(WENN)) wennAnweisung() else block()
        }
        return WennAnweisung(bedingung, dann, sonst, pos)
    }

    private fun solangeAnweisung(): SolangeAnweisung {
        val pos = erwarte(SOLANGE, "'solange'").position
        val bedingung = ausdruck()
        return SolangeAnweisung(bedingung, block(), pos)
    }

    private fun fürAnweisung(): Anweisung {
        val pos = erwarte(FÜR, "'für'").position
        val variable = erwarte(BEZEICHNER, "Schleifenvariable").text
        return when {
            passt(IN) -> {
                val iterierbar = ausdruck()
                FürInAnweisung(variable, iterierbar, block(), pos)
            }
            passt(VON) -> {
                val von = ausdruck()
                erwarte(BIS, "'bis'")
                val bis = ausdruck()
                FürVonBisAnweisung(variable, von, bis, block(), pos)
            }
            else -> fehler("Erwartet 'in' oder 'von' nach der Schleifenvariable", aktuell().position)
        }
    }

    private fun zurückAnweisung(): ZurückAnweisung {
        val pos = erwarte(ZURÜCK, "'zurück'").position
        val wert = if (prüfe(GESCHWEIFT_ZU)) null else ausdruck()
        return ZurückAnweisung(wert, pos)
    }

    private fun ausdruckOderZuweisung(): Anweisung {
        val ausdruck = ausdruck()
        if (prüfe(ZUWEISUNG)) {
            val pos = tokens[i++].position
            if (ausdruck !is Bezeichner && ausdruck !is FeldzugriffAusdruck && ausdruck !is IndexAusdruck) {
                fehler("Ungueltiges Zuweisungsziel", ausdruck.position)
            }
            return ZuweisungAnweisung(ausdruck, ausdruck(), pos)
        }
        return AusdruckAnweisung(ausdruck, ausdruck.position)
    }

    // ---- Ausdruecke (Praezedenzkletterung) ----------------------------------

    private fun ausdruck(): Ausdruck = oder()

    private fun oder(): Ausdruck {
        var links = und()
        while (prüfe(ODER)) {
            val op = tokens[i++]
            links = BinärAusdruck(links, op.typ, und(), op.position)
        }
        return links
    }

    private fun und(): Ausdruck {
        var links = gleichheit()
        while (prüfe(UND)) {
            val op = tokens[i++]
            links = BinärAusdruck(links, op.typ, gleichheit(), op.position)
        }
        return links
    }

    private fun gleichheit(): Ausdruck {
        var links = vergleich()
        while (aktuell().typ == GLEICH || aktuell().typ == UNGLEICH) {
            val op = tokens[i++]
            links = BinärAusdruck(links, op.typ, vergleich(), op.position)
        }
        return links
    }

    private fun vergleich(): Ausdruck {
        var links = summe()
        while (aktuell().typ in VERGLEICHSOPERATOREN) {
            val op = tokens[i++]
            links = BinärAusdruck(links, op.typ, summe(), op.position)
        }
        return links
    }

    private fun summe(): Ausdruck {
        var links = produkt()
        while (aktuell().typ == PLUS || aktuell().typ == MINUS) {
            val op = tokens[i++]
            links = BinärAusdruck(links, op.typ, produkt(), op.position)
        }
        return links
    }

    private fun produkt(): Ausdruck {
        var links = unär()
        while (aktuell().typ in PRODUKTOPERATOREN) {
            val op = tokens[i++]
            links = BinärAusdruck(links, op.typ, unär(), op.position)
        }
        return links
    }

    private fun unär(): Ausdruck {
        if (aktuell().typ == MINUS || aktuell().typ == NICHT) {
            val op = tokens[i++]
            return UnärAusdruck(op.typ, unär(), op.position)
        }
        return nachgestellt()
    }

    private fun nachgestellt(): Ausdruck {
        var ausdruck = primär()
        while (true) {
            ausdruck = when (aktuell().typ) {
                KLAMMER_AUF -> {
                    val pos = tokens[i++].position
                    val argumente = argumentListe()
                    AufrufAusdruck(ausdruck, argumente, pos)
                }
                ECKIG_AUF -> {
                    val pos = tokens[i++].position
                    val index = ausdruck()
                    erwarte(ECKIG_ZU, "']'")
                    IndexAusdruck(ausdruck, index, pos)
                }
                PUNKT -> {
                    val pos = tokens[i++].position
                    val feld = erwarte(BEZEICHNER, "Feld- oder Methodenname").text
                    FeldzugriffAusdruck(ausdruck, feld, pos)
                }
                else -> return ausdruck
            }
        }
    }

    private fun argumentListe(): List<Ausdruck> {
        val argumente = mutableListOf<Ausdruck>()
        if (!prüfe(KLAMMER_ZU)) {
            do {
                argumente.add(ausdruck())
            } while (passt(KOMMA))
        }
        erwarte(KLAMMER_ZU, "')'")
        return argumente
    }

    private fun primär(): Ausdruck {
        val tok = aktuell()
        return when (tok.typ) {
            GANZZAHL_LITERAL -> { i++; GanzzahlLiteral(tok.literal as Long, tok.position) }
            KOMMAZAHL_LITERAL -> { i++; KommazahlLiteral(tok.literal as Double, tok.position) }
            TEXT_LITERAL -> { i++; TextLiteral(tok.literal as String, tok.position) }
            ZEICHEN_LITERAL -> { i++; ZeichenLiteral(tok.literal as Char, tok.position) }
            WAHR -> { i++; WahrheitLiteral(true, tok.position) }
            FALSCH -> { i++; WahrheitLiteral(false, tok.position) }
            NICHTS -> { i++; NichtsLiteral(tok.position) }
            DIES -> { i++; DiesAusdruck(tok.position) }
            BEZEICHNER -> { i++; Bezeichner(tok.text, tok.position) }
            NEU -> neuAusdruck()
            WÄHLE -> wähleAusdruck()
            KLAMMER_AUF -> if (istLambda()) lambdaAusdruck() else gruppierung()
            else -> fehler("Unerwartetes Token im Ausdruck: '${tok.text.ifEmpty { "Dateiende" }}'", tok.position)
        }
    }

    private fun gruppierung(): Ausdruck {
        erwarte(KLAMMER_AUF, "'('")
        val ausdruck = ausdruck()
        erwarte(KLAMMER_ZU, "')'")
        return ausdruck
    }

    private fun neuAusdruck(): NeuAusdruck {
        val pos = erwarte(NEU, "'neu'").position
        val typname = erwarte(BEZEICHNER, "Typname nach 'neu'").text
        erwarte(KLAMMER_AUF, "'('")
        return NeuAusdruck(typname, argumentListe(), pos)
    }

    private fun wähleAusdruck(): WähleAusdruck {
        val pos = erwarte(WÄHLE, "'wähle'").position
        val subjekt = ausdruck()
        erwarte(GESCHWEIFT_AUF, "'{'")
        val fälle = mutableListOf<WähleFall>()
        while (prüfe(FALL)) {
            val fpos = tokens[i++].position
            val muster = ausdruck()
            erwarte(PFEIL, "'->'")
            fälle.add(WähleFall(muster, ausdruck(), fpos))
        }
        erwarte(SONST, "'sonst' (jeder 'wähle'-Ausdruck braucht einen 'sonst'-Zweig)")
        erwarte(PFEIL, "'->'")
        val sonst = ausdruck()
        erwarte(GESCHWEIFT_ZU, "'}'")
        return WähleAusdruck(subjekt, fälle, sonst, pos)
    }

    /** Prueft per Vorausschau, ob ab `(` ein Lambda `(...) ->` beginnt. */
    private fun istLambda(): Boolean {
        var tiefe = 0
        var j = i
        while (j < tokens.size) {
            when (tokens[j].typ) {
                KLAMMER_AUF -> tiefe++
                KLAMMER_ZU -> {
                    tiefe--
                    if (tiefe == 0) {
                        return j + 1 < tokens.size && tokens[j + 1].typ == PFEIL
                    }
                }
                DATEI_ENDE -> return false
                else -> {}
            }
            j++
        }
        return false
    }

    private fun lambdaAusdruck(): LambdaAusdruck {
        val pos = erwarte(KLAMMER_AUF, "'('").position
        val parameter = mutableListOf<Parameter>()
        if (!prüfe(KLAMMER_ZU)) {
            do {
                parameter.add(parameter())
            } while (passt(KOMMA))
        }
        erwarte(KLAMMER_ZU, "')'")
        erwarte(PFEIL, "'->'")
        return LambdaAusdruck(parameter, ausdruck(), pos)
    }

    private companion object {
        val VERGLEICHSOPERATOREN = setOf(KLEINER, KLEINER_GLEICH, GRÖSSER, GRÖSSER_GLEICH)
        val PRODUKTOPERATOREN = setOf(STERN, SCHRÄGSTRICH, PROZENT)
    }
}
