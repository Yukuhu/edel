package edel.lsp

import edel.AnalyseErgebnis
import edel.analysiere
import edel.lexer.SCHLÜSSELWÖRTER
import edel.parser.AufzählungDeklaration
import edel.parser.DatensatzDeklaration
import edel.parser.Deklaration
import edel.parser.EinfacherTypausdruck
import edel.parser.FunktionDeklaration
import edel.parser.FunktionsTypausdruck
import edel.parser.KlasseDeklaration
import edel.parser.NullbarTypausdruck
import edel.parser.Programm
import edel.parser.SchnittstelleDeklaration
import edel.parser.Typausdruck
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Eingebaute Typnamen und Funktionen, die der Editor stets kennt. */
private val EINGEBAUTE_TYPEN =
    listOf("Ganzzahl", "Kommazahl", "Text", "Wahrheit", "Zeichen", "Nichts", "Liste", "Abbildung", "Paar")
private val EINGEBAUTE_FUNKTIONEN = listOf("drucke", "lies", "länge")

/**
 * Bearbeitet alle dokumentbezogenen LSP-Anfragen. Jede Anfrage fuehrt die
 * statische Analyse des Edel-Compilers aus; das Ergebnis wird je Dokument
 * zwischengespeichert, solange sich der Text nicht aendert.
 */
class EdelDokumentdienst : TextDocumentService {
    var klient: LanguageClient? = null

    private val inhalte = ConcurrentHashMap<String, String>()
    private val zwischenspeicher = ConcurrentHashMap<String, Pair<String, AnalyseErgebnis>>()

    private fun analyse(uri: String): AnalyseErgebnis? {
        val text = inhalte[uri] ?: return null
        zwischenspeicher[uri]?.let { (gespeichert, ergebnis) ->
            if (gespeichert == text) return ergebnis
        }
        val ergebnis = try {
            analysiere(text)
        } catch (fehler: Throwable) {
            return null
        }
        zwischenspeicher[uri] = text to ergebnis
        return ergebnis
    }

    // ---- Lebenszyklus des Dokuments ----------------------------------------

    override fun didOpen(params: DidOpenTextDocumentParams) {
        inhalte[params.textDocument.uri] = params.textDocument.text
        veröffentlicheDiagnosen(params.textDocument.uri)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        // Voller Synchronisationsmodus: die letzte Aenderung traegt den ganzen Text.
        params.contentChanges.lastOrNull()?.let {
            inhalte[params.textDocument.uri] = it.text
        }
        veröffentlicheDiagnosen(params.textDocument.uri)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        inhalte.remove(uri)
        zwischenspeicher.remove(uri)
        klient?.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
    }

    override fun didSave(params: DidSaveTextDocumentParams) {}

    private fun veröffentlicheDiagnosen(uri: String) {
        val text = inhalte[uri] ?: return
        val ergebnis = analyse(uri) ?: return
        val diagnosen = ergebnis.diagnosen.map { d ->
            Diagnostic(namensBereich(text, d.position), d.meldung, DiagnosticSeverity.Error, "edel")
        }
        klient?.publishDiagnostics(PublishDiagnosticsParams(uri, diagnosen))
    }

    // ---- Hover --------------------------------------------------------------

    override fun hover(params: HoverParams): CompletableFuture<Hover?> {
        val uri = params.textDocument.uri
        val text = inhalte[uri]
        val ergebnis = analyse(uri)
        val programm = ergebnis?.programm
        if (text == null || programm == null) return CompletableFuture.completedFuture(null)
        val ziel = zuEdel(params.position)

        val bezeichner = alleBezeichner(programm)
            .firstOrNull { spanneEnthält(it.position, it.name, ziel) }
        if (bezeichner != null) {
            val typ = ergebnis.bezeichnerTypen[bezeichner]
            val inhalt = if (typ != null) "${bezeichner.name}: ${typ.anzeige()}" else bezeichner.name
            return CompletableFuture.completedFuture(
                hover(inhalt, namensBereich(text, bezeichner.position)),
            )
        }

        val typverwendung = alleTypVerwendungen(programm, text)
            .firstOrNull { spanneEnthält(it.position, it.name, ziel) }
        if (typverwendung != null) {
            return CompletableFuture.completedFuture(
                hover("Typ ${typverwendung.name}", namensBereich(text, typverwendung.position)),
            )
        }

        val deklaration = deklarationAn(programm, text, ziel)
        if (deklaration != null) {
            val (name, beschreibung) = deklaration
            return CompletableFuture.completedFuture(
                hover(beschreibung, namensBereich(text, name)),
            )
        }
        return CompletableFuture.completedFuture(null)
    }

