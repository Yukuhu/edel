package edel.codegen

import edel.fehler.Diagnose
import edel.fehler.NichtUnterstützt
import edel.fehler.Position
import edel.lexer.TokenTyp
import edel.lexer.TokenTyp.*
import edel.parser.*
import edel.semantik.*
import edel.semantik.Parallelplan
import edel.semantik.Reduktion
import java.lang.classfile.ClassFile
import java.lang.classfile.CodeBuilder
import java.lang.classfile.Label
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDesc
import java.lang.constant.ConstantDescs
import java.lang.constant.DirectMethodHandleDesc
import java.lang.constant.DynamicCallSiteDesc
import java.lang.constant.MethodHandleDesc
import java.lang.constant.MethodTypeDesc

/**
 * Phase-2-Backend (Kern): uebersetzt ein bereits typgeprueftes Edel-Programm in
 * eine echte JVM-`.class`-Datei mit der Standardbibliotheks-Class-File-API.
 *
 * Unterstuetzt werden Funktionen, native Grundtypen (`Ganzzahl`->`long`,
 * `Kommazahl`->`double`, `Wahrheit`->`boolean`, `Zeichen`->`char`,
 * `Text`->`String`), saemtlicher Kontrollfluss, Rekursion und `wähle` ueber
 * Grundwerte. Klassen, Datensaetze, Aufzaehlungen, Lambdas und Sammlungen
 * werden mit [NichtUnterstützt] abgelehnt — dafuer bleibt der Interpreter.
 */
