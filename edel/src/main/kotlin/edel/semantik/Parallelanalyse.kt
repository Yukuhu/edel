package edel.semantik

import edel.lexer.TokenTyp
import edel.parser.*

/** Ein Akkumulator einer Reduktionsschleife: Variablenname und Operator (`+` oder `*`). */
class Akkumulator(val name: String, val operator: TokenTyp)

/** Eine von ausserhalb der Schleife gelesene Variable (fuer das Bytecode-Backend). */
class GefangeneVariable(val name: String, val typ: Typ)

/** Beschreibung einer als parallele Reduktion erkannten Schleife. */
class Reduktion(val akkumulatoren: List<Akkumulator>, val gefangene: List<GefangeneVariable>)

/**
 * Eine "Gabelung": ein binaerer Ausdruck `A op B`, dessen Operanden beide rein
 * und nicht trivial sind und daher nebenlaeufig (fork/join) berechnet werden
 * koennen. [linkeGefangene] sind die freien Variablen des linken Operanden,
 * [ergebnistyp] der Typ des Gesamtausdrucks (fuer das Bytecode-Backend).
 */
class Gabel(val linkeGefangene: List<GefangeneVariable>, val ergebnistyp: Typ)

/**
 * Eine Streuung ("parallel map"): eine `für von bis`-Schleife, die nur in
 * disjunkte Listenelemente am Schleifenindex schreibt — `K.setze(i, …)`.
 */
class Streuung(val zielListen: List<String>)

/** Eine Bindung innerhalb einer parallelen `sei`-Gruppe. */
class GruppenBindung(
    val anweisung: SeiAnweisung,
    val gefangene: List<GefangeneVariable>,
    val typ: Typ,
    val nichtTrivial: Boolean,
)

/**
 * Eine Gruppe aufeinanderfolgender, voneinander unabhaengiger reiner
 * `sei`-Bindungen, deren Anfangswerte nebenlaeufig berechnet werden koennen.
 */
class Gruppe(val bindungen: List<GruppenBindung>)

/** Ergebnis der Parallelanalyse: alle automatisch parallelisierbaren Stellen. */
class Parallelplan(
    val reduktionen: Map<Anweisung, Reduktion>,
    val gabeln: Map<BinärAusdruck, Gabel> = emptyMap(),
    val streuungen: Map<Anweisung, Streuung> = emptyMap(),
    val gruppen: Map<SeiAnweisung, Gruppe> = emptyMap(),
) {
    val anzahl: Int get() = reduktionen.size
    val gabelAnzahl: Int get() = gabeln.size
    val streuAnzahl: Int get() = streuungen.size
    val gruppenAnzahl: Int get() = gruppen.size
    fun reduktionVon(schleife: Anweisung): Reduktion? = reduktionen[schleife]
    fun gabelVon(ausdruck: BinärAusdruck): Gabel? = gabeln[ausdruck]
    fun streuungVon(schleife: Anweisung): Streuung? = streuungen[schleife]
    fun gruppeAb(anweisung: Anweisung): Gruppe? = gruppen[anweisung]
}

/**
 * Erkennt automatisch parallelisierbare `für`-Schleifen. Eine Schleife gilt als
 * parallele **Reduktion**, wenn ihre Iterationen nachweislich unabhaengig sind:
 * der Rumpf aktualisiert nur aeussere `Ganzzahl`-Akkumulatoren ueber einen festen
 * assoziativen Operator (`+` oder `*`), tut keine Ein-/Ausgabe, enthaelt kein
 * `brich`/`zurück`/`weiter` und ruft nur reine Funktionen auf.
 *
 * Da `+` und `*` auf 64-Bit-Ganzzahlen (mod 2^64) assoziativ und kommutativ sind,
 * ist das parallele Ergebnis bitgleich zum sequentiellen.
 */
