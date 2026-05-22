package edel.semantik

import edel.fehler.DiagnoseSammler
import edel.fehler.Position
import edel.lexer.TokenTyp
import edel.parser.*

private class VariableInfo(val typ: Typ, val wandelbar: Boolean)

private class Geltungsbereich(val eltern: Geltungsbereich?) {
    private val variablen = HashMap<String, VariableInfo>()

    fun definiere(name: String, typ: Typ, wandelbar: Boolean) {
        variablen[name] = VariableInfo(typ, wandelbar)
    }

    fun finde(name: String): VariableInfo? = variablen[name] ?: eltern?.finde(name)
    fun istLokal(name: String): Boolean = variablen.containsKey(name)
}

/**
 * Zweite semantische Phase: statische Typpruefung mit lokaler Typinferenz.
 * Lokale Bindungen (`sei`/`ver`) leiten ihren Typ aus dem Anfangswert ab;
 * Funktions- und Methodensignaturen muessen explizit annotiert sein.
 */
class Typpruefer(
    private val programm: Programm,
    private val symbole: GlobaleSymbole,
    private val diagnosen: DiagnoseSammler,
) {
    private var aktuelleRückgabe: Typ = NichtsTyp
    private var aktuelleKlasse: KlassenTyp? = null
    private var schleifenTiefe = 0

    /** Typ jeder Namensverwendung; von der Parallelanalyse genutzt. */
    val bezeichnerTypen = HashMap<Bezeichner, Typ>()

    /** Ergebnistyp jedes binaeren Ausdrucks; von der Parallelanalyse genutzt. */
    val binärTypen = HashMap<BinärAusdruck, Typ>()

    /** Typ jeder `sei`-/`ver`-Bindung; von der Parallelanalyse genutzt. */
    val bindungsTypen = HashMap<SeiAnweisung, Typ>()

    fun prüfe() {
        for (d in programm.deklarationen) {
            when (d) {
                is FunktionDeklaration -> prüfeFunktion(d, klasse = null)
                is KlasseDeklaration -> prüfeKlasse(d)
                else -> {}
            }
        }
    }

    // ---- Deklarationen ------------------------------------------------------

    private fun prüfeFunktion(d: FunktionDeklaration, klasse: KlassenTyp?) {
        val basis = Geltungsbereich(null)
        for (p in d.parameter) {
            basis.definiere(p.name, symbole.auflöser.auflöse(p.typ), wandelbar = false)
        }
        val vorherKlasse = aktuelleKlasse
        val vorherRückgabe = aktuelleRückgabe
        aktuelleKlasse = klasse
        aktuelleRückgabe = d.rückgabetyp?.let { symbole.auflöser.auflöse(it) } ?: NichtsTyp
        prüfeBlock(d.körper, Geltungsbereich(basis))
        aktuelleKlasse = vorherKlasse
        aktuelleRückgabe = vorherRückgabe
    }

    private fun prüfeKlasse(d: KlasseDeklaration) {
        val klassenTyp = symbole.typen[d.name] as? KlassenTyp ?: return

        // Feldinitialisierer pruefen.
        for (feld in d.felder) {
            if (feld.initialwert != null) {
                val erwartet = symbole.auflöser.auflöse(feld.typ)
                val tatsächlich = prüfeAusdruck(feld.initialwert, Geltungsbereich(null), erwartet)
                if (!istZuweisbar(erwartet, tatsächlich)) {
                    diagnosen.melde(
                        "Feld '${feld.name}': erwartet $erwartet, erhielt $tatsächlich",
                        feld.position,
                    )
                }
            }
        }

        // Methodenruempfe pruefen.
        for (methode in d.methoden) {
            prüfeFunktion(methode, klassenTyp)
        }

        // Schnittstellenkonformitaet pruefen.
        for (schnittstelle in klassenTyp.schnittstellen) {
            for ((mname, signatur) in schnittstelle.methoden) {
                val vorhanden = klassenTyp.findeMethode(mname)
                if (vorhanden == null) {
                    diagnosen.melde(
                        "Klasse '${d.name}' erfuellt Schnittstelle '${schnittstelle.name}' " +
                            "nicht: Methode '$mname' fehlt",
                        d.position,
                    )
                } else if (vorhanden != signatur) {
                    diagnosen.melde(
                        "Klasse '${d.name}': Methode '$mname' hat Signatur $vorhanden, " +
                            "Schnittstelle '${schnittstelle.name}' verlangt $signatur",
                        d.position,
                    )
                }
            }
        }
    }

    // ---- Anweisungen --------------------------------------------------------

    private fun prüfeBlock(block: Block, bereich: Geltungsbereich) {
        for (anweisung in block.anweisungen) {
            prüfeAnweisung(anweisung, bereich)
            // Fluss-Verfeinerung: bricht ein 'wenn'-Zweig ab, gilt danach die
            // jeweils andere Bedingungsseite (z. B. nach 'wenn x == nichts { zurück }').
            if (anweisung is WennAnweisung) {
                if (terminiert(anweisung.dann)) {
                    verfeinereImBereich(bereich, verfeinerungWennFalsch(anweisung.bedingung))
                }
                val sonst = anweisung.sonst
                if (sonst != null && terminiert(sonst)) {
                    verfeinereImBereich(bereich, verfeinerungWennWahr(anweisung.bedingung))
                }
            }
        }
    }

    // ---- Nullsicherheit: Verfeinerung (smart cast) und Pruefung -------------

    /** Meldet einen Fehler, falls [typ] `nichts` sein kann; liefert den entnullten Typ. */
    private fun verlangeNichtNull(typ: Typ, position: Position, was: String): Typ = when {
        typ is NullbarTyp -> {
            diagnosen.melde("$was kann 'nichts' sein — benutze '?.', '?:' oder '!!'", position)
            typ.basis
        }
        typ == NichtsTyp -> {
            diagnosen.melde("$was ist immer 'nichts'", position)
            FehlerTyp
        }
        else -> typ
    }

    private fun terminiert(anweisung: Anweisung): Boolean = when (anweisung) {
        is ZurückAnweisung, is BrichAnweisung, is WeiterAnweisung -> true
        is Block -> anweisung.anweisungen.any { terminiert(it) }
        is WennAnweisung -> {
            val sonst = anweisung.sonst
            sonst != null && terminiert(anweisung.dann) && terminiert(sonst)
        }
        else -> false
    }

    /** Variablen, die garantiert nicht `nichts` sind, wenn [bedingung] wahr ist. */
    private fun verfeinerungWennWahr(bedingung: Ausdruck): Set<String> = when (bedingung) {
        is BinärAusdruck -> when (bedingung.operator) {
            TokenTyp.UNGLEICH -> nullPrüfungsname(bedingung)?.let { setOf(it) } ?: emptySet()
            TokenTyp.UND ->
                verfeinerungWennWahr(bedingung.links) + verfeinerungWennWahr(bedingung.rechts)
            TokenTyp.ODER ->
                verfeinerungWennWahr(bedingung.links) intersect verfeinerungWennWahr(bedingung.rechts)
            else -> emptySet()
        }
        is UnärAusdruck ->
            if (bedingung.operator == TokenTyp.NICHT) verfeinerungWennFalsch(bedingung.operand)
            else emptySet()
        else -> emptySet()
    }

    /** Variablen, die garantiert nicht `nichts` sind, wenn [bedingung] falsch ist. */
    private fun verfeinerungWennFalsch(bedingung: Ausdruck): Set<String> = when (bedingung) {
        is BinärAusdruck -> when (bedingung.operator) {
            TokenTyp.GLEICH -> nullPrüfungsname(bedingung)?.let { setOf(it) } ?: emptySet()
            TokenTyp.UND ->
                verfeinerungWennFalsch(bedingung.links) intersect verfeinerungWennFalsch(bedingung.rechts)
            TokenTyp.ODER ->
                verfeinerungWennFalsch(bedingung.links) + verfeinerungWennFalsch(bedingung.rechts)
            else -> emptySet()
        }
        is UnärAusdruck ->
            if (bedingung.operator == TokenTyp.NICHT) verfeinerungWennWahr(bedingung.operand)
            else emptySet()
        else -> emptySet()
    }

    /** Liefert den Variablennamen von `x == nichts` / `x != nichts`, sonst `null`. */
    private fun nullPrüfungsname(ausdruck: BinärAusdruck): String? {
        val links = ausdruck.links
        val rechts = ausdruck.rechts
        return when {
            links is Bezeichner && rechts is NichtsLiteral -> links.name
            rechts is Bezeichner && links is NichtsLiteral -> rechts.name
            else -> null
        }
    }

    /** Kindbereich, in dem die genannten stabilen Variablen als nicht-nullbar gelten. */
    private fun verfeinere(bereich: Geltungsbereich, namen: Set<String>): Geltungsbereich {
        val verfeinert = Geltungsbereich(bereich)
        verfeinereImBereich(verfeinert, namen)
        return verfeinert
    }

    private fun verfeinereImBereich(bereich: Geltungsbereich, namen: Set<String>) {
        for (name in namen) {
            val info = bereich.finde(name) ?: continue
            val typ = info.typ
            if (!info.wandelbar && typ is NullbarTyp) {
                bereich.definiere(name, typ.basis, wandelbar = false)
            }
        }
    }

    private fun prüfeAnweisung(anweisung: Anweisung, bereich: Geltungsbereich) {
        when (anweisung) {
            is SeiAnweisung -> {
                if (bereich.istLokal(anweisung.name)) {
                    diagnosen.melde(
                        "Bindung '${anweisung.name}' ist in diesem Bereich bereits vergeben",
                        anweisung.position,
                    )
                }
                val annotation = anweisung.typannotation?.let { symbole.auflöser.auflöse(it) }
                val initTyp = prüfeAusdruck(anweisung.initialwert, bereich, annotation)
                val typ = when {
                    annotation == null -> {
                        if (initTyp == FehlerTyp) {
                            diagnosen.melde(
                                "Typ von '${anweisung.name}' kann nicht abgeleitet werden; " +
                                    "bitte annotieren",
                                anweisung.position,
                            )
                        }
                        initTyp
                    }
                    !istZuweisbar(annotation, initTyp) -> {
                        diagnosen.melde(
                            "'${anweisung.name}': erwartet $annotation, erhielt $initTyp",
                            anweisung.position,
                        )
                        annotation
                    }
                    else -> annotation
                }
                bindungsTypen[anweisung] = typ
                bereich.definiere(anweisung.name, typ, anweisung.wandelbar)
            }

            is AusdruckAnweisung -> prüfeAusdruck(anweisung.ausdruck, bereich, null)

            is ZuweisungAnweisung -> prüfeZuweisung(anweisung, bereich)

            is WennAnweisung -> {
                val bedingung = prüfeAusdruck(anweisung.bedingung, bereich, WahrheitTyp)
                erwarteWahrheit(bedingung, anweisung.bedingung.position, "Bedingung von 'wenn'")
                prüfeBlock(
                    anweisung.dann,
                    verfeinere(bereich, verfeinerungWennWahr(anweisung.bedingung)),
                )
                anweisung.sonst?.let {
                    prüfeAnweisung(it, verfeinere(bereich, verfeinerungWennFalsch(anweisung.bedingung)))
                }
            }

            is SolangeAnweisung -> {
                val bedingung = prüfeAusdruck(anweisung.bedingung, bereich, WahrheitTyp)
                erwarteWahrheit(bedingung, anweisung.bedingung.position, "Bedingung von 'solange'")
                schleifenTiefe++
                prüfeBlock(
                    anweisung.körper,
                    verfeinere(bereich, verfeinerungWennWahr(anweisung.bedingung)),
                )
                schleifenTiefe--
            }

            is FürInAnweisung -> {
                val iterierbar = verlangeNichtNull(
                    prüfeAusdruck(anweisung.iterierbar, bereich, null),
                    anweisung.iterierbar.position, "Iterierbarer Ausdruck",
                )
                val elementTyp = when (iterierbar) {
                    is ListeTyp -> iterierbar.element
                    TextTyp -> ZeichenTyp
                    FehlerTyp -> FehlerTyp
                    else -> {
                        diagnosen.melde(
                            "Ueber Typ $iterierbar kann nicht iteriert werden",
                            anweisung.iterierbar.position,
                        )
                        FehlerTyp
                    }
                }
                val schleifenBereich = Geltungsbereich(bereich)
                schleifenBereich.definiere(anweisung.variable, elementTyp, wandelbar = false)
                schleifenTiefe++
                prüfeBlock(anweisung.körper, schleifenBereich)
                schleifenTiefe--
            }

            is FürVonBisAnweisung -> {
                val von = prüfeAusdruck(anweisung.von, bereich, GanzzahlTyp)
                val bis = prüfeAusdruck(anweisung.bis, bereich, GanzzahlTyp)
                if (von != GanzzahlTyp && von != FehlerTyp) {
                    diagnosen.melde("Schleifengrenze 'von' muss Ganzzahl sein", anweisung.von.position)
                }
                if (bis != GanzzahlTyp && bis != FehlerTyp) {
                    diagnosen.melde("Schleifengrenze 'bis' muss Ganzzahl sein", anweisung.bis.position)
                }
                val schleifenBereich = Geltungsbereich(bereich)
                schleifenBereich.definiere(anweisung.variable, GanzzahlTyp, wandelbar = false)
                schleifenTiefe++
                prüfeBlock(anweisung.körper, schleifenBereich)
                schleifenTiefe--
            }

            is ZurückAnweisung -> {
                if (anweisung.wert == null) {
                    if (aktuelleRückgabe != NichtsTyp) {
                        diagnosen.melde(
                            "'zurück' ohne Wert, aber Rueckgabetyp ist $aktuelleRückgabe",
                            anweisung.position,
                        )
                    }
                } else {
                    val typ = prüfeAusdruck(anweisung.wert, bereich, aktuelleRückgabe)
                    if (!istZuweisbar(aktuelleRückgabe, typ)) {
                        diagnosen.melde(
                            "Rueckgabe: erwartet $aktuelleRückgabe, erhielt $typ",
                            anweisung.position,
                        )
                    }
                }
            }

            is BrichAnweisung -> if (schleifenTiefe == 0) {
                diagnosen.melde("'brich' ist nur innerhalb einer Schleife erlaubt", anweisung.position)
            }

            is WeiterAnweisung -> if (schleifenTiefe == 0) {
                diagnosen.melde("'weiter' ist nur innerhalb einer Schleife erlaubt", anweisung.position)
            }

            is Block -> prüfeBlock(anweisung, Geltungsbereich(bereich))
        }
    }

    private fun prüfeZuweisung(anweisung: ZuweisungAnweisung, bereich: Geltungsbereich) {
        val zielTyp: Typ
        when (val ziel = anweisung.ziel) {
            is Bezeichner -> {
                val info = bereich.finde(ziel.name)
                if (info == null) {
                    diagnosen.melde("Unbekannter Name: '${ziel.name}'", ziel.position)
                    zielTyp = FehlerTyp
                } else {
                    if (!info.wandelbar) {
                        diagnosen.melde(
                            "'${ziel.name}' ist eine unveraenderliche Bindung ('sei')",
                            ziel.position,
                        )
                    }
                    zielTyp = info.typ
                }
            }
            is FeldzugriffAusdruck -> {
                val basis = verlangeNichtNull(
                    prüfeAusdruck(ziel.ziel, bereich, null), ziel.position,
                    "Empfaenger von '.${ziel.feld}'",
                )
                zielTyp = when (basis) {
                    is KlassenTyp -> {
                        val feld = basis.findeFeld(ziel.feld)
                        when {
                            feld == null -> {
                                diagnosen.melde(
                                    "Klasse $basis hat kein Feld '${ziel.feld}'", ziel.position,
                                )
                                FehlerTyp
                            }
                            !feld.wandelbar -> {
                                diagnosen.melde(
                                    "Feld '${ziel.feld}' ist unveraenderlich ('sei')", ziel.position,
                                )
                                feld.typ
                            }
                            else -> feld.typ
                        }
                    }
                    is DatensatzTyp -> {
                        diagnosen.melde(
                            "Felder eines Datensatzes sind unveraenderlich", ziel.position,
                        )
                        basis.felder[ziel.feld] ?: FehlerTyp
                    }
                    else -> {
                        diagnosen.melde("Typ $basis hat kein veraenderliches Feld", ziel.position)
                        FehlerTyp
                    }
                }
            }
            is IndexAusdruck -> {
                zielTyp = prüfeIndex(ziel, bereich, schreibend = true)
            }
            else -> {
                diagnosen.melde("Ungueltiges Zuweisungsziel", ziel.position)
                zielTyp = FehlerTyp
            }
        }
        val wertTyp = prüfeAusdruck(anweisung.wert, bereich, zielTyp)
        if (!istZuweisbar(zielTyp, wertTyp)) {
            diagnosen.melde("Zuweisung: erwartet $zielTyp, erhielt $wertTyp", anweisung.position)
        }
    }

    // ---- Ausdruecke ---------------------------------------------------------

    private fun prüfeAusdruck(ausdruck: Ausdruck, bereich: Geltungsbereich, erwartet: Typ?): Typ =
        when (ausdruck) {
            is GanzzahlLiteral -> GanzzahlTyp
            is KommazahlLiteral -> KommazahlTyp
            is TextLiteral -> TextTyp
            is ZeichenLiteral -> ZeichenTyp
            is WahrheitLiteral -> WahrheitTyp
            is NichtsLiteral -> NichtsTyp

            is Bezeichner -> {
                val info = bereich.finde(ausdruck.name)
                val typ = when {
                    info != null -> info.typ
                    ausdruck.name in symbole.funktionen -> symbole.funktionen[ausdruck.name]!!
                    ausdruck.name in Resolver.EINGEBAUTE_NAMEN -> {
                        diagnosen.melde(
                            "'${ausdruck.name}' ist eingebaut und muss aufgerufen werden",
                            ausdruck.position,
                        )
                        FehlerTyp
                    }
                    else -> {
                        diagnosen.melde("Unbekannter Name: '${ausdruck.name}'", ausdruck.position)
                        FehlerTyp
                    }
                }
                bezeichnerTypen[ausdruck] = typ
                typ
            }

            is DiesAusdruck -> {
                val klasse = aktuelleKlasse
                if (klasse == null) {
                    diagnosen.melde("'dies' ist nur innerhalb einer Methode erlaubt", ausdruck.position)
                    FehlerTyp
                } else {
                    klasse
                }
            }

            is UnärAusdruck -> {
                val operand = prüfeAusdruck(ausdruck.operand, bereich, null)
                when (ausdruck.operator) {
                    TokenTyp.MINUS -> {
                        if (!istNumerisch(operand) && operand != FehlerTyp) {
                            diagnosen.melde("'-' erwartet eine Zahl, erhielt $operand", ausdruck.position)
                        }
                        operand
                    }
                    TokenTyp.NICHT -> {
                        erwarteWahrheit(operand, ausdruck.position, "'nicht'")
                        WahrheitTyp
                    }
                    else -> FehlerTyp
                }
            }

            is BinärAusdruck -> {
                val typ = prüfeBinär(ausdruck, bereich)
                binärTypen[ausdruck] = typ
                typ
            }

            is AufrufAusdruck -> prüfeAufruf(ausdruck, bereich, erwartet)

            is IndexAusdruck -> prüfeIndex(ausdruck, bereich, schreibend = false)

            is FeldzugriffAusdruck -> prüfeFeldzugriff(ausdruck, bereich)

            is NeuAusdruck -> prüfeNeu(ausdruck, bereich)

            is LambdaAusdruck -> {
                val lambdaBereich = Geltungsbereich(bereich)
                val parameterTypen = ausdruck.parameter.map { symbole.auflöser.auflöse(it.typ) }
                ausdruck.parameter.forEachIndexed { idx, p ->
                    lambdaBereich.definiere(p.name, parameterTypen[idx], wandelbar = false)
                }
                val rückgabe = prüfeAusdruck(ausdruck.körper, lambdaBereich, null)
                FunktionsTyp(parameterTypen, rückgabe)
            }

            is WähleAusdruck -> {
                val subjekt = prüfeAusdruck(ausdruck.subjekt, bereich, null)
                var ergebnis: Typ = FehlerTyp
                var erstes = true
                for (fall in ausdruck.fälle) {
                    val muster = prüfeMuster(fall.muster, bereich)
                    if (muster != FehlerTyp && subjekt != FehlerTyp &&
                        gemeinsamerTyp(subjekt, muster) == FehlerTyp
                    ) {
                        diagnosen.melde(
                            "Muster vom Typ $muster passt nicht zum Subjekt vom Typ $subjekt",
                            fall.position,
                        )
                    }
                    val zweig = prüfeAusdruck(fall.ergebnis, bereich, erwartet)
                    ergebnis = if (erstes) zweig else gemeinsamerTyp(ergebnis, zweig)
                    erstes = false
                }
                val sonst = prüfeAusdruck(ausdruck.sonst, bereich, erwartet)
                ergebnis = if (erstes) sonst else gemeinsamerTyp(ergebnis, sonst)
                ergebnis
            }

            is ElvisAusdruck -> {
                val links = prüfeAusdruck(ausdruck.links, bereich, erwartet?.let { nullbar(it) })
                val rechts = prüfeAusdruck(ausdruck.rechts, bereich, erwartet)
                gemeinsamerTyp(entnullt(links), rechts)
            }

            is NichtNullAusdruck -> {
                val operand = prüfeAusdruck(ausdruck.operand, bereich, erwartet?.let { nullbar(it) })
                entnullt(operand)
            }
        }

    private fun prüfeMuster(muster: Ausdruck, bereich: Geltungsbereich): Typ {
        // Aufzaehlungsvarianten als 'EnumName.Variante'.
        if (muster is FeldzugriffAusdruck && muster.ziel is Bezeichner) {
            val typ = symbole.typen[(muster.ziel as Bezeichner).name]
            if (typ is AufzählungTyp) {
                if (muster.feld !in typ.varianten) {
                    diagnosen.melde(
                        "'${typ.name}' hat keine Variante '${muster.feld}'", muster.position,
                    )
                }
                return typ
            }
        }
        return prüfeAusdruck(muster, bereich, null)
    }

    private fun prüfeBinär(ausdruck: BinärAusdruck, bereich: Geltungsbereich): Typ {
        val links = prüfeAusdruck(ausdruck.links, bereich, null)
        // 'und'/'oder' verfeinern den rechten Operanden (smart cast in Konjunktionen).
        val rechtsBereich = when (ausdruck.operator) {
            TokenTyp.UND -> verfeinere(bereich, verfeinerungWennWahr(ausdruck.links))
            TokenTyp.ODER -> verfeinere(bereich, verfeinerungWennFalsch(ausdruck.links))
            else -> bereich
        }
        val rechts = prüfeAusdruck(ausdruck.rechts, rechtsBereich, null)

        // Gleichheit erlaubt nullbare Operanden (das ist die Nullpruefung selbst).
        if (ausdruck.operator == TokenTyp.GLEICH || ausdruck.operator == TokenTyp.UNGLEICH) {
            if (links != NichtsTyp && rechts != NichtsTyp &&
                links != FehlerTyp && rechts != FehlerTyp &&
                gemeinsamerTyp(links, rechts) == FehlerTyp
            ) {
                diagnosen.melde(
                    "$links und $rechts koennen nicht verglichen werden", ausdruck.position,
                )
            }
            return WahrheitTyp
        }

        // Alle anderen Operatoren verlangen nicht-nullbare Operanden.
        val name = operatorText(ausdruck.operator)
        val l = verlangeNichtNull(links, ausdruck.links.position, "Linker Operand von '$name'")
        val r = verlangeNichtNull(rechts, ausdruck.rechts.position, "Rechter Operand von '$name'")
        if (l == FehlerTyp || r == FehlerTyp) {
            return when (ausdruck.operator) {
                TokenTyp.UND, TokenTyp.ODER, TokenTyp.KLEINER, TokenTyp.KLEINER_GLEICH,
                TokenTyp.GRÖSSER, TokenTyp.GRÖSSER_GLEICH -> WahrheitTyp
                else -> FehlerTyp
            }
        }
        return when (ausdruck.operator) {
            TokenTyp.PLUS -> when {
                l == TextTyp || r == TextTyp -> TextTyp
                istNumerisch(l) && istNumerisch(r) -> numerischesErgebnis(l, r)
                else -> {
                    diagnosen.melde("'+' passt nicht auf $l und $r", ausdruck.position)
                    FehlerTyp
                }
            }
            TokenTyp.MINUS, TokenTyp.STERN, TokenTyp.SCHRÄGSTRICH, TokenTyp.PROZENT -> {
                if (istNumerisch(l) && istNumerisch(r)) {
                    numerischesErgebnis(l, r)
                } else {
                    diagnosen.melde("'$name' erwartet Zahlen, erhielt $l und $r", ausdruck.position)
                    FehlerTyp
                }
            }
            TokenTyp.UND, TokenTyp.ODER -> {
                erwarteWahrheit(l, ausdruck.links.position, "'$name'")
                erwarteWahrheit(r, ausdruck.rechts.position, "'$name'")
                WahrheitTyp
            }
            TokenTyp.KLEINER, TokenTyp.KLEINER_GLEICH,
            TokenTyp.GRÖSSER, TokenTyp.GRÖSSER_GLEICH -> {
                val ordbar = (istNumerisch(l) && istNumerisch(r)) ||
                    (l == TextTyp && r == TextTyp) ||
                    (l == ZeichenTyp && r == ZeichenTyp)
                if (!ordbar) {
                    diagnosen.melde("'$name' passt nicht auf $l und $r", ausdruck.position)
                }
                WahrheitTyp
            }
            else -> FehlerTyp
        }
    }

    private fun prüfeAufruf(ausdruck: AufrufAusdruck, bereich: Geltungsbereich, erwartet: Typ?): Typ {
        val ziel = ausdruck.ziel
        // Eingebaute Funktionen / Konstruktoren.
        if (ziel is Bezeichner && bereich.finde(ziel.name) == null &&
            ziel.name !in symbole.funktionen
        ) {
            when (ziel.name) {
                "drucke" -> {
                    if (ausdruck.argumente.size != 1) {
                        diagnosen.melde("'drucke' erwartet genau ein Argument", ausdruck.position)
                    }
                    ausdruck.argumente.forEach { prüfeAusdruck(it, bereich, null) }
                    return NichtsTyp
                }
                "lies" -> {
                    if (ausdruck.argumente.isNotEmpty()) {
                        diagnosen.melde("'lies' erwartet keine Argumente", ausdruck.position)
                    }
                    return TextTyp
                }
                "länge" -> {
                    if (ausdruck.argumente.size != 1) {
                        diagnosen.melde("'länge' erwartet genau ein Argument", ausdruck.position)
                        return GanzzahlTyp
                    }
                    val arg = verlangeNichtNull(
                        prüfeAusdruck(ausdruck.argumente[0], bereich, null),
                        ausdruck.argumente[0].position, "Argument von 'länge'",
                    )
                    if (arg != TextTyp && arg !is ListeTyp && arg !is AbbildungTyp && arg != FehlerTyp) {
                        diagnosen.melde(
                            "'länge' erwartet Text, Liste oder Abbildung, erhielt $arg",
                            ausdruck.position,
                        )
                    }
                    return GanzzahlTyp
                }
                "Liste" -> return prüfeListeKonstruktor(ausdruck, bereich, erwartet)
                "Paar" -> {
                    if (ausdruck.argumente.size != 2) {
                        diagnosen.melde("'Paar' erwartet genau zwei Argumente", ausdruck.position)
                        return FehlerTyp
                    }
                    val a = prüfeAusdruck(ausdruck.argumente[0], bereich, null)
                    val b = prüfeAusdruck(ausdruck.argumente[1], bereich, null)
                    return PaarTyp(a, b)
                }
                "Abbildung" -> return prüfeAbbildungKonstruktor(ausdruck, bereich, erwartet)
            }
        }
        // Methodenaufruf.
        if (ziel is FeldzugriffAusdruck) {
            return prüfeMethodenaufruf(ziel, ausdruck.argumente, bereich)
        }
        // Aufruf eines Funktionswerts.
        val zielTyp = prüfeAusdruck(ziel, bereich, null)
        if (zielTyp == FehlerTyp) {
            ausdruck.argumente.forEach { prüfeAusdruck(it, bereich, null) }
            return FehlerTyp
        }
        if (zielTyp !is FunktionsTyp) {
            diagnosen.melde("$zielTyp ist nicht aufrufbar", ausdruck.position)
            ausdruck.argumente.forEach { prüfeAusdruck(it, bereich, null) }
            return FehlerTyp
        }
        prüfeArgumente(zielTyp.parameter, ausdruck.argumente, bereich, ausdruck.position, "Aufruf")
        return zielTyp.rückgabe
    }

    private fun prüfeListeKonstruktor(
        ausdruck: AufrufAusdruck,
        bereich: Geltungsbereich,
        erwartet: Typ?,
    ): Typ {
        if (ausdruck.argumente.isEmpty()) {
            if (erwartet is ListeTyp) return erwartet
            diagnosen.melde(
                "Leere 'Liste()' braucht eine Typ-Annotation, z. B. 'sei x: Liste<Ganzzahl> = Liste()'",
                ausdruck.position,
            )
            return ListeTyp(FehlerTyp)
        }
        val hinweis = (erwartet as? ListeTyp)?.element
        var element: Typ? = null
        for (arg in ausdruck.argumente) {
            val t = prüfeAusdruck(arg, bereich, hinweis)
            element = if (element == null) t else gemeinsamerTyp(element, t)
        }
        if (element == FehlerTyp) {
            diagnosen.melde("'Liste' enthaelt Werte unterschiedlicher Typen", ausdruck.position)
        }
        return ListeTyp(element ?: FehlerTyp)
    }

    private fun prüfeAbbildungKonstruktor(
        ausdruck: AufrufAusdruck,
        bereich: Geltungsbereich,
        erwartet: Typ?,
    ): Typ {
        if (ausdruck.argumente.isEmpty()) {
            if (erwartet is AbbildungTyp) return erwartet
            diagnosen.melde(
                "Leere 'Abbildung()' braucht eine Typ-Annotation", ausdruck.position,
            )
            return AbbildungTyp(FehlerTyp, FehlerTyp)
        }
        var schlüssel: Typ? = null
        var wert: Typ? = null
        for (arg in ausdruck.argumente) {
            val t = prüfeAusdruck(arg, bereich, null)
            if (t is PaarTyp) {
                schlüssel = if (schlüssel == null) t.erst else gemeinsamerTyp(schlüssel, t.erst)
                wert = if (wert == null) t.zweit else gemeinsamerTyp(wert, t.zweit)
            } else if (t != FehlerTyp) {
                diagnosen.melde("'Abbildung' erwartet Paar-Argumente, erhielt $t", arg.position)
            }
        }
        return AbbildungTyp(schlüssel ?: FehlerTyp, wert ?: FehlerTyp)
    }

    private fun prüfeMethodenaufruf(
        ziel: FeldzugriffAusdruck,
        argumente: List<Ausdruck>,
        bereich: Geltungsbereich,
    ): Typ {
        val rohBasis = prüfeAusdruck(ziel.ziel, bereich, null)
        val basis = if (ziel.sicher) {
            entnullt(rohBasis)
        } else {
            verlangeNichtNull(rohBasis, ziel.position, "Empfaenger von '.${ziel.feld}'")
        }
        if (basis == FehlerTyp) {
            argumente.forEach { prüfeAusdruck(it, bereich, null) }
            return FehlerTyp
        }
        val signatur: FunktionsTyp? = when (basis) {
            is KlassenTyp -> basis.findeMethode(ziel.feld)
                ?: (basis.findeFeld(ziel.feld)?.typ as? FunktionsTyp)
            is SchnittstellenTyp -> basis.methoden[ziel.feld]
            is DatensatzTyp -> basis.felder[ziel.feld] as? FunktionsTyp
            else -> eingebauteMethode(basis, ziel.feld)
        }
        if (signatur == null) {
            diagnosen.melde("$basis hat keine Methode '${ziel.feld}'", ziel.position)
            argumente.forEach { prüfeAusdruck(it, bereich, null) }
            return FehlerTyp
        }
        prüfeArgumente(signatur.parameter, argumente, bereich, ziel.position, "Methode '${ziel.feld}'")
        // Ein sicherer Aufruf auf einem nullbaren Empfaenger liefert ein nullbares Ergebnis.
        return if (ziel.sicher && istNullbar(rohBasis)) nullbar(signatur.rückgabe) else signatur.rückgabe
    }

    private fun prüfeFeldzugriff(ausdruck: FeldzugriffAusdruck, bereich: Geltungsbereich): Typ {
        // Aufzaehlungsvariante: 'EnumName.Variante'.
        if (ausdruck.ziel is Bezeichner && bereich.finde((ausdruck.ziel as Bezeichner).name) == null) {
            val typ = symbole.typen[(ausdruck.ziel as Bezeichner).name]
            if (typ is AufzählungTyp) {
                if (ausdruck.feld !in typ.varianten) {
                    diagnosen.melde(
                        "'${typ.name}' hat keine Variante '${ausdruck.feld}'", ausdruck.position,
                    )
                }
                return typ
            }
        }
        val rohBasis = prüfeAusdruck(ausdruck.ziel, bereich, null)
        val basis = if (ausdruck.sicher) {
            entnullt(rohBasis)
        } else {
            verlangeNichtNull(rohBasis, ausdruck.position, "Empfaenger von '.${ausdruck.feld}'")
        }
        val feldTyp = when (basis) {
            FehlerTyp -> FehlerTyp
            is DatensatzTyp -> basis.felder[ausdruck.feld] ?: run {
                diagnosen.melde("$basis hat kein Feld '${ausdruck.feld}'", ausdruck.position)
                FehlerTyp
            }
            is KlassenTyp -> basis.findeFeld(ausdruck.feld)?.typ
                ?: basis.findeMethode(ausdruck.feld)
                ?: run {
                    diagnosen.melde("$basis hat kein Feld '${ausdruck.feld}'", ausdruck.position)
                    FehlerTyp
                }
            is PaarTyp -> when (ausdruck.feld) {
                "erst" -> basis.erst
                "zweit" -> basis.zweit
                else -> {
                    diagnosen.melde("Paar hat kein Feld '${ausdruck.feld}'", ausdruck.position)
                    FehlerTyp
                }
            }
            else -> {
                diagnosen.melde("$basis hat kein Feld '${ausdruck.feld}'", ausdruck.position)
                FehlerTyp
            }
        }
        return if (ausdruck.sicher && istNullbar(rohBasis)) nullbar(feldTyp) else feldTyp
    }

    private fun prüfeIndex(ausdruck: IndexAusdruck, bereich: Geltungsbereich, schreibend: Boolean): Typ {
        val basis = verlangeNichtNull(
            prüfeAusdruck(ausdruck.ziel, bereich, null), ausdruck.position, "Indexzugriff",
        )
        val index = prüfeAusdruck(ausdruck.index, bereich, null)
        return when (basis) {
            FehlerTyp -> FehlerTyp
            is ListeTyp -> {
                if (index != GanzzahlTyp && index != FehlerTyp) {
                    diagnosen.melde("Listenindex muss Ganzzahl sein, erhielt $index", ausdruck.position)
                }
                basis.element
            }
            is AbbildungTyp -> {
                if (!istZuweisbar(basis.schlüssel, index) && index != FehlerTyp) {
                    diagnosen.melde(
                        "Abbildungsschluessel muss ${basis.schlüssel} sein, erhielt $index",
                        ausdruck.position,
                    )
                }
                basis.wert
            }
            TextTyp -> {
                if (schreibend) {
                    diagnosen.melde("Text ist unveraenderlich", ausdruck.position)
                }
                if (index != GanzzahlTyp && index != FehlerTyp) {
                    diagnosen.melde("Textindex muss Ganzzahl sein, erhielt $index", ausdruck.position)
                }
                ZeichenTyp
            }
            else -> {
                diagnosen.melde("$basis unterstuetzt keinen Indexzugriff", ausdruck.position)
                FehlerTyp
            }
        }
    }

    private fun prüfeNeu(ausdruck: NeuAusdruck, bereich: Geltungsbereich): Typ {
        val typ = symbole.typen[ausdruck.typname]
        return when (typ) {
            is DatensatzTyp -> {
                prüfeArgumente(
                    typ.felder.values.toList(), ausdruck.argumente, bereich,
                    ausdruck.position, "Datensatz '${typ.name}'",
                )
                typ
            }
            is KlassenTyp -> {
                prüfeArgumente(
                    konstruktorParameter(typ), ausdruck.argumente, bereich,
                    ausdruck.position, "Klasse '${typ.name}'",
                )
                typ
            }
            else -> {
                diagnosen.melde(
                    "'${ausdruck.typname}' kann nicht mit 'neu' erzeugt werden", ausdruck.position,
                )
                ausdruck.argumente.forEach { prüfeAusdruck(it, bereich, null) }
                FehlerTyp
            }
        }
    }

    // ---- Hilfen -------------------------------------------------------------

    private fun prüfeArgumente(
        erwartet: List<Typ>,
        argumente: List<Ausdruck>,
        bereich: Geltungsbereich,
        position: Position,
        was: String,
    ) {
        if (erwartet.size != argumente.size) {
            diagnosen.melde(
                "$was erwartet ${erwartet.size} Argument(e), erhielt ${argumente.size}",
                position,
            )
        }
        argumente.forEachIndexed { idx, arg ->
            val ziel = erwartet.getOrNull(idx)
            val typ = prüfeAusdruck(arg, bereich, ziel)
            if (ziel != null && !istZuweisbar(ziel, typ)) {
                diagnosen.melde(
                    "$was, Argument ${idx + 1}: erwartet $ziel, erhielt $typ", arg.position,
                )
            }
        }
    }

    private fun erwarteWahrheit(typ: Typ, position: Position, was: String) {
        if (typ != WahrheitTyp && typ != FehlerTyp) {
            diagnosen.melde("$was muss vom Typ Wahrheit sein, erhielt $typ", position)
        }
    }

    private fun numerischesErgebnis(a: Typ, b: Typ): Typ =
        if (a == KommazahlTyp || b == KommazahlTyp) KommazahlTyp else GanzzahlTyp

    private fun operatorText(typ: TokenTyp): String = when (typ) {
        TokenTyp.PLUS -> "+"
        TokenTyp.MINUS -> "-"
        TokenTyp.STERN -> "*"
        TokenTyp.SCHRÄGSTRICH -> "/"
        TokenTyp.PROZENT -> "%"
        TokenTyp.UND -> "und"
        TokenTyp.ODER -> "oder"
        else -> typ.name
    }

    companion object {
        /** Liefert die Signatur einer eingebauten Methode oder `null`. */
        fun eingebauteMethode(basis: Typ, name: String): FunktionsTyp? = when (basis) {
            TextTyp -> when (name) {
                "länge" -> FunktionsTyp(emptyList(), GanzzahlTyp)
                "großbuchstaben", "kleinbuchstaben" -> FunktionsTyp(emptyList(), TextTyp)
                "zeichenBei" -> FunktionsTyp(listOf(GanzzahlTyp), ZeichenTyp)
                "enthält" -> FunktionsTyp(listOf(TextTyp), WahrheitTyp)
                "teile" -> FunktionsTyp(listOf(TextTyp), ListeTyp(TextTyp))
                "alsText" -> FunktionsTyp(emptyList(), TextTyp)
                else -> null
            }
            is ListeTyp -> when (name) {
                "länge" -> FunktionsTyp(emptyList(), GanzzahlTyp)
                "istLeer" -> FunktionsTyp(emptyList(), WahrheitTyp)
                "holen" -> FunktionsTyp(listOf(GanzzahlTyp), basis.element)
                "setze" -> FunktionsTyp(listOf(GanzzahlTyp, basis.element), NichtsTyp)
                "hinzufügen" -> FunktionsTyp(listOf(basis.element), NichtsTyp)
                "entferne" -> FunktionsTyp(listOf(GanzzahlTyp), basis.element)
                else -> null
            }
            is AbbildungTyp -> when (name) {
                "länge" -> FunktionsTyp(emptyList(), GanzzahlTyp)
                "istLeer" -> FunktionsTyp(emptyList(), WahrheitTyp)
                "holen" -> FunktionsTyp(listOf(basis.schlüssel), basis.wert)
                "setze" -> FunktionsTyp(listOf(basis.schlüssel, basis.wert), NichtsTyp)
                "enthält" -> FunktionsTyp(listOf(basis.schlüssel), WahrheitTyp)
                "schlüssel" -> FunktionsTyp(emptyList(), ListeTyp(basis.schlüssel))
                else -> null
            }
            GanzzahlTyp, KommazahlTyp, WahrheitTyp, ZeichenTyp ->
                if (name == "alsText") FunktionsTyp(emptyList(), TextTyp) else null
            else -> null
        }

        /** Konstruktorparameter einer Klasse: Oberklassenfelder zuerst. */
        fun konstruktorParameter(klasse: KlassenTyp): List<Typ> {
            val ererbt = klasse.oberklasse?.let { konstruktorParameter(it) } ?: emptyList()
            val eigene = klasse.felder.values
                .filter { it.istKonstruktorParameter }
                .map { it.typ }
            return ererbt + eigene
        }
    }
}
