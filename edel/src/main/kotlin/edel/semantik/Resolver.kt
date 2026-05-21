package edel.semantik

import edel.fehler.DiagnoseSammler
import edel.parser.*

/**
 * Loest Typ-Annotationen aus dem Quelltext in [Typ]-Objekte auf. Kennt die
 * eingebauten Typen sowie alle vom Resolver registrierten Benutzertypen.
 */
class TypAuflöser(
    private val benutzertypen: Map<String, Typ>,
    private val diagnosen: DiagnoseSammler,
) {
    fun auflöse(ausdruck: Typausdruck): Typ = when (ausdruck) {
        is NullbarTypausdruck -> nullbar(auflöse(ausdruck.basis))

        is FunktionsTypausdruck ->
            FunktionsTyp(ausdruck.parameter.map { auflöse(it) }, auflöse(ausdruck.rückgabe))

        is EinfacherTypausdruck -> {
            val argumente = ausdruck.typargumente.map { auflöse(it) }
            fun erwarteAnzahl(n: Int): Boolean {
                if (argumente.size != n) {
                    diagnosen.melde(
                        "Typ '${ausdruck.name}' erwartet $n Typargument(e), " +
                            "erhielt ${argumente.size}",
                        ausdruck.position,
                    )
                    return false
                }
                return true
            }
            when (ausdruck.name) {
                "Ganzzahl" -> GanzzahlTyp
                "Kommazahl" -> KommazahlTyp
                "Text" -> TextTyp
                "Wahrheit" -> WahrheitTyp
                "Zeichen" -> ZeichenTyp
                "Nichts" -> NichtsTyp
                "Liste" -> if (erwarteAnzahl(1)) ListeTyp(argumente[0]) else FehlerTyp
                "Abbildung" -> if (erwarteAnzahl(2)) AbbildungTyp(argumente[0], argumente[1]) else FehlerTyp
                "Paar" -> if (erwarteAnzahl(2)) PaarTyp(argumente[0], argumente[1]) else FehlerTyp
                else -> {
                    val typ = benutzertypen[ausdruck.name]
                    if (typ == null) {
                        diagnosen.melde("Unbekannter Typ: '${ausdruck.name}'", ausdruck.position)
                        FehlerTyp
                    } else {
                        typ
                    }
                }
            }
        }
    }
}

/** Gesammelte globale Symbole eines Programms. */
class GlobaleSymbole(
    val typen: Map<String, Typ>,
    val funktionen: Map<String, FunktionsTyp>,
    val funktionDeklarationen: Map<String, FunktionDeklaration>,
    val klassenDeklarationen: Map<String, KlasseDeklaration>,
    val datensatzDeklarationen: Map<String, DatensatzDeklaration>,
    val auflöser: TypAuflöser,
)

/**
 * Erste semantische Phase: registriert alle Top-Level-Deklarationen, baut die
 * Benutzertypen auf (Felder, Methoden, Vererbung) und meldet Namenskonflikte.
 */
class Resolver(private val programm: Programm, private val diagnosen: DiagnoseSammler) {

