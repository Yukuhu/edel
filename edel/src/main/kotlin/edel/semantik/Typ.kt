package edel.semantik

/** Ein statischer Typ der Sprache Edel. */
sealed class Typ {
    abstract fun anzeige(): String
    override fun toString(): String = anzeige()
}

// ---- Eingebaute Grundtypen --------------------------------------------------

object GanzzahlTyp : Typ() { override fun anzeige() = "Ganzzahl" }
object KommazahlTyp : Typ() { override fun anzeige() = "Kommazahl" }
object TextTyp : Typ() { override fun anzeige() = "Text" }
object WahrheitTyp : Typ() { override fun anzeige() = "Wahrheit" }
object ZeichenTyp : Typ() { override fun anzeige() = "Zeichen" }
object NichtsTyp : Typ() { override fun anzeige() = "Nichts" }

/** Platzhalter zur Fehlererholung; vertraegt sich mit jedem anderen Typ. */
object FehlerTyp : Typ() { override fun anzeige() = "<Fehler>" }

// ---- Eingebaute Sammlungen --------------------------------------------------

class ListeTyp(val element: Typ) : Typ() {
    override fun anzeige() = "Liste<${element.anzeige()}>"
    override fun equals(other: Any?) = other is ListeTyp && other.element == element
    override fun hashCode() = 31 * element.hashCode() + 1
}

class AbbildungTyp(val schlüssel: Typ, val wert: Typ) : Typ() {
    override fun anzeige() = "Abbildung<${schlüssel.anzeige()}, ${wert.anzeige()}>"
    override fun equals(other: Any?) =
        other is AbbildungTyp && other.schlüssel == schlüssel && other.wert == wert
    override fun hashCode() = 31 * schlüssel.hashCode() + wert.hashCode()
}

class PaarTyp(val erst: Typ, val zweit: Typ) : Typ() {
    override fun anzeige() = "Paar<${erst.anzeige()}, ${zweit.anzeige()}>"
    override fun equals(other: Any?) =
        other is PaarTyp && other.erst == erst && other.zweit == zweit
    override fun hashCode() = 31 * erst.hashCode() + zweit.hashCode()
}

class FunktionsTyp(val parameter: List<Typ>, val rückgabe: Typ) : Typ() {
    override fun anzeige() =
        "(${parameter.joinToString(", ") { it.anzeige() }}) -> ${rückgabe.anzeige()}"
    override fun equals(other: Any?) =
        other is FunktionsTyp && other.parameter == parameter && other.rückgabe == rückgabe
    override fun hashCode() = 31 * parameter.hashCode() + rückgabe.hashCode()
}

// ---- Benutzerdefinierte Typen (Gleichheit ueber den Namen) ------------------

class DatensatzTyp(val name: String) : Typ() {
    val felder = LinkedHashMap<String, Typ>()
    override fun anzeige() = name
    override fun equals(other: Any?) = other is DatensatzTyp && other.name == name
    override fun hashCode() = name.hashCode()
}

class FeldInfo(val typ: Typ, val wandelbar: Boolean, val istKonstruktorParameter: Boolean)

class KlassenTyp(val name: String) : Typ() {
    var oberklasse: KlassenTyp? = null
    val schnittstellen = mutableListOf<SchnittstellenTyp>()
    val felder = LinkedHashMap<String, FeldInfo>()
    val methoden = LinkedHashMap<String, FunktionsTyp>()

    /** Sucht ein Feld in dieser Klasse und allen Oberklassen. */
    fun findeFeld(feldname: String): FeldInfo? =
        felder[feldname] ?: oberklasse?.findeFeld(feldname)

    /** Sucht eine Methode in dieser Klasse und allen Oberklassen. */
    fun findeMethode(methodenname: String): FunktionsTyp? =
        methoden[methodenname] ?: oberklasse?.findeMethode(methodenname)

    fun istUntertypVon(anderer: KlassenTyp): Boolean =
        name == anderer.name || (oberklasse?.istUntertypVon(anderer) ?: false)

    fun erfülltSchnittstelle(s: SchnittstellenTyp): Boolean =
        schnittstellen.any { it.name == s.name } || (oberklasse?.erfülltSchnittstelle(s) ?: false)

    override fun anzeige() = name
    override fun equals(other: Any?) = other is KlassenTyp && other.name == name
    override fun hashCode() = name.hashCode()
}

class AufzählungTyp(val name: String) : Typ() {
    val varianten = mutableListOf<String>()
    override fun anzeige() = name
    override fun equals(other: Any?) = other is AufzählungTyp && other.name == name
    override fun hashCode() = name.hashCode()
}

class SchnittstellenTyp(val name: String) : Typ() {
    val methoden = LinkedHashMap<String, FunktionsTyp>()
    override fun anzeige() = name
    override fun equals(other: Any?) = other is SchnittstellenTyp && other.name == name
    override fun hashCode() = name.hashCode()
}

// ---- Typbeziehungen ---------------------------------------------------------

fun istNumerisch(typ: Typ): Boolean = typ == GanzzahlTyp || typ == KommazahlTyp

/** Ist ein Wert vom Typ [quelle] einem Ziel vom Typ [ziel] zuweisbar? */
fun istZuweisbar(ziel: Typ, quelle: Typ): Boolean {
    if (ziel == FehlerTyp || quelle == FehlerTyp) return true
    if (ziel == quelle) return true
    // Numerische Erweiterung: Ganzzahl passt in Kommazahl.
    if (ziel == KommazahlTyp && quelle == GanzzahlTyp) return true
    // Klassenhierarchie und Schnittstellen.
    if (quelle is KlassenTyp) {
        if (ziel is KlassenTyp && quelle.istUntertypVon(ziel)) return true
        if (ziel is SchnittstellenTyp && quelle.erfülltSchnittstelle(ziel)) return true
    }
    return false
}

/** Kleinster gemeinsamer Typ zweier Zweige (z. B. fuer `wähle`-Arme). */
fun gemeinsamerTyp(a: Typ, b: Typ): Typ = when {
    a == FehlerTyp -> b
    b == FehlerTyp -> a
    istZuweisbar(a, b) -> a
    istZuweisbar(b, a) -> b
    istNumerisch(a) && istNumerisch(b) -> KommazahlTyp
    else -> FehlerTyp
}
