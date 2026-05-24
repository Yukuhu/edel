package edel.lsp

import edel.fehler.Position as EdelPosition
import edel.parser.AufrufAusdruck
import edel.parser.AufzählungDeklaration
import edel.parser.Ausdruck
import edel.parser.AusdruckAnweisung
import edel.parser.Anweisung
import edel.parser.BinärAusdruck
import edel.parser.Bezeichner
import edel.parser.Block
import edel.parser.BrichAnweisung
import edel.parser.DatensatzDeklaration
import edel.parser.DiesAusdruck
import edel.parser.EinfacherTypausdruck
import edel.parser.ElvisAusdruck
import edel.parser.FeldzugriffAusdruck
import edel.parser.FunktionDeklaration
import edel.parser.FunktionsTypausdruck
import edel.parser.FürInAnweisung
import edel.parser.FürVonBisAnweisung
import edel.parser.IndexAusdruck
import edel.parser.KlasseDeklaration
import edel.parser.LambdaAusdruck
import edel.parser.NeuAusdruck
import edel.parser.NichtNullAusdruck
import edel.parser.NullbarTypausdruck
import edel.parser.Programm
import edel.parser.SchnittstelleDeklaration
import edel.parser.SeiAnweisung
import edel.parser.SolangeAnweisung
import edel.parser.Typausdruck
import edel.parser.UnärAusdruck
import edel.parser.WeiterAnweisung
import edel.parser.WennAnweisung
import edel.parser.WähleAusdruck
import edel.parser.ZurückAnweisung
import edel.parser.ZuweisungAnweisung
import org.eclipse.lsp4j.Position as LspPosition
import org.eclipse.lsp4j.Range

// ===========================================================================
// Positionsumrechnung
// ===========================================================================
// Edel-Positionen sind 1-basiert (Zeile/Spalte), LSP-Positionen 0-basiert.