class Bytecodeerzeuger(
    private val programm: Programm,
    private val symbole: GlobaleSymbole,
    private val klassenname: String,
    private val parallelplan: Parallelplan = Parallelplan(emptyMap()),
) {
    // Nicht-nullbare Grundarten und ihre nullbaren (geboxten) Gegenstuecke.
    private enum class Art {
        GANZ, KOMMA, WAHR, ZEICH, TEXT,
        N_GANZ, N_KOMMA, N_WAHR, N_ZEICH, N_TEXT,
        NICHTS,
    }
    private enum class Vgl { EQ, NE, LT, LE, GT, GE }

    private class Variable(val typ: Typ, val art: Art, val slot: Int)
    private class Schleife(val weiter: Label, val brich: Label)

    private val selbst: ClassDesc = ClassDesc.of(klassenname)

    // Pro Methode zuruekgesetzter Zustand.
    private lateinit var cob: CodeBuilder
    private var nächsterSlot = 0
    private val bereiche = ArrayDeque<HashMap<String, Variable>>()
    private val schleifen = ArrayDeque<Schleife>()
    private var rückgabeTyp: Typ = NichtsTyp

    // Parallel reduzierbare `für von bis`-Schleifen mit genau einem Akkumulator,
    // jeweils mit einem eindeutigen Index fuer die erzeugte `beitrag$n`-Methode.
    private var paralleleSchleifen: Map<FürVonBisAnweisung, Int> = emptyMap()

    // Gabelbare binaere Ganzzahl-Ausdruecke, je mit Index fuer die `gabel$n`-Methode.
    private var gabelIndizes: Map<BinärAusdruck, Int> = emptyMap()

    // Bindungen nebenlaeufiger `sei`-Gruppen, je mit Index fuer ihre `gruppe$n`-Methode.
    private var gruppenMethoden: Map<SeiAnweisung, Int> = emptyMap()

    // ---- Einstieg -----------------------------------------------------------

    fun kompiliere(): ByteArray {
        for (d in programm.deklarationen) {
            if (d !is FunktionDeklaration && d !is ImportDeklaration && d !is PaketDeklaration) {
                ablehnen(
                    "Das Bytecode-Backend (Kern) unterstuetzt noch keine Klassen, Datensaetze, " +
                        "Aufzaehlungen oder Schnittstellen — bitte 'edel starte' verwenden",
                    d.position,
                )
            }
        }
        val funktionen = programm.deklarationen.filterIsInstance<FunktionDeklaration>()

        // Parallel reduzierbare Schleifen erfassen (genau ein Ganzzahl-Akkumulator).
        val parallele = LinkedHashMap<FürVonBisAnweisung, Int>()
        for ((schleife, reduktion) in parallelplan.reduktionen) {
            if (schleife is FürVonBisAnweisung && reduktion.akkumulatoren.size == 1) {
                parallele[schleife] = parallele.size
            }
        }
        paralleleSchleifen = parallele
        val brauchtProdukt = parallele.keys.any {
            parallelplan.reduktionVon(it)!!.akkumulatoren[0].operator == STERN
        }

        // Gabelbare binaere Ausdruecke erfassen (Ganzzahl-Arithmetik `+ - *`).
        val gabeln = LinkedHashMap<BinärAusdruck, Int>()
        for ((ausdruck, gabel) in parallelplan.gabeln) {
            if (gabel.ergebnistyp == GanzzahlTyp &&
                (ausdruck.operator == PLUS || ausdruck.operator == MINUS || ausdruck.operator == STERN)
            ) {
                gabeln[ausdruck] = gabeln.size
            }
        }
        gabelIndizes = gabeln

        // Nebenlaeufige 'sei'-Gruppen erfassen (alle Bindungen Ganzzahl, nicht trivial).
        val gruppenBindungen = ArrayList<Pair<GruppenBindung, Int>>()
        val gruppenIndex = HashMap<SeiAnweisung, Int>()
        for (gruppe in parallelplan.gruppen.values) {
            if (gruppe.bindungen.all { it.typ == GanzzahlTyp && it.nichtTrivial }) {
                for (idx in 0 until gruppe.bindungen.size - 1) { // letzte Bindung inline
                    val nummer = gruppenBindungen.size
                    gruppenIndex[gruppe.bindungen[idx].anweisung] = nummer
                    gruppenBindungen.add(gruppe.bindungen[idx] to nummer)
                }
            }
        }
        gruppenMethoden = gruppenIndex

        return ClassFile.of().build(selbst) { clb ->
            clb.withFlags(ClassFile.ACC_PUBLIC or ClassFile.ACC_FINAL)
            // Einsprungpunkt: main ruft die Edel-Funktion start auf.
            clb.withMethodBody(
                "main",
                MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.ofDescriptor("[Ljava/lang/String;")),
                ClassFile.ACC_PUBLIC or ClassFile.ACC_STATIC,
            ) { code ->
                code.invokestatic(selbst, "start", MethodTypeDesc.of(ConstantDescs.CD_void))
                code.return_()
            }
            for (funktion in funktionen) {
                clb.withMethodBody(
                    funktion.name,
                    deskriptor(funktion.name),
                    ClassFile.ACC_PUBLIC or ClassFile.ACC_STATIC,
                ) { code ->
                    cob = code
                    erzeugeFunktion(funktion)
                }
            }
            // Je eine 'beitrag'-Methode pro paralleler Schleife (das Lambda fuer den Stream).
            for ((schleife, index) in parallele) {
                val reduktion = parallelplan.reduktionVon(schleife)!!
                val parameter = reduktion.gefangene.map { classDesc(artVon(it.typ, schleife.position)) } +
                    ConstantDescs.CD_long
                clb.withMethodBody(
                    "beitrag$$index",
                    MethodTypeDesc.of(ConstantDescs.CD_long, parameter),
                    ClassFile.ACC_PRIVATE or ClassFile.ACC_STATIC,
                ) { code ->
                    cob = code
                    erzeugeBeitrag(schleife, reduktion)
                }
            }
            // Gemeinsame Multiplikationsfunktion fuer Produkt-Reduktionen.
            if (brauchtProdukt) {
                clb.withMethodBody(
                    "produkt$",
                    MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_long, ConstantDescs.CD_long),
                    ClassFile.ACC_PRIVATE or ClassFile.ACC_STATIC,
                ) { code ->
                    code.lload(0)
                    code.lload(2)
                    code.lmul()
                    code.lreturn()
                }
            }
            // Je eine 'gabel'-Methode pro gabelbarem Ausdruck (der linke Operand).
            for ((ausdruck, index) in gabeln) {
                val gabel = parallelplan.gabelVon(ausdruck)!!
                val parameter = gabel.linkeGefangene.map {
                    classDesc(artVon(it.typ, ausdruck.position))
                }
                clb.withMethodBody(
                    "gabel$$index",
                    MethodTypeDesc.of(ConstantDescs.CD_long, parameter),
                    ClassFile.ACC_PRIVATE or ClassFile.ACC_STATIC,
                ) { code ->
                    cob = code
                    erzeugeLieferant(ausdruck.links, gabel.linkeGefangene, ausdruck.position)
                }
            }
            // Je eine 'gruppe'-Methode pro nebenlaeufiger 'sei'-Bindung.
            for ((bindung, index) in gruppenBindungen) {
                val parameter = bindung.gefangene.map {
                    classDesc(artVon(it.typ, bindung.anweisung.position))
                }
                clb.withMethodBody(
                    "gruppe$$index",
                    MethodTypeDesc.of(ConstantDescs.CD_long, parameter),
                    ClassFile.ACC_PRIVATE or ClassFile.ACC_STATIC,
                ) { code ->
                    cob = code
                    erzeugeLieferant(
                        bindung.anweisung.initialwert, bindung.gefangene, bindung.anweisung.position,
                    )
                }
            }
        }
    }

    /** Erzeugt eine `gabel$n`-Methode: berechnet den linken Operanden einer Gabelung. */
    /**
     * Erzeugt eine Lieferant-Methode: berechnet [ausdruck] (Typ Ganzzahl) aus
     * den gefangenen Variablen. Basis fuer `gabel$n`- und `gruppe$n`-Methoden.
     */
    private fun erzeugeLieferant(
        ausdruck: Ausdruck,
        gefangene: List<GefangeneVariable>,
        position: Position,
    ) {
        nächsterSlot = 0
        bereiche.clear()
        schleifen.clear()
        bereiche.addLast(HashMap())
        rückgabeTyp = GanzzahlTyp
        for (gefangen in gefangene) {
            val art = artVon(gefangen.typ, position)
            bereiche.last()[gefangen.name] = Variable(gefangen.typ, art, belegeSlot(art))
        }
        erzeugeMitArt(ausdruck, Art.GANZ)
        cob.lreturn()
    }

    /**
     * Erzeugt eine Gabelung: der linke Operand wird per `CompletableFuture` im
     * Fork-Join-Pool berechnet, der rechte im aktuellen Thread; danach werden
     * beide mit dem Operator verknuepft. Eine Granularitaetsschranke
     * (`getSurplusQueuedTaskCount`) vermeidet uebermaessiges Forken.
     */
    private fun erzeugeGabelArithmetik(ausdruck: BinärAusdruck) {
        val index = gabelIndizes.getValue(ausdruck)
        val gabel = parallelplan.gabelVon(ausdruck)!!
        val gefangenDescs = gabel.linkeGefangene.map { classDesc(artVon(it.typ, ausdruck.position)) }
        val sequentiell = cob.newLabel()
        val ende = cob.newLabel()

        cob.invokestatic(
            CD_ForkJoinTask, "getSurplusQueuedTaskCount",
            MethodTypeDesc.of(ConstantDescs.CD_int),
        )
        cob.loadConstant(3)
        cob.if_icmpgt(sequentiell)

        // Parallel: linker Operand als CompletableFuture, rechter inline.
        for (gefangen in gabel.linkeGefangene) {
            val variable = findeVariable(gefangen.name)
                ?: ablehnen("Variable '${gefangen.name}' nicht gefunden", ausdruck.position)
            lade(variable.art, variable.slot)
        }
        cob.invokedynamic(lieferantAufrufstelle("gabel$$index", gefangenDescs))
        cob.invokestatic(
            CD_CompletableFuture, "supplyAsync",
            MethodTypeDesc.of(CD_CompletableFuture, CD_Supplier),
        )
        val futureSlot = belegeSlot(Art.TEXT)
        cob.astore(futureSlot)
        erzeugeMitArt(ausdruck.rechts, Art.GANZ)
        val rechtsSlot = belegeSlot(Art.GANZ)
        cob.lstore(rechtsSlot)
        cob.aload(futureSlot)
        cob.invokevirtual(CD_CompletableFuture, "join", MethodTypeDesc.of(ConstantDescs.CD_Object))
        cob.checkcast(CD_Long)
        cob.invokevirtual(CD_Long, "longValue", MethodTypeDesc.of(ConstantDescs.CD_long))
        cob.lload(rechtsSlot)
        gabelOperator(ausdruck.operator)
        cob.goto_(ende)

        // Sequentiell.
        cob.labelBinding(sequentiell)
        erzeugeMitArt(ausdruck.links, Art.GANZ)
        erzeugeMitArt(ausdruck.rechts, Art.GANZ)
        gabelOperator(ausdruck.operator)

        cob.labelBinding(ende)
    }

    private fun gabelOperator(operator: TokenTyp) {
        when (operator) {
            PLUS -> cob.ladd()
            MINUS -> cob.lsub()
            STERN -> cob.lmul()
            else -> {}
        }
    }

    private fun lieferantAufrufstelle(
        methodenname: String,
        gefangenDescs: List<ClassDesc>,
    ): DynamicCallSiteDesc {
        val implTyp = MethodTypeDesc.of(ConstantDescs.CD_long, gefangenDescs)
        val impl = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC, selbst, methodenname, implTyp,
        )
        val samTyp = MethodTypeDesc.of(ConstantDescs.CD_Object)
        val instanzTyp = MethodTypeDesc.of(CD_Long)
        val fabrikTyp = MethodTypeDesc.of(CD_Supplier, gefangenDescs)
        return DynamicCallSiteDesc.of(METAFACTORY, "get", fabrikTyp, samTyp, impl, instanzTyp)
    }

    /** Erzeugt den Rumpf einer `beitrag$n`-Methode: ein Schleifendurchlauf, der den Beitrag liefert. */
    private fun erzeugeBeitrag(schleife: FürVonBisAnweisung, reduktion: Reduktion) {
        nächsterSlot = 0
        bereiche.clear()
        schleifen.clear()
        bereiche.addLast(HashMap())
        rückgabeTyp = GanzzahlTyp
        val akku = reduktion.akkumulatoren[0]

        for (gefangen in reduktion.gefangene) {
            val art = artVon(gefangen.typ, schleife.position)
            bereiche.last()[gefangen.name] = Variable(gefangen.typ, art, belegeSlot(art))
        }
        val schleifenSlot = belegeSlot(Art.GANZ)
        bereiche.last()[schleife.variable] = Variable(GanzzahlTyp, Art.GANZ, schleifenSlot)

        val akkuSlot = belegeSlot(Art.GANZ)
        cob.loadConstant(if (akku.operator == STERN) 1L else 0L)
        cob.lstore(akkuSlot)
        bereiche.last()[akku.name] = Variable(GanzzahlTyp, Art.GANZ, akkuSlot)

        erzeugeBlock(schleife.körper)

        cob.lload(akkuSlot)
        cob.lreturn()
    }

    /**
     * Erzeugt fuer eine parallele Reduktion einen `LongStream`:
     * `akku = akku <op> rangeClosed(von, bis).parallel().map(beitrag)<.sum()|.reduce(1, *)>`.
     */
    private fun erzeugeParalleleReduktion(schleife: FürVonBisAnweisung) {
        val index = paralleleSchleifen.getValue(schleife)
        val reduktion = parallelplan.reduktionVon(schleife)!!
        val akku = reduktion.akkumulatoren[0]
        val akkuVariable = findeVariable(akku.name)
            ?: ablehnen("Akkumulator '${akku.name}' nicht gefunden", schleife.position)
        val gefangenDescs = reduktion.gefangene.map { classDesc(artVon(it.typ, schleife.position)) }

        lade(akkuVariable.art, akkuVariable.slot) // bisheriger Wert des Akkumulators
        erzeugeMitArt(schleife.von, Art.GANZ)
        erzeugeMitArt(schleife.bis, Art.GANZ)
        cob.invokestatic(
            CD_LongStream, "rangeClosed",
            MethodTypeDesc.of(CD_LongStream, ConstantDescs.CD_long, ConstantDescs.CD_long),
            true,
        )
        cob.invokeinterface(CD_LongStream, "parallel", MethodTypeDesc.of(CD_LongStream))
        for (gefangen in reduktion.gefangene) {
            val variable = findeVariable(gefangen.name)
                ?: ablehnen("Variable '${gefangen.name}' nicht gefunden", schleife.position)
            lade(variable.art, variable.slot)
        }
        cob.invokedynamic(beitragAufrufstelle(index, gefangenDescs))
        cob.invokeinterface(
            CD_LongStream, "map",
            MethodTypeDesc.of(CD_LongStream, CD_LongUnaryOperator),
        )
        if (akku.operator == STERN) {
            cob.loadConstant(1L)
            cob.invokedynamic(produktAufrufstelle())
            cob.invokeinterface(
                CD_LongStream, "reduce",
                MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_long, CD_LongBinaryOperator),
            )
            cob.lmul()
        } else {
            cob.invokeinterface(CD_LongStream, "sum", MethodTypeDesc.of(ConstantDescs.CD_long))
            cob.ladd()
        }
        speichere(akkuVariable.art, akkuVariable.slot)
    }

    private fun beitragAufrufstelle(index: Int, gefangenDescs: List<ClassDesc>): DynamicCallSiteDesc {
        val implTyp = MethodTypeDesc.of(ConstantDescs.CD_long, gefangenDescs + ConstantDescs.CD_long)
        val impl = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC, selbst, "beitrag$$index", implTyp,
        )
        val samTyp = MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_long)
        val fabrikTyp = MethodTypeDesc.of(CD_LongUnaryOperator, gefangenDescs)
        return DynamicCallSiteDesc.of(METAFACTORY, "applyAsLong", fabrikTyp, samTyp, impl, samTyp)
    }

    private fun produktAufrufstelle(): DynamicCallSiteDesc {
        val typ = MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_long, ConstantDescs.CD_long)
        val impl = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, selbst, "produkt$", typ)
        return DynamicCallSiteDesc.of(
            METAFACTORY, "applyAsLong", MethodTypeDesc.of(CD_LongBinaryOperator), typ, impl, typ,
        )
    }

    private fun erzeugeFunktion(funktion: FunktionDeklaration) {
        nächsterSlot = 0
        bereiche.clear()
        schleifen.clear()
        bereiche.addLast(HashMap())
        val signatur = symbole.funktionen.getValue(funktion.name)
        rückgabeTyp = signatur.rückgabe

        funktion.parameter.forEachIndexed { i, parameter ->
            val typ = signatur.parameter[i]
            val art = artVon(typ, parameter.position)
            val slot = belegeSlot(art)
            bereiche.last()[parameter.name] = Variable(typ, art, slot)
        }

        erzeugeBlock(funktion.körper)

        if (!terminiert(funktion.körper)) {
            if (rückgabeTyp == NichtsTyp) {
                cob.return_()
            } else {
                val art = artVon(rückgabeTyp, funktion.position)
                standardwert(art)
                rückgabeBefehl(art)
            }
        }
    }

    // ---- Anweisungen --------------------------------------------------------

    private fun erzeugeBlock(block: Block) {
        bereiche.addLast(HashMap())
        val anweisungen = block.anweisungen
        var i = 0
        while (i < anweisungen.size) {
            val gruppe = bytecodeGruppe(anweisungen[i])
            if (gruppe != null) {
                erzeugeParalleleGruppe(gruppe)
                i += gruppe.bindungen.size
            } else {
                erzeugeAnweisung(anweisungen[i])
                if (terminiert(anweisungen[i])) break
                i++
            }
        }
        bereiche.removeLast()
    }

    /** Liefert die `sei`-Gruppe ab [anweisung], falls sie im Bytecode parallelisierbar ist. */
    private fun bytecodeGruppe(anweisung: Anweisung): Gruppe? {
        val gruppe = parallelplan.gruppeAb(anweisung) ?: return null
        return if (gruppe.bindungen.all { it.typ == GanzzahlTyp && it.nichtTrivial }) gruppe else null
    }

    /**
     * Erzeugt eine nebenlaeufige `sei`-Gruppe: alle Anfangswerte bis auf den
     * letzten werden als `CompletableFuture` im Fork-Join-Pool berechnet, der
     * letzte im aktuellen Thread. Eine Granularitaetsschranke begrenzt den Aufwand.
     */
    private fun erzeugeParalleleGruppe(gruppe: Gruppe) {
        val bindungSlots = gruppe.bindungen.map { bindung ->
            val slot = belegeSlot(Art.GANZ)
            bereiche.last()[bindung.anweisung.name] = Variable(GanzzahlTyp, Art.GANZ, slot)
            slot
        }
        val sequentiell = cob.newLabel()
        val ende = cob.newLabel()
        cob.invokestatic(
            CD_ForkJoinTask, "getSurplusQueuedTaskCount", MethodTypeDesc.of(ConstantDescs.CD_int),
        )
        cob.loadConstant(3)
        cob.if_icmpgt(sequentiell)

        // Parallel: alle Bindungen bis auf die letzte als CompletableFuture.
        val futureSlots = ArrayList<Int>()
        for (idx in 0 until gruppe.bindungen.size - 1) {
            val bindung = gruppe.bindungen[idx]
            val gefangenDescs = bindung.gefangene.map {
                classDesc(artVon(it.typ, bindung.anweisung.position))
            }
            for (gefangen in bindung.gefangene) {
                val variable = findeVariable(gefangen.name)
                    ?: ablehnen("Variable '${gefangen.name}' nicht gefunden", bindung.anweisung.position)
                lade(variable.art, variable.slot)
            }
            val name = "gruppe$${gruppenMethoden.getValue(bindung.anweisung)}"
            cob.invokedynamic(lieferantAufrufstelle(name, gefangenDescs))
            cob.invokestatic(
                CD_CompletableFuture, "supplyAsync",
                MethodTypeDesc.of(CD_CompletableFuture, CD_Supplier),
            )
            val futureSlot = belegeSlot(Art.TEXT)
            cob.astore(futureSlot)
            futureSlots.add(futureSlot)
        }
        // Letzte Bindung im aktuellen Thread berechnen.
        erzeugeMitArt(gruppe.bindungen.last().anweisung.initialwert, Art.GANZ)
        cob.lstore(bindungSlots.last())
        // Ergebnisse der Futures einsammeln.
        for (idx in 0 until gruppe.bindungen.size - 1) {
            cob.aload(futureSlots[idx])
            cob.invokevirtual(CD_CompletableFuture, "join", MethodTypeDesc.of(ConstantDescs.CD_Object))
            cob.checkcast(CD_Long)
            cob.invokevirtual(CD_Long, "longValue", MethodTypeDesc.of(ConstantDescs.CD_long))
            cob.lstore(bindungSlots[idx])
        }
        cob.goto_(ende)

        // Sequentiell.
        cob.labelBinding(sequentiell)
        for (idx in gruppe.bindungen.indices) {
            erzeugeMitArt(gruppe.bindungen[idx].anweisung.initialwert, Art.GANZ)
            cob.lstore(bindungSlots[idx])
        }
        cob.labelBinding(ende)
    }

    private fun erzeugeAnweisung(anweisung: Anweisung) {
        when (anweisung) {
            is SeiAnweisung -> {
                val typ = anweisung.typannotation?.let { symbole.auflöser.auflöse(it) }
                    ?: typVon(anweisung.initialwert)
                val art = artVon(typ, anweisung.position)
                val slot = belegeSlot(art)
                erzeugeMitTyp(anweisung.initialwert, typ)
                speichere(art, slot)
                bereiche.last()[anweisung.name] = Variable(typ, art, slot)
            }

            is ZuweisungAnweisung -> {
                val ziel = anweisung.ziel
                if (ziel !is Bezeichner) {
                    ablehnen("Nur Zuweisungen an Variablen werden unterstuetzt", ziel.position)
                }
                val variable = findeVariable(ziel.name)
                    ?: ablehnen("Unbekannte Variable: '${ziel.name}'", ziel.position)
                erzeugeMitTyp(anweisung.wert, variable.typ)
                speichere(variable.art, variable.slot)
            }

            is AusdruckAnweisung -> {
                val typ = typVon(anweisung.ausdruck)
                erzeugeAusdruck(anweisung.ausdruck)
                when {
                    typ == NichtsTyp -> {}
                    artVon(typ, anweisung.position).let { it == Art.GANZ || it == Art.KOMMA } -> cob.pop2()
                    else -> cob.pop()
                }
            }

            is WennAnweisung -> erzeugeWenn(anweisung)

            is SolangeAnweisung -> {
                val start = cob.newLabel()
                val ende = cob.newLabel()
                cob.labelBinding(start)
                erzeugeAusdruck(anweisung.bedingung)
                cob.ifeq(ende)
                schleifen.addLast(Schleife(start, ende))
                erzeugeBlock(anweisung.körper)
                schleifen.removeLast()
                if (!terminiert(anweisung.körper)) cob.goto_(start)
                cob.labelBinding(ende)
            }

            is FürVonBisAnweisung -> erzeugeFürVonBis(anweisung)

            is FürInAnweisung -> erzeugeFürIn(anweisung)

            is ZurückAnweisung -> {
                if (anweisung.wert == null) {
                    cob.return_()
                } else {
                    erzeugeMitTyp(anweisung.wert, rückgabeTyp)
                    rückgabeBefehl(artVon(rückgabeTyp, anweisung.position))
                }
            }

            is BrichAnweisung -> {
                val schleife = schleifen.lastOrNull()
                    ?: ablehnen("'brich' ausserhalb einer Schleife", anweisung.position)
                cob.goto_(schleife.brich)
            }

            is WeiterAnweisung -> {
                val schleife = schleifen.lastOrNull()
                    ?: ablehnen("'weiter' ausserhalb einer Schleife", anweisung.position)
                cob.goto_(schleife.weiter)
            }

            is Block -> erzeugeBlock(anweisung)
        }
    }

    private fun erzeugeWenn(anweisung: WennAnweisung) {
        erzeugeAusdruck(anweisung.bedingung)
        val ende = cob.newLabel()
        val sonst = anweisung.sonst
        if (sonst == null) {
            cob.ifeq(ende)
            erzeugeBlock(anweisung.dann)
            cob.labelBinding(ende)
        } else {
            val sonstMarke = cob.newLabel()
            cob.ifeq(sonstMarke)
            erzeugeBlock(anweisung.dann)
            if (!terminiert(anweisung.dann)) cob.goto_(ende)
            cob.labelBinding(sonstMarke)
            erzeugeAnweisung(sonst)
            cob.labelBinding(ende)
        }
    }

    private fun erzeugeFürVonBis(anweisung: FürVonBisAnweisung) {
        if (anweisung in paralleleSchleifen) {
            erzeugeParalleleReduktion(anweisung)
            return
        }
        bereiche.addLast(HashMap())
        val variablenSlot = belegeSlot(Art.GANZ)
        val grenzeSlot = belegeSlot(Art.GANZ)
        bereiche.last()[anweisung.variable] = Variable(GanzzahlTyp, Art.GANZ, variablenSlot)

        erzeugeMitTyp(anweisung.von, GanzzahlTyp)
        cob.lstore(variablenSlot)
        erzeugeMitTyp(anweisung.bis, GanzzahlTyp)
        cob.lstore(grenzeSlot)

        val start = cob.newLabel()
        val weiter = cob.newLabel()
        val ende = cob.newLabel()
        cob.labelBinding(start)
        cob.lload(variablenSlot)
        cob.lload(grenzeSlot)
        cob.lcmp()
        cob.ifgt(ende) // variable > grenze -> Schleife verlassen

        schleifen.addLast(Schleife(weiter, ende))
        erzeugeBlock(anweisung.körper)
        schleifen.removeLast()

        cob.labelBinding(weiter)
        if (!terminiert(anweisung.körper)) {
            cob.lload(variablenSlot)
            cob.loadConstant(1L)
            cob.ladd()
            cob.lstore(variablenSlot)
            cob.goto_(start)
        }
        cob.labelBinding(ende)
        bereiche.removeLast()
    }

    private fun erzeugeFürIn(anweisung: FürInAnweisung) {
        val iterierbar = typVon(anweisung.iterierbar)
        if (iterierbar != TextTyp) {
            ablehnen(
                "'für ... in' ueber $iterierbar wird vom Bytecode-Backend noch nicht " +
                    "unterstuetzt (nur ueber Text)",
                anweisung.iterierbar.position,
            )
        }
        bereiche.addLast(HashMap())
        val textSlot = belegeSlot(Art.TEXT)
        val indexSlot = belegeSlot(Art.ZEICH)
        val zeichenSlot = belegeSlot(Art.ZEICH)
        bereiche.last()[anweisung.variable] = Variable(ZeichenTyp, Art.ZEICH, zeichenSlot)

        erzeugeAusdruck(anweisung.iterierbar)
        cob.astore(textSlot)
        cob.iconst_0()
        cob.istore(indexSlot)

        val start = cob.newLabel()
        val weiter = cob.newLabel()
        val ende = cob.newLabel()
        cob.labelBinding(start)
        cob.iload(indexSlot)
        cob.aload(textSlot)
        cob.invokevirtual(CD_String, "length", MethodTypeDesc.of(ConstantDescs.CD_int))
        cob.if_icmpge(ende)
        cob.aload(textSlot)
        cob.iload(indexSlot)
        cob.invokevirtual(
            CD_String, "charAt",
            MethodTypeDesc.of(ConstantDescs.CD_char, ConstantDescs.CD_int),
        )
        cob.istore(zeichenSlot)

        schleifen.addLast(Schleife(weiter, ende))
        erzeugeBlock(anweisung.körper)
        schleifen.removeLast()

        cob.labelBinding(weiter)
        if (!terminiert(anweisung.körper)) {
            cob.iinc(indexSlot, 1)
            cob.goto_(start)
        }
        cob.labelBinding(ende)
        bereiche.removeLast()
    }

    // ---- Ausdruecke ---------------------------------------------------------

    private fun erzeugeAusdruck(ausdruck: Ausdruck) {
        when (ausdruck) {
            is GanzzahlLiteral -> cob.loadConstant(ausdruck.wert)
            is KommazahlLiteral -> cob.loadConstant(ausdruck.wert)
            is TextLiteral -> ladeText(ausdruck.wert)
            is ZeichenLiteral -> cob.loadConstant(ausdruck.wert.code)
            is WahrheitLiteral -> if (ausdruck.wert) cob.iconst_1() else cob.iconst_0()

            is Bezeichner -> {
                val variable = findeVariable(ausdruck.name)
                    ?: ablehnen(
                        "Funktionen koennen im Bytecode-Backend nicht als Werte verwendet werden",
                        ausdruck.position,
                    )
                lade(variable.art, variable.slot)
            }

            is UnärAusdruck -> {
                when (ausdruck.operator) {
                    MINUS -> {
                        val art = artVon(typVon(ausdruck.operand), ausdruck.position)
                        erzeugeAusdruck(ausdruck.operand)
                        if (art == Art.KOMMA) cob.dneg() else cob.lneg()
                    }
                    NICHT -> {
                        erzeugeAusdruck(ausdruck.operand)
                        cob.iconst_1()
                        cob.ixor()
                    }
                    else -> ablehnen("Unbekannter Operator", ausdruck.position)
                }
            }

            is BinärAusdruck -> erzeugeBinär(ausdruck)

            is AufrufAusdruck -> erzeugeAufruf(ausdruck)

            is WähleAusdruck -> erzeugeWähle(ausdruck)

            is NichtsLiteral -> cob.aconst_null()
            is ElvisAusdruck -> erzeugeElvis(ausdruck)
            is NichtNullAusdruck -> erzeugeNichtNull(ausdruck)

            is DiesAusdruck ->
                ablehnen("'dies' wird vom Bytecode-Backend nicht unterstuetzt", ausdruck.position)
            is NeuAusdruck ->
                ablehnen("'neu' wird vom Bytecode-Backend nicht unterstuetzt", ausdruck.position)
            is LambdaAusdruck ->
                ablehnen("Lambdas werden vom Bytecode-Backend nicht unterstuetzt", ausdruck.position)
            is IndexAusdruck ->
                ablehnen("Indexzugriff wird vom Bytecode-Backend nicht unterstuetzt", ausdruck.position)
            is FeldzugriffAusdruck ->
                ablehnen("Feldzugriff wird vom Bytecode-Backend nicht unterstuetzt", ausdruck.position)
        }
    }

    private fun erzeugeBinär(ausdruck: BinärAusdruck) {
        when (ausdruck.operator) {
            UND -> kurzschluss(ausdruck, sprungWennWahr = false)
            ODER -> kurzschluss(ausdruck, sprungWennWahr = true)
            GLEICH, UNGLEICH -> {
                val links = typVon(ausdruck.links)
                val rechts = typVon(ausdruck.rechts)
                if (istNullbar(links) || istNullbar(rechts)) {
                    erzeugeNullVergleich(ausdruck)
                } else {
                    erzeugeVergleich(ausdruck)
                }
            }
            KLEINER, KLEINER_GLEICH, GRÖSSER, GRÖSSER_GLEICH ->
                erzeugeVergleich(ausdruck)
            PLUS, MINUS, STERN, SCHRÄGSTRICH, PROZENT -> {
                if (ausdruck.operator == PLUS && typVon(ausdruck) == TextTyp) {
                    erzeugeVerkettung(ausdruck)
                } else if (ausdruck in gabelIndizes) {
                    erzeugeGabelArithmetik(ausdruck)
                } else {
                    erzeugeArithmetik(ausdruck)
                }
            }
            else -> ablehnen("Unbekannter Operator", ausdruck.position)
        }
    }

    /** Elvis `links ?: rechts`: liefert [rechts], falls [links] `nichts` ist. */
    private fun erzeugeElvis(ausdruck: ElvisAusdruck) {
        val linksArt = artVon(typVon(ausdruck.links), ausdruck.links.position)
        val ergArt = artVon(typVon(ausdruck), ausdruck.position)
        if (!istReferenz(linksArt)) {
            erzeugeMitArt(ausdruck.links, ergArt) // linke Seite ist nie 'nichts'
            return
        }
        val sonst = cob.newLabel()
        val ende = cob.newLabel()
        erzeugeAusdruck(ausdruck.links)
        cob.dup()
        cob.ifnull(sonst)
        koerziere(linksArt, ergArt)
        cob.goto_(ende)
        cob.labelBinding(sonst)
        cob.pop()
        erzeugeMitArt(ausdruck.rechts, ergArt)
        cob.labelBinding(ende)
    }

    /** Nicht-null-Zusicherung `operand!!`: wirft, falls der Wert `nichts` ist. */
    private fun erzeugeNichtNull(ausdruck: NichtNullAusdruck) {
        val operandArt = artVon(typVon(ausdruck.operand), ausdruck.operand.position)
        erzeugeAusdruck(ausdruck.operand)
        if (!istReferenz(operandArt)) return // bereits nicht-nullbar
        val ok = cob.newLabel()
        cob.dup()
        cob.ifnonnull(ok)
        cob.pop()
        cob.new_(CD_NullPointerException)
        cob.dup()
        ladeText("Wert ist 'nichts'")
        cob.invokespecial(
            CD_NullPointerException, "<init>",
            MethodTypeDesc.of(ConstantDescs.CD_void, CD_String),
        )
        cob.athrow()
        cob.labelBinding(ok)
        koerziere(operandArt, entnullArt(operandArt))
    }

    /** Vergleich, an dem `nichts` bzw. ein nullbarer Wert beteiligt ist. */
    private fun erzeugeNullVergleich(ausdruck: BinärAusdruck) {
        val wahr = cob.newLabel()
        val ende = cob.newLabel()
        val linksNichts = ausdruck.links is NichtsLiteral
        val rechtsNichts = ausdruck.rechts is NichtsLiteral
        if (linksNichts || rechtsNichts) {
            val wert = if (linksNichts) ausdruck.rechts else ausdruck.links
            val art = artVon(typVon(wert), wert.position)
            erzeugeAusdruck(wert)
            if (istReferenz(art)) {
                if (ausdruck.operator == GLEICH) cob.ifnull(wahr) else cob.ifnonnull(wahr)
            } else {
                // Ein nicht-nullbarer Wert ist nie 'nichts'.
                if (art == Art.GANZ || art == Art.KOMMA) cob.pop2() else cob.pop()
                if (ausdruck.operator == UNGLEICH) cob.goto_(wahr)
            }
        } else {
            erzeugeMitArt(
                ausdruck.links, nullArt(artVon(typVon(ausdruck.links), ausdruck.links.position)),
            )
            erzeugeMitArt(
                ausdruck.rechts, nullArt(artVon(typVon(ausdruck.rechts), ausdruck.rechts.position)),
            )
            cob.invokestatic(
                CD_Objects, "equals",
                MethodTypeDesc.of(
                    ConstantDescs.CD_boolean, ConstantDescs.CD_Object, ConstantDescs.CD_Object,
                ),
            )
            if (ausdruck.operator == GLEICH) cob.ifne(wahr) else cob.ifeq(wahr)
        }
        cob.iconst_0()
        cob.goto_(ende)
        cob.labelBinding(wahr)
        cob.iconst_1()
        cob.labelBinding(ende)
    }

    private fun erzeugeArithmetik(ausdruck: BinärAusdruck) {
        val art = artVon(typVon(ausdruck), ausdruck.position)
        erzeugeMitArt(ausdruck.links, art)
        erzeugeMitArt(ausdruck.rechts, art)
        if (art == Art.KOMMA) {
            when (ausdruck.operator) {
                PLUS -> cob.dadd(); MINUS -> cob.dsub(); STERN -> cob.dmul()
                SCHRÄGSTRICH -> cob.ddiv(); PROZENT -> cob.drem()
                else -> {}
            }
        } else {
            when (ausdruck.operator) {
                PLUS -> cob.ladd(); MINUS -> cob.lsub(); STERN -> cob.lmul()
                SCHRÄGSTRICH -> cob.ldiv(); PROZENT -> cob.lrem()
                else -> {}
            }
        }
    }

    private fun erzeugeVerkettung(ausdruck: BinärAusdruck) {
        erzeugeAlsText(ausdruck.links)
        erzeugeAlsText(ausdruck.rechts)
        cob.invokevirtual(
            CD_String, "concat",
            MethodTypeDesc.of(CD_String, CD_String),
        )
    }

    /** Erzeugt einen Ausdruck und sorgt dafuer, dass ein String auf dem Stapel liegt. */
    private fun erzeugeAlsText(ausdruck: Ausdruck) {
        val art = artVon(typVon(ausdruck), ausdruck.position)
        erzeugeAusdruck(ausdruck)
        when (art) {
            Art.TEXT -> {}
            Art.WAHR -> wahrheitswertZuText()
            Art.GANZ -> cob.invokestatic(
                CD_String, "valueOf", MethodTypeDesc.of(CD_String, ConstantDescs.CD_long),
            )
            Art.KOMMA -> cob.invokestatic(
                CD_String, "valueOf", MethodTypeDesc.of(CD_String, ConstantDescs.CD_double),
            )
            Art.ZEICH -> cob.invokestatic(
                CD_String, "valueOf", MethodTypeDesc.of(CD_String, ConstantDescs.CD_char),
            )
            else -> { // nullbare Arten (vom Typpruefer in '+' eigentlich ausgeschlossen)
                ladeText("nichts")
                cob.invokestatic(
                    CD_Objects, "toString",
                    MethodTypeDesc.of(CD_String, ConstantDescs.CD_Object, CD_String),
                )
            }
        }
    }

    /** Ersetzt einen booleschen Wert auf dem Stapel durch "wahr" bzw. "falsch". */
    private fun wahrheitswertZuText() {
        val falsch = cob.newLabel()
        val ende = cob.newLabel()
        cob.ifeq(falsch)
        ladeText("wahr")
        cob.goto_(ende)
        cob.labelBinding(falsch)
        ladeText("falsch")
        cob.labelBinding(ende)
    }

    /** Laedt eine Textkonstante (Umweg ueber [ConstantDesc], da Kotlins String dies verbirgt). */
    private fun ladeText(text: String) {
        cob.loadConstant((text as Any) as ConstantDesc)
    }

    private fun kurzschluss(ausdruck: BinärAusdruck, sprungWennWahr: Boolean) {
        val ziel = cob.newLabel()
        val ende = cob.newLabel()
        erzeugeAusdruck(ausdruck.links)
        if (sprungWennWahr) cob.ifne(ziel) else cob.ifeq(ziel)
        erzeugeAusdruck(ausdruck.rechts)
        if (sprungWennWahr) cob.ifne(ziel) else cob.ifeq(ziel)
        if (sprungWennWahr) cob.iconst_0() else cob.iconst_1()
        cob.goto_(ende)
        cob.labelBinding(ziel)
        if (sprungWennWahr) cob.iconst_1() else cob.iconst_0()
        cob.labelBinding(ende)
    }

    private fun erzeugeVergleich(ausdruck: BinärAusdruck) {
        val art = gemeinsameArt(ausdruck.links, ausdruck.rechts)
        erzeugeMitArt(ausdruck.links, art)
        erzeugeMitArt(ausdruck.rechts, art)
        val vgl = vergleichVon(ausdruck.operator)
        val wahr = cob.newLabel()
        val ende = cob.newLabel()
        vergleichSprung(art, vgl, wahr)
        cob.iconst_0()
        cob.goto_(ende)
        cob.labelBinding(wahr)
        cob.iconst_1()
        cob.labelBinding(ende)
    }

    /** Verbraucht zwei Operanden der Art [art] und springt bei erfuelltem Vergleich. */
    private fun vergleichSprung(art: Art, vgl: Vgl, ziel: Label) {
        when (art) {
            Art.GANZ -> {
                cob.lcmp()
                sprungAufNull(vgl, ziel)
            }
            Art.KOMMA -> {
                if (vgl == Vgl.GT || vgl == Vgl.GE || vgl == Vgl.EQ || vgl == Vgl.NE) {
                    cob.dcmpl()
                } else {
                    cob.dcmpg()
                }
                sprungAufNull(vgl, ziel)
            }
            Art.WAHR, Art.ZEICH -> when (vgl) {
                Vgl.EQ -> cob.if_icmpeq(ziel)
                Vgl.NE -> cob.if_icmpne(ziel)
                Vgl.LT -> cob.if_icmplt(ziel)
                Vgl.LE -> cob.if_icmple(ziel)
                Vgl.GT -> cob.if_icmpgt(ziel)
                Vgl.GE -> cob.if_icmpge(ziel)
            }
            Art.TEXT -> when (vgl) {
                Vgl.EQ -> {
                    cob.invokevirtual(
                        CD_String, "equals",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object),
                    )
                    cob.ifne(ziel)
                }
                Vgl.NE -> {
                    cob.invokevirtual(
                        CD_String, "equals",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object),
                    )
                    cob.ifeq(ziel)
                }
                else -> {
                    cob.invokevirtual(
                        CD_String, "compareTo",
                        MethodTypeDesc.of(ConstantDescs.CD_int, CD_String),
                    )
                    sprungAufNull(vgl, ziel)
                }
            }
            else -> {} // nullbare Arten laufen ueber erzeugeNullVergleich
        }
    }

    private fun sprungAufNull(vgl: Vgl, ziel: Label) {
        when (vgl) {
            Vgl.EQ -> cob.ifeq(ziel)
            Vgl.NE -> cob.ifne(ziel)
            Vgl.LT -> cob.iflt(ziel)
            Vgl.LE -> cob.ifle(ziel)
            Vgl.GT -> cob.ifgt(ziel)
            Vgl.GE -> cob.ifge(ziel)
        }
    }

    private fun erzeugeWähle(ausdruck: WähleAusdruck) {
        val subjektTyp = typVon(ausdruck.subjekt)
        val subjektArt = artVon(subjektTyp, ausdruck.subjekt.position)
        val subjektSlot = belegeSlot(subjektArt)
        erzeugeAusdruck(ausdruck.subjekt)
        speichere(subjektArt, subjektSlot)

        val ergebnisTyp = typVon(ausdruck)
        val ende = cob.newLabel()
        for (fall in ausdruck.fälle) {
            val nächster = cob.newLabel()
            lade(subjektArt, subjektSlot)
            erzeugeMitArt(fall.muster, subjektArt)
            vergleichSprung(subjektArt, Vgl.NE, nächster)
            erzeugeMitTyp(fall.ergebnis, ergebnisTyp)
            cob.goto_(ende)
            cob.labelBinding(nächster)
        }
        erzeugeMitTyp(ausdruck.sonst, ergebnisTyp)
        cob.labelBinding(ende)
    }

    private fun erzeugeAufruf(ausdruck: AufrufAusdruck) {
        val ziel = ausdruck.ziel
        if (ziel !is Bezeichner) {
            ablehnen("Nur direkte Funktionsaufrufe werden unterstuetzt", ausdruck.position)
        }
        when (ziel.name) {
            "drucke" -> erzeugeDrucke(ausdruck.argumente[0])
            "länge" -> {
                val argument = ausdruck.argumente[0]
                if (typVon(argument) != TextTyp) {
                    ablehnen(
                        "'länge' wird vom Bytecode-Backend nur fuer Text unterstuetzt",
                        argument.position,
                    )
                }
                erzeugeAusdruck(argument)
                cob.invokevirtual(CD_String, "length", MethodTypeDesc.of(ConstantDescs.CD_int))
                cob.i2l()
            }
            "lies", "Liste", "Abbildung", "Paar" -> ablehnen(
                "'${ziel.name}' wird vom Bytecode-Backend (Kern) noch nicht unterstuetzt",
                ausdruck.position,
            )
            else -> {
                val signatur = symbole.funktionen[ziel.name]
                    ?: ablehnen("Unbekannte Funktion: '${ziel.name}'", ausdruck.position)
                ausdruck.argumente.forEachIndexed { i, argument ->
                    erzeugeMitTyp(argument, signatur.parameter[i])
                }
                cob.invokestatic(selbst, ziel.name, deskriptor(ziel.name))
            }
        }
    }

    private fun erzeugeDrucke(argument: Ausdruck) {
        cob.getstatic(CD_System, "out", CD_PrintStream)
        val art = artVon(typVon(argument), argument.position)
        erzeugeAusdruck(argument)
        if (istReferenz(art) && art != Art.TEXT) {
            // Nullbarer Wert: 'nichts' -> "nichts", sonst die Standarddarstellung.
            ladeText("nichts")
            cob.invokestatic(
                CD_Objects, "toString",
                MethodTypeDesc.of(CD_String, ConstantDescs.CD_Object, CD_String),
            )
            cob.invokevirtual(
                CD_PrintStream, "println", MethodTypeDesc.of(ConstantDescs.CD_void, CD_String),
            )
            return
        }
        val parameter = when (art) {
            Art.WAHR -> {
                wahrheitswertZuText()
                CD_String
            }
            Art.TEXT -> CD_String
            Art.GANZ -> ConstantDescs.CD_long
            Art.KOMMA -> ConstantDescs.CD_double
            Art.ZEICH -> ConstantDescs.CD_char
            else -> CD_String
        }
        cob.invokevirtual(CD_PrintStream, "println", MethodTypeDesc.of(ConstantDescs.CD_void, parameter))
    }

    // ---- Werterzeugung mit Zieltyp ------------------------------------------

    private fun erzeugeMitTyp(ausdruck: Ausdruck, zielTyp: Typ) {
        erzeugeMitArt(ausdruck, artVon(zielTyp, ausdruck.position))
    }

    /** Erzeugt einen Ausdruck und erweitert `Ganzzahl` bei Bedarf zu `Kommazahl`. */
    private fun erzeugeMitArt(ausdruck: Ausdruck, zielArt: Art) {
        val art = artVon(typVon(ausdruck), ausdruck.position)
        erzeugeAusdruck(ausdruck)
        koerziere(art, zielArt)
    }

    // ---- Typermittlung ------------------------------------------------------

    /** Leitet den statischen Typ eines (bereits geprueften) Ausdrucks ab. */
    private fun typVon(ausdruck: Ausdruck): Typ = when (ausdruck) {
        is GanzzahlLiteral -> GanzzahlTyp
        is KommazahlLiteral -> KommazahlTyp
        is TextLiteral -> TextTyp
        is ZeichenLiteral -> ZeichenTyp
        is WahrheitLiteral -> WahrheitTyp
        is NichtsLiteral -> NichtsTyp
        is Bezeichner -> findeVariable(ausdruck.name)?.typ
            ?: ablehnen("Unbekannter Name: '${ausdruck.name}'", ausdruck.position)
        is UnärAusdruck -> if (ausdruck.operator == NICHT) WahrheitTyp else typVon(ausdruck.operand)
        is BinärAusdruck -> when (ausdruck.operator) {
            UND, ODER, GLEICH, UNGLEICH, KLEINER, KLEINER_GLEICH, GRÖSSER, GRÖSSER_GLEICH -> WahrheitTyp
            PLUS -> if (typVon(ausdruck.links) == TextTyp || typVon(ausdruck.rechts) == TextTyp) {
                TextTyp
            } else {
                numerischerTyp(ausdruck.links, ausdruck.rechts)
            }
            else -> numerischerTyp(ausdruck.links, ausdruck.rechts)
        }
        is AufrufAusdruck -> {
            val ziel = ausdruck.ziel
            if (ziel is Bezeichner) {
                when (ziel.name) {
                    "drucke" -> NichtsTyp
                    "länge" -> GanzzahlTyp
                    else -> symbole.funktionen[ziel.name]?.rückgabe
                        ?: ablehnen("Unbekannte Funktion: '${ziel.name}'", ausdruck.position)
                }
            } else {
                ablehnen("Nur direkte Funktionsaufrufe werden unterstuetzt", ausdruck.position)
            }
        }
        is WähleAusdruck -> {
            var typ = typVon(ausdruck.sonst)
            for (fall in ausdruck.fälle) typ = gemeinsamerTyp(typ, typVon(fall.ergebnis))
            typ
        }
        is ElvisAusdruck -> gemeinsamerTyp(entnullt(typVon(ausdruck.links)), typVon(ausdruck.rechts))
        is NichtNullAusdruck -> entnullt(typVon(ausdruck.operand))
        is DiesAusdruck, is NeuAusdruck, is LambdaAusdruck, is IndexAusdruck, is FeldzugriffAusdruck ->
            ablehnen("Dieser Ausdruck wird vom Bytecode-Backend nicht unterstuetzt", ausdruck.position)
    }

    private fun numerischerTyp(links: Ausdruck, rechts: Ausdruck): Typ =
        if (typVon(links) == KommazahlTyp || typVon(rechts) == KommazahlTyp) KommazahlTyp else GanzzahlTyp

    // ---- Kleine Hilfen ------------------------------------------------------

    private fun ablehnen(meldung: String, position: Position): Nothing =
        throw NichtUnterstützt(Diagnose(meldung, position))

    private fun terminiert(anweisung: Anweisung): Boolean = when (anweisung) {
        is ZurückAnweisung, is BrichAnweisung, is WeiterAnweisung -> true
        is Block -> anweisung.anweisungen.any { terminiert(it) }
        is WennAnweisung -> {
            val sonst = anweisung.sonst
            sonst != null && terminiert(anweisung.dann) && terminiert(sonst)
        }
        else -> false
    }

    private fun findeVariable(name: String): Variable? {
        for (bereich in bereiche.asReversed()) bereich[name]?.let { return it }
        return null
    }

    private fun belegeSlot(art: Art): Int {
        val slot = nächsterSlot
        nächsterSlot += if (art == Art.GANZ || art == Art.KOMMA) 2 else 1
        return slot
    }

    private fun deskriptor(funktionsname: String): MethodTypeDesc {
        val signatur = symbole.funktionen.getValue(funktionsname)
        val rückgabe = if (signatur.rückgabe == NichtsTyp) {
            ConstantDescs.CD_void
        } else {
            classDesc(artVon(signatur.rückgabe, Position(0, 0)))
        }
        val parameter = signatur.parameter.map { classDesc(artVon(it, Position(0, 0))) }
        return MethodTypeDesc.of(rückgabe, parameter)
    }

    private fun classDesc(art: Art): ClassDesc = when (art) {
        Art.GANZ -> ConstantDescs.CD_long
        Art.KOMMA -> ConstantDescs.CD_double
        Art.WAHR -> ConstantDescs.CD_boolean
        Art.ZEICH -> ConstantDescs.CD_char
        Art.TEXT -> CD_String
        Art.N_GANZ -> CD_Long
        Art.N_KOMMA -> CD_Double
        Art.N_WAHR -> CD_Boolean
        Art.N_ZEICH -> CD_Character
        Art.N_TEXT -> CD_String
        Art.NICHTS -> ConstantDescs.CD_Object
    }

    private fun artVon(typ: Typ, position: Position): Art = when (typ) {
        GanzzahlTyp -> Art.GANZ
        KommazahlTyp -> Art.KOMMA
        WahrheitTyp -> Art.WAHR
        ZeichenTyp -> Art.ZEICH
        TextTyp -> Art.TEXT
        NichtsTyp -> Art.NICHTS
        is NullbarTyp -> when (typ.basis) {
            GanzzahlTyp -> Art.N_GANZ
            KommazahlTyp -> Art.N_KOMMA
            WahrheitTyp -> Art.N_WAHR
            ZeichenTyp -> Art.N_ZEICH
            TextTyp -> Art.N_TEXT
            else -> ablehnen(
                "Der Typ '$typ' wird vom Bytecode-Backend (Kern) nicht unterstuetzt",
                position,
            )
        }
        else -> ablehnen(
            "Der Typ '$typ' wird vom Bytecode-Backend (Kern) nicht unterstuetzt",
            position,
        )
    }

    private fun istReferenz(art: Art): Boolean =
        art != Art.GANZ && art != Art.KOMMA && art != Art.WAHR && art != Art.ZEICH

    private fun nullArt(art: Art): Art = when (art) {
        Art.GANZ -> Art.N_GANZ
        Art.KOMMA -> Art.N_KOMMA
        Art.WAHR -> Art.N_WAHR
        Art.ZEICH -> Art.N_ZEICH
        else -> art
    }

    private fun entnullArt(art: Art): Art = when (art) {
        Art.N_GANZ -> Art.GANZ
        Art.N_KOMMA -> Art.KOMMA
        Art.N_WAHR -> Art.WAHR
        Art.N_ZEICH -> Art.ZEICH
        Art.N_TEXT -> Art.TEXT
        else -> art
    }

    private fun gemeinsameArt(links: Ausdruck, rechts: Ausdruck): Art {
        val a = artVon(typVon(links), links.position)
        val b = artVon(typVon(rechts), rechts.position)
        return when {
            a == Art.KOMMA || b == Art.KOMMA -> Art.KOMMA
            else -> a
        }
    }

    private fun vergleichVon(operator: TokenTyp): Vgl = when (operator) {
        GLEICH -> Vgl.EQ
        UNGLEICH -> Vgl.NE
        KLEINER -> Vgl.LT
        KLEINER_GLEICH -> Vgl.LE
        GRÖSSER -> Vgl.GT
        GRÖSSER_GLEICH -> Vgl.GE
        else -> Vgl.EQ
    }

    private fun lade(art: Art, slot: Int) {
        when (art) {
            Art.GANZ -> cob.lload(slot)
            Art.KOMMA -> cob.dload(slot)
            Art.WAHR, Art.ZEICH -> cob.iload(slot)
            else -> cob.aload(slot) // Text und alle nullbaren Arten sind Referenzen
        }
    }

    private fun speichere(art: Art, slot: Int) {
        when (art) {
            Art.GANZ -> cob.lstore(slot)
            Art.KOMMA -> cob.dstore(slot)
            Art.WAHR, Art.ZEICH -> cob.istore(slot)
            else -> cob.astore(slot)
        }
    }

    private fun rückgabeBefehl(art: Art) {
        when (art) {
            Art.GANZ -> cob.lreturn()
            Art.KOMMA -> cob.dreturn()
            Art.WAHR, Art.ZEICH -> cob.ireturn()
            else -> cob.areturn()
        }
    }

    private fun standardwert(art: Art) {
        when (art) {
            Art.GANZ -> cob.loadConstant(0L)
            Art.KOMMA -> cob.loadConstant(0.0)
            Art.WAHR, Art.ZEICH -> cob.iconst_0()
            Art.TEXT -> ladeText("")
            else -> cob.aconst_null() // nullbare Arten
        }
    }

    /** Boxt einen nicht-nullbaren Grundwert in seinen nullbaren (Referenz-)Typ. */
    private fun boxe(zielArt: Art) {
        when (zielArt) {
            Art.N_GANZ -> cob.invokestatic(CD_Long, "valueOf", MethodTypeDesc.of(CD_Long, ConstantDescs.CD_long))
            Art.N_KOMMA -> cob.invokestatic(CD_Double, "valueOf", MethodTypeDesc.of(CD_Double, ConstantDescs.CD_double))
            Art.N_WAHR -> cob.invokestatic(CD_Boolean, "valueOf", MethodTypeDesc.of(CD_Boolean, ConstantDescs.CD_boolean))
            Art.N_ZEICH -> cob.invokestatic(CD_Character, "valueOf", MethodTypeDesc.of(CD_Character, ConstantDescs.CD_char))
            else -> {} // N_TEXT/NICHTS sind bereits Referenzen
        }
    }

    /** Entboxt einen nullbaren (Referenz-)Wert in seinen nicht-nullbaren Grundwert. */
    private fun entboxe(art: Art) {
        when (art) {
            Art.N_GANZ -> cob.invokevirtual(CD_Long, "longValue", MethodTypeDesc.of(ConstantDescs.CD_long))
            Art.N_KOMMA -> cob.invokevirtual(CD_Double, "doubleValue", MethodTypeDesc.of(ConstantDescs.CD_double))
            Art.N_WAHR -> cob.invokevirtual(CD_Boolean, "booleanValue", MethodTypeDesc.of(ConstantDescs.CD_boolean))
            Art.N_ZEICH -> cob.invokevirtual(CD_Character, "charValue", MethodTypeDesc.of(ConstantDescs.CD_char))
            else -> {} // N_TEXT bleibt String
        }
    }

    /** Wandelt einen Wert der Art [art] auf dem Stapel in die Art [zielArt] um. */
    private fun koerziere(art: Art, zielArt: Art) {
        if (art == zielArt) return
        if (art == Art.NICHTS) return // 'nichts' (null) passt in jede nullbare Art
        if (istReferenz(art) && istReferenz(zielArt) && classDesc(art) == classDesc(zielArt)) {
            return // z. B. Text <-> Text?
        }
        when {
            art == Art.GANZ && zielArt == Art.KOMMA -> cob.l2d()
            art == Art.GANZ && zielArt == Art.N_KOMMA -> { cob.l2d(); boxe(Art.N_KOMMA) }
            !istReferenz(art) && zielArt == nullArt(art) -> boxe(zielArt)
            istReferenz(art) && zielArt == entnullArt(art) -> entboxe(art)
        }
    }

    private companion object {
        val CD_String: ClassDesc = ConstantDescs.CD_String
        val CD_System: ClassDesc = ClassDesc.of("java.lang.System")
        val CD_PrintStream: ClassDesc = ClassDesc.of("java.io.PrintStream")
        val CD_LongStream: ClassDesc = ClassDesc.of("java.util.stream.LongStream")
        val CD_LongUnaryOperator: ClassDesc = ClassDesc.of("java.util.function.LongUnaryOperator")
        val CD_LongBinaryOperator: ClassDesc = ClassDesc.of("java.util.function.LongBinaryOperator")
        val CD_ForkJoinTask: ClassDesc = ClassDesc.of("java.util.concurrent.ForkJoinTask")
        val CD_CompletableFuture: ClassDesc = ClassDesc.of("java.util.concurrent.CompletableFuture")
        val CD_Supplier: ClassDesc = ClassDesc.of("java.util.function.Supplier")
        val CD_Long: ClassDesc = ClassDesc.of("java.lang.Long")
        val CD_Double: ClassDesc = ClassDesc.of("java.lang.Double")
        val CD_Boolean: ClassDesc = ClassDesc.of("java.lang.Boolean")
        val CD_Character: ClassDesc = ClassDesc.of("java.lang.Character")
        val CD_Objects: ClassDesc = ClassDesc.of("java.util.Objects")
        val CD_NullPointerException: ClassDesc = ClassDesc.of("java.lang.NullPointerException")

        /** Bootstrap-Methode java.lang.invoke.LambdaMetafactory.metafactory fuer invokedynamic. */
        val METAFACTORY: DirectMethodHandleDesc = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC,
            ClassDesc.of("java.lang.invoke.LambdaMetafactory"),
            "metafactory",
            MethodTypeDesc.of(
                ConstantDescs.CD_CallSite,
                ConstantDescs.CD_MethodHandles_Lookup,
                ConstantDescs.CD_String,
                ConstantDescs.CD_MethodType,
                ConstantDescs.CD_MethodType,
                ConstantDescs.CD_MethodHandle,
                ConstantDescs.CD_MethodType,
            ),
        )
    }
}
