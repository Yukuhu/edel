package edel.parser

import edel.fehler.Position
import edel.lexer.TokenTyp

// ===========================================================================
// Typausdruecke (Typ-Annotationen im Quelltext)
// ===========================================================================

sealed class Typausdruck {
    abstract val position: Position
}

/** Ein benannter Typ, evtl. mit Typargumenten: `Ganzzahl`, `Liste<Text>`, `Punkt`. */
class EinfacherTypausdruck(
    val name: String,
    val typargumente: List<Typausdruck>,
    override val position: Position,
) : Typausdruck()

/** Ein Funktionstyp: `(Ganzzahl, Text) -> Wahrheit`. */
class FunktionsTypausdruck(
    val parameter: List<Typausdruck>,
    val rückgabe: Typausdruck,
    override val position: Position,
) : Typausdruck()

// ===========================================================================
// Gemeinsame Bausteine
// ===========================================================================

/** Ein Parameter einer Funktion, Methode oder eines Lambdas. */
class Parameter(val name: String, val typ: Typausdruck, val position: Position)

enum class Sichtbarkeit { ÖFFENTLICH, PRIVAT, GESCHÜTZT, STANDARD }

// ===========================================================================
// Ausdruecke
// ===========================================================================

sealed class Ausdruck {
    abstract val position: Position
}

class GanzzahlLiteral(val wert: Long, override val position: Position) : Ausdruck()
class KommazahlLiteral(val wert: Double, override val position: Position) : Ausdruck()
class TextLiteral(val wert: String, override val position: Position) : Ausdruck()
class ZeichenLiteral(val wert: Char, override val position: Position) : Ausdruck()
class WahrheitLiteral(val wert: Boolean, override val position: Position) : Ausdruck()
class NichtsLiteral(override val position: Position) : Ausdruck()

/** Ein Namensbezug: Variable oder Funktion. */
class Bezeichner(val name: String, override val position: Position) : Ausdruck()

/** Bezug auf das aktuelle Objekt innerhalb einer Methode. */
class DiesAusdruck(override val position: Position) : Ausdruck()

class UnärAusdruck(
    val operator: TokenTyp,
    val operand: Ausdruck,
    override val position: Position,
) : Ausdruck()

class BinärAusdruck(
    val links: Ausdruck,
    val operator: TokenTyp,
    val rechts: Ausdruck,
    override val position: Position,
) : Ausdruck()

class AufrufAusdruck(
    val ziel: Ausdruck,
    val argumente: List<Ausdruck>,
    override val position: Position,
) : Ausdruck()

class IndexAusdruck(
    val ziel: Ausdruck,
    val index: Ausdruck,
    override val position: Position,
) : Ausdruck()

class FeldzugriffAusdruck(
    val ziel: Ausdruck,
    val feld: String,
    override val position: Position,
) : Ausdruck()

/** Erzeugung eines Datensatzes oder Klassenobjekts: `neu Punkt(3, 4)`. */
class NeuAusdruck(
    val typname: String,
    val argumente: List<Ausdruck>,
    override val position: Position,
) : Ausdruck()

class LambdaAusdruck(
    val parameter: List<Parameter>,
    val körper: Ausdruck,
    override val position: Position,
) : Ausdruck()

class WähleFall(val muster: Ausdruck, val ergebnis: Ausdruck, val position: Position)

class WähleAusdruck(
    val subjekt: Ausdruck,
    val fälle: List<WähleFall>,
    val sonst: Ausdruck,
    override val position: Position,
) : Ausdruck()

// ===========================================================================
// Anweisungen
// ===========================================================================

sealed class Anweisung {
    abstract val position: Position
}

/** `sei`-/`ver`-Bindung; [wandelbar] unterscheidet die beiden. */
class SeiAnweisung(
    val name: String,
    val typannotation: Typausdruck?,
    val initialwert: Ausdruck,
    val wandelbar: Boolean,
    override val position: Position,
) : Anweisung()

class AusdruckAnweisung(val ausdruck: Ausdruck, override val position: Position) : Anweisung()

class ZuweisungAnweisung(
    val ziel: Ausdruck,
    val wert: Ausdruck,
    override val position: Position,
) : Anweisung()

class WennAnweisung(
    val bedingung: Ausdruck,
    val dann: Block,
    val sonst: Anweisung?, // Block oder eine weitere WennAnweisung
    override val position: Position,
) : Anweisung()

class SolangeAnweisung(
    val bedingung: Ausdruck,
    val körper: Block,
    override val position: Position,
) : Anweisung()

class FürInAnweisung(
    val variable: String,
    val iterierbar: Ausdruck,
    val körper: Block,
    override val position: Position,
) : Anweisung()

class FürVonBisAnweisung(
    val variable: String,
    val von: Ausdruck,
    val bis: Ausdruck,
    val körper: Block,
    override val position: Position,
) : Anweisung()

class ZurückAnweisung(val wert: Ausdruck?, override val position: Position) : Anweisung()
class BrichAnweisung(override val position: Position) : Anweisung()
class WeiterAnweisung(override val position: Position) : Anweisung()

class Block(val anweisungen: List<Anweisung>, override val position: Position) : Anweisung()

// ===========================================================================
// Deklarationen (Top-Level)
// ===========================================================================

sealed class Deklaration {
    abstract val name: String
    abstract val position: Position
}

class FunktionDeklaration(
    override val name: String,
    val parameter: List<Parameter>,
    val rückgabetyp: Typausdruck?,
    val körper: Block,
    val statisch: Boolean,
    val sichtbarkeit: Sichtbarkeit,
    override val position: Position,
) : Deklaration()

class DatensatzDeklaration(
    override val name: String,
    val felder: List<Parameter>,
    override val position: Position,
) : Deklaration()

/** Ein Feld einer Klasse. Felder ohne [initialwert] werden zu Konstruktorparametern. */
class FeldDeklaration(
    val name: String,
    val typ: Typausdruck,
    val initialwert: Ausdruck?,
    val wandelbar: Boolean,
    val position: Position,
)

class KlasseDeklaration(
    override val name: String,
    val oberklasse: String?,
    val schnittstellen: List<String>,
    val felder: List<FeldDeklaration>,
    val methoden: List<FunktionDeklaration>,
    override val position: Position,
) : Deklaration()

class AufzählungDeklaration(
    override val name: String,
    val varianten: List<String>,
    override val position: Position,
) : Deklaration()

class MethodenSignatur(
    val name: String,
    val parameter: List<Parameter>,
    val rückgabetyp: Typausdruck?,
    val position: Position,
)

class SchnittstelleDeklaration(
    override val name: String,
    val methoden: List<MethodenSignatur>,
    override val position: Position,
) : Deklaration()

class ImportDeklaration(override val name: String, override val position: Position) : Deklaration()
class PaketDeklaration(override val name: String, override val position: Position) : Deklaration()

/** Das gesamte uebersetzte Programm. */
class Programm(val deklarationen: List<Deklaration>)
