package edel.semantik

import edel.fehler.DiagnoseSammler
import edel.fehler.Position
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
                "Ergebnis" -> if (erwarteAnzahl(1)) ErgebnisTyp(argumente[0]) else FehlerTyp
                else -> {
                    val schluessel = ausdruck.aufgelöst ?: ausdruck.name
                    val typ = benutzertypen[schluessel]
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
        val EINGEBAUTE_NAMEN =
            setOf("drucke", "lies", "länge", "Liste", "Abbildung", "Paar", "Erfolg", "Fehler")

        val EINGEBAUTE_TYPEN =
            setOf(
                "Ganzzahl", "Kommazahl", "Text", "Wahrheit", "Zeichen", "Nichts",
                "Liste", "Abbildung", "Paar", "Ergebnis",
            )

        /**
         * Aufloesungs-Vorlauf fuer ein Mehrdateienprojekt: validiert `paket`- und
         * `importiere`-Direktiven, schreibt Deklarationsnamen sowie qualifizierte
         * Bezuege jeder Datei auf ihre vollqualifizierte Form (FQN) um und fuehrt
         * die Deklarationen aller Dateien zu einem einzigen [Programm] zusammen,
         * das anschliessend vom bestehenden Resolver typisiert werden kann.
         *
         *  - [dateien]   Eingangspfad -> bereits geparstes [Programm] der Datei.
         *  - [eintrag]   Pfad der Einstiegsdatei (ihre `start`-Funktion ist die
         *                Programmausfuehrung); muss in [dateien] vorhanden sein.
         *
         * Liefert das zusammengefuehrte [Programm]; die Einzelobjekte aus
         * [dateien] wurden in-place qualifiziert und sind danach Teil davon.
         */
        fun führeZusammen(
            dateien: Map<String, Programm>,
            eintrag: String,
            diagnosen: DiagnoseSammler,
        ): Programm {
            // Pro Datei: kurze Deklarationsnamen + ihre FQN.
            val perDateiLokale = LinkedHashMap<String, Map<String, String>>()
            for ((pfad, programm) in dateien) {
                val lokale = LinkedHashMap<String, String>()
                for (d in programm.deklarationen) {
                    val fqn = if (programm.paket.isNullOrEmpty()) d.name else "${programm.paket}.${d.name}"
                    if (d.name in lokale) {
                        diagnosen.melde(
                            "Name '${d.name}' ist in '$pfad' bereits deklariert",
                            d.position,
                        )
                    } else {
                        lokale[d.name] = fqn
                    }
                }
                perDateiLokale[pfad] = lokale
            }

            // Globale FQN -> deklarierende Datei (zur Aufloesung von Importen).
            val globaleFqn = HashMap<String, String>()
            for ((pfad, lokale) in perDateiLokale) {
                for ((_, fqn) in lokale) {
                    val frueher = globaleFqn.put(fqn, pfad)
                    if (frueher != null) {
                        diagnosen.melde(
                            "Vollqualifizierter Name '$fqn' wird sowohl in '$frueher' als auch in '$pfad' deklariert",
                            dateien[pfad]!!.deklarationen.first { d ->
                                (dateien[pfad]!!.paket?.plus(".") ?: "") + d.name == fqn
                            }.position,
                        )
                    }
                }
            }

            // Pro Datei: alle sichtbaren Kurznamen (Lokale + Importe) auf FQN abbilden.
            for ((pfad, programm) in dateien) {
                val kurzAufFqn = LinkedHashMap<String, String>()
                for ((kurz, fqn) in perDateiLokale[pfad]!!) kurzAufFqn[kurz] = fqn
                for ((kurz, fqn) in programm.importe) {
                    if (kurz in kurzAufFqn) {
                        diagnosen.melde(
                            "Import '$kurz' kollidiert mit einer Deklaration im selben Modul",
                            programm.deklarationen.firstOrNull()?.position ?: Position(1, 1, pfad),
                        )
                        continue
                    }
                    if (fqn !in globaleFqn && fqn.substringAfterLast('.') !in EINGEBAUTE_NAMEN) {
                        diagnosen.melde(
                            "Importierter Name '$fqn' wurde im Projekt nicht gefunden",
                            programm.deklarationen.firstOrNull()?.position ?: Position(1, 1, pfad),
                        )
                        continue
                    }
                    kurzAufFqn[kurz] = fqn
                }
                NamensAuflöser(programm.paket, kurzAufFqn, diagnosen).rewrite(programm)
            }

            // Eintrag zuerst, damit die Hauptklasse im Bytecode-Backend stabil bleibt
            // und Diagnosen mit der Einstiegsdatei beginnen.
            val reihenfolge = (listOf(eintrag) + dateien.keys.filter { it != eintrag })
                .filter { it in dateien }
            val alle = mutableListOf<Deklaration>()
            for (pfad in reihenfolge) alle.addAll(dateien[pfad]!!.deklarationen)
            return Programm(alle, paket = null, importe = emptyMap())
        }
    }
}