class Parallelanalyse(
    private val programm: Programm,
    private val symbole: GlobaleSymbole,
    private val bezeichnerTypen: Map<Bezeichner, Typ>,
    private val binärTypen: Map<BinärAusdruck, Typ> = emptyMap(),
    private val bindungsTypen: Map<SeiAnweisung, Typ> = emptyMap(),
) {
    private val reineFunktionen: Set<String> by lazy { berechneReinheit() }

    fun analysiere(): Parallelplan {
        val reduktionen = LinkedHashMap<Anweisung, Reduktion>() // Quelltextreihenfolge
        val gabeln = LinkedHashMap<BinärAusdruck, Gabel>()
        val streuungen = LinkedHashMap<Anweisung, Streuung>()
        val gruppen = LinkedHashMap<SeiAnweisung, Gruppe>()
        for (deklaration in programm.deklarationen) {
            if (deklaration is FunktionDeklaration) {
                sucheSchleifen(deklaration.körper, reduktionen, streuungen)
                sucheGabeln(deklaration.körper, gabeln)
                sucheGruppen(deklaration.körper, gruppen)
            }
        }
        return Parallelplan(reduktionen, gabeln, streuungen, gruppen)
    }

    // ---- Gabelungen (unabhaengige reine Teilausdruecke) ---------------------

    private fun sucheGabeln(körper: Block, ergebnis: MutableMap<BinärAusdruck, Gabel>) {
        for (anweisung in alleAnweisungen(körper)) {
            for (ausdruck in direkteAusdrücke(anweisung)) {
                for (teil in alleTeilausdrücke(ausdruck)) {
                    if (teil is BinärAusdruck && istGabelKandidat(teil)) {
                        ergebnis[teil] = Gabel(
                            gefangeneVon(teil.links),
                            binärTypen[teil] ?: FehlerTyp,
                        )
                    }
                }
            }
        }
    }

    /** `A op B` ist gabelbar, wenn beide Operanden rein sind und je einen Aufruf enthalten. */
    private fun istGabelKandidat(ausdruck: BinärAusdruck): Boolean {
        if (ausdruck.operator == TokenTyp.UND || ausdruck.operator == TokenTyp.ODER) return false
        return istReinerAusdruck(ausdruck.links) && enthältAufruf(ausdruck.links) &&
            istReinerAusdruck(ausdruck.rechts) && enthältAufruf(ausdruck.rechts)
    }

    private fun enthältAufruf(ausdruck: Ausdruck): Boolean =
        alleTeilausdrücke(ausdruck).any { it is AufrufAusdruck }

    private fun istReinerAusdruck(ausdruck: Ausdruck): Boolean {
        for (teil in alleTeilausdrücke(ausdruck)) {
            if (teil is LambdaAusdruck) return false
            if (teil is AufrufAusdruck) {
                when (val ziel = teil.ziel) {
                    is Bezeichner -> {
                        if (ziel.name == "drucke" || ziel.name == "lies") return false
                        val fqn = ziel.aufgelöst ?: ziel.name
                        if (fqn !in reineFunktionen && ziel.name !in REINE_EINGEBAUTE) return false
                    }
                    is FeldzugriffAusdruck -> if (ziel.feld !in REINE_METHODEN) return false
                    else -> return false
                }
            }
        }
        return true
    }

    private fun gefangeneVon(ausdruck: Ausdruck): List<GefangeneVariable> {
        val ergebnis = LinkedHashMap<String, Typ>()
        for (teil in alleTeilausdrücke(ausdruck)) {
            if (teil is Bezeichner && !istGlobalerName(teil)) {
                bezeichnerTypen[teil]?.let { ergebnis.putIfAbsent(teil.name, it) }
            }
        }
        return ergebnis.map { GefangeneVariable(it.key, it.value) }
    }

    /** Namen aller in [ausdruck] gelesenen Variablen (ohne Funktionen). */
    private fun freieNamen(ausdruck: Ausdruck): Set<String> =
        alleTeilausdrücke(ausdruck)
            .filterIsInstance<Bezeichner>()
            .filterNot { istGlobalerName(it) }
            .mapTo(HashSet()) { it.name }

    private fun istGlobalerName(b: Bezeichner): Boolean {
        val fqn = b.aufgelöst ?: b.name
        return fqn in symbole.funktionen || b.name in Resolver.EINGEBAUTE_NAMEN
    }

    // ---- Unabhaengige 'sei'-Gruppen -----------------------------------------

    private fun sucheGruppen(körper: Block, ziel: MutableMap<SeiAnweisung, Gruppe>) {
        for (anweisung in alleAnweisungen(körper)) {
            if (anweisung is Block) gruppenImBlock(anweisung.anweisungen, ziel)
        }
    }

    /**
     * Findet maximale Folgen aufeinanderfolgender reiner `sei`-Bindungen, deren
     * Anfangswerte sich nicht gegenseitig referenzieren. Eine Gruppe wird
     * registriert, sobald sie mindestens zwei nicht triviale Bindungen enthaelt.
     */
    private fun gruppenImBlock(anweisungen: List<Anweisung>, ziel: MutableMap<SeiAnweisung, Gruppe>) {
        var gruppe = mutableListOf<SeiAnweisung>()
        val gebundene = HashSet<String>()

        fun abschliessen() {
            if (gruppe.size >= 2 && gruppe.count { enthältAufruf(it.initialwert) } >= 2) {
                ziel[gruppe.first()] = Gruppe(
                    gruppe.map { sei ->
                        GruppenBindung(
                            sei,
                            gefangeneVon(sei.initialwert),
                            bindungsTypen[sei] ?: FehlerTyp,
                            enthältAufruf(sei.initialwert),
                        )
                    },
                )
            }
            gruppe = mutableListOf()
            gebundene.clear()
        }

        fun versucheAufnahme(anweisung: Anweisung): Boolean {
            if (anweisung !is SeiAnweisung) return false
            if (!istReinerAusdruck(anweisung.initialwert)) return false
            if (freieNamen(anweisung.initialwert).any { it in gebundene }) return false
            gruppe.add(anweisung)
            gebundene.add(anweisung.name)
            return true
        }

        for (anweisung in anweisungen) {
            if (!versucheAufnahme(anweisung)) {
                abschliessen()
                versucheAufnahme(anweisung) // darf eine neue Gruppe beginnen
            }
        }
        abschliessen()
    }

    // ---- Schleifensuche -----------------------------------------------------

    private fun sucheSchleifen(
        anweisung: Anweisung,
        reduktionen: MutableMap<Anweisung, Reduktion>,
        streuungen: MutableMap<Anweisung, Streuung>,
    ) {
        if (anweisung is FürVonBisAnweisung || anweisung is FürInAnweisung) {
            val reduktion = alsReduktion(anweisung)
            if (reduktion != null) {
                reduktionen[anweisung] = reduktion
                return // verschachtelte Schleifen laufen innerhalb sequentiell
            }
            if (anweisung is FürVonBisAnweisung) {
                val streuung = alsStreuung(anweisung)
                if (streuung != null) {
                    streuungen[anweisung] = streuung
                    return
                }
            }
        }
        for (unter in unterAnweisungen(anweisung)) {
            sucheSchleifen(unter, reduktionen, streuungen)
        }
    }

    // ---- Streuungen (parallel map: disjunkte Listenelemente) ----------------

    /**
     * Erkennt eine Streuschleife: jede Anweisung des Rumpfes ist entweder eine
     * lokale `sei`-Bindung oder ein Schreibzugriff `K.setze(i, …)` bzw.
     * `K[i] = …` auf eine aeussere Liste am Schleifenindex `i`. Da jede Iteration
     * ein anderes Element schreibt, sind die Iterationen unabhaengig.
     */
    private fun alsStreuung(schleife: FürVonBisAnweisung): Streuung? {
        val körper = schleife.körper
        val loopVar = schleife.variable
        val lokale = HashSet<String>()
        lokale.add(loopVar)
        sammleLokaleNamen(körper, lokale)

        val ziele = LinkedHashSet<String>()
        val ausnahmen = java.util.Collections.newSetFromMap(IdentityHashMap<Ausdruck, Boolean>())

        // Rumpf darf nur lokale 'sei' und Streuschreibzugriffe enthalten.
        for (anweisung in körper.anweisungen) {
            when (anweisung) {
                is SeiAnweisung -> {} // lokale Bindung; Ausdruck wird unten geprueft
                is ZuweisungAnweisung -> {
                    val ziel = anweisung.ziel as? IndexAusdruck ?: return null
                    val liste = ziel.ziel as? Bezeichner ?: return null
                    val index = ziel.index as? Bezeichner ?: return null
                    if (liste.name in lokale || index.name != loopVar) return null
                    ziele.add(liste.name)
                    ausnahmen.add(ziel)
                    ausnahmen.add(liste)
                }
                is AusdruckAnweisung -> {
                    val ruf = anweisung.ausdruck as? AufrufAusdruck ?: return null
                    val feld = ruf.ziel as? FeldzugriffAusdruck ?: return null
                    val liste = feld.ziel as? Bezeichner ?: return null
                    val index = ruf.argumente.getOrNull(0) as? Bezeichner
                    if (feld.feld != "setze" || ruf.argumente.size != 2) return null
                    if (liste.name in lokale || index?.name != loopVar) return null
                    ziele.add(liste.name)
                    ausnahmen.add(ruf)
                    ausnahmen.add(feld)
                    ausnahmen.add(liste)
                }
                else -> return null // kein wenn/verschachtelte Schleifen im Streurumpf (v1)
            }
        }
        if (ziele.isEmpty()) return null

        // Ziel-Listen muessen Listen sein und duerfen nicht gelesen werden.
        for (name in ziele) {
            if (typVonName(körper, name) !is ListeTyp) return null
        }
        for (anweisung in alleAnweisungen(körper)) {
            for (ausdruck in direkteAusdrücke(anweisung)) {
                for (teil in alleTeilausdrücke(ausdruck)) {
                    if (teil is LambdaAusdruck) return null
                    if (teil is AufrufAusdruck && teil !in ausnahmen && aufrufVerletzt(teil)) return null
                    if (teil is Bezeichner && teil.name in ziele && teil !in ausnahmen) return null
                }
            }
        }
        return Streuung(ziele.toList())
    }

    private fun alsReduktion(schleife: Anweisung): Reduktion? {
        val körper: Block
        val schleifenVariable: String
        when (schleife) {
            is FürVonBisAnweisung -> { körper = schleife.körper; schleifenVariable = schleife.variable }
            is FürInAnweisung -> { körper = schleife.körper; schleifenVariable = schleife.variable }
            else -> return null
        }

        val lokale = HashSet<String>()
        lokale.add(schleifenVariable)
        sammleLokaleNamen(körper, lokale)

        // Akkumulator-Zuweisungen einsammeln und pruefen.
        val operatoren = HashMap<String, TokenTyp>()
        val legaleAkkuKnoten = java.util.Collections.newSetFromMap(IdentityHashMap<Bezeichner, Boolean>())
        for (anw in alleAnweisungen(körper)) {
            if (anw !is ZuweisungAnweisung) continue
            val ziel = anw.ziel
            if (ziel !is Bezeichner) return null // Feld-/Indexzuweisung -> nicht rein
            if (ziel.name in lokale) continue // lokale Variable, unkritisch
            // Aeussere Variable: muss eine Akkumulator-Aktualisierung sein.
            val rhs = anw.wert as? BinärAusdruck ?: return null
            if (rhs.operator != TokenTyp.PLUS && rhs.operator != TokenTyp.STERN) return null
            val links = rhs.links
            val rechts = rhs.rechts
            val akkuOperand = when {
                links is Bezeichner && links.name == ziel.name -> links
                rechts is Bezeichner && rechts.name == ziel.name -> rechts
                else -> return null
            }
            val vorher = operatoren[ziel.name]
            if (vorher != null && vorher != rhs.operator) return null // uneinheitlicher Operator
            operatoren[ziel.name] = rhs.operator
            legaleAkkuKnoten.add(ziel)
            legaleAkkuKnoten.add(akkuOperand)
        }
        if (operatoren.isEmpty()) return null // keine Reduktion erkennbar

        // Akkumulatoren muessen Ganzzahlen sein.
        for (name in operatoren.keys) {
            val typ = typVonName(körper, name) ?: return null
            if (typ != GanzzahlTyp) return null
        }

        // Akkumulatoren duerfen ausserhalb ihrer eigenen Aktualisierung nicht vorkommen.
        for (bezeichner in alleBezeichner(körper)) {
            if (bezeichner.name in operatoren && bezeichner !in legaleAkkuKnoten) return null
        }

        // Rumpf darf nichts Unreines / Reihenfolgeabhaengiges enthalten.
        if (rumpfVerletztUnabhängigkeit(körper)) return null

        // Gefangene aeussere Variablen einsammeln (fuer das Bytecode-Backend).
        val gefangene = LinkedHashMap<String, Typ>()
        for (bezeichner in alleBezeichner(körper)) {
            val name = bezeichner.name
            if (name in lokale || name in operatoren) continue
            if (istGlobalerName(bezeichner)) continue
            bezeichnerTypen[bezeichner]?.let { gefangene.putIfAbsent(name, it) }
        }

        val akkumulatoren = operatoren.entries.map { Akkumulator(it.key, it.value) }
        return Reduktion(akkumulatoren, gefangene.map { GefangeneVariable(it.key, it.value) })
    }

    private fun typVonName(körper: Block, name: String): Typ? {
        for (bezeichner in alleBezeichner(körper)) {
            if (bezeichner.name == name) bezeichnerTypen[bezeichner]?.let { return it }
        }
        return null
    }

    /** Prueft den Rumpf auf Ein-/Ausgabe, Sprungbefehle, Mutation und unreine Aufrufe. */
    private fun rumpfVerletztUnabhängigkeit(körper: Block): Boolean {
        for (anw in alleAnweisungen(körper)) {
            when (anw) {
                is BrichAnweisung, is WeiterAnweisung, is ZurückAnweisung -> return true
                is ZuweisungAnweisung ->
                    if (anw.ziel is FeldzugriffAusdruck || anw.ziel is IndexAusdruck) return true
                else -> {}
            }
            for (ausdruck in direkteAusdrücke(anw)) {
                for (teil in alleTeilausdrücke(ausdruck)) {
                    if (teil is LambdaAusdruck) return true
                    if (teil is AufrufAusdruck && aufrufVerletzt(teil)) return true
                }
            }
        }
        return false
    }

    private fun aufrufVerletzt(aufruf: AufrufAusdruck): Boolean {
        val ziel = aufruf.ziel
        if (ziel is Bezeichner) {
            // Eingebaute Namen werden ueber den Kurznamen erkannt; benutzerdefinierte
            // Funktionen sind in `reineFunktionen` per FQN registriert (Resolver-Vorpass).
            return when (ziel.name) {
                "drucke", "lies" -> true
                "länge", "Liste", "Abbildung", "Paar" -> false // reine Erzeugung/Abfrage
                else -> (ziel.aufgelöst ?: ziel.name) !in reineFunktionen
            }
        }
        if (ziel is FeldzugriffAusdruck) {
            return ziel.feld !in REINE_METHODEN // mutierende oder benutzerdefinierte Methode
        }
        return true
    }

    // ---- Reinheitsanalyse der Funktionen ------------------------------------

    private fun berechneReinheit(): Set<String> {
        val funktionen = programm.deklarationen.filterIsInstance<FunktionDeklaration>()
        val unrein = HashSet<String>()
        var geändert = true
        while (geändert) {
            geändert = false
            for (funktion in funktionen) {
                if (funktion.name in unrein) continue
                if (funktionIstUnrein(funktion.körper, unrein)) {
                    unrein.add(funktion.name)
                    geändert = true
                }
            }
        }
        return funktionen.map { it.name }.toSet() - unrein
    }

    private fun funktionIstUnrein(körper: Block, unrein: Set<String>): Boolean {
        for (anw in alleAnweisungen(körper)) {
            if (anw is ZuweisungAnweisung &&
                (anw.ziel is FeldzugriffAusdruck || anw.ziel is IndexAusdruck)
            ) {
                return true
            }
            for (ausdruck in direkteAusdrücke(anw)) {
                for (teil in alleTeilausdrücke(ausdruck)) {
                    if (teil !is AufrufAusdruck) continue
                    val ziel = teil.ziel
                    if (ziel is Bezeichner) {
                        if (ziel.name == "drucke" || ziel.name == "lies") return true
                        // `unrein` ist nach Resolver-Vorpass per FQN indiziert.
                        if ((ziel.aufgelöst ?: ziel.name) in unrein) return true
                    } else if (ziel is FeldzugriffAusdruck) {
                        if (ziel.feld !in REINE_METHODEN) return true
                    }
                }
            }
        }
        return false
    }

    // ---- AST-Durchlauf ------------------------------------------------------

    private fun sammleLokaleNamen(anweisung: Anweisung, ziel: MutableSet<String>) {
        when (anweisung) {
            is SeiAnweisung -> ziel.add(anweisung.name)
            is FürVonBisAnweisung -> ziel.add(anweisung.variable)
            is FürInAnweisung -> ziel.add(anweisung.variable)
            else -> {}
        }
        for (unter in unterAnweisungen(anweisung)) sammleLokaleNamen(unter, ziel)
    }

    private fun alleAnweisungen(anweisung: Anweisung): List<Anweisung> {
        val ergebnis = mutableListOf(anweisung)
        for (unter in unterAnweisungen(anweisung)) ergebnis.addAll(alleAnweisungen(unter))
        return ergebnis
    }

    private fun unterAnweisungen(anweisung: Anweisung): List<Anweisung> = when (anweisung) {
        is Block -> anweisung.anweisungen
        is WennAnweisung -> listOfNotNull(anweisung.dann, anweisung.sonst)
        is SolangeAnweisung -> listOf(anweisung.körper)
        is FürInAnweisung -> listOf(anweisung.körper)
        is FürVonBisAnweisung -> listOf(anweisung.körper)
        else -> emptyList()
    }

    private fun direkteAusdrücke(anweisung: Anweisung): List<Ausdruck> = when (anweisung) {
        is SeiAnweisung -> listOf(anweisung.initialwert)
        is AusdruckAnweisung -> listOf(anweisung.ausdruck)
        is ZuweisungAnweisung -> listOf(anweisung.ziel, anweisung.wert)
        is WennAnweisung -> listOf(anweisung.bedingung)
        is SolangeAnweisung -> listOf(anweisung.bedingung)
        is FürInAnweisung -> listOf(anweisung.iterierbar)
        is FürVonBisAnweisung -> listOf(anweisung.von, anweisung.bis)
        is ZurückAnweisung -> listOfNotNull(anweisung.wert)
        else -> emptyList()
    }

    private fun alleTeilausdrücke(ausdruck: Ausdruck): List<Ausdruck> {
        val ergebnis = mutableListOf(ausdruck)
        for (kind in kinder(ausdruck)) ergebnis.addAll(alleTeilausdrücke(kind))
        return ergebnis
    }

    private fun kinder(ausdruck: Ausdruck): List<Ausdruck> = when (ausdruck) {
        is UnärAusdruck -> listOf(ausdruck.operand)
        is BinärAusdruck -> listOf(ausdruck.links, ausdruck.rechts)
        is AufrufAusdruck -> listOf(ausdruck.ziel) + ausdruck.argumente
        is IndexAusdruck -> listOf(ausdruck.ziel, ausdruck.index)
        is FeldzugriffAusdruck -> listOf(ausdruck.ziel)
        is NeuAusdruck -> ausdruck.argumente
        is LambdaAusdruck -> listOf(ausdruck.körper)
        is WähleAusdruck ->
            listOf(ausdruck.subjekt) + ausdruck.fälle.flatMap { listOf(it.muster, it.ergebnis) } +
                ausdruck.sonst
        else -> emptyList()
    }

    private fun alleBezeichner(körper: Block): List<Bezeichner> {
        val ergebnis = mutableListOf<Bezeichner>()
        for (anw in alleAnweisungen(körper)) {
            for (ausdruck in direkteAusdrücke(anw)) {
                for (teil in alleTeilausdrücke(ausdruck)) {
                    if (teil is Bezeichner) ergebnis.add(teil)
                }
            }
        }
        return ergebnis
    }

    private companion object {
        /** Eingebaute Methoden ohne Seiteneffekt. */
        val REINE_METHODEN = setOf(
            "länge", "holen", "istLeer", "großbuchstaben", "kleinbuchstaben",
            "zeichenBei", "enthält", "teile", "alsText", "schlüssel",
        )

        /** Eingebaute Funktionen ohne Seiteneffekt (reine Abfrage bzw. Erzeugung). */
        val REINE_EINGEBAUTE = setOf("länge", "Liste", "Abbildung", "Paar")
    }
}

private typealias IdentityHashMap<K, V> = java.util.IdentityHashMap<K, V>
