package edel.laufzeit

import edel.fehler.LaufzeitFehler
import edel.lexer.TokenTyp
import edel.lexer.TokenTyp.*
import edel.parser.*
import edel.semantik.Parallelplan
import edel.semantik.Reduktion
import java.util.concurrent.Callable
import java.util.concurrent.ForkJoinTask

// Kontrollfluss-Signale; Stacktraces sind unnoetig und werden unterdrueckt.
private class ZurückSignal(val wert: Wert) : RuntimeException() {
    override fun fillInStackTrace() = this
}
private class BrichSignal : RuntimeException() {
    override fun fillInStackTrace() = this
}
private class WeiterSignal : RuntimeException() {
    override fun fillInStackTrace() = this
}

/**
 * Baumdurchlaufender Interpreter. Erwartet einen bereits typgeprueften
 * Syntaxbaum; Laufzeitfehler bleiben moeglich (z. B. Indexfehler).
 */
class Interpreter(
    private val programm: Programm,
    private val parallelplan: Parallelplan = Parallelplan(emptyMap()),
    private val ausgabe: (String) -> Unit = ::println,
) {
    private val global = Umgebung()
    private val klassen = HashMap<String, KlasseDeklaration>()
    private val datensätze = HashMap<String, DatensatzDeklaration>()

    // True innerhalb eines parallelen Schleifendurchlaufs; verschachtelte
    // Schleifen laufen dann sequentiell.
    private val inParalleler = ThreadLocal.withInitial { false }

    fun starte() {
        registriereDeklarationen()
        val start = global.hole("start")
        if (start !is FunktionWert) throw LaufzeitFehler("'start' ist keine Funktion")
        rufeAuf(start, emptyList())
    }

    private fun registriereDeklarationen() {
        global.definiere("drucke", EingebauteFunktion("drucke") { args ->
            ausgabe(darstelle(args[0])); NichtsWert
        })
        global.definiere("lies", EingebauteFunktion("lies") { _ ->
            TextWert(readlnOrNull() ?: "")
        })
        global.definiere("länge", EingebauteFunktion("länge") { args ->
            when (val a = args[0]) {
                is TextWert -> GanzzahlWert(a.wert.length.toLong())
                is ListeWert -> GanzzahlWert(a.elemente.size.toLong())
                is AbbildungWert -> GanzzahlWert(a.eintraege.size.toLong())
                else -> throw LaufzeitFehler("'länge' ist auf ${darstelle(a)} nicht anwendbar")
            }
        })
        global.definiere("Liste", EingebauteFunktion("Liste") { args ->
            ListeWert(args.toMutableList())
        })
        global.definiere("Paar", EingebauteFunktion("Paar") { args ->
            PaarWert(args[0], args[1])
        })
        global.definiere("Abbildung", EingebauteFunktion("Abbildung") { args ->
            val eintraege = LinkedHashMap<Wert, Wert>()
            for (arg in args) {
                val paar = arg as? PaarWert
                    ?: throw LaufzeitFehler("'Abbildung' erwartet Paar-Argumente")
                eintraege[paar.erst] = paar.zweit
            }
            AbbildungWert(eintraege)
        })

        for (d in programm.deklarationen) {
            when (d) {
                is FunktionDeklaration ->
                    global.definiere(d.name, FunktionWert(d.parameter, d.körper, global, d.name))
                is AufzählungDeklaration ->
                    global.definiere(d.name, AufzählungstypWert(d.name, d.varianten))
                is KlasseDeklaration -> klassen[d.name] = d
                is DatensatzDeklaration -> datensätze[d.name] = d
                else -> {}
            }
        }
    }

    // ---- Anweisungen --------------------------------------------------------

    private fun führeAnweisungAus(anweisung: Anweisung, umgebung: Umgebung) {
        when (anweisung) {
            is SeiAnweisung ->
                umgebung.definiere(anweisung.name, evaluiere(anweisung.initialwert, umgebung))

            is AusdruckAnweisung -> evaluiere(anweisung.ausdruck, umgebung)

            is ZuweisungAnweisung -> führeZuweisungAus(anweisung, umgebung)

            is WennAnweisung -> {
                if (wahrheit(evaluiere(anweisung.bedingung, umgebung))) {
                    führeBlockAus(anweisung.dann, Umgebung(umgebung))
                } else {
                    anweisung.sonst?.let { führeAnweisungAus(it, umgebung) }
                }
            }

            is SolangeAnweisung -> {
                while (wahrheit(evaluiere(anweisung.bedingung, umgebung))) {
                    try {
                        führeBlockAus(anweisung.körper, Umgebung(umgebung))
                    } catch (_: WeiterSignal) {
                        // naechste Iteration
                    } catch (_: BrichSignal) {
                        break
                    }
                }
            }

            is FürInAnweisung -> {
                val iterierbar = evaluiere(anweisung.iterierbar, umgebung)
                val elemente: List<Wert> = when (iterierbar) {
                    is ListeWert -> iterierbar.elemente.toList()
                    is TextWert -> iterierbar.wert.map { ZeichenWert(it) }
                    else -> throw LaufzeitFehler("Ueber ${darstelle(iterierbar)} kann nicht iteriert werden")
                }
                val reduktion = parallelplan.reduktionVon(anweisung)
                if (reduktion != null && darfParallel(elemente.size.toLong())) {
                    führeParalleleReduktion(
                        reduktion, anweisung.variable, anweisung.körper, umgebung,
                        elemente.size.toLong(),
                    ) { k -> elemente[k.toInt()] }
                } else {
                    for (element in elemente) {
                        val schleifenUmgebung = Umgebung(umgebung)
                        schleifenUmgebung.definiere(anweisung.variable, element)
                        try {
                            führeBlockAus(anweisung.körper, schleifenUmgebung)
                        } catch (_: WeiterSignal) {
                        } catch (_: BrichSignal) {
                            break
                        }
                    }
                }
            }

            is FürVonBisAnweisung -> {
                val von = (evaluiere(anweisung.von, umgebung) as GanzzahlWert).wert
                val bis = (evaluiere(anweisung.bis, umgebung) as GanzzahlWert).wert
                val reduktion = parallelplan.reduktionVon(anweisung)
                if (reduktion != null && darfParallel(bis - von + 1)) {
                    führeParalleleReduktion(
                        reduktion, anweisung.variable, anweisung.körper, umgebung,
                        bis - von + 1,
                    ) { k -> GanzzahlWert(von + k) }
                } else {
                    var i = von
                    while (i <= bis) {
                        val schleifenUmgebung = Umgebung(umgebung)
                        schleifenUmgebung.definiere(anweisung.variable, GanzzahlWert(i))
                        try {
                            führeBlockAus(anweisung.körper, schleifenUmgebung)
                        } catch (_: WeiterSignal) {
                        } catch (_: BrichSignal) {
                            break
                        }
                        i++
                    }
                }
            }

            is ZurückAnweisung ->
                throw ZurückSignal(anweisung.wert?.let { evaluiere(it, umgebung) } ?: NichtsWert)

            is BrichAnweisung -> throw BrichSignal()
            is WeiterAnweisung -> throw WeiterSignal()

            is Block -> führeBlockAus(anweisung, Umgebung(umgebung))
        }
    }

    private fun führeBlockAus(block: Block, umgebung: Umgebung) {
        for (anweisung in block.anweisungen) führeAnweisungAus(anweisung, umgebung)
    }

    // ---- Automatische Parallelisierung -------------------------------------

    private fun darfParallel(anzahl: Long): Boolean =
        !inParalleler.get() && anzahl >= 2 && Runtime.getRuntime().availableProcessors() >= 2

    /**
     * Darf eine Gabelung nebenlaeufig ausgewertet werden? Innerhalb einer
     * parallelen Schleife nicht; sonst nur, solange der Fork-Join-Pool nicht
     * mit Aufgaben gesaettigt ist (selbstregulierende Granularitaet).
     */
    private fun darfGabeln(): Boolean =
        !inParalleler.get() && ForkJoinTask.getSurplusQueuedTaskCount() <= 3

    /**
     * Wertet zwei unabhaengige reine Teilausdruecke nebenlaeufig aus: der linke
     * Operand laeuft im Fork-Join-Pool, der rechte im aktuellen Thread.
     */
    private fun gabelEvaluiere(
        links: Ausdruck,
        rechts: Ausdruck,
        umgebung: Umgebung,
    ): Pair<Wert, Wert> {
        val aufgabe = ForkJoinTask.adapt(Callable { evaluiere(links, umgebung) }).fork()
        val rechtsWert = evaluiere(rechts, umgebung)
        return aufgabe.join() to rechtsWert
    }

    /**
     * Fuehrt eine als parallele Reduktion erkannte Schleife nebenlaeufig aus:
     * die Iterationen werden gleichmaessig (verschraenkt) auf Threads verteilt,
     * jeder Thread fuehrt private Teil-Akkumulatoren, am Ende werden sie
     * zusammengefuehrt. Da `+`/`*` assoziativ sind, ist das Ergebnis identisch
     * zur sequentiellen Ausfuehrung.
     */
    private fun führeParalleleReduktion(
        reduktion: Reduktion,
        schleifenVariable: String,
        körper: Block,
        umgebung: Umgebung,
        anzahl: Long,
        wertBei: (Long) -> Wert,
    ) {
        val akkus = reduktion.akkumulatoren
        val chunks = minOf(Runtime.getRuntime().availableProcessors().toLong(), anzahl).toInt()
        val teilergebnisse = arrayOfNulls<LongArray>(chunks)
        val fehler = arrayOfNulls<Throwable>(chunks)

        fun identität(operator: TokenTyp): Long = if (operator == STERN) 1L else 0L

        val threads = (0 until chunks).map { k ->
            Thread {
                try {
                    inParalleler.set(true)
                    val chunkUmgebung = Umgebung(umgebung)
                    for (akku in akkus) {
                        chunkUmgebung.definiere(akku.name, GanzzahlWert(identität(akku.operator)))
                    }
                    var index = k.toLong()
                    while (index < anzahl) {
                        val iterUmgebung = Umgebung(chunkUmgebung)
                        iterUmgebung.definiere(schleifenVariable, wertBei(index))
                        führeBlockAus(körper, iterUmgebung)
                        index += chunks
                    }
                    teilergebnisse[k] = LongArray(akkus.size) { i ->
                        (chunkUmgebung.hole(akkus[i].name) as GanzzahlWert).wert
                    }
                } catch (t: Throwable) {
                    fehler[k] = t
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        fehler.firstOrNull { it != null }?.let { throw it }

        // Teil-Akkumulatoren mit dem Ausgangswert zusammenfuehren.
        for ((i, akku) in akkus.withIndex()) {
            var ergebnis = (umgebung.hole(akku.name) as GanzzahlWert).wert
            for (k in 0 until chunks) {
                val teil = teilergebnisse[k]!![i]
                ergebnis = if (akku.operator == STERN) ergebnis * teil else ergebnis + teil
            }
            umgebung.setze(akku.name, GanzzahlWert(ergebnis))
        }
    }

    private fun führeZuweisungAus(anweisung: ZuweisungAnweisung, umgebung: Umgebung) {
        val wert = evaluiere(anweisung.wert, umgebung)
        when (val ziel = anweisung.ziel) {
            is Bezeichner -> umgebung.setze(ziel.name, wert)
            is FeldzugriffAusdruck -> {
                when (val basis = evaluiere(ziel.ziel, umgebung)) {
                    is ObjektWert -> basis.felder[ziel.feld] = wert
                    is DatensatzWert -> basis.felder[ziel.feld] = wert
                    else -> throw LaufzeitFehler("Feldzuweisung auf ${darstelle(basis)} nicht moeglich")
                }
            }
            is IndexAusdruck -> {
                val basis = evaluiere(ziel.ziel, umgebung)
                val index = evaluiere(ziel.index, umgebung)
                when (basis) {
                    is ListeWert -> {
                        val i = (index as GanzzahlWert).wert.toInt()
                        if (i !in basis.elemente.indices) {
                            throw LaufzeitFehler("Listenindex ausserhalb des Bereichs: $i")
                        }
                        basis.elemente[i] = wert
                    }
                    is AbbildungWert -> basis.eintraege[index] = wert
                    else -> throw LaufzeitFehler("Indexzuweisung auf ${darstelle(basis)} nicht moeglich")
                }
            }
            else -> throw LaufzeitFehler("Ungueltiges Zuweisungsziel")
        }
    }

    // ---- Ausdruecke ---------------------------------------------------------

    private fun evaluiere(ausdruck: Ausdruck, umgebung: Umgebung): Wert = when (ausdruck) {
        is GanzzahlLiteral -> GanzzahlWert(ausdruck.wert)
        is KommazahlLiteral -> KommazahlWert(ausdruck.wert)
        is TextLiteral -> TextWert(ausdruck.wert)
        is ZeichenLiteral -> ZeichenWert(ausdruck.wert)
        is WahrheitLiteral -> WahrheitWert(ausdruck.wert)
        is NichtsLiteral -> NichtsWert

        is Bezeichner -> umgebung.hole(ausdruck.name)
        is DiesAusdruck -> umgebung.hole("dies")

        is UnärAusdruck -> {
            val operand = evaluiere(ausdruck.operand, umgebung)
            when (ausdruck.operator) {
                MINUS -> when (operand) {
                    is GanzzahlWert -> GanzzahlWert(-operand.wert)
                    is KommazahlWert -> KommazahlWert(-operand.wert)
                    else -> throw LaufzeitFehler("'-' ist auf ${darstelle(operand)} nicht anwendbar")
                }
                NICHT -> WahrheitWert(!wahrheit(operand))
                else -> throw LaufzeitFehler("Unbekannter unaerer Operator")
            }
        }

        is BinärAusdruck -> {
            when (ausdruck.operator) {
                UND -> {
                    if (!wahrheit(evaluiere(ausdruck.links, umgebung))) WahrheitWert(false)
                    else WahrheitWert(wahrheit(evaluiere(ausdruck.rechts, umgebung)))
                }
                ODER -> {
                    if (wahrheit(evaluiere(ausdruck.links, umgebung))) WahrheitWert(true)
                    else WahrheitWert(wahrheit(evaluiere(ausdruck.rechts, umgebung)))
                }
                else -> {
                    if (parallelplan.gabelVon(ausdruck) != null && darfGabeln()) {
                        val (links, rechts) = gabelEvaluiere(ausdruck.links, ausdruck.rechts, umgebung)
                        binär(ausdruck.operator, links, rechts)
                    } else {
                        binär(
                            ausdruck.operator,
                            evaluiere(ausdruck.links, umgebung),
                            evaluiere(ausdruck.rechts, umgebung),
                        )
                    }
                }
            }
        }

        is AufrufAusdruck -> führeAufrufAus(ausdruck, umgebung)

        is IndexAusdruck -> {
            val basis = evaluiere(ausdruck.ziel, umgebung)
            val index = evaluiere(ausdruck.index, umgebung)
            when (basis) {
                is ListeWert -> {
                    val i = (index as GanzzahlWert).wert.toInt()
                    if (i !in basis.elemente.indices) {
                        throw LaufzeitFehler("Listenindex ausserhalb des Bereichs: $i")
                    }
                    basis.elemente[i]
                }
                is AbbildungWert -> basis.eintraege[index]
                    ?: throw LaufzeitFehler("Schluessel nicht gefunden: ${darstelle(index)}")
                is TextWert -> {
                    val i = (index as GanzzahlWert).wert.toInt()
                    if (i !in basis.wert.indices) {
                        throw LaufzeitFehler("Textindex ausserhalb des Bereichs: $i")
                    }
                    ZeichenWert(basis.wert[i])
                }
                else -> throw LaufzeitFehler("${darstelle(basis)} unterstuetzt keinen Indexzugriff")
            }
        }

        is FeldzugriffAusdruck -> {
            when (val basis = evaluiere(ausdruck.ziel, umgebung)) {
                is AufzählungstypWert -> {
                    if (ausdruck.feld !in basis.varianten) {
                        throw LaufzeitFehler("'${basis.typname}' hat keine Variante '${ausdruck.feld}'")
                    }
                    AufzählungWert(basis.typname, ausdruck.feld)
                }
                is DatensatzWert -> basis.felder[ausdruck.feld]
                    ?: throw LaufzeitFehler("Kein Feld '${ausdruck.feld}'")
                is ObjektWert -> basis.felder[ausdruck.feld]
                    ?: findeMethode(basis.klasse, ausdruck.feld)?.let { gebundeneMethode(basis, it) }
                    ?: throw LaufzeitFehler("Kein Feld '${ausdruck.feld}'")
                is PaarWert -> when (ausdruck.feld) {
                    "erst" -> basis.erst
                    "zweit" -> basis.zweit
                    else -> throw LaufzeitFehler("Paar hat kein Feld '${ausdruck.feld}'")
                }
                else -> throw LaufzeitFehler("Feldzugriff auf ${darstelle(basis)} nicht moeglich")
            }
        }

        is NeuAusdruck -> {
            val argumente = ausdruck.argumente.map { evaluiere(it, umgebung) }
            konstruiere(ausdruck.typname, argumente)
        }

        is LambdaAusdruck -> {
            val körper = Block(
                listOf(ZurückAnweisung(ausdruck.körper, ausdruck.position)),
                ausdruck.position,
            )
            FunktionWert(ausdruck.parameter, körper, umgebung, "<lambda>")
        }

        is WähleAusdruck -> {
            val subjekt = evaluiere(ausdruck.subjekt, umgebung)
            val treffer = ausdruck.fälle.firstOrNull {
                gleichheit(subjekt, evaluiere(it.muster, umgebung))
            }
            if (treffer != null) evaluiere(treffer.ergebnis, umgebung)
            else evaluiere(ausdruck.sonst, umgebung)
        }
    }

    // ---- Aufrufe ------------------------------------------------------------

    private fun führeAufrufAus(ausdruck: AufrufAusdruck, umgebung: Umgebung): Wert {
        val argumente = ausdruck.argumente.map { evaluiere(it, umgebung) }
        val ziel = ausdruck.ziel
        if (ziel is FeldzugriffAusdruck) {
            val basis = evaluiere(ziel.ziel, umgebung)
            return methodenaufruf(basis, ziel.feld, argumente)
        }
        return rufeAuf(evaluiere(ziel, umgebung), argumente)
    }

    private fun rufeAuf(funktion: Wert, argumente: List<Wert>): Wert = when (funktion) {
        is FunktionWert -> {
            val lokal = Umgebung(funktion.abschluss)
            funktion.parameter.forEachIndexed { idx, p -> lokal.definiere(p.name, argumente[idx]) }
            try {
                führeAnweisungAus(funktion.körper, lokal)
                NichtsWert
            } catch (z: ZurückSignal) {
                z.wert
            }
        }
        is EingebauteFunktion -> funktion.funktion(argumente)
        else -> throw LaufzeitFehler("${darstelle(funktion)} ist nicht aufrufbar")
    }

    private fun methodenaufruf(basis: Wert, name: String, argumente: List<Wert>): Wert = when (basis) {
        is ObjektWert -> {
            val methode = findeMethode(basis.klasse, name)
            when {
                methode != null -> rufeMethodeAuf(basis, methode, argumente)
                basis.felder[name] != null -> rufeAuf(basis.felder.getValue(name), argumente)
                else -> throw LaufzeitFehler("Objekt hat keine Methode '$name'")
            }
        }
        is DatensatzWert -> {
            val feld = basis.felder[name]
                ?: throw LaufzeitFehler("Datensatz hat kein Feld '$name'")
            rufeAuf(feld, argumente)
        }
        else -> eingebauteMethode(basis, name, argumente)
    }

    private fun rufeMethodeAuf(
        objekt: ObjektWert,
        methode: FunktionDeklaration,
        argumente: List<Wert>,
    ): Wert {
        val lokal = Umgebung(global)
        lokal.definiere("dies", objekt)
        methode.parameter.forEachIndexed { idx, p -> lokal.definiere(p.name, argumente[idx]) }
        return try {
            führeAnweisungAus(methode.körper, lokal)
            NichtsWert
        } catch (z: ZurückSignal) {
            z.wert
        }
    }

    private fun gebundeneMethode(objekt: ObjektWert, methode: FunktionDeklaration): FunktionWert {
        val abschluss = Umgebung(global)
        abschluss.definiere("dies", objekt)
        return FunktionWert(methode.parameter, methode.körper, abschluss, methode.name)
    }

    private fun findeMethode(klasse: KlasseDeklaration, name: String): FunktionDeklaration? {
        klasse.methoden.firstOrNull { it.name == name }?.let { return it }
        val oberklasse = klasse.oberklasse?.let { klassen[it] }
        return oberklasse?.let { findeMethode(it, name) }
    }

    // ---- Konstruktion -------------------------------------------------------

    private fun konstruiere(typname: String, argumente: List<Wert>): Wert {
        datensätze[typname]?.let { datensatz ->
            val felder = LinkedHashMap<String, Wert>()
            datensatz.felder.forEachIndexed { idx, feld -> felder[feld.name] = argumente[idx] }
            return DatensatzWert(typname, felder)
        }
        klassen[typname]?.let { klasse ->
            return konstruiereObjekt(klasse, argumente)
        }
        throw LaufzeitFehler("Unbekannter Typ: '$typname'")
    }

    private fun konstruiereObjekt(klasse: KlasseDeklaration, argumente: List<Wert>): ObjektWert {
        val kette = klassenKette(klasse)
        val felder = LinkedHashMap<String, Wert>()
        val konstruktorFelder = kette.flatMap { k -> k.felder.filter { it.initialwert == null } }
        konstruktorFelder.forEachIndexed { idx, feld -> felder[feld.name] = argumente[idx] }
        val objekt = ObjektWert(klasse, felder)
        // Initialisierer koennen 'dies' verwenden.
        val umgebung = Umgebung(global)
        umgebung.definiere("dies", objekt)
        for (k in kette) {
            for (feld in k.felder) {
                if (feld.initialwert != null) {
                    felder[feld.name] = evaluiere(feld.initialwert, umgebung)
                }
            }
        }
        return objekt
    }

    private fun klassenKette(klasse: KlasseDeklaration): List<KlasseDeklaration> {
        val kette = ArrayDeque<KlasseDeklaration>()
        var aktuell: KlasseDeklaration? = klasse
        while (aktuell != null) {
            kette.addFirst(aktuell)
            aktuell = aktuell.oberklasse?.let { klassen[it] }
        }
        return kette.toList()
    }

    // ---- Operatoren ---------------------------------------------------------

    private fun binär(operator: TokenTyp, links: Wert, rechts: Wert): Wert = when (operator) {
        GLEICH -> WahrheitWert(gleichheit(links, rechts))
        UNGLEICH -> WahrheitWert(!gleichheit(links, rechts))
        KLEINER, KLEINER_GLEICH, GRÖSSER, GRÖSSER_GLEICH -> vergleich(operator, links, rechts)
        PLUS, MINUS, STERN, SCHRÄGSTRICH, PROZENT -> {
            if (operator == PLUS && (links is TextWert || rechts is TextWert)) {
                TextWert(darstelle(links) + darstelle(rechts))
            } else {
                arithmetik(operator, links, rechts)
            }
        }
        else -> throw LaufzeitFehler("Unbekannter Operator: $operator")
    }

    private fun arithmetik(operator: TokenTyp, links: Wert, rechts: Wert): Wert {
        if (links is GanzzahlWert && rechts is GanzzahlWert) {
            val a = links.wert
            val b = rechts.wert
            return GanzzahlWert(
                when (operator) {
                    PLUS -> a + b
                    MINUS -> a - b
                    STERN -> a * b
                    SCHRÄGSTRICH -> {
                        if (b == 0L) throw LaufzeitFehler("Division durch Null")
                        a / b
                    }
                    PROZENT -> {
                        if (b == 0L) throw LaufzeitFehler("Modulo durch Null")
                        a % b
                    }
                    else -> throw LaufzeitFehler("Unbekannter Operator")
                },
            )
        }
        val a = alsDouble(links)
        val b = alsDouble(rechts)
        return KommazahlWert(
            when (operator) {
                PLUS -> a + b
                MINUS -> a - b
                STERN -> a * b
                SCHRÄGSTRICH -> {
                    if (b == 0.0) throw LaufzeitFehler("Division durch Null")
                    a / b
                }
                PROZENT -> a % b
                else -> throw LaufzeitFehler("Unbekannter Operator")
            },
        )
    }

    private fun vergleich(operator: TokenTyp, links: Wert, rechts: Wert): Wert {
        val ordnung = when {
            (links is GanzzahlWert || links is KommazahlWert) &&
                (rechts is GanzzahlWert || rechts is KommazahlWert) ->
                alsDouble(links).compareTo(alsDouble(rechts))
            links is TextWert && rechts is TextWert -> links.wert.compareTo(rechts.wert)
            links is ZeichenWert && rechts is ZeichenWert -> links.wert.compareTo(rechts.wert)
            else -> throw LaufzeitFehler("Werte sind nicht vergleichbar")
        }
        return WahrheitWert(
            when (operator) {
                KLEINER -> ordnung < 0
                KLEINER_GLEICH -> ordnung <= 0
                GRÖSSER -> ordnung > 0
                GRÖSSER_GLEICH -> ordnung >= 0
                else -> false
            },
        )
    }

    // ---- Eingebaute Methoden ------------------------------------------------

    private fun eingebauteMethode(basis: Wert, name: String, argumente: List<Wert>): Wert =
        when (basis) {
            is TextWert -> textMethode(basis, name, argumente)
            is ListeWert -> listeMethode(basis, name, argumente)
            is AbbildungWert -> abbildungMethode(basis, name, argumente)
            is GanzzahlWert, is KommazahlWert, is WahrheitWert, is ZeichenWert ->
                if (name == "alsText") TextWert(darstelle(basis))
                else throw LaufzeitFehler("${darstelle(basis)} hat keine Methode '$name'")
            else -> throw LaufzeitFehler("${darstelle(basis)} hat keine Methode '$name'")
        }

    private fun textMethode(basis: TextWert, name: String, argumente: List<Wert>): Wert = when (name) {
        "länge" -> GanzzahlWert(basis.wert.length.toLong())
        "großbuchstaben" -> TextWert(basis.wert.uppercase())
        "kleinbuchstaben" -> TextWert(basis.wert.lowercase())
        "alsText" -> basis
        "enthält" -> WahrheitWert(basis.wert.contains((argumente[0] as TextWert).wert))
        "teile" -> ListeWert(
            basis.wert.split((argumente[0] as TextWert).wert)
                .map { TextWert(it) as Wert }
                .toMutableList(),
        )
        "zeichenBei" -> {
            val i = (argumente[0] as GanzzahlWert).wert.toInt()
            if (i !in basis.wert.indices) throw LaufzeitFehler("Textindex ausserhalb des Bereichs: $i")
            ZeichenWert(basis.wert[i])
        }
        else -> throw LaufzeitFehler("Text hat keine Methode '$name'")
    }

    private fun listeMethode(basis: ListeWert, name: String, argumente: List<Wert>): Wert = when (name) {
        "länge" -> GanzzahlWert(basis.elemente.size.toLong())
        "istLeer" -> WahrheitWert(basis.elemente.isEmpty())
        "hinzufügen" -> {
            basis.elemente.add(argumente[0]); NichtsWert
        }
        "holen" -> {
            val i = (argumente[0] as GanzzahlWert).wert.toInt()
            if (i !in basis.elemente.indices) throw LaufzeitFehler("Listenindex ausserhalb des Bereichs: $i")
            basis.elemente[i]
        }
        "setze" -> {
            val i = (argumente[0] as GanzzahlWert).wert.toInt()
            if (i !in basis.elemente.indices) throw LaufzeitFehler("Listenindex ausserhalb des Bereichs: $i")
            basis.elemente[i] = argumente[1]
            NichtsWert
        }
        "entferne" -> {
            val i = (argumente[0] as GanzzahlWert).wert.toInt()
            if (i !in basis.elemente.indices) throw LaufzeitFehler("Listenindex ausserhalb des Bereichs: $i")
            basis.elemente.removeAt(i)
        }
        else -> throw LaufzeitFehler("Liste hat keine Methode '$name'")
    }

    private fun abbildungMethode(basis: AbbildungWert, name: String, argumente: List<Wert>): Wert =
        when (name) {
            "länge" -> GanzzahlWert(basis.eintraege.size.toLong())
            "istLeer" -> WahrheitWert(basis.eintraege.isEmpty())
            "enthält" -> WahrheitWert(basis.eintraege.containsKey(argumente[0]))
            "holen" -> basis.eintraege[argumente[0]]
                ?: throw LaufzeitFehler("Schluessel nicht gefunden: ${darstelle(argumente[0])}")
            "setze" -> {
                basis.eintraege[argumente[0]] = argumente[1]; NichtsWert
            }
            "schlüssel" -> ListeWert(basis.eintraege.keys.toMutableList())
            else -> throw LaufzeitFehler("Abbildung hat keine Methode '$name'")
        }

    // ---- Kleine Hilfen ------------------------------------------------------

    private fun wahrheit(wert: Wert): Boolean =
        (wert as? WahrheitWert)?.wert
            ?: throw LaufzeitFehler("Wahrheitswert erwartet, erhielt ${darstelle(wert)}")

    private fun alsDouble(wert: Wert): Double = when (wert) {
        is GanzzahlWert -> wert.wert.toDouble()
        is KommazahlWert -> wert.wert
        else -> throw LaufzeitFehler("Zahl erwartet, erhielt ${darstelle(wert)}")
    }
}