internal fun istNamensZeichen(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

/** Edel-Position -> LSP-Position. */
internal fun zuLsp(p: EdelPosition): LspPosition =
    LspPosition(maxOf(0, p.zeile - 1), maxOf(0, p.spalte - 1))

/** LSP-Position -> Edel-Position. */
internal fun zuEdel(p: LspPosition): EdelPosition =
    EdelPosition(p.line + 1, p.character + 1)

/** Liefert den Bereich des Namens (oder mindestens eines Zeichens), der bei [p] beginnt. */
internal fun namensBereich(text: String, p: EdelPosition): Range {
    val start = zuLsp(p)
    val zeilen = text.split("\n")
    val zi = p.zeile - 1
    if (zi !in zeilen.indices) return Range(start, LspPosition(start.line, start.character + 1))
    val zeile = zeilen[zi]
    var ende = p.spalte - 1
    while (ende < zeile.length && istNamensZeichen(zeile[ende])) ende++
    if (ende <= p.spalte - 1) ende = minOf(zeile.length, p.spalte)
    return Range(start, LspPosition(zi, ende))
}

/** Sucht ab der (Schluesselwort-)Position [ab] das naechste ganze Wort [name]. */
internal fun namensPosition(text: String, ab: EdelPosition, name: String): EdelPosition {
    val zeilen = text.split("\n")
    val zi = ab.zeile - 1
    if (zi !in zeilen.indices || name.isEmpty()) return ab
    val zeile = zeilen[zi]
    var idx = zeile.indexOf(name, maxOf(0, ab.spalte - 1))
    while (idx >= 0) {
        val vorOk = idx == 0 || !istNamensZeichen(zeile[idx - 1])
        val nach = idx + name.length
        val nachOk = nach >= zeile.length || !istNamensZeichen(zeile[nach])
        if (vorOk && nachOk) return EdelPosition(ab.zeile, idx + 1)
        idx = zeile.indexOf(name, idx + 1)
    }
    return ab
}

/** Enthaelt der Name [name] ab [p] die Zielposition [ziel]? */
internal fun spanneEnthält(p: EdelPosition, name: String, ziel: EdelPosition): Boolean =
    p.zeile == ziel.zeile && ziel.spalte >= p.spalte && ziel.spalte <= p.spalte + name.length

internal fun vorOderGleich(a: EdelPosition, b: EdelPosition): Boolean =
    a.zeile < b.zeile || (a.zeile == b.zeile && a.spalte <= b.spalte)

// ===========================================================================
// Baumdurchlauf
// ===========================================================================

/** Ruft [f] fuer diesen Ausdruck und rekursiv jeden Teilausdruck auf. */
internal fun jedeAusdruck(a: Ausdruck, f: (Ausdruck) -> Unit) {
    f(a)
    when (a) {
        is UnärAusdruck -> jedeAusdruck(a.operand, f)
        is BinärAusdruck -> { jedeAusdruck(a.links, f); jedeAusdruck(a.rechts, f) }
        is AufrufAusdruck -> { jedeAusdruck(a.ziel, f); a.argumente.forEach { jedeAusdruck(it, f) } }
        is IndexAusdruck -> { jedeAusdruck(a.ziel, f); jedeAusdruck(a.index, f) }
        is FeldzugriffAusdruck -> jedeAusdruck(a.ziel, f)
        is ElvisAusdruck -> { jedeAusdruck(a.links, f); jedeAusdruck(a.rechts, f) }
        is NichtNullAusdruck -> jedeAusdruck(a.operand, f)
        is NeuAusdruck -> a.argumente.forEach { jedeAusdruck(it, f) }
        is LambdaAusdruck -> jedeAusdruck(a.körper, f)
        is WähleAusdruck -> {
            jedeAusdruck(a.subjekt, f)
            a.fälle.forEach { jedeAusdruck(it.muster, f); jedeAusdruck(it.ergebnis, f) }
            jedeAusdruck(a.sonst, f)
        }
        is Bezeichner, is DiesAusdruck -> {}
        else -> {} // Literale
    }
}

/** Ruft [fAnw]/[fAus] fuer jede Anweisung bzw. jeden Ausdruck im Teilbaum auf. */
internal fun jedeAnweisung(s: Anweisung, fAnw: (Anweisung) -> Unit, fAus: (Ausdruck) -> Unit) {
    fAnw(s)
    when (s) {
        is SeiAnweisung -> jedeAusdruck(s.initialwert, fAus)
        is AusdruckAnweisung -> jedeAusdruck(s.ausdruck, fAus)
        is ZuweisungAnweisung -> { jedeAusdruck(s.ziel, fAus); jedeAusdruck(s.wert, fAus) }
        is WennAnweisung -> {
            jedeAusdruck(s.bedingung, fAus)
            jedeAnweisung(s.dann, fAnw, fAus)
            s.sonst?.let { jedeAnweisung(it, fAnw, fAus) }
        }
        is SolangeAnweisung -> { jedeAusdruck(s.bedingung, fAus); jedeAnweisung(s.körper, fAnw, fAus) }
        is FürInAnweisung -> { jedeAusdruck(s.iterierbar, fAus); jedeAnweisung(s.körper, fAnw, fAus) }
        is FürVonBisAnweisung -> {
            jedeAusdruck(s.von, fAus)
            jedeAusdruck(s.bis, fAus)
            jedeAnweisung(s.körper, fAnw, fAus)
        }
        is ZurückAnweisung -> s.wert?.let { jedeAusdruck(it, fAus) }
        is Block -> s.anweisungen.forEach { jedeAnweisung(it, fAnw, fAus) }
        is BrichAnweisung, is WeiterAnweisung -> {}
    }
}

/** Ruft [f] fuer diesen Typausdruck und rekursiv jeden Teiltyp auf. */
internal fun jedeTypausdruck(t: Typausdruck, f: (Typausdruck) -> Unit) {
    f(t)
    when (t) {
        is EinfacherTypausdruck -> t.typargumente.forEach { jedeTypausdruck(it, f) }
        is FunktionsTypausdruck -> {
            t.parameter.forEach { jedeTypausdruck(it, f) }
            jedeTypausdruck(t.rückgabe, f)
        }
        is NullbarTypausdruck -> jedeTypausdruck(t.basis, f)
    }
}

// ===========================================================================
// Abfragen ueber das gesamte Programm
// ===========================================================================

/** Alle Funktionen samt Klassenmethoden. */
internal fun alleFunktionen(programm: Programm): List<FunktionDeklaration> {
    val raus = mutableListOf<FunktionDeklaration>()
    for (d in programm.deklarationen) {
        when (d) {
            is FunktionDeklaration -> raus.add(d)
            is KlasseDeklaration -> raus.addAll(d.methoden)
            else -> {}
        }
    }
    return raus
}

/** Alle Namensverwendungen (Bezeichner) im Programm. */
internal fun alleBezeichner(programm: Programm): List<Bezeichner> {
    val raus = mutableListOf<Bezeichner>()
    for (f in alleFunktionen(programm)) {
        jedeAnweisung(f.körper, {}) { a -> if (a is Bezeichner) raus.add(a) }
    }
    return raus
}

/** Sucht die Funktion, in deren Koerper der Bezeichner [ziel] vorkommt. */
internal fun funktionVon(programm: Programm, ziel: Bezeichner): FunktionDeklaration? {
    for (f in alleFunktionen(programm)) {
        var gefunden = false
        jedeAnweisung(f.körper, {}) { a -> if (a === ziel) gefunden = true }
        if (gefunden) return f
    }
    return null
}

/** Eine lokale Bindung: Parameter, `sei`/`ver` oder Schleifenvariable. */
internal class Bindung(val name: String, val position: EdelPosition)

/** Alle in [f] sichtbaren lokalen Bindungen. */
internal fun lokaleBindungen(f: FunktionDeklaration): List<Bindung> {
    val raus = mutableListOf<Bindung>()
    f.parameter.forEach { raus.add(Bindung(it.name, it.position)) }
    jedeAnweisung(f.körper, { s ->
        when (s) {
            is SeiAnweisung -> raus.add(Bindung(s.name, s.position))
            is FürInAnweisung -> raus.add(Bindung(s.variable, s.position))
            is FürVonBisAnweisung -> raus.add(Bindung(s.variable, s.position))
            else -> {}
        }
    }) { a ->
        if (a is LambdaAusdruck) a.parameter.forEach { raus.add(Bindung(it.name, it.position)) }
    }
    return raus
}

/** Heuristik: die zuletzt vor [p] beginnende Funktion. */
internal fun umschließendeFunktion(programm: Programm, p: EdelPosition): FunktionDeklaration? =
    alleFunktionen(programm)
        .filter { it.position.zeile <= p.zeile }
        .maxByOrNull { it.position.zeile }

/**
 * Eine Typverwendung im Quelltext: Kurzname [name] und (falls vom Resolver
 * aufgeloest) der FQN [aufgelöst], unter dem die Deklaration registriert ist.
 */
internal class TypVerwendung(
    val name: String,
    val position: EdelPosition,
    val aufgelöst: String? = null,
)

/** Alle Typverwendungen: Annotationen, Rueckgabetypen, Felder und `neu`-Ausdruecke. */
internal fun alleTypVerwendungen(programm: Programm, text: String): List<TypVerwendung> {
    val raus = mutableListOf<TypVerwendung>()
    fun ausTyp(t: Typausdruck?) {
        if (t == null) return
        jedeTypausdruck(t) { tt ->
            if (tt is EinfacherTypausdruck) raus.add(TypVerwendung(tt.name, tt.position, tt.aufgelöst))
        }
    }
    fun ausBlock(b: Block) {
        jedeAnweisung(b, { s -> if (s is SeiAnweisung) ausTyp(s.typannotation) }) { a ->
            if (a is LambdaAusdruck) a.parameter.forEach { ausTyp(it.typ) }
            if (a is NeuAusdruck) {
                raus.add(TypVerwendung(a.typname, namensPosition(text, a.position, a.typname), a.aufgelöst))
            }
        }
    }
    for (d in programm.deklarationen) {
        when (d) {
            is FunktionDeklaration -> {
                d.parameter.forEach { ausTyp(it.typ) }
                ausTyp(d.rückgabetyp)
                ausBlock(d.körper)
            }
            is DatensatzDeklaration -> d.felder.forEach { ausTyp(it.typ) }
            is KlasseDeklaration -> {
                d.felder.forEach { ausTyp(it.typ) }
                for (m in d.methoden) {
                    m.parameter.forEach { ausTyp(it.typ) }
                    ausTyp(m.rückgabetyp)
                    ausBlock(m.körper)
                }
            }
            is SchnittstelleDeklaration -> for (m in d.methoden) {
                m.parameter.forEach { ausTyp(it.typ) }
                ausTyp(m.rückgabetyp)
            }
            else -> {}
        }
    }
    return raus
}