// ===========================================================================
// Namensaufloesung pro Datei (Modul-FQN-Vorlauf)
// ===========================================================================

/**
 * Schreibt eine [Programm]-Datei in-place auf ihre vollqualifizierten Namen um:
 *
 *  - Top-Level-Deklarationen erhalten ihren FQN `paket.kurzname` als Name.
 *  - [Bezeichner], [EinfacherTypausdruck] und [NeuAusdruck], deren Kurzname in
 *    [kurzAufFqn] enthalten ist (lokale Deklarationen oder Importe), bekommen
 *    ihren FQN ueber das `aufgelöst`-Feld zugeordnet.
 *  - [KlasseDeklaration.oberklasse]/[KlasseDeklaration.schnittstellen] werden
 *    direkt auf die FQN-Form gesetzt.
 *
 * Lokale Bindungen (`sei`/`ver`, Parameter) bleiben unberuehrt; ihre Bezeichner
 * besitzen kein passendes Eintrag in [kurzAufFqn] -- das natuerliche Verschatten
 * gleichnamiger Globaler wird in den Folgephasen durch Reihenfolge im
 * [edel.laufzeit.Umgebung]-Lookup (kurzer Name zuerst, dann FQN) erreicht.
 */
private class NamensAuflöser(
    private val paket: String?,
    private val kurzAufFqn: Map<String, String>,
    private val diagnosen: DiagnoseSammler,
) {
    fun rewrite(programm: Programm) {
        // 1) Deklarationsnamen und Klassen-Vererbungsreferenzen auf FQN.
        for (d in programm.deklarationen) {
            d.qualifiziere()
            if (d is KlasseDeklaration) {
                d.oberklasse = d.oberklasse?.let { kurzAufFqn[it] ?: it }
                d.schnittstellen = d.schnittstellen.map { kurzAufFqn[it] ?: it }
            }
        }
        // 2) Reine Bezugsknoten in Bodies und Typannotationen.
        for (d in programm.deklarationen) walke(d)
    }

    private fun Deklaration.qualifiziere() {
        val fqn = if (paket.isNullOrEmpty()) name else "$paket.$name"
        when (this) {
            is FunktionDeklaration -> name = fqn
            is DatensatzDeklaration -> name = fqn
            is KlasseDeklaration -> name = fqn
            is AufzählungDeklaration -> name = fqn
            is SchnittstelleDeklaration -> name = fqn
        }
    }

    private fun walke(d: Deklaration) {
        when (d) {
            is FunktionDeklaration -> walkeFunktion(d)
            is DatensatzDeklaration -> d.felder.forEach { walkeTyp(it.typ) }
            is KlasseDeklaration -> {
                for (feld in d.felder) {
                    walkeTyp(feld.typ)
                    feld.initialwert?.let { walkeAusdruck(it) }
                }
                d.methoden.forEach { walkeFunktion(it) }
            }
            is SchnittstelleDeklaration -> {
                for (sig in d.methoden) {
                    sig.parameter.forEach { walkeTyp(it.typ) }
                    sig.rückgabetyp?.let { walkeTyp(it) }
                }
            }
            is AufzählungDeklaration -> {}
        }
    }

    private fun walkeFunktion(f: FunktionDeklaration) {
        f.parameter.forEach { walkeTyp(it.typ) }
        f.rückgabetyp?.let { walkeTyp(it) }
        walkeBlock(f.körper)
    }

    private fun walkeBlock(block: Block) {
        for (a in block.anweisungen) walkeAnweisung(a)
    }

    private fun walkeAnweisung(a: Anweisung) {
        when (a) {
            is SeiAnweisung -> {
                a.typannotation?.let { walkeTyp(it) }
                walkeAusdruck(a.initialwert)
            }
            is AusdruckAnweisung -> walkeAusdruck(a.ausdruck)
            is ZuweisungAnweisung -> { walkeAusdruck(a.ziel); walkeAusdruck(a.wert) }
            is WennAnweisung -> {
                walkeAusdruck(a.bedingung); walkeBlock(a.dann); a.sonst?.let { walkeAnweisung(it) }
            }
            is SolangeAnweisung -> { walkeAusdruck(a.bedingung); walkeBlock(a.körper) }
            is FürInAnweisung -> { walkeAusdruck(a.iterierbar); walkeBlock(a.körper) }
            is FürVonBisAnweisung -> { walkeAusdruck(a.von); walkeAusdruck(a.bis); walkeBlock(a.körper) }
            is ZurückAnweisung -> a.wert?.let { walkeAusdruck(it) }
            is BrichAnweisung, is WeiterAnweisung -> {}
            is Block -> walkeBlock(a)
        }
    }

    private fun walkeAusdruck(e: Ausdruck) {
        when (e) {
            is Bezeichner -> kurzAufFqn[e.name]?.let { e.aufgelöst = it }
            is NeuAusdruck -> {
                kurzAufFqn[e.typname]?.let { e.aufgelöst = it }
                e.argumente.forEach { walkeAusdruck(it) }
            }
            is UnärAusdruck -> walkeAusdruck(e.operand)
            is BinärAusdruck -> { walkeAusdruck(e.links); walkeAusdruck(e.rechts) }
            is AufrufAusdruck -> {
                walkeAusdruck(e.ziel); e.argumente.forEach { walkeAusdruck(it) }
            }
            is IndexAusdruck -> { walkeAusdruck(e.ziel); walkeAusdruck(e.index) }
            is FeldzugriffAusdruck -> walkeAusdruck(e.ziel)
            is ElvisAusdruck -> { walkeAusdruck(e.links); walkeAusdruck(e.rechts) }
            is NichtNullAusdruck -> walkeAusdruck(e.operand)
            is LambdaAusdruck -> {
                e.parameter.forEach { walkeTyp(it.typ) }
                walkeAusdruck(e.körper)
            }
            is WähleAusdruck -> {
                walkeAusdruck(e.subjekt)
                for (fall in e.fälle) { walkeAusdruck(fall.muster); walkeAusdruck(fall.ergebnis) }
                walkeAusdruck(e.sonst)
            }
            is GanzzahlLiteral, is KommazahlLiteral, is TextLiteral, is ZeichenLiteral,
            is WahrheitLiteral, is NichtsLiteral, is DiesAusdruck -> {}
        }
    }

    private fun walkeTyp(t: Typausdruck) {
        when (t) {
            is EinfacherTypausdruck -> {
                if (t.name !in Resolver.EINGEBAUTE_TYPEN) {
                    kurzAufFqn[t.name]?.let { t.aufgelöst = it }
                }
                t.typargumente.forEach { walkeTyp(it) }
            }
            is FunktionsTypausdruck -> {
                t.parameter.forEach { walkeTyp(it) }
                walkeTyp(t.rückgabe)
            }
            is NullbarTypausdruck -> walkeTyp(t.basis)
        }
    }
}