    fun auflösen(): GlobaleSymbole {
        val typen = LinkedHashMap<String, Typ>()
        val klassenDekl = LinkedHashMap<String, KlasseDeklaration>()
        val datensatzDekl = LinkedHashMap<String, DatensatzDeklaration>()

        // Phase 1: leere Typ-Rohlinge anlegen, damit sich Typen gegenseitig
        // referenzieren koennen.
        for (d in programm.deklarationen) {
            when (d) {
                is DatensatzDeklaration -> {
                    if (registriere(typen, d.name, d.position)) {
                        typen[d.name] = DatensatzTyp(d.name)
                        datensatzDekl[d.name] = d
                    }
                }
                is KlasseDeklaration -> {
                    if (registriere(typen, d.name, d.position)) {
                        typen[d.name] = KlassenTyp(d.name)
                        klassenDekl[d.name] = d
                    }
                }
                is AufzählungDeklaration -> {
                    if (registriere(typen, d.name, d.position)) {
                        val t = AufzählungTyp(d.name)
                        t.varianten.addAll(d.varianten)
                        typen[d.name] = t
                    }
                }
                is SchnittstelleDeklaration -> {
                    if (registriere(typen, d.name, d.position)) {
                        typen[d.name] = SchnittstellenTyp(d.name)
                    }
                }
                else -> {}
            }
        }

        val auflöser = TypAuflöser(typen, diagnosen)

        // Phase 2: Felder, Methoden und Vererbung fuellen.
        for (d in programm.deklarationen) {
            when (d) {
                is DatensatzDeklaration -> {
                    val t = typen[d.name] as? DatensatzTyp ?: continue
                    for (feld in d.felder) t.felder[feld.name] = auflöser.auflöse(feld.typ)
                }
                is KlasseDeklaration -> {
                    val t = typen[d.name] as? KlassenTyp ?: continue
                    if (d.oberklasse != null) {
                        val ober = typen[d.oberklasse]
                        if (ober is KlassenTyp) {
                            t.oberklasse = ober
                        } else {
                            diagnosen.melde(
                                "Unbekannte Oberklasse: '${d.oberklasse}'", d.position,
                            )
                        }
                    }
                    for (sname in d.schnittstellen) {
                        val s = typen[sname]
                        if (s is SchnittstellenTyp) {
                            t.schnittstellen.add(s)
                        } else {
                            diagnosen.melde("Unbekannte Schnittstelle: '$sname'", d.position)
                        }
                    }
                    for (feld in d.felder) {
                        t.felder[feld.name] = FeldInfo(
                            auflöser.auflöse(feld.typ),
                            feld.wandelbar,
                            istKonstruktorParameter = feld.initialwert == null,
                        )
                    }
                    for (methode in d.methoden) {
                        t.methoden[methode.name] = funktionsTyp(methode, auflöser)
                    }
                }
                is SchnittstelleDeklaration -> {
                    val t = typen[d.name] as? SchnittstellenTyp ?: continue
                    for (m in d.methoden) {
                        t.methoden[m.name] = FunktionsTyp(
                            m.parameter.map { auflöser.auflöse(it.typ) },
                            m.rückgabetyp?.let { auflöser.auflöse(it) } ?: NichtsTyp,
                        )
                    }
                }
                else -> {}
            }
        }

        // Phase 3: globale Funktionen registrieren.
        val funktionen = LinkedHashMap<String, FunktionsTyp>()
        val funktionDekl = LinkedHashMap<String, FunktionDeklaration>()
        for (d in programm.deklarationen) {
            if (d is FunktionDeklaration) {
                if (d.name in funktionDekl || d.name in typen) {
                    diagnosen.melde("Name '${d.name}' ist bereits vergeben", d.position)
                } else if (d.name in EINGEBAUTE_NAMEN) {
                    diagnosen.melde(
                        "'${d.name}' ist ein eingebauter Name und kann nicht ueberschrieben werden",
                        d.position,
                    )
                } else {
                    funktionen[d.name] = funktionsTyp(d, auflöser)
                    funktionDekl[d.name] = d
                }
            }
        }

        return GlobaleSymbole(typen, funktionen, funktionDekl, klassenDekl, datensatzDekl, auflöser)
    }

    private fun registriere(
        typen: Map<String, Typ>,
        name: String,
        position: edel.fehler.Position,
    ): Boolean {
        if (name in typen) {
            diagnosen.melde("Typ '$name' ist bereits deklariert", position)
            return false
        }
        return true
    }

    private fun funktionsTyp(d: FunktionDeklaration, auflöser: TypAuflöser): FunktionsTyp =
        FunktionsTyp(
            d.parameter.map { auflöser.auflöse(it.typ) },
            d.rückgabetyp?.let { auflöser.auflöse(it) } ?: NichtsTyp,
        )

    companion object {
        val EINGEBAUTE_NAMEN = setOf("drucke", "lies", "länge", "Liste", "Abbildung", "Paar")
    }
}
