package edel.lsp

import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

/**
 * Der Edel-Sprachserver: stellt einem Editor ueber das Language Server Protocol
 * statische Analyse zur Verfuegung (Diagnosen, Hover, Sprung zur Definition,
 * Gliederung und Vervollstaendigung). Er nutzt direkt den Edel-Compiler.
 */
class EdelSprachserver : LanguageServer, LanguageClientAware {
    private val dokumentdienst = EdelDokumentdienst()
    private val arbeitsraumdienst = EdelArbeitsraumdienst()

    override fun connect(client: LanguageClient) {
        dokumentdienst.klient = client
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val fähigkeiten = ServerCapabilities().apply {
            setTextDocumentSync(TextDocumentSyncKind.Full)
            hoverProvider = Either.forLeft(true)
            definitionProvider = Either.forLeft(true)
            documentSymbolProvider = Either.forLeft(true)
            completionProvider = CompletionOptions()
        }
        val ergebnis = InitializeResult(fähigkeiten)
        ergebnis.serverInfo = ServerInfo("Edel-Sprachserver", "0.1.0")
        return CompletableFuture.completedFuture(ergebnis)
    }

    override fun shutdown(): CompletableFuture<Any> = CompletableFuture.completedFuture(null)

    override fun exit() {}

    override fun getTextDocumentService(): TextDocumentService = dokumentdienst

    override fun getWorkspaceService(): WorkspaceService = arbeitsraumdienst
}

/** Minimaler Arbeitsraum-Dienst: Edel kennt (noch) keine projektweiten Einstellungen. */
class EdelArbeitsraumdienst : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {}
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {}
}

/** Einstiegspunkt: startet den Server und kommuniziert ueber stdin/stdout (JSON-RPC). */
fun main() {
    val server = EdelSprachserver()
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out)
    server.connect(launcher.remoteProxy)
    launcher.startListening().get()
}