    private fun hover(inhalt: String, bereich: Range): Hover =
        Hover(MarkupContent(MarkupKind.MARKDOWN, "```edel\n$inhalt\n```"), bereich)

    // ---- Sprung zur Definition ---------------------------------------------

    override fun definition(
        params: DefinitionParams,
    ): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>?> {
        val uri = params.textDocument.uri
        val text = inhalte[uri]
        val programm = analyse(uri)?.programm
        if (text == null || programm == null) return CompletableFuture.completedFuture(null)
        val ziel = zuEdel(params.position)

        val bezeichner = alleBezeichner(programm)
            .firstOrNull { spanneEnthält(it.position, it.name, ziel) }
        if (bezeichner != null) {
            val funktion = funktionVon(programm, bezeichner)
            if (funktion != null) {
                val bindung = lokaleBindungen(funktion)
                    .filter { it.name == bezeichner.name }
                    .let { treffer ->
                        treffer.filter { vorOderGleich(it.position, bezeichner.position) }
                            .maxByOrNull { it.position.zeile * 10000 + it.position.spalte }
                            ?: treffer.firstOrNull()
                    }
                if (bindung != null) {
                    val pos = namensPosition(text, bindung.position, bindung.name)
                    return treffer(uri, namensBereich(text, pos))
                }
            }
            val global = programm.deklarationen.filterIsInstance<FunktionDeklaration>()
                .firstOrNull { it.name == bezeichner.name }
            if (global != null) {
                val pos = namensPosition(text, global.position, global.name)
                return treffer(uri, namensBereich(text, pos))
            }
        }

        val typverwendung = alleTypVerwendungen(programm, text)
            .firstOrNull { spanneEnthält(it.position, it.name, ziel) }
        if (typverwendung != null) {
            val typdekl = programm.deklarationen
                .firstOrNull { it.name == typverwendung.name && it !is FunktionDeklaration }
            if (typdekl != null) {
                val pos = namensPosition(text, typdekl.position, typdekl.name)
                return treffer(uri, namensBereich(text, pos))
            }
        }
        return CompletableFuture.completedFuture(null)
    }

    private fun treffer(
        uri: String,
        bereich: Range,
    ): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>?> {
        val orte: MutableList<out Location> = mutableListOf(Location(uri, bereich))
        return CompletableFuture.completedFuture(Either.forLeft(orte))
    }

    // ---- Gliederung (Document Symbols) -------------------------------------

    override fun documentSymbol(
        params: DocumentSymbolParams,
    ): CompletableFuture<MutableList<Either<SymbolInformation, DocumentSymbol>>> {
        val uri = params.textDocument.uri
        val text = inhalte[uri]
        val programm = analyse(uri)?.programm
        val symbole = mutableListOf<Either<SymbolInformation, DocumentSymbol>>()
        if (text == null || programm == null) return CompletableFuture.completedFuture(symbole)

        for (d in programm.deklarationen) {
            val symbol = when (d) {
                is FunktionDeklaration ->
                    symbol(text, d.name, d.position, SymbolKind.Function, signatur(d))
                is DatensatzDeklaration -> symbol(
                    text, d.name, d.position, SymbolKind.Struct, "datensatz",
                    d.felder.map { feld ->
                        symbol(text, feld.name, feld.position, SymbolKind.Field, typText(feld.typ))
                    },
                )
                is KlasseDeklaration -> symbol(
                    text, d.name, d.position, SymbolKind.Class, "klasse",
                    d.felder.map { feld ->
                        symbol(text, feld.name, feld.position, SymbolKind.Field, typText(feld.typ))
                    } + d.methoden.map { methode ->
                        symbol(text, methode.name, methode.position, SymbolKind.Method, signatur(methode))
                    },
                )
                is AufzählungDeklaration -> symbol(
                    text, d.name, d.position, SymbolKind.Enum, "aufzählung",
                    d.varianten.map { variante ->
                        // Varianten tragen keine eigene Position; sie verweisen auf den Kopf.
                        symbol(text, variante, d.position, SymbolKind.EnumMember, "Variante")
                    },
                )
                is SchnittstelleDeklaration -> symbol(
                    text, d.name, d.position, SymbolKind.Interface, "schnittstelle",
                    d.methoden.map { methode ->
                        symbol(text, methode.name, methode.position, SymbolKind.Method, "funktion")
                    },
                )
            }
            symbole.add(Either.forRight(symbol))
        }
        return CompletableFuture.completedFuture(symbole)
    }

