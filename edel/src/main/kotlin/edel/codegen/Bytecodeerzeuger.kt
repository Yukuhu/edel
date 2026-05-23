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
 * `Text`->`String`), Datensaetze (`datensatz`->eigene Klasse), saemtlicher
 * Kontrollfluss, Rekursion und `wähle` ueber Grundwerte. Klassen,
 * Aufzaehlungen, Lambdas und Sammlungen werden mit [NichtUnterstützt]
 * abgelehnt — dafuer bleibt der Interpreter.
 *
 * [kompiliere] liefert eine Abbildung Klassenname -> Bytes: die Hauptklasse
 * sowie je eine Klasse pro `datensatz`.
 */
class Bytecodeerzeuger(
    private val programm: Programm,
    private val symbole: GlobaleSymbole,
    private val klassenname: String,
    private val parallelplan: Parallelplan = Parallelplan(emptyMap()),
) {
    // Nicht-nullbare Grundarten, ihre nullbaren (geboxten) Gegenstuecke sowie
    // OBJEKT fuer Referenzen auf Datensatz-Klassen.
    private enum class Art {
        GANZ, KOMMA, WAHR, ZEICH, TEXT,
        N_GANZ, N_KOMMA, N_WAHR, N_ZEICH, N_TEXT,
        NICHTS, OBJEKT,
    }
    private enum class Vgl { EQ, NE, LT, LE, GT, GE }

    private class Variable(val typ: Typ, val art: Art, val slot: Int)
    private class Schleife(val weiter: Label, val brich: Label)

    /** Ein Lambda-Ausdruck samt gefangenen Variablen und ermitteltem Rueckgabetyp. */
    private class LambdaInfo(
        val lambda: LambdaAusdruck,
        val gefangene: List<Pair<String, Typ>>,
        val rückgabe: Typ,
    )

    private val selbst: ClassDesc = ClassDesc.of(klassenname)

    // Pro Methode zuruekgesetzter Zustand.
    private lateinit var cob: CodeBuilder
    private var nächsterSlot = 0
    private val bereiche = ArrayDeque<HashMap<String, Variable>>()
    private val schleifen = ArrayDeque<Schleife>()
    private var rückgabeTyp: Typ = NichtsTyp

    // Die Klasse, deren Methode/Konstruktor gerade erzeugt wird (fuer `dies`).
    private var aktuelleKlasse: KlassenTyp? = null

    // Parallel reduzierbare `für von bis`-Schleifen mit genau einem Akkumulator,
    // jeweils mit einem eindeutigen Index fuer die erzeugte `beitrag$n`-Methode.
    private var paralleleSchleifen: Map<FürVonBisAnweisung, Int> = emptyMap()

    // Gabelbare binaere Ganzzahl-Ausdruecke, je mit Index fuer die `gabel$n`-Methode.
    private var gabelIndizes: Map<BinärAusdruck, Int> = emptyMap()

    // Bindungen nebenlaeufiger `sei`-Gruppen, je mit Index fuer ihre `gruppe$n`-Methode.
    private var gruppenMethoden: Map<SeiAnweisung, Int> = emptyMap()

    // Lambda-Ausdruecke in Erkennungsreihenfolge; der Listenindex ist die Methodennummer.
    private val lambdaListe = ArrayList<LambdaInfo>()
    private val lambdaNummer = HashMap<LambdaAusdruck, Int>()

    // Vorkommende Stelligkeiten von Funktionswerten (je eine EdelFunktionN-Schnittstelle).
    private val funktionsArten = sortedSetOf<Int>()

    // Wird gesetzt, sobald ein `Paar` vorkommt: dann wird die Klasse EdelPaar erzeugt.
    private var brauchtPaar = false

    // Wird gesetzt, sobald ein `Ergebnis` vorkommt: dann wird EdelErgebnis erzeugt.
    private var brauchtErgebnis = false

    // ---- Einstieg -----------------------------------------------------------

    fun kompiliere(): Map<String, ByteArray> {
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

        // Zuerst die benutzerdefinierten Typen (ihre Methoden koennen Lambdas enthalten,
        // deren Implementierungsmethoden auf der Hauptklasse landen).
        val klassen = LinkedHashMap<String, ByteArray>()
        for (d in programm.deklarationen) {
            when (d) {
                is DatensatzDeklaration -> klassen[d.name] = erzeugeDatensatz(d)
                is KlasseDeklaration -> klassen[d.name] = erzeugeKlasse(d)
                is AufzählungDeklaration -> klassen[d.name] = erzeugeAufzählung(d)
                is SchnittstelleDeklaration -> klassen[d.name] = erzeugeSchnittstelle(d)
                else -> {}
            }
        }

        klassen[klassenname] = ClassFile.of().build(selbst) { clb ->
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
            // Je eine 'edelLambda'-Methode pro Lambda-Ausdruck (sein uebersetzter Rumpf).
            // Die Liste waechst, waehrend die obigen Ruempfe erzeugt werden.
            var idx = 0
            while (idx < lambdaListe.size) {
                val info = lambdaListe[idx]
                val nummer = idx
                clb.withMethodBody(
                    "edelLambda$$nummer", lambdaImplDeskriptor(info),
                    ClassFile.ACC_PUBLIC or ClassFile.ACC_STATIC,
                ) { code ->
                    cob = code
                    erzeugeLambdaImpl(info)
                }
                idx++
            }
        }

        // Funktionale Schnittstellen fuer jede vorkommende Lambda-Stelligkeit.
        for (stelligkeit in funktionsArten) {
            klassen["EdelFunktion$stelligkeit"] = erzeugeFunktionsSchnittstelle(stelligkeit)
        }
        // Die Paar-Klasse, falls `Paar` vorkommt.
        if (brauchtPaar) {
            klassen["EdelPaar"] = erzeugeEdelPaar()
        }
        // Die Ergebnis-Klasse, falls `Ergebnis` vorkommt.
        if (brauchtErgebnis) {
            klassen["EdelErgebnis"] = erzeugeEdelErgebnis()
        }
        return klassen
    }

    // ---- Datensaetze --------------------------------------------------------

    /**
     * Erzeugt fuer einen `datensatz` eine eigene `.class`: oeffentliche `final`
     * Felder, einen Konstruktor, der sie belegt, und ein `toString`, das die
     * Darstellung des Interpreters (`Name(feld, feld)`) nachbildet.
     */
    private fun erzeugeDatensatz(d: DatensatzDeklaration): ByteArray {
        val ziel = ClassDesc.of(d.name)
        val felder = d.felder.map { it.name to symbole.auflöser.auflöse(it.typ) }
        for ((_, typ) in felder) {
            if (typ is NullbarTyp) {
                ablehnen(
                    "Nullbare Datensatzfelder werden vom Bytecode-Backend noch nicht unterstuetzt",
                    d.position,
                )
            }
        }
        return ClassFile.of().build(ziel) { clb ->
            clb.withFlags(ClassFile.ACC_PUBLIC or ClassFile.ACC_FINAL)
            for ((name, typ) in felder) {
                clb.withField(name, classDescVonTyp(typ), ClassFile.ACC_PUBLIC or ClassFile.ACC_FINAL)
            }
            val ktorDesc = MethodTypeDesc.of(
                ConstantDescs.CD_void, felder.map { classDescVonTyp(it.second) },
            )
            clb.withMethodBody("<init>", ktorDesc, ClassFile.ACC_PUBLIC) { code ->
                cob = code
                code.aload(0)
                code.invokespecial(
                    ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void),
                )
                var slot = 1
                for ((name, typ) in felder) {
                    val art = artVon(typ, d.position)
                    code.aload(0)
                    lade(art, slot)
                    code.putfield(ziel, name, classDescVonTyp(typ))
                    slot += if (art == Art.GANZ || art == Art.KOMMA) 2 else 1
                }
                code.return_()
            }
            clb.withMethodBody("toString", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC) { code ->
                cob = code
                erzeugeDatensatzToString(ziel, d.name, felder)
            }
        }
    }

    /** Baut `Name(feld0, feld1, …)` ueber einen `StringBuilder` und gibt es zurueck. */
    private fun erzeugeDatensatzToString(
        ziel: ClassDesc,
        name: String,
        felder: List<Pair<String, Typ>>,
    ) {
        cob.new_(CD_StringBuilder)
        cob.dup()
        cob.invokespecial(CD_StringBuilder, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
        anhängenText("$name(")
        felder.forEachIndexed { i, (feldname, typ) ->
            if (i > 0) anhängenText(", ")
            cob.aload(0)
            cob.getfield(ziel, feldname, classDescVonTyp(typ))
            anhängenFeld(artVon(typ, Position(0, 0)))
        }
        anhängenText(")")
        cob.invokevirtual(CD_StringBuilder, "toString", MethodTypeDesc.of(CD_String))
        cob.areturn()
    }

    /** Haengt eine Textkonstante an den `StringBuilder` auf dem Stapel an. */
    private fun anhängenText(text: String) {
        ladeText(text)
        cob.invokevirtual(CD_StringBuilder, "append", MethodTypeDesc.of(CD_StringBuilder, CD_String))
    }

    /** Haengt den Wert auf dem Stapel (Art [art]) an den darunterliegenden `StringBuilder` an. */
    private fun anhängenFeld(art: Art) {
        val typDesc = when (art) {
            Art.GANZ -> ConstantDescs.CD_long
            Art.KOMMA -> ConstantDescs.CD_double
            Art.ZEICH -> ConstantDescs.CD_char
            Art.TEXT -> CD_String
            Art.WAHR -> {
                wahrheitswertZuText() // Wahrheitswert -> "wahr"/"falsch"
                CD_String
            }
            else -> ConstantDescs.CD_Object // verschachtelte Datensaetze ueber toString
        }
        cob.invokevirtual(CD_StringBuilder, "append", MethodTypeDesc.of(CD_StringBuilder, typDesc))
    }

    // ---- Klassen ------------------------------------------------------------

    private fun auflöse(typausdruck: Typausdruck): Typ = symbole.auflöser.auflöse(typausdruck)

    /** Die Klassen-Deklarationskette von der Wurzel bis zu [d] (Wurzel zuerst). */
    private fun klassenKette(d: KlasseDeklaration): List<KlasseDeklaration> {
        val kette = ArrayDeque<KlasseDeklaration>()
        var aktuell: KlasseDeklaration? = d
        while (aktuell != null) {
            kette.addFirst(aktuell)
            aktuell = aktuell.oberklasse?.let { symbole.klassenDeklarationen[it] }
        }
        return kette.toList()
    }

    /** Alle Konstruktorfelder (ohne Initialwert) der ganzen Kette, Wurzel zuerst. */
    private fun ktorFelderKette(d: KlasseDeklaration): List<FeldDeklaration> =
        klassenKette(d).flatMap { k -> k.felder.filter { it.initialwert == null } }

    /**
     * Erzeugt fuer eine `klasse` eine eigene `.class`: Instanzfelder, einen
     * Konstruktor (Felder ohne Initialwert werden Konstruktorparameter, geerbte
     * zuerst), die Methoden und ein `toString`. Vererbung und Schnittstellen
     * werden auf `extends`/`implements` der JVM abgebildet.
     */
    private fun erzeugeKlasse(d: KlasseDeklaration): ByteArray {
        for (feld in d.felder) {
            if (auflöse(feld.typ) is NullbarTyp) {
                ablehnen(
                    "Nullbare Klassenfelder werden vom Bytecode-Backend noch nicht unterstuetzt",
                    feld.position,
                )
            }
        }
        val klassenTyp = symbole.typen.getValue(d.name) as KlassenTyp
        val ziel = ClassDesc.of(d.name)
        return ClassFile.of().build(ziel) { clb ->
            clb.withFlags(ClassFile.ACC_PUBLIC) // nicht final: koennte erweitert werden
            d.oberklasse?.let { clb.withSuperclass(ClassDesc.of(it)) }
            if (d.schnittstellen.isNotEmpty()) {
                clb.withInterfaceSymbols(d.schnittstellen.map { ClassDesc.of(it) })
            }
            for (feld in d.felder) { // nur die eigenen Felder; geerbte liegen in der Oberklasse
                val flags = ClassFile.ACC_PUBLIC or
                    (if (feld.wandelbar) 0 else ClassFile.ACC_FINAL)
                clb.withField(feld.name, classDescVonTyp(auflöse(feld.typ)), flags)
            }
            val ktorDesc = MethodTypeDesc.of(
                ConstantDescs.CD_void, ktorFelderKette(d).map { classDescVonTyp(auflöse(it.typ)) },
            )
            clb.withMethodBody("<init>", ktorDesc, ClassFile.ACC_PUBLIC) { code ->
                cob = code
                erzeugeKonstruktor(klassenTyp, ziel, d)
            }
            for (methode in d.methoden) {
                clb.withMethodBody(
                    methode.name, methodenDeskriptor(klassenTyp, methode.name), ClassFile.ACC_PUBLIC,
                ) { code ->
                    cob = code
                    erzeugeMethode(klassenTyp, methode)
                }
            }
            clb.withMethodBody("toString", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC) { code ->
                cob = code
                erzeugeKlasseToString(ziel, d)
            }
        }
    }

    /**
     * Konstruktor: ruft mit den geerbten Konstruktorfeldern den Oberklassen-
     * Konstruktor auf, belegt dann die eigenen Konstruktorfelder und fuehrt
     * zuletzt die eigenen Feld-Initialisierer aus.
     */
    private fun erzeugeKonstruktor(klassenTyp: KlassenTyp, ziel: ClassDesc, d: KlasseDeklaration) {
        bereiche.clear()
        schleifen.clear()
        bereiche.addLast(HashMap())
        aktuelleKlasse = klassenTyp
        val geerbte = d.oberklasse
            ?.let { ktorFelderKette(symbole.klassenDeklarationen.getValue(it)) }
            ?: emptyList()
        val eigeneKtor = d.felder.filter { it.initialwert == null }
        val eigeneInit = d.felder.filter { it.initialwert != null }
        val alle = geerbte + eigeneKtor
        // Slot jedes Konstruktorparameters (dies = Slot 0).
        val slots = IntArray(alle.size)
        var slot = 1
        alle.forEachIndexed { i, feld ->
            slots[i] = slot
            val art = artVon(auflöse(feld.typ), feld.position)
            slot += if (art == Art.GANZ || art == Art.KOMMA) 2 else 1
        }
        nächsterSlot = slot

        // super(geerbte Konstruktorfelder)
        cob.aload(0)
        for (i in geerbte.indices) {
            lade(artVon(auflöse(geerbte[i].typ), geerbte[i].position), slots[i])
        }
        val oberklasse = d.oberklasse?.let { ClassDesc.of(it) } ?: ConstantDescs.CD_Object
        cob.invokespecial(
            oberklasse, "<init>",
            MethodTypeDesc.of(ConstantDescs.CD_void, geerbte.map { classDescVonTyp(auflöse(it.typ)) }),
        )
        // eigene Konstruktorfelder belegen
        for (i in geerbte.size until alle.size) {
            val feld = alle[i]
            val typ = auflöse(feld.typ)
            cob.aload(0)
            lade(artVon(typ, feld.position), slots[i])
            cob.putfield(ziel, feld.name, classDescVonTyp(typ))
        }
        // eigene Initialisierer ausfuehren
        for (feld in eigeneInit) {
            val typ = auflöse(feld.typ)
            cob.aload(0)
            erzeugeMitTyp(feld.initialwert!!, typ)
            cob.putfield(ziel, feld.name, classDescVonTyp(typ))
        }
        cob.return_()
        aktuelleKlasse = null
    }

    /** Erzeugt den Rumpf einer Instanzmethode (Slot 0 = dies, Parameter ab Slot 1). */
    private fun erzeugeMethode(klassenTyp: KlassenTyp, methode: FunktionDeklaration) {
        nächsterSlot = 1
        bereiche.clear()
        schleifen.clear()
        bereiche.addLast(HashMap())
        aktuelleKlasse = klassenTyp
        val signatur = klassenTyp.methoden.getValue(methode.name)
        rückgabeTyp = signatur.rückgabe
        methode.parameter.forEachIndexed { i, parameter ->
            val typ = signatur.parameter[i]
            val art = artVon(typ, parameter.position)
            val slot = belegeSlot(art)
            bereiche.last()[parameter.name] = Variable(typ, art, slot)
        }
        erzeugeBlock(methode.körper)
        if (!terminiert(methode.körper)) {
            if (rückgabeTyp == NichtsTyp) {
                cob.return_()
            } else {
                val art = artVon(rückgabeTyp, methode.position)
                standardwert(art)
                rückgabeBefehl(art)
            }
        }
        aktuelleKlasse = null
    }

    /**
     * Baut `Name(feld=wert, …)` analog zur Darstellung des Interpreters: erst
     * die Konstruktorfelder der ganzen Kette, dann die initialisierten Felder.
     */
    private fun erzeugeKlasseToString(ziel: ClassDesc, d: KlasseDeklaration) {
        val kette = klassenKette(d)
        val felder = kette.flatMap { k -> k.felder.filter { it.initialwert == null } } +
            kette.flatMap { k -> k.felder.filter { it.initialwert != null } }
        cob.new_(CD_StringBuilder)
        cob.dup()
        cob.invokespecial(CD_StringBuilder, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
        anhängenText("${d.name}(")
        felder.forEachIndexed { i, feld ->
            if (i > 0) anhängenText(", ")
            anhängenText("${feld.name}=")
            val typ = auflöse(feld.typ)
            cob.aload(0)
            cob.getfield(ziel, feld.name, classDescVonTyp(typ))
            anhängenFeld(artVon(typ, feld.position))
        }
        anhängenText(")")
        cob.invokevirtual(CD_StringBuilder, "toString", MethodTypeDesc.of(CD_String))
        cob.areturn()
    }

    /** Der JVM-Methodendeskriptor einer Instanzmethode. */
    private fun methodenDeskriptor(klassenTyp: KlassenTyp, name: String): MethodTypeDesc {
        val signatur = klassenTyp.methoden.getValue(name)
        val rückgabe = if (signatur.rückgabe == NichtsTyp) {
            ConstantDescs.CD_void
        } else {
            classDescVonTyp(signatur.rückgabe)
        }
        return MethodTypeDesc.of(rückgabe, signatur.parameter.map { classDescVonTyp(it) })
    }

    // ---- Aufzaehlungen ------------------------------------------------------

    /**
     * Erzeugt fuer eine `aufzählung` eine eigene `.class`: je Variante eine
     * `static final`-Instanz (Singleton), erzeugt im statischen Initialisierer.
     * Gleichheit ist damit Referenzgleichheit; `toString` liefert `Typ.Variante`.
     */
    private fun erzeugeAufzählung(d: AufzählungDeklaration): ByteArray {
        val ziel = ClassDesc.of(d.name)
        return ClassFile.of().build(ziel) { clb ->
            clb.withFlags(ClassFile.ACC_PUBLIC or ClassFile.ACC_FINAL)
            for (variante in d.varianten) {
                clb.withField(
                    variante, ziel,
                    ClassFile.ACC_PUBLIC or ClassFile.ACC_STATIC or ClassFile.ACC_FINAL,
                )
            }
            clb.withField("name$", CD_String, ClassFile.ACC_PRIVATE or ClassFile.ACC_FINAL)
            clb.withMethodBody(
                "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, CD_String), ClassFile.ACC_PRIVATE,
            ) { code ->
                code.aload(0)
                code.invokespecial(
                    ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void),
                )
                code.aload(0)
                code.aload(1)
                code.putfield(ziel, "name$", CD_String)
                code.return_()
            }
            clb.withMethodBody(
                "<clinit>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC,
            ) { code ->
                cob = code
                for (variante in d.varianten) {
                    code.new_(ziel)
                    code.dup()
                    ladeText(variante)
                    code.invokespecial(
                        ziel, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, CD_String),
                    )
                    code.putstatic(ziel, variante, ziel)
                }
                code.return_()
            }
            clb.withMethodBody("toString", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC) { code ->
                cob = code
                ladeText("${d.name}.")
                code.aload(0)
                code.getfield(ziel, "name$", CD_String)
                code.invokevirtual(CD_String, "concat", MethodTypeDesc.of(CD_String, CD_String))
                code.areturn()
            }
        }
    }

    /** Liefert den Aufzaehlungstyp, falls [ausdruck] eine Variante `Typ.Variante` ist. */
    private fun aufzählungsVariante(ausdruck: FeldzugriffAusdruck): AufzählungTyp? {
        val ziel = ausdruck.ziel
        if (ziel is Bezeichner && findeVariable(ziel.name) == null) {
            val typ = symbole.typen[ziel.name]
            if (typ is AufzählungTyp && ausdruck.feld in typ.varianten) return typ
        }
        return null
    }

    // ---- Schnittstellen -----------------------------------------------------

    /** Erzeugt fuer eine `schnittstelle` eine JVM-Schnittstelle mit abstrakten Methoden. */
    private fun erzeugeSchnittstelle(d: SchnittstelleDeklaration): ByteArray {
        val ziel = ClassDesc.of(d.name)
        return ClassFile.of().build(ziel) { clb ->
            clb.withFlags(ClassFile.ACC_PUBLIC or ClassFile.ACC_INTERFACE or ClassFile.ACC_ABSTRACT)
            for (m in d.methoden) {
                val rückgabe = m.rückgabetyp?.let { classDescVonTyp(auflöse(it)) }
                    ?: ConstantDescs.CD_void
                val desc = MethodTypeDesc.of(
                    rückgabe, m.parameter.map { classDescVonTyp(auflöse(it.typ)) },
                )
                clb.withMethod(m.name, desc, ClassFile.ACC_PUBLIC or ClassFile.ACC_ABSTRACT) { }
            }
        }
    }

    // ---- Lambdas ------------------------------------------------------------
    //
    // Ein Lambda wird zu einer `edelLambda$n`-Methode auf der Hauptklasse; der
    // Ausdruck selbst erzeugt per `invokedynamic` (LambdaMetafactory) eine
    // Instanz der funktionalen Schnittstelle `EdelFunktionN`. Werte werden an
    // der Schnittstelle als `Object` uebergeben (geboxt), damit dieselbe
    // Schnittstelle fuer jede Signatur derselben Stelligkeit dient.

    private fun funktionsSchnittstelle(stelligkeit: Int): ClassDesc {
        funktionsArten.add(stelligkeit)
        return ClassDesc.of("EdelFunktion$stelligkeit")
    }

    /** Ermittelt den Rueckgabetyp eines Lambdas (Typ des Rumpfes mit Parametern im Geltungsbereich). */
    private fun lambdaRückgabe(lambda: LambdaAusdruck): Typ {
        bereiche.addLast(HashMap())
        for (p in lambda.parameter) {
            val typ = auflöse(p.typ)
            bereiche.last()[p.name] = Variable(typ, artVon(typ, p.position), -1)
        }
        val rück = typVon(lambda.körper)
        bereiche.removeLast()
        return rück
    }

    /** Erzeugt einen Lambda-Ausdruck: gefangene Werte laden, dann `invokedynamic`. */
    private fun erzeugeLambda(lambda: LambdaAusdruck) {
        val bezeichner = mutableListOf<Bezeichner>()
        val verschachtelt = mutableListOf<LambdaAusdruck>()
        sammleAusdruck(lambda.körper, bezeichner, verschachtelt)
        if (verschachtelt.isNotEmpty()) {
            ablehnen(
                "Verschachtelte Lambdas werden vom Bytecode-Backend noch nicht unterstuetzt",
                lambda.position,
            )
        }
        val rückgabe = lambdaRückgabe(lambda)
        if (rückgabe == NichtsTyp) {
            ablehnen(
                "Lambdas ohne Rueckgabewert werden vom Bytecode-Backend noch nicht unterstuetzt",
                lambda.position,
            )
        }
        // Gefangene Variablen: im Rumpf benutzte Namen, die auf eine umschliessende
        // lokale Variable verweisen (keine Parameter, keine globalen Funktionen).
        val parameterNamen = lambda.parameter.mapTo(HashSet()) { it.name }
        val gefangen = LinkedHashMap<String, Variable>()
        for (b in bezeichner) {
            if (b.name in parameterNamen || b.name in gefangen) continue
            findeVariable(b.name)?.let { gefangen[b.name] = it }
        }
        val nummer = lambdaListe.size
        lambdaNummer[lambda] = nummer
        lambdaListe.add(LambdaInfo(lambda, gefangen.map { it.key to it.value.typ }, rückgabe))

        for (variable in gefangen.values) lade(variable.art, variable.slot)
        cob.invokedynamic(
            lambdaAufrufstelle(
                nummer, gefangen.values.map { classDescVonTyp(it.typ) }, lambda.parameter.size,
            ),
        )
    }

    private fun lambdaAufrufstelle(
        nummer: Int,
        gefangenDescs: List<ClassDesc>,
        stelligkeit: Int,
    ): DynamicCallSiteDesc {
        val objekte = List(stelligkeit) { ConstantDescs.CD_Object }
        val samTyp = MethodTypeDesc.of(ConstantDescs.CD_Object, objekte)
        val implTyp = MethodTypeDesc.of(ConstantDescs.CD_Object, gefangenDescs + objekte)
        val impl = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC, selbst, "edelLambda$$nummer", implTyp,
        )
        val fabrikTyp = MethodTypeDesc.of(funktionsSchnittstelle(stelligkeit), gefangenDescs)
        return DynamicCallSiteDesc.of(METAFACTORY, "anwenden", fabrikTyp, samTyp, impl, samTyp)
    }

    private fun lambdaImplDeskriptor(info: LambdaInfo): MethodTypeDesc {
        val gefangen = info.gefangene.map { classDescVonTyp(it.second) }
        val objekte = List(info.lambda.parameter.size) { ConstantDescs.CD_Object }
        return MethodTypeDesc.of(ConstantDescs.CD_Object, gefangen + objekte)
    }

    /** Erzeugt den Rumpf einer `edelLambda$n`-Methode. */
    private fun erzeugeLambdaImpl(info: LambdaInfo) {
        bereiche.clear()
        schleifen.clear()
        bereiche.addLast(HashMap())
        aktuelleKlasse = null
        nächsterSlot = 0
        for ((name, typ) in info.gefangene) {
            val art = artVon(typ, info.lambda.position)
            bereiche.last()[name] = Variable(typ, art, belegeSlot(art))
        }
        // Lambda-Parameter kommen als Object; in echt-typisierte lokale Variablen auspacken.
        val objektSlots = info.lambda.parameter.map { belegeSlot(Art.OBJEKT) }
        info.lambda.parameter.forEachIndexed { i, p ->
            val typ = auflöse(p.typ)
            val art = artVon(typ, p.position)
            cob.aload(objektSlots[i])
            entObjektiviere(typ)
            val slot = belegeSlot(art)
            speichere(art, slot)
            bereiche.last()[p.name] = Variable(typ, art, slot)
        }
        rückgabeTyp = info.rückgabe
        erzeugeMitTyp(info.lambda.körper, info.rückgabe)
        objektiviere(artVon(info.rückgabe, info.lambda.position))
        cob.areturn()
    }

    private fun erzeugeFunktionsSchnittstelle(stelligkeit: Int): ByteArray {
        val ziel = ClassDesc.of("EdelFunktion$stelligkeit")
        val samTyp = MethodTypeDesc.of(
            ConstantDescs.CD_Object, List(stelligkeit) { ConstantDescs.CD_Object },
        )
        return ClassFile.of().build(ziel) { clb ->
            clb.withFlags(ClassFile.ACC_PUBLIC or ClassFile.ACC_INTERFACE or ClassFile.ACC_ABSTRACT)
            clb.withMethod("anwenden", samTyp, ClassFile.ACC_PUBLIC or ClassFile.ACC_ABSTRACT) { }
        }
    }

    /** Boxt einen Grundwert auf dem Stapel in ein `Object`. */
    private fun objektiviere(art: Art) {
        when (art) {
            Art.GANZ -> cob.invokestatic(
                CD_Long, "valueOf", MethodTypeDesc.of(CD_Long, ConstantDescs.CD_long),
            )
            Art.KOMMA -> cob.invokestatic(
                CD_Double, "valueOf", MethodTypeDesc.of(CD_Double, ConstantDescs.CD_double),
            )
            Art.WAHR -> cob.invokestatic(
                CD_Boolean, "valueOf", MethodTypeDesc.of(CD_Boolean, ConstantDescs.CD_boolean),
            )
            Art.ZEICH -> cob.invokestatic(
                CD_Character, "valueOf", MethodTypeDesc.of(CD_Character, ConstantDescs.CD_char),
            )
            else -> {} // bereits eine Referenz
        }
    }

    /** Wandelt ein `Object` auf dem Stapel in einen Wert des Typs [typ] um. */
    private fun entObjektiviere(typ: Typ) {
        when (artVon(typ, Position(0, 0))) {
            Art.GANZ -> {
                cob.checkcast(CD_Number)
                cob.invokevirtual(CD_Number, "longValue", MethodTypeDesc.of(ConstantDescs.CD_long))
            }
            Art.KOMMA -> {
                cob.checkcast(CD_Number)
                cob.invokevirtual(CD_Number, "doubleValue", MethodTypeDesc.of(ConstantDescs.CD_double))
            }
            Art.WAHR -> {
                cob.checkcast(CD_Boolean)
                cob.invokevirtual(CD_Boolean, "booleanValue", MethodTypeDesc.of(ConstantDescs.CD_boolean))
            }
            Art.ZEICH -> {
                cob.checkcast(CD_Character)
                cob.invokevirtual(CD_Character, "charValue", MethodTypeDesc.of(ConstantDescs.CD_char))
            }
            Art.NICHTS -> {}
            else -> cob.checkcast(classDescVonTyp(typ)) // Text, Objekte, nullbare Arten
        }
    }

    /** Sammelt rekursiv alle Bezeichner und (verschachtelten) Lambdas eines Ausdrucks. */
    private fun sammleAusdruck(
        a: Ausdruck,
        bezeichner: MutableList<Bezeichner>,
        lambdas: MutableList<LambdaAusdruck>,
    ) {
        when (a) {
            is Bezeichner -> bezeichner.add(a)
            is LambdaAusdruck -> lambdas.add(a)
            is UnärAusdruck -> sammleAusdruck(a.operand, bezeichner, lambdas)
            is BinärAusdruck -> {
                sammleAusdruck(a.links, bezeichner, lambdas)
                sammleAusdruck(a.rechts, bezeichner, lambdas)
            }
            is AufrufAusdruck -> {
                sammleAusdruck(a.ziel, bezeichner, lambdas)
                a.argumente.forEach { sammleAusdruck(it, bezeichner, lambdas) }
            }
            is IndexAusdruck -> {
                sammleAusdruck(a.ziel, bezeichner, lambdas)
                sammleAusdruck(a.index, bezeichner, lambdas)
            }
            is FeldzugriffAusdruck -> sammleAusdruck(a.ziel, bezeichner, lambdas)
            is ElvisAusdruck -> {
                sammleAusdruck(a.links, bezeichner, lambdas)
                sammleAusdruck(a.rechts, bezeichner, lambdas)
            }
            is NichtNullAusdruck -> sammleAusdruck(a.operand, bezeichner, lambdas)
            is NeuAusdruck -> a.argumente.forEach { sammleAusdruck(it, bezeichner, lambdas) }
            is WähleAusdruck -> {
                sammleAusdruck(a.subjekt, bezeichner, lambdas)
                a.fälle.forEach {
                    sammleAusdruck(it.muster, bezeichner, lambdas)
                    sammleAusdruck(it.ergebnis, bezeichner, lambdas)
                }
                sammleAusdruck(a.sonst, bezeichner, lambdas)
            }
            else -> {} // Literale, DiesAusdruck
        }
    }

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
                when (val ziel = anweisung.ziel) {
                    is Bezeichner -> {
                        val variable = findeVariable(ziel.name)
                            ?: ablehnen("Unbekannte Variable: '${ziel.name}'", ziel.position)
                        erzeugeMitTyp(anweisung.wert, variable.typ)
                        speichere(variable.art, variable.slot)
                    }
                    is FeldzugriffAusdruck -> {
                        if (ziel.sicher) {
                            ablehnen(
                                "Sicherer Feldzugriff '?.' wird vom Bytecode-Backend noch nicht unterstuetzt",
                                ziel.position,
                            )
                        }
                        val empfängerTyp = entnullt(typVon(ziel.ziel))
                        val feldTyp = feldTypVon(empfängerTyp, ziel.feld, ziel.position)
                        erzeugeAusdruck(ziel.ziel)
                        erzeugeMitTyp(anweisung.wert, feldTyp)
                        cob.putfield(ClassDesc.of(typname(empfängerTyp)), ziel.feld, classDescVonTyp(feldTyp))
                    }
                    is IndexAusdruck -> erzeugeIndexZuweisung(ziel, anweisung.wert)
                    else -> ablehnen(
                        "Nur Zuweisungen an Variablen, Felder oder Indizes werden unterstuetzt",
                        ziel.position,
                    )
                }
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
        val iterierbar = entnullt(typVon(anweisung.iterierbar))
        if (iterierbar is ListeTyp) {
            erzeugeFürInListe(anweisung, iterierbar)
            return
        }
        if (iterierbar != TextTyp) {
            ablehnen(
                "'für ... in' ueber $iterierbar wird vom Bytecode-Backend noch nicht " +
                    "unterstuetzt (nur ueber Text und Liste)",
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

    /** `für ... in liste`: Iteration ueber eine `java.util.List` per `Iterator`. */
    private fun erzeugeFürInListe(anweisung: FürInAnweisung, listeTyp: ListeTyp) {
        bereiche.addLast(HashMap())
        val element = listeTyp.element
        val elementArt = artVon(element, anweisung.position)
        val itSlot = belegeSlot(Art.OBJEKT)
        val variableSlot = belegeSlot(elementArt)
        bereiche.last()[anweisung.variable] = Variable(element, elementArt, variableSlot)

        erzeugeAusdruck(anweisung.iterierbar)
        cob.invokeinterface(CD_List, "iterator", MethodTypeDesc.of(CD_Iterator))
        cob.astore(itSlot)

        val start = cob.newLabel()
        val weiter = cob.newLabel()
        val ende = cob.newLabel()
        cob.labelBinding(start)
        cob.aload(itSlot)
        cob.invokeinterface(CD_Iterator, "hasNext", MethodTypeDesc.of(ConstantDescs.CD_boolean))
        cob.ifeq(ende)
        cob.aload(itSlot)
        cob.invokeinterface(CD_Iterator, "next", MethodTypeDesc.of(ConstantDescs.CD_Object))
        entObjektiviere(element)
        speichere(elementArt, variableSlot)

        schleifen.addLast(Schleife(weiter, ende))
        erzeugeBlock(anweisung.körper)
        schleifen.removeLast()

        cob.labelBinding(weiter)
        if (!terminiert(anweisung.körper)) cob.goto_(start)
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

            is NeuAusdruck -> erzeugeNeu(ausdruck)
            is FeldzugriffAusdruck -> erzeugeFeldzugriff(ausdruck)

            is DiesAusdruck -> {
                if (aktuelleKlasse == null) {
                    ablehnen("'dies' ist nur in Methoden gueltig", ausdruck.position)
                }
                cob.aload(0)
            }
            is LambdaAusdruck -> erzeugeLambda(ausdruck)
            is IndexAusdruck -> erzeugeIndex(ausdruck)
        }
    }

    /** Erzeugt `ziel[index]` fuer Liste, Abbildung oder Text. */
    private fun erzeugeIndex(ausdruck: IndexAusdruck) {
        when (val zielTyp = entnullt(typVon(ausdruck.ziel))) {
            is ListeTyp -> {
                erzeugeAusdruck(ausdruck.ziel)
                erzeugeMitTyp(ausdruck.index, GanzzahlTyp)
                cob.l2i()
                cob.invokeinterface(
                    CD_List, "get", MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int),
                )
                entObjektiviere(zielTyp.element)
            }
            is AbbildungTyp -> {
                erzeugeAusdruck(ausdruck.ziel)
                erzeugeMitTyp(ausdruck.index, zielTyp.schlüssel)
                objektiviere(artVon(zielTyp.schlüssel, ausdruck.position))
                cob.invokeinterface(
                    CD_Map, "get", MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object),
                )
                entObjektiviere(zielTyp.wert)
            }
            TextTyp -> {
                erzeugeAusdruck(ausdruck.ziel)
                erzeugeMitTyp(ausdruck.index, GanzzahlTyp)
                cob.l2i()
                cob.invokevirtual(
                    CD_String, "charAt", MethodTypeDesc.of(ConstantDescs.CD_char, ConstantDescs.CD_int),
                )
            }
            else -> ablehnen(
                "Indexzugriff wird nur fuer Liste, Abbildung und Text unterstuetzt", ausdruck.position,
            )
        }
    }

    /** Erzeugt `ziel[index] = wert` fuer Liste und Abbildung. */
    private fun erzeugeIndexZuweisung(ziel: IndexAusdruck, wert: Ausdruck) {
        when (val zielTyp = entnullt(typVon(ziel.ziel))) {
            is ListeTyp -> {
                erzeugeAusdruck(ziel.ziel)
                erzeugeMitTyp(ziel.index, GanzzahlTyp)
                cob.l2i()
                erzeugeMitTyp(wert, zielTyp.element)
                objektiviere(artVon(zielTyp.element, ziel.position))
                cob.invokeinterface(
                    CD_List, "set",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int, ConstantDescs.CD_Object),
                )
                cob.pop()
            }
            is AbbildungTyp -> {
                erzeugeAusdruck(ziel.ziel)
                erzeugeMitTyp(ziel.index, zielTyp.schlüssel)
                objektiviere(artVon(zielTyp.schlüssel, ziel.position))
                erzeugeMitTyp(wert, zielTyp.wert)
                objektiviere(artVon(zielTyp.wert, ziel.position))
                cob.invokeinterface(
                    CD_Map, "put",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object, ConstantDescs.CD_Object),
                )
                cob.pop()
            }
            else -> ablehnen(
                "Indexzuweisung wird nur fuer Liste und Abbildung unterstuetzt", ziel.position,
            )
        }
    }

    /** Erzeugt `neu Typ(...)`: `new` + Konstruktoraufruf fuer Datensaetze und Klassen. */
    private fun erzeugeNeu(ausdruck: NeuAusdruck) {
        val fqn = ausdruck.aufgelöst ?: ausdruck.typname
        val ktorTypen = when (symbole.typen[fqn]) {
            is DatensatzTyp -> symbole.datensatzDeklarationen.getValue(fqn)
                .felder.map { auflöse(it.typ) }
            is KlassenTyp -> ktorFelderKette(symbole.klassenDeklarationen.getValue(fqn))
                .map { auflöse(it.typ) }
            else -> ablehnen(
                "'neu ${ausdruck.typname}': unbekannter oder nicht unterstuetzter Typ",
                ausdruck.position,
            )
        }
        val ziel = ClassDesc.of(fqn)
        cob.new_(ziel)
        cob.dup()
        ausdruck.argumente.forEachIndexed { i, argument -> erzeugeMitTyp(argument, ktorTypen[i]) }
        cob.invokespecial(
            ziel, "<init>",
            MethodTypeDesc.of(ConstantDescs.CD_void, ktorTypen.map { classDescVonTyp(it) }),
        )
    }

    /** Erzeugt `ziel.feld`: Aufzaehlungsvariante (`getstatic`) oder Feldzugriff (`getfield`). */
    private fun erzeugeFeldzugriff(ausdruck: FeldzugriffAusdruck) {
        val enumTyp = aufzählungsVariante(ausdruck)
        if (enumTyp != null) {
            val ziel = ClassDesc.of(enumTyp.name)
            cob.getstatic(ziel, ausdruck.feld, ziel)
            return
        }
        if (ausdruck.sicher) {
            ablehnen(
                "Sicherer Feldzugriff '?.' wird vom Bytecode-Backend noch nicht unterstuetzt",
                ausdruck.position,
            )
        }
        val zielTyp = entnullt(typVon(ausdruck.ziel))
        val feldTyp = feldTypVon(zielTyp, ausdruck.feld, ausdruck.position)
        erzeugeAusdruck(ausdruck.ziel)
        if (zielTyp is PaarTyp) {
            // EdelPaar speichert beide Komponenten als Object.
            cob.getfield(CD_EdelPaar, ausdruck.feld, ConstantDescs.CD_Object)
            entObjektiviere(feldTyp)
        } else {
            cob.getfield(ClassDesc.of(typname(zielTyp)), ausdruck.feld, classDescVonTyp(feldTyp))
        }
    }

    /** Der Typ des Feldes [feld] eines Datensatz-, Klassen- oder Paartyps. */
    private fun feldTypVon(zielTyp: Typ, feld: String, position: Position): Typ = when (zielTyp) {
        is PaarTyp -> when (feld) {
            "erst" -> zielTyp.erst
            "zweit" -> zielTyp.zweit
            else -> ablehnen("Paar hat kein Feld '$feld'", position)
        }
        is DatensatzTyp -> zielTyp.felder[feld]
            ?: ablehnen("Unbekanntes Feld '$feld'", position)
        is KlassenTyp -> zielTyp.findeFeld(feld)?.typ
            ?: ablehnen("Unbekanntes Feld '$feld'", position)
        else -> ablehnen("Feldzugriff wird nur fuer Datensaetze und Klassen unterstuetzt", position)
    }

    private fun typname(typ: Typ): String = when (typ) {
        is DatensatzTyp -> typ.name
        is KlassenTyp -> typ.name
        else -> ablehnen("Erwartet wurde ein Objekttyp, gefunden: '$typ'", Position(0, 0))
    }

    private fun erzeugeBinär(ausdruck: BinärAusdruck) {
        when (ausdruck.operator) {
            UND -> kurzschluss(ausdruck, sprungWennWahr = false)
            ODER -> kurzschluss(ausdruck, sprungWennWahr = true)
            GLEICH, UNGLEICH -> {
                val links = typVon(ausdruck.links)
                val rechts = typVon(ausdruck.rechts)
                val basis = entnullt(links).takeIf { it != NichtsTyp } ?: entnullt(rechts)
                if (basis is DatensatzTyp || basis is KlassenTyp) {
                    ablehnen(
                        "Gleichheit auf Datensaetzen/Klassen wird vom Bytecode-Backend noch " +
                            "nicht unterstuetzt",
                        ausdruck.position,
                    )
                }
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
            Art.OBJEKT -> when (vgl) {
                // Aufzaehlungsvarianten sind Singletons: Referenzgleichheit genuegt.
                Vgl.NE -> cob.if_acmpne(ziel)
                else -> cob.if_acmpeq(ziel)
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
        entnullt(subjektTyp).let { basis ->
            if (basis is DatensatzTyp || basis is KlassenTyp) {
                ablehnen(
                    "'wähle' ueber Datensaetze/Klassen wird vom Bytecode-Backend noch " +
                        "nicht unterstuetzt",
                    ausdruck.position,
                )
            }
        }
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
        if (ziel is FeldzugriffAusdruck) {
            erzeugeMethodenaufruf(ziel, ausdruck.argumente)
            return
        }
        // Ein Funktionswert (Lambda in einer Variablen oder beliebiger Ausdruck).
        if (ziel !is Bezeichner || findeVariable(ziel.name) != null) {
            val zielTyp = typVon(ziel)
            if (zielTyp is FunktionsTyp) {
                erzeugeFunktionswertAufruf(ziel, zielTyp, ausdruck.argumente)
                return
            }
            ablehnen("Nur Funktionswerte koennen aufgerufen werden", ausdruck.position)
        }
        when (ziel.name) {
            "drucke" -> erzeugeDrucke(ausdruck.argumente[0])
            "länge" -> {
                val argument = ausdruck.argumente[0]
                erzeugeAusdruck(argument)
                when (entnullt(typVon(argument))) {
                    TextTyp -> cob.invokevirtual(CD_String, "length", MethodTypeDesc.of(ConstantDescs.CD_int))
                    is ListeTyp -> cob.invokeinterface(CD_List, "size", MethodTypeDesc.of(ConstantDescs.CD_int))
                    is AbbildungTyp -> cob.invokeinterface(CD_Map, "size", MethodTypeDesc.of(ConstantDescs.CD_int))
                    else -> ablehnen(
                        "'länge' wird nur fuer Text, Liste und Abbildung unterstuetzt",
                        argument.position,
                    )
                }
                cob.i2l()
            }
            "Liste" -> erzeugeListe(ausdruck)
            "Paar" -> erzeugePaar(ausdruck)
            "Abbildung" -> erzeugeAbbildung(ausdruck)
            "Erfolg" -> erzeugeErfolg(ausdruck)
            "Fehler" -> erzeugeFehler(ausdruck)
            "lies" -> ablehnen(
                "'lies' wird vom Bytecode-Backend noch nicht unterstuetzt",
                ausdruck.position,
            )
            else -> {
                val fqn = ziel.aufgelöst ?: ziel.name
                val signatur = symbole.funktionen[fqn]
                    ?: ablehnen("Unbekannte Funktion: '${ziel.name}'", ausdruck.position)
                ausdruck.argumente.forEachIndexed { i, argument ->
                    erzeugeMitTyp(argument, signatur.parameter[i])
                }
                cob.invokestatic(selbst, fqn, deskriptor(fqn))
            }
        }
    }

    /** Ruft einen Funktionswert auf: `invokeinterface EdelFunktionN.anwenden(Object…)Object`. */
    private fun erzeugeFunktionswertAufruf(
        ziel: Ausdruck,
        funkTyp: FunktionsTyp,
        argumente: List<Ausdruck>,
    ) {
        erzeugeAusdruck(ziel)
        argumente.forEachIndexed { i, argument ->
            erzeugeMitTyp(argument, funkTyp.parameter[i])
            objektiviere(artVon(funkTyp.parameter[i], argument.position))
        }
        val stelligkeit = funkTyp.parameter.size
        val samTyp = MethodTypeDesc.of(
            ConstantDescs.CD_Object, List(stelligkeit) { ConstantDescs.CD_Object },
        )
        cob.invokeinterface(funktionsSchnittstelle(stelligkeit), "anwenden", samTyp)
        entObjektiviere(funkTyp.rückgabe)
    }

    /** Erzeugt `empfänger.methode(args)` ueber `invokevirtual`. */
    private fun erzeugeMethodenaufruf(zugriff: FeldzugriffAusdruck, argumente: List<Ausdruck>) {
        if (zugriff.sicher) {
            ablehnen(
                "Sicherer Methodenaufruf '?.' wird vom Bytecode-Backend noch nicht unterstuetzt",
                zugriff.position,
            )
        }
        val empfängerTyp = entnullt(typVon(zugriff.ziel))
        if (empfängerTyp is TextTyp || empfängerTyp is ListeTyp || empfängerTyp is AbbildungTyp ||
            empfängerTyp is ErgebnisTyp ||
            empfängerTyp == GanzzahlTyp || empfängerTyp == KommazahlTyp ||
            empfängerTyp == WahrheitTyp || empfängerTyp == ZeichenTyp
        ) {
            erzeugeEingebauteMethode(zugriff.ziel, empfängerTyp, zugriff.feld, argumente, zugriff.position)
            return
        }
        val signatur: FunktionsTyp
        val besitzer: ClassDesc
        val istSchnittstelle: Boolean
        when (empfängerTyp) {
            is KlassenTyp -> {
                signatur = empfängerTyp.findeMethode(zugriff.feld)
                    ?: ablehnen("Unbekannte Methode '${zugriff.feld}'", zugriff.position)
                besitzer = ClassDesc.of(empfängerTyp.name)
                istSchnittstelle = false
            }
            is SchnittstellenTyp -> {
                signatur = empfängerTyp.methoden[zugriff.feld]
                    ?: ablehnen("Unbekannte Methode '${zugriff.feld}'", zugriff.position)
                besitzer = ClassDesc.of(empfängerTyp.name)
                istSchnittstelle = true
            }
            else -> ablehnen(
                "Methodenaufrufe werden nur fuer Klassen und Schnittstellen unterstuetzt",
                zugriff.position,
            )
        }
        erzeugeAusdruck(zugriff.ziel)
        argumente.forEachIndexed { i, argument -> erzeugeMitTyp(argument, signatur.parameter[i]) }
        val rückgabe = if (signatur.rückgabe == NichtsTyp) {
            ConstantDescs.CD_void
        } else {
            classDescVonTyp(signatur.rückgabe)
        }
        val desc = MethodTypeDesc.of(rückgabe, signatur.parameter.map { classDescVonTyp(it) })
        if (istSchnittstelle) {
            cob.invokeinterface(besitzer, zugriff.feld, desc)
        } else {
            cob.invokevirtual(besitzer, zugriff.feld, desc)
        }
    }

    // ---- Sammlungen (Liste, Abbildung, Paar) --------------------------------

    /** Erzeugt `Liste(...)`: eine `ArrayList`, gefuellt mit den geboxten Argumenten. */
    private fun erzeugeListe(ausdruck: AufrufAusdruck) {
        val element = (typVon(ausdruck) as ListeTyp).element
        cob.new_(CD_ArrayList)
        cob.dup()
        cob.invokespecial(CD_ArrayList, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
        for (argument in ausdruck.argumente) {
            cob.dup()
            erzeugeMitTyp(argument, element)
            objektiviere(artVon(element, argument.position))
            cob.invokeinterface(
                CD_List, "add", MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object),
            )
            cob.pop()
        }
    }

    /** Erzeugt `Paar(a, b)`: ein `EdelPaar` mit zwei geboxten Komponenten. */
    private fun erzeugePaar(ausdruck: AufrufAusdruck) {
        brauchtPaar = true
        val paarTyp = typVon(ausdruck) as PaarTyp
        cob.new_(CD_EdelPaar)
        cob.dup()
        erzeugeMitTyp(ausdruck.argumente[0], paarTyp.erst)
        objektiviere(artVon(paarTyp.erst, ausdruck.position))
        erzeugeMitTyp(ausdruck.argumente[1], paarTyp.zweit)
        objektiviere(artVon(paarTyp.zweit, ausdruck.position))
        cob.invokespecial(
            CD_EdelPaar, "<init>",
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_Object),
        )
    }

    /** Erzeugt `Abbildung(Paar(...), …)`: eine `LinkedHashMap` aus den Paaren. */
    private fun erzeugeAbbildung(ausdruck: AufrufAusdruck) {
        brauchtPaar = true
        cob.new_(CD_LinkedHashMap)
        cob.dup()
        cob.invokespecial(CD_LinkedHashMap, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
        for (argument in ausdruck.argumente) {
            erzeugeAusdruck(argument) // ein EdelPaar
            val paarSlot = belegeSlot(Art.OBJEKT)
            cob.astore(paarSlot)
            cob.dup()
            cob.aload(paarSlot)
            cob.getfield(CD_EdelPaar, "erst", ConstantDescs.CD_Object)
            cob.aload(paarSlot)
            cob.getfield(CD_EdelPaar, "zweit", ConstantDescs.CD_Object)
            cob.invokeinterface(
                CD_Map, "put",
                MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object, ConstantDescs.CD_Object),
            )
            cob.pop()
        }
    }

    /** Erzeugt die Klasse `EdelPaar` (zwei `Object`-Felder, Konstruktor, `toString`). */
    private fun erzeugeEdelPaar(): ByteArray = ClassFile.of().build(CD_EdelPaar) { clb ->
        clb.withFlags(ClassFile.ACC_PUBLIC or ClassFile.ACC_FINAL)
        clb.withField("erst", ConstantDescs.CD_Object, ClassFile.ACC_PUBLIC or ClassFile.ACC_FINAL)
        clb.withField("zweit", ConstantDescs.CD_Object, ClassFile.ACC_PUBLIC or ClassFile.ACC_FINAL)
        clb.withMethodBody(
            "<init>",
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_Object),
            ClassFile.ACC_PUBLIC,
        ) { code ->
            code.aload(0)
            code.invokespecial(ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
            code.aload(0); code.aload(1); code.putfield(CD_EdelPaar, "erst", ConstantDescs.CD_Object)
            code.aload(0); code.aload(2); code.putfield(CD_EdelPaar, "zweit", ConstantDescs.CD_Object)
            code.return_()
        }
        clb.withMethodBody("toString", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC) { code ->
            cob = code
            cob.new_(CD_StringBuilder)
            cob.dup()
            cob.invokespecial(CD_StringBuilder, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
            anhängenText("(")
            for ((i, feld) in listOf("erst", "zweit").withIndex()) {
                if (i > 0) anhängenText(", ")
                cob.aload(0)
                cob.getfield(CD_EdelPaar, feld, ConstantDescs.CD_Object)
                cob.invokestatic(
                    CD_String, "valueOf", MethodTypeDesc.of(CD_String, ConstantDescs.CD_Object),
                )
                cob.invokevirtual(
                    CD_StringBuilder, "append", MethodTypeDesc.of(CD_StringBuilder, CD_String),
                )
            }
            anhängenText(")")
            cob.invokevirtual(CD_StringBuilder, "toString", MethodTypeDesc.of(CD_String))
            cob.areturn()
        }
    }

    /** Erzeugt den Aufruf einer eingebauten Methode auf Text, Liste, Abbildung oder Grundwert. */
    private fun erzeugeEingebauteMethode(
        empfänger: Ausdruck,
        empfängerTyp: Typ,
        name: String,
        argumente: List<Ausdruck>,
        position: Position,
    ) {
        when (empfängerTyp) {
            TextTyp -> erzeugeTextMethode(empfänger, name, argumente, position)
            is ListeTyp -> erzeugeListeMethode(empfänger, empfängerTyp, name, argumente, position)
            is AbbildungTyp -> erzeugeAbbildungMethode(empfänger, empfängerTyp, name, argumente, position)
            is ErgebnisTyp -> erzeugeErgebnisMethode(empfänger, empfängerTyp, name, argumente, position)
            else -> if (name == "alsText") {
                erzeugeAlsText(empfänger)
            } else {
                ablehnen("'$empfängerTyp' hat keine Methode '$name'", position)
            }
        }
    }

    private fun erzeugeTextMethode(
        empfänger: Ausdruck,
        name: String,
        argumente: List<Ausdruck>,
        position: Position,
    ) {
        when (name) {
            "länge" -> {
                erzeugeAusdruck(empfänger)
                cob.invokevirtual(CD_String, "length", MethodTypeDesc.of(ConstantDescs.CD_int))
                cob.i2l()
            }
            "großbuchstaben" -> {
                erzeugeAusdruck(empfänger)
                cob.invokevirtual(CD_String, "toUpperCase", MethodTypeDesc.of(CD_String))
            }
            "kleinbuchstaben" -> {
                erzeugeAusdruck(empfänger)
                cob.invokevirtual(CD_String, "toLowerCase", MethodTypeDesc.of(CD_String))
            }
            "alsText" -> erzeugeAusdruck(empfänger)
            "enthält" -> {
                erzeugeAusdruck(empfänger)
                erzeugeMitTyp(argumente[0], TextTyp)
                cob.invokevirtual(
                    CD_String, "contains", MethodTypeDesc.of(ConstantDescs.CD_boolean, CD_CharSequence),
                )
            }
            "zeichenBei" -> {
                erzeugeAusdruck(empfänger)
                erzeugeMitTyp(argumente[0], GanzzahlTyp)
                cob.l2i()
                cob.invokevirtual(
                    CD_String, "charAt", MethodTypeDesc.of(ConstantDescs.CD_char, ConstantDescs.CD_int),
                )
            }
            "teile" -> {
                cob.new_(CD_ArrayList)
                cob.dup()
                erzeugeAusdruck(empfänger)
                erzeugeMitTyp(argumente[0], TextTyp)
                cob.invokevirtual(CD_String, "split", MethodTypeDesc.of(CD_StringArray, CD_String))
                cob.invokestatic(CD_Arrays, "asList", MethodTypeDesc.of(CD_List, CD_ObjectArray))
                cob.invokespecial(CD_ArrayList, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, CD_Collection))
            }
            else -> ablehnen("Text hat keine Methode '$name'", position)
        }
    }

    private fun erzeugeListeMethode(
        empfänger: Ausdruck,
        listeTyp: ListeTyp,
        name: String,
        argumente: List<Ausdruck>,
        position: Position,
    ) {
        val element = listeTyp.element
        when (name) {
            "länge" -> {
                erzeugeAusdruck(empfänger)
                cob.invokeinterface(CD_List, "size", MethodTypeDesc.of(ConstantDescs.CD_int))
                cob.i2l()
            }
            "istLeer" -> {
                erzeugeAusdruck(empfänger)
                cob.invokeinterface(CD_List, "isEmpty", MethodTypeDesc.of(ConstantDescs.CD_boolean))
            }
            "hinzufügen" -> {
                erzeugeAusdruck(empfänger)
                erzeugeMitTyp(argumente[0], element)
                objektiviere(artVon(element, position))
                cob.invokeinterface(
                    CD_List, "add", MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object),
                )
                cob.pop()
            }
            "holen" -> {
                erzeugeAusdruck(empfänger)
                erzeugeMitTyp(argumente[0], GanzzahlTyp)
                cob.l2i()
                cob.invokeinterface(
                    CD_List, "get", MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int),
                )
                entObjektiviere(element)
            }
            "setze" -> {
                erzeugeAusdruck(empfänger)
                erzeugeMitTyp(argumente[0], GanzzahlTyp)
                cob.l2i()
                erzeugeMitTyp(argumente[1], element)
                objektiviere(artVon(element, position))
                cob.invokeinterface(
                    CD_List, "set",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int, ConstantDescs.CD_Object),
                )
                cob.pop()
            }
            "entferne" -> {
                erzeugeAusdruck(empfänger)
                erzeugeMitTyp(argumente[0], GanzzahlTyp)
                cob.l2i()
                cob.invokeinterface(
                    CD_List, "remove", MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int),
                )
                entObjektiviere(element)
            }
            else -> ablehnen("Liste hat keine Methode '$name'", position)
        }
    }

    private fun erzeugeAbbildungMethode(
        empfänger: Ausdruck,
        abbTyp: AbbildungTyp,
        name: String,
        argumente: List<Ausdruck>,
        position: Position,
    ) {
        when (name) {
            "länge" -> {
                erzeugeAusdruck(empfänger)
                cob.invokeinterface(CD_Map, "size", MethodTypeDesc.of(ConstantDescs.CD_int))
                cob.i2l()
            }
            "istLeer" -> {
                erzeugeAusdruck(empfänger)
                cob.invokeinterface(CD_Map, "isEmpty", MethodTypeDesc.of(ConstantDescs.CD_boolean))
            }
            "enthält" -> {
                erzeugeAusdruck(empfänger)
                erzeugeMitTyp(argumente[0], abbTyp.schlüssel)
                objektiviere(artVon(abbTyp.schlüssel, position))
                cob.invokeinterface(
                    CD_Map, "containsKey",
                    MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object),
                )
            }
            "holen" -> {
                erzeugeAusdruck(empfänger)
                erzeugeMitTyp(argumente[0], abbTyp.schlüssel)
                objektiviere(artVon(abbTyp.schlüssel, position))
                cob.invokeinterface(
                    CD_Map, "get", MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object),
                )
                entObjektiviere(abbTyp.wert)
            }
            "setze" -> {
                erzeugeAusdruck(empfänger)
                erzeugeMitTyp(argumente[0], abbTyp.schlüssel)
                objektiviere(artVon(abbTyp.schlüssel, position))
                erzeugeMitTyp(argumente[1], abbTyp.wert)
                objektiviere(artVon(abbTyp.wert, position))
                cob.invokeinterface(
                    CD_Map, "put",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object, ConstantDescs.CD_Object),
                )
                cob.pop()
            }
            "schlüssel" -> {
                cob.new_(CD_ArrayList)
                cob.dup()
                erzeugeAusdruck(empfänger)
                cob.invokeinterface(CD_Map, "keySet", MethodTypeDesc.of(CD_Set))
                cob.invokespecial(CD_ArrayList, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, CD_Collection))
            }
            else -> ablehnen("Abbildung hat keine Methode '$name'", position)
        }
    }

    // ---- Ergebnis (Fehlerbehandlung) ----------------------------------------

    /** Erzeugt `Erfolg(wert)`: ein `EdelErgebnis` mit gesetztem Erfolgs-Flag. */
    private fun erzeugeErfolg(ausdruck: AufrufAusdruck) {
        brauchtErgebnis = true
        val wertTyp = typVon(ausdruck.argumente[0])
        cob.new_(CD_EdelErgebnis)
        cob.dup()
        cob.iconst_1()
        erzeugeAusdruck(ausdruck.argumente[0])
        objektiviere(artVon(wertTyp, ausdruck.position))
        cob.aconst_null()
        cob.invokespecial(CD_EdelErgebnis, "<init>", CD_ergebnisKtor)
    }

    /** Erzeugt `Fehler(meldung)`: ein `EdelErgebnis` mit Fehlschlag-Flag und Meldung. */
    private fun erzeugeFehler(ausdruck: AufrufAusdruck) {
        brauchtErgebnis = true
        cob.new_(CD_EdelErgebnis)
        cob.dup()
        cob.iconst_0()
        cob.aconst_null()
        erzeugeMitTyp(ausdruck.argumente[0], TextTyp)
        cob.invokespecial(CD_EdelErgebnis, "<init>", CD_ergebnisKtor)
    }

    private fun erzeugeErgebnisMethode(
        empfänger: Ausdruck,
        ergebnisTyp: ErgebnisTyp,
        name: String,
        argumente: List<Ausdruck>,
        position: Position,
    ) {
        when (name) {
            "istErfolg" -> {
                erzeugeAusdruck(empfänger)
                cob.invokevirtual(CD_EdelErgebnis, "istErfolg", MethodTypeDesc.of(ConstantDescs.CD_boolean))
            }
            "istFehler" -> {
                erzeugeAusdruck(empfänger)
                cob.invokevirtual(CD_EdelErgebnis, "istFehler", MethodTypeDesc.of(ConstantDescs.CD_boolean))
            }
            "meldung" -> {
                erzeugeAusdruck(empfänger)
                cob.invokevirtual(CD_EdelErgebnis, "meldung", MethodTypeDesc.of(CD_String))
            }
            "wert" -> {
                erzeugeAusdruck(empfänger)
                cob.invokevirtual(CD_EdelErgebnis, "wert", MethodTypeDesc.of(ConstantDescs.CD_Object))
                entObjektiviere(ergebnisTyp.wert)
            }
            "oderSonst" -> {
                erzeugeAusdruck(empfänger)
                erzeugeMitTyp(argumente[0], ergebnisTyp.wert)
                objektiviere(artVon(ergebnisTyp.wert, position))
                cob.invokevirtual(
                    CD_EdelErgebnis, "oderSonst",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object),
                )
                entObjektiviere(ergebnisTyp.wert)
            }
            else -> ablehnen("Ergebnis hat keine Methode '$name'", position)
        }
    }

    /** Erzeugt die Klasse `EdelErgebnis` (Flag, Wert, Meldung) samt Zugriffsmethoden. */
    private fun erzeugeEdelErgebnis(): ByteArray = ClassFile.of().build(CD_EdelErgebnis) { clb ->
        clb.withFlags(ClassFile.ACC_PUBLIC or ClassFile.ACC_FINAL)
        clb.withField("erfolg", ConstantDescs.CD_boolean, ClassFile.ACC_PRIVATE or ClassFile.ACC_FINAL)
        clb.withField("wert", ConstantDescs.CD_Object, ClassFile.ACC_PRIVATE or ClassFile.ACC_FINAL)
        clb.withField("meldung", CD_String, ClassFile.ACC_PRIVATE or ClassFile.ACC_FINAL)
        clb.withMethodBody("<init>", CD_ergebnisKtor, ClassFile.ACC_PUBLIC) { code ->
            code.aload(0)
            code.invokespecial(ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
            code.aload(0); code.iload(1); code.putfield(CD_EdelErgebnis, "erfolg", ConstantDescs.CD_boolean)
            code.aload(0); code.aload(2); code.putfield(CD_EdelErgebnis, "wert", ConstantDescs.CD_Object)
            code.aload(0); code.aload(3); code.putfield(CD_EdelErgebnis, "meldung", CD_String)
            code.return_()
        }
        clb.withMethodBody("istErfolg", MethodTypeDesc.of(ConstantDescs.CD_boolean), ClassFile.ACC_PUBLIC) { code ->
            code.aload(0)
            code.getfield(CD_EdelErgebnis, "erfolg", ConstantDescs.CD_boolean)
            code.ireturn()
        }
        clb.withMethodBody("istFehler", MethodTypeDesc.of(ConstantDescs.CD_boolean), ClassFile.ACC_PUBLIC) { code ->
            code.aload(0)
            code.getfield(CD_EdelErgebnis, "erfolg", ConstantDescs.CD_boolean)
            code.iconst_1()
            code.ixor()
            code.ireturn()
        }
        clb.withMethodBody("wert", MethodTypeDesc.of(ConstantDescs.CD_Object), ClassFile.ACC_PUBLIC) { code ->
            cob = code
            val ok = code.newLabel()
            code.aload(0)
            code.getfield(CD_EdelErgebnis, "erfolg", ConstantDescs.CD_boolean)
            code.ifne(ok)
            wirfLaufzeitfehler {
                cob.new_(CD_StringBuilder)
                cob.dup()
                cob.invokespecial(CD_StringBuilder, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
                anhängenText("Ergebnis ist ein Fehler: ")
                cob.aload(0)
                cob.getfield(CD_EdelErgebnis, "meldung", CD_String)
                cob.invokevirtual(CD_StringBuilder, "append", MethodTypeDesc.of(CD_StringBuilder, CD_String))
                cob.invokevirtual(CD_StringBuilder, "toString", MethodTypeDesc.of(CD_String))
            }
            code.labelBinding(ok)
            code.aload(0)
            code.getfield(CD_EdelErgebnis, "wert", ConstantDescs.CD_Object)
            code.areturn()
        }
        clb.withMethodBody("meldung", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC) { code ->
            cob = code
            val ok = code.newLabel()
            code.aload(0)
            code.getfield(CD_EdelErgebnis, "erfolg", ConstantDescs.CD_boolean)
            code.ifeq(ok)
            wirfLaufzeitfehler { ladeText("Ergebnis ist ein Erfolg, keine Fehlermeldung vorhanden") }
            code.labelBinding(ok)
            code.aload(0)
            code.getfield(CD_EdelErgebnis, "meldung", CD_String)
            code.areturn()
        }
        clb.withMethodBody(
            "oderSonst", MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object),
            ClassFile.ACC_PUBLIC,
        ) { code ->
            val sonst = code.newLabel()
            code.aload(0)
            code.getfield(CD_EdelErgebnis, "erfolg", ConstantDescs.CD_boolean)
            code.ifeq(sonst)
            code.aload(0)
            code.getfield(CD_EdelErgebnis, "wert", ConstantDescs.CD_Object)
            code.areturn()
            code.labelBinding(sonst)
            code.aload(1)
            code.areturn()
        }
        clb.withMethodBody("toString", MethodTypeDesc.of(CD_String), ClassFile.ACC_PUBLIC) { code ->
            cob = code
            val fehlerFall = code.newLabel()
            code.aload(0)
            code.getfield(CD_EdelErgebnis, "erfolg", ConstantDescs.CD_boolean)
            code.ifeq(fehlerFall)
            cob.new_(CD_StringBuilder)
            cob.dup()
            cob.invokespecial(CD_StringBuilder, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
            anhängenText("Erfolg(")
            cob.aload(0)
            cob.getfield(CD_EdelErgebnis, "wert", ConstantDescs.CD_Object)
            cob.invokestatic(CD_String, "valueOf", MethodTypeDesc.of(CD_String, ConstantDescs.CD_Object))
            cob.invokevirtual(CD_StringBuilder, "append", MethodTypeDesc.of(CD_StringBuilder, CD_String))
            anhängenText(")")
            cob.invokevirtual(CD_StringBuilder, "toString", MethodTypeDesc.of(CD_String))
            cob.areturn()
            code.labelBinding(fehlerFall)
            cob.new_(CD_StringBuilder)
            cob.dup()
            cob.invokespecial(CD_StringBuilder, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
            anhängenText("Fehler(")
            cob.aload(0)
            cob.getfield(CD_EdelErgebnis, "meldung", CD_String)
            cob.invokevirtual(CD_StringBuilder, "append", MethodTypeDesc.of(CD_StringBuilder, CD_String))
            anhängenText(")")
            cob.invokevirtual(CD_StringBuilder, "toString", MethodTypeDesc.of(CD_String))
            cob.areturn()
        }
    }

    /** Erzeugt `throw new RuntimeException(meldung)`; [meldung] legt den String auf den Stapel. */
    private fun wirfLaufzeitfehler(meldung: () -> Unit) {
        cob.new_(CD_RuntimeException)
        cob.dup()
        meldung()
        cob.invokespecial(
            CD_RuntimeException, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, CD_String),
        )
        cob.athrow()
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
            when (val ziel = ausdruck.ziel) {
                is FeldzugriffAusdruck -> when (val empfängerTyp = entnullt(typVon(ziel.ziel))) {
                    is KlassenTyp -> empfängerTyp.findeMethode(ziel.feld)?.rückgabe
                        ?: ablehnen("Unbekannte Methode '${ziel.feld}'", ausdruck.position)
                    is SchnittstellenTyp -> empfängerTyp.methoden[ziel.feld]?.rückgabe
                        ?: ablehnen("Unbekannte Methode '${ziel.feld}'", ausdruck.position)
                    else -> Typpruefer.eingebauteMethode(empfängerTyp, ziel.feld)?.rückgabe
                        ?: ablehnen("$empfängerTyp hat keine Methode '${ziel.feld}'", ausdruck.position)
                }
                is Bezeichner -> {
                    val variable = findeVariable(ziel.name)
                    when {
                        variable?.typ is FunktionsTyp -> (variable.typ as FunktionsTyp).rückgabe
                        variable != null -> ablehnen("'${ziel.name}' ist kein Funktionswert", ausdruck.position)
                        ziel.name == "drucke" -> NichtsTyp
                        ziel.name == "länge" -> GanzzahlTyp
                        ziel.name == "Liste" -> ListeTyp(
                            ausdruck.argumente.map { typVon(it) }
                                .reduceOrNull { a, b -> gemeinsamerTyp(a, b) } ?: FehlerTyp,
                        )
                        ziel.name == "Paar" -> PaarTyp(
                            typVon(ausdruck.argumente[0]), typVon(ausdruck.argumente[1]),
                        )
                        ziel.name == "Abbildung" -> {
                            val paare = ausdruck.argumente.map { typVon(it) as PaarTyp }
                            AbbildungTyp(
                                paare.map { it.erst }.reduceOrNull { a, b -> gemeinsamerTyp(a, b) } ?: FehlerTyp,
                                paare.map { it.zweit }.reduceOrNull { a, b -> gemeinsamerTyp(a, b) } ?: FehlerTyp,
                            )
                        }
                        ziel.name == "Erfolg" -> ErgebnisTyp(typVon(ausdruck.argumente[0]))
                        ziel.name == "Fehler" -> ErgebnisTyp(NichtsTyp)
                        else -> symbole.funktionen[ziel.aufgelöst ?: ziel.name]?.rückgabe
                            ?: ablehnen("Unbekannte Funktion: '${ziel.name}'", ausdruck.position)
                    }
                }
                else -> {
                    val zielTyp = typVon(ziel)
                    if (zielTyp is FunktionsTyp) {
                        zielTyp.rückgabe
                    } else {
                        ablehnen("Nur Funktionswerte koennen aufgerufen werden", ausdruck.position)
                    }
                }
            }
        }
        is WähleAusdruck -> {
            var typ = typVon(ausdruck.sonst)
            for (fall in ausdruck.fälle) typ = gemeinsamerTyp(typ, typVon(fall.ergebnis))
            typ
        }
        is ElvisAusdruck -> gemeinsamerTyp(entnullt(typVon(ausdruck.links)), typVon(ausdruck.rechts))
        is NichtNullAusdruck -> entnullt(typVon(ausdruck.operand))
        is NeuAusdruck -> symbole.typen[ausdruck.aufgelöst ?: ausdruck.typname]
            ?: ablehnen("Unbekannter Typ: '${ausdruck.typname}'", ausdruck.position)
        is FeldzugriffAusdruck -> aufzählungsVariante(ausdruck)
            ?: feldTypVon(entnullt(typVon(ausdruck.ziel)), ausdruck.feld, ausdruck.position)
        is DiesAusdruck -> aktuelleKlasse
            ?: ablehnen("'dies' ist nur in Methoden gueltig", ausdruck.position)
        is LambdaAusdruck ->
            FunktionsTyp(ausdruck.parameter.map { auflöse(it.typ) }, lambdaRückgabe(ausdruck))
        is IndexAusdruck -> when (val zielTyp = entnullt(typVon(ausdruck.ziel))) {
            is ListeTyp -> zielTyp.element
            is AbbildungTyp -> zielTyp.wert
            TextTyp -> ZeichenTyp
            else -> ablehnen(
                "Indexzugriff wird nur fuer Liste, Abbildung und Text unterstuetzt", ausdruck.position,
            )
        }
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
            classDescVonTyp(signatur.rückgabe)
        }
        return MethodTypeDesc.of(rückgabe, signatur.parameter.map { classDescVonTyp(it) })
    }

    /** Liefert den JVM-Klassendeskriptor eines Edel-Typs (Grundtypen, Text, Datensaetze). */
    private fun classDescVonTyp(typ: Typ): ClassDesc = when (typ) {
        GanzzahlTyp -> ConstantDescs.CD_long
        KommazahlTyp -> ConstantDescs.CD_double
        WahrheitTyp -> ConstantDescs.CD_boolean
        ZeichenTyp -> ConstantDescs.CD_char
        TextTyp -> CD_String
        NichtsTyp -> ConstantDescs.CD_Object
        is DatensatzTyp -> ClassDesc.of(typ.name)
        is KlassenTyp -> ClassDesc.of(typ.name)
        is AufzählungTyp -> ClassDesc.of(typ.name)
        is SchnittstellenTyp -> ClassDesc.of(typ.name)
        is FunktionsTyp -> funktionsSchnittstelle(typ.parameter.size)
        is ListeTyp -> CD_List
        is AbbildungTyp -> CD_Map
        is PaarTyp -> { brauchtPaar = true; CD_EdelPaar }
        is ErgebnisTyp -> { brauchtErgebnis = true; CD_EdelErgebnis }
        is NullbarTyp -> when (val basis = typ.basis) {
            GanzzahlTyp -> CD_Long
            KommazahlTyp -> CD_Double
            WahrheitTyp -> CD_Boolean
            ZeichenTyp -> CD_Character
            TextTyp -> CD_String
            is DatensatzTyp -> ClassDesc.of(basis.name)
            is KlassenTyp -> ClassDesc.of(basis.name)
            is AufzählungTyp -> ClassDesc.of(basis.name)
            is SchnittstellenTyp -> ClassDesc.of(basis.name)
            is FunktionsTyp -> funktionsSchnittstelle(basis.parameter.size)
            is ListeTyp -> CD_List
            is AbbildungTyp -> CD_Map
            is PaarTyp -> { brauchtPaar = true; CD_EdelPaar }
            is ErgebnisTyp -> { brauchtErgebnis = true; CD_EdelErgebnis }
            else -> ablehnen("Der Typ '$typ' wird vom Bytecode-Backend nicht unterstuetzt", Position(0, 0))
        }
        else -> ablehnen("Der Typ '$typ' wird vom Bytecode-Backend nicht unterstuetzt", Position(0, 0))
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
        Art.NICHTS, Art.OBJEKT -> ConstantDescs.CD_Object
    }

    private fun artVon(typ: Typ, position: Position): Art = when (typ) {
        GanzzahlTyp -> Art.GANZ
        KommazahlTyp -> Art.KOMMA
        WahrheitTyp -> Art.WAHR
        ZeichenTyp -> Art.ZEICH
        TextTyp -> Art.TEXT
        NichtsTyp -> Art.NICHTS
        is DatensatzTyp -> Art.OBJEKT
        is KlassenTyp -> Art.OBJEKT
        is AufzählungTyp -> Art.OBJEKT
        is SchnittstellenTyp -> Art.OBJEKT
        is FunktionsTyp -> Art.OBJEKT
        is ListeTyp, is AbbildungTyp, is PaarTyp, is ErgebnisTyp -> Art.OBJEKT
        is NullbarTyp -> when (typ.basis) {
            GanzzahlTyp -> Art.N_GANZ
            KommazahlTyp -> Art.N_KOMMA
            WahrheitTyp -> Art.N_WAHR
            ZeichenTyp -> Art.N_ZEICH
            TextTyp -> Art.N_TEXT
            is DatensatzTyp -> Art.OBJEKT
            is KlassenTyp -> Art.OBJEKT
            is AufzählungTyp -> Art.OBJEKT
            is SchnittstellenTyp -> Art.OBJEKT
            is FunktionsTyp -> Art.OBJEKT
            is ListeTyp, is AbbildungTyp, is PaarTyp, is ErgebnisTyp -> Art.OBJEKT
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
        val CD_StringBuilder: ClassDesc = ClassDesc.of("java.lang.StringBuilder")
        val CD_Number: ClassDesc = ClassDesc.of("java.lang.Number")
        val CD_List: ClassDesc = ClassDesc.of("java.util.List")
        val CD_ArrayList: ClassDesc = ClassDesc.of("java.util.ArrayList")
        val CD_Map: ClassDesc = ClassDesc.of("java.util.Map")
        val CD_LinkedHashMap: ClassDesc = ClassDesc.of("java.util.LinkedHashMap")
        val CD_Collection: ClassDesc = ClassDesc.of("java.util.Collection")
        val CD_Set: ClassDesc = ClassDesc.of("java.util.Set")
        val CD_Iterator: ClassDesc = ClassDesc.of("java.util.Iterator")
        val CD_Arrays: ClassDesc = ClassDesc.of("java.util.Arrays")
        val CD_StringArray: ClassDesc = ClassDesc.ofDescriptor("[Ljava/lang/String;")
        val CD_ObjectArray: ClassDesc = ClassDesc.ofDescriptor("[Ljava/lang/Object;")
        val CD_CharSequence: ClassDesc = ClassDesc.of("java.lang.CharSequence")
        val CD_EdelPaar: ClassDesc = ClassDesc.of("EdelPaar")
        val CD_EdelErgebnis: ClassDesc = ClassDesc.of("EdelErgebnis")
        val CD_RuntimeException: ClassDesc = ClassDesc.of("java.lang.RuntimeException")

        /** Konstruktordeskriptor von EdelErgebnis: (Wahrheit, Wert, Meldung). */
        val CD_ergebnisKtor: MethodTypeDesc = MethodTypeDesc.of(
            ConstantDescs.CD_void, ConstantDescs.CD_boolean, ConstantDescs.CD_Object, CD_String,
        )

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
