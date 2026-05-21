package edel.fehler

/** Eine 1-basierte Position im Quelltext. */
data class Position(val zeile: Int, val spalte: Int) {
    override fun toString(): String = "$zeile:$spalte"
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