    private fun symbol(
        text: String,
        name: String,
        kopfPosition: edel.fehler.Position,
        art: SymbolKind,
        detail: String,
        kinder: List<DocumentSymbol> = emptyList(),
    ): DocumentSymbol {
        val bereich = namensBereich(text, namensPosition(text, kopfPosition, name))
        val symbol = DocumentSymbol(name, art, bereich, bereich, detail)
        if (kinder.isNotEmpty()) symbol.children = kinder
        return symbol
    }

    // ---- Vervollstaendigung -------------------------------------------------

    override fun completion(
        params: CompletionParams,
    ): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
        val eintraege = mutableListOf<CompletionItem>()
        SCHLÜSSELWÖRTER.keys.forEach { eintraege.add(eintrag(it, CompletionItemKind.Keyword)) }
        EINGEBAUTE_TYPEN.forEach { eintraege.add(eintrag(it, CompletionItemKind.Class)) }
        EINGEBAUTE_FUNKTIONEN.forEach { eintraege.add(eintrag(it, CompletionItemKind.Function)) }

        val programm = analyse(params.textDocument.uri)?.programm
        if (programm != null) {
            for (d in programm.deklarationen) {
                when (d) {
                    is FunktionDeklaration -> eintraege.add(eintrag(d.name, CompletionItemKind.Function))
                    is KlasseDeklaration -> eintraege.add(eintrag(d.name, CompletionItemKind.Class))
                    is DatensatzDeklaration -> eintraege.add(eintrag(d.name, CompletionItemKind.Struct))
                    is SchnittstelleDeklaration -> eintraege.add(eintrag(d.name, CompletionItemKind.Interface))
                    is AufzählungDeklaration -> {
                        eintraege.add(eintrag(d.name, CompletionItemKind.Enum))
                        d.varianten.forEach { eintraege.add(eintrag(it, CompletionItemKind.EnumMember)) }
                    }
                }
            }
            umschließendeFunktion(programm, zuEdel(params.position))?.let { funktion ->
                lokaleBindungen(funktion).forEach {
                    eintraege.add(eintrag(it.name, CompletionItemKind.Variable))
                }
            }
        }
        return CompletableFuture.completedFuture(Either.forLeft(eintraege))
    }

    private fun eintrag(name: String, art: CompletionItemKind): CompletionItem {
        val eintrag = CompletionItem(name)
        eintrag.kind = art
        return eintrag
    }

    // ---- Hilfsfunktionen ----------------------------------------------------

    /** Liefert (Namensposition, Beschreibung) der Deklaration, die [ziel] traegt. */
    private fun deklarationAn(
        programm: Programm,
        text: String,
        ziel: edel.fehler.Position,
    ): Pair<edel.fehler.Position, String>? {
        for (d in programm.deklarationen) {
            val pos = namensPosition(text, d.position, d.name)
            if (spanneEnthält(pos, d.name, ziel)) {
                return pos to beschreibung(d)
            }
        }
        return null
    }

    private fun beschreibung(d: Deklaration): String = when (d) {
        is FunktionDeklaration -> signatur(d)
        is DatensatzDeklaration -> "datensatz ${d.name}"
        is KlasseDeklaration -> "klasse ${d.name}"
        is AufzählungDeklaration -> "aufzählung ${d.name}"
        is SchnittstelleDeklaration -> "schnittstelle ${d.name}"
    }

    private fun signatur(f: FunktionDeklaration): String {
        val parameter = f.parameter.joinToString(", ") { "${it.name}: ${typText(it.typ)}" }
        val rückgabe = f.rückgabetyp?.let { ": ${typText(it)}" } ?: ""
        return "funktion ${f.name}($parameter)$rückgabe"
    }

    private fun typText(t: Typausdruck): String = when (t) {
        is EinfacherTypausdruck ->
            if (t.typargumente.isEmpty()) {
                t.name
            } else {
                "${t.name}<${t.typargumente.joinToString(", ") { typText(it) }}>"
            }
        is NullbarTypausdruck -> "${typText(t.basis)}?"
        is FunktionsTypausdruck ->
            "(${t.parameter.joinToString(", ") { typText(it) }}) -> ${typText(t.rückgabe)}"
    }
}
