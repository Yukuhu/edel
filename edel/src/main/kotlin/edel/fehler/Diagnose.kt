package edel.fehler

/** Eine 1-basierte Position im Quelltext, optional mit Dateipfad. */
data class Position(val zeile: Int, val spalte: Int, val datei: String? = null) {
    override fun toString(): String =
        if (datei == null) "$zeile:$spalte" else "$datei:$zeile:$spalte"
}

/** Eine einzelne Fehlermeldung mit Quellort. */
data class Diagnose(val meldung: String, val position: Position) {
    fun formatiert(): String = "Fehler [$position]: $meldung"
}

/** Sammelt mehrere Diagnosen waehrend der semantischen Analyse. */
class DiagnoseSammler {
    private val eintraege = mutableListOf<Diagnose>()
    val diagnosen: List<Diagnose> get() = eintraege
    val hatFehler: Boolean get() = eintraege.isNotEmpty()

    fun melde(meldung: String, position: Position) {
        eintraege.add(Diagnose(meldung, position))
    }
}

/** Wird bei nicht behebbaren Lexer- oder Parserfehlern geworfen. */
class QuellFehler(val diagnose: Diagnose) : RuntimeException(diagnose.formatiert())

/** Wird bei Fehlern zur Laufzeit des Interpreters geworfen. */
class LaufzeitFehler(meldung: String) : RuntimeException(meldung)

/**
 * Wird geworfen, wenn das Bytecode-Backend auf eine Sprachfunktion trifft, die
 * es (noch) nicht unterstuetzt. Das Programm bleibt mit `edel starte` lauffaehig.
 */
class NichtUnterstützt(val diagnose: Diagnose) : RuntimeException(diagnose.formatiert())
