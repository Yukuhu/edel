package edel.laufzeit

import edel.parser.Anweisung
import edel.parser.KlasseDeklaration
import edel.parser.Parameter

/** Ein Laufzeitwert eines Edel-Programms. */
sealed class Wert

// Die Grundwerte sind Datenklassen, damit sie als Abbildungsschluessel taugen.
data class GanzzahlWert(val wert: Long) : Wert()
data class KommazahlWert(val wert: Double) : Wert()
data class TextWert(val wert: String) : Wert()
data class WahrheitWert(val wert: Boolean) : Wert()
data class ZeichenWert(val wert: Char) : Wert()

object NichtsWert : Wert()

class ListeWert(val elemente: MutableList<Wert>) : Wert()
class AbbildungWert(val eintraege: LinkedHashMap<Wert, Wert>) : Wert()
class PaarWert(val erst: Wert, val zweit: Wert) : Wert()

class DatensatzWert(val typname: String, val felder: LinkedHashMap<String, Wert>) : Wert()
class ObjektWert(val klasse: KlasseDeklaration, val felder: LinkedHashMap<String, Wert>) : Wert()

data class AufzählungWert(val typname: String, val variante: String) : Wert()

/** Ein aufrufbarer Wert: benannte Funktion, Methode oder Lambda. */
class FunktionWert(
    val parameter: List<Parameter>,
    val körper: Anweisung,
    val abschluss: Umgebung,
    val name: String,
) : Wert()

/** Eine in Kotlin implementierte eingebaute Funktion. */
class EingebauteFunktion(val name: String, val funktion: (List<Wert>) -> Wert) : Wert()

/** Der Wert eines Aufzaehlungstyps; Feldzugriff liefert seine Varianten. */
class AufzählungstypWert(val typname: String, val varianten: List<String>) : Wert()

/** Strukturelle Gleichheit zweier Werte (Semantik des `==`-Operators). */
fun gleichheit(a: Wert, b: Wert): Boolean = when {
    a is GanzzahlWert && b is GanzzahlWert -> a.wert == b.wert
    a is KommazahlWert || b is KommazahlWert ->
        (a is GanzzahlWert || a is KommazahlWert) && (b is GanzzahlWert || b is KommazahlWert) &&
            zahlAlsDouble(a) == zahlAlsDouble(b)
    a is TextWert && b is TextWert -> a.wert == b.wert
    a is WahrheitWert && b is WahrheitWert -> a.wert == b.wert
    a is ZeichenWert && b is ZeichenWert -> a.wert == b.wert
    a is NichtsWert && b is NichtsWert -> true
    a is AufzählungWert && b is AufzählungWert -> a.typname == b.typname && a.variante == b.variante
    a is PaarWert && b is PaarWert -> gleichheit(a.erst, b.erst) && gleichheit(a.zweit, b.zweit)
    a is ListeWert && b is ListeWert ->
        a.elemente.size == b.elemente.size &&
            a.elemente.indices.all { gleichheit(a.elemente[it], b.elemente[it]) }
    a is DatensatzWert && b is DatensatzWert ->
        a.typname == b.typname && a.felder.keys == b.felder.keys &&
            a.felder.all { gleichheit(it.value, b.felder.getValue(it.key)) }
    else -> a === b
}

private fun zahlAlsDouble(w: Wert): Double = when (w) {
    is GanzzahlWert -> w.wert.toDouble()
    is KommazahlWert -> w.wert
    else -> Double.NaN
}

/** Menschenlesbare Darstellung eines Wertes (fuer `drucke` und Textverkettung). */
fun darstelle(w: Wert): String = when (w) {
    is GanzzahlWert -> w.wert.toString()
    is KommazahlWert -> w.wert.toString()
    is TextWert -> w.wert
    is WahrheitWert -> if (w.wert) "wahr" else "falsch"
    is ZeichenWert -> w.wert.toString()
    is NichtsWert -> "nichts"
    is ListeWert -> w.elemente.joinToString(", ", "[", "]") { darstelle(it) }
    is AbbildungWert -> w.eintraege.entries
        .joinToString(", ", "{", "}") { "${darstelle(it.key)}: ${darstelle(it.value)}" }
    is PaarWert -> "(${darstelle(w.erst)}, ${darstelle(w.zweit)})"
    is DatensatzWert -> w.felder.values.joinToString(", ", "${w.typname}(", ")") { darstelle(it) }
    is ObjektWert -> w.felder.entries
        .joinToString(", ", "${w.klasse.name}(", ")") { "${it.key}=${darstelle(it.value)}" }
    is AufzählungWert -> "${w.typname}.${w.variante}"
    is FunktionWert -> "<funktion ${w.name}>"
    is EingebauteFunktion -> "<eingebaut ${w.name}>"
    is AufzählungstypWert -> "<aufzählung ${w.typname}>"
}
