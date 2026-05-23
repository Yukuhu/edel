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
) : Typausdruck() {
    /** Vom Resolver gesetzter vollqualifizierter Name (z. B. `geometrie.Punkt`),
     *  falls [name] auf einen benutzerdefinierten Typ verweist; sonst `null`. */
    var aufgelöst: String? = null
}

/** Ein Funktionstyp: `(Ganzzahl, Text) -> Wahrheit`. */
class FunktionsTypausdruck(
    val parameter: List<Typausdruck>,
    val rückgabe: Typausdruck,
    override val position: Position,
) : Typausdruck()

/** Ein nullbarer Typ: `Basis?`. */
class NullbarTypausdruck(val basis: Typausdruck, override val position: Position) : Typausdruck()

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
class Bezeichner(val name: String, override val position: Position) : Ausdruck() {
    /** Vom Resolver gesetzter vollqualifizierter Name (z. B. `geometrie.entfernung`),
     *  falls [name] auf eine globale Deklaration verweist; bei lokalen
     *  Bindungen oder Eingebauten bleibt das Feld `null`. */
    var aufgelöst: String? = null
}

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

/** Feld- oder Methodenzugriff; [sicher] markiert den `?.`-Operator. */
class FeldzugriffAusdruck(
    val ziel: Ausdruck,
    val feld: String,
    override val position: Position,
    val sicher: Boolean = false,
) : Ausdruck()

/** Elvis-Operator `links ?: rechts`: liefert [rechts], falls [links] `nichts` ist. */
class ElvisAusdruck(
    val links: Ausdruck,
    val rechts: Ausdruck,
    override val position: Position,
) : Ausdruck()

/** Nicht-null-Zusicherung `operand!!`: wirft zur Laufzeit, falls der Wert `nichts` ist. */
class NichtNullAusdruck(val operand: Ausdruck, override val position: Position) : Ausdruck()

/** Erzeugung eines Datensatzes oder Klassenobjekts: `neu Punkt(3, 4)`. */
class NeuAusdruck(
    val typname: String,
    val argumente: List<Ausdruck>,
    override val position: Position,
) : Ausdruck() {
    /** Vom Resolver gesetzter vollqualifizierter Typname; siehe [Bezeichner.aufgelöst]. */
    var aufgelöst: String? = null
}

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
    override var name: String,
    val parameter: List<Parameter>,
    val rückgabetyp: Typausdruck?,
    val körper: Block,
    val statisch: Boolean,
    val sichtbarkeit: Sichtbarkeit,
    override val position: Position,
) : Deklaration()

class DatensatzDeklaration(
    override var name: String,
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
    override var name: String,
    var oberklasse: String?,
    var schnittstellen: List<String>,
    val felder: List<FeldDeklaration>,
    val methoden: List<FunktionDeklaration>,
    override val position: Position,
) : Deklaration()

class AufzählungDeklaration(
    override var name: String,
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
    override var name: String,
    val methoden: List<MethodenSignatur>,
    override val position: Position,
) : Deklaration()

/**
 * Das gesamte uebersetzte Programm einer einzelnen Quelldatei.
 *
 * `paket` und `importe` werden vom Parser aus den `paket`/`importiere`-
 * Direktiven gesammelt und nicht in [deklarationen] gefuehrt — sie sind
 * Modulmetadaten, keine ausfuehrbaren Deklarationen.
 *
 *  - [paket]   ist der vollqualifizierte Paketname dieser Datei (`a.b.c`)
 *              oder `null`, wenn keine `paket`-Direktive vorliegt.
 *  - [importe] bildet die vom Quelltext sichtbaren Kurznamen auf ihren
 *              vollqualifizierten Namen ab (z. B. `"Punkt" -> "geometrie.Punkt"`).
 */
class Programm(
    val deklarationen: List<Deklaration>,
    val paket: String? = null,
    val importe: Map<String, String> = emptyMap(),
)
