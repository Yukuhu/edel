package edel.laufzeit

import edel.fehler.LaufzeitFehler

/** Eine lexikalische Umgebung: Namen-zu-Wert-Bindungen mit Elternverkettung. */
class Umgebung(val eltern: Umgebung? = null) {
    private val werte = HashMap<String, Wert>()

    fun definiere(name: String, wert: Wert) {
        werte[name] = wert
    }

    fun hole(name: String): Wert =
        werte[name]
            ?: eltern?.hole(name)
            ?: throw LaufzeitFehler("Unbekannter Name: '$name'")

    fun setze(name: String, wert: Wert) {
        when {
            werte.containsKey(name) -> werte[name] = wert
            eltern != null -> eltern.setze(name, wert)
            else -> throw LaufzeitFehler("Unbekannter Name: '$name'")
        }
    }
}
