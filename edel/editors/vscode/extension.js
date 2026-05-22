// LSP-Client der Edel-Erweiterung. Startet den Sprachserver (edel-lsp.jar) als
// JVM-Prozess und verbindet ihn ueber stdin/stdout mit VS Code.
const path = require("path");
const fs = require("fs");
const { workspace, window } = require("vscode");
const { LanguageClient, TransportKind } = require("vscode-languageclient/node");

let client;

/** Ermittelt den Pfad zu edel-lsp.jar aus der Einstellung oder dem Arbeitsordner. */
function findeServerJar(context) {
  const konfiguriert = workspace.getConfiguration("edel").get("server.jar");
  if (konfiguriert) {
    return fs.existsSync(konfiguriert) ? konfiguriert : null;
  }
  const ordner = workspace.workspaceFolders;
  if (ordner && ordner.length > 0) {
    const kandidat = path.join(ordner[0].uri.fsPath, "build", "libs", "edel-lsp.jar");
    if (fs.existsSync(kandidat)) return kandidat;
  }
  const gebuendelt = path.join(context.extensionPath, "server", "edel-lsp.jar");
  if (fs.existsSync(gebuendelt)) return gebuendelt;
  return null;
}

/** Ermittelt den auszufuehrenden java-Befehl. */
function javaBefehl() {
  const home = workspace.getConfiguration("edel").get("java.home") || process.env.JAVA_HOME;
  if (home) {
    const datei = process.platform === "win32" ? "java.exe" : "java";
    const bin = path.join(home, "bin", datei);
    if (fs.existsSync(bin)) return bin;
  }
  return "java";
}

function activate(context) {
  if (!workspace.getConfiguration("edel").get("server.enabled")) {
    return;
  }
  const jar = findeServerJar(context);
  if (!jar) {
    window.showWarningMessage(
      "Edel-Sprachserver nicht gefunden. Baue ihn mit './gradlew lspJar' " +
        "oder setze die Einstellung 'edel.server.jar'."
    );
    return;
  }
  const serverOptions = {
    command: javaBefehl(),
    args: ["-jar", jar],
    transport: TransportKind.stdio,
  };
  const clientOptions = {
    documentSelector: [{ scheme: "file", language: "edel" }],
  };
  client = new LanguageClient("edel", "Edel-Sprachserver", serverOptions, clientOptions);
  client.start();
}

function deactivate() {
  return client ? client.stop() : undefined;
}

module.exports = { activate, deactivate };
