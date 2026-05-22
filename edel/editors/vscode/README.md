# Edel für VS Code

Sprachunterstützung für **Edel** in Visual Studio Code: Syntaxhervorhebung sowie
ein vollwertiger Sprachserver (Language Server Protocol).

*Language support for Edel in VS Code: syntax highlighting plus a full
Language Server Protocol server.*

## Funktionen / Features

- **Syntaxhervorhebung** — über eine TextMate-Grammatik (kein Server nötig).
- **Diagnosen** — Syntax- und Typfehler werden beim Tippen unterstrichen.
- **Hover** — zeigt den statischen Typ einer Variablen bzw. die Signatur.
- **Gehe zu Definition** — springt zu Funktionen, Typen und lokalen Bindungen.
- **Gliederung** — Funktionen, Klassen, Datensätze, Aufzählungen.
- **Vervollständigung** — Schlüsselwörter, Typen und Namen im Geltungsbereich.

Die semantischen Funktionen liefert der Edel-Sprachserver; er nutzt direkt
den Edel-Compiler.

## Installation (Entwicklung)

1. Den Sprachserver bauen:
   ```bash
   ./gradlew lspJar      # erzeugt build/libs/edel-lsp.jar
   ```
2. Die Abhängigkeiten der Erweiterung installieren:
   ```bash
   cd editors/vscode && npm install
   ```
3. Den Ordner `editors/vscode` in VS Code öffnen und mit **F5** eine
   Entwicklungsinstanz starten. `.edel`-Dateien werden dort unterstützt.

Der Server wird automatisch unter `build/libs/edel-lsp.jar` im Arbeitsordner
gesucht. Über die Einstellung `edel.server.jar` lässt sich ein anderer Pfad
angeben, über `edel.java.home` ein bestimmtes JDK.

## Einstellungen / Settings

| Schlüssel | Vorgabe | Bedeutung |
|---|---|---|
| `edel.server.enabled` | `true` | Sprachserver starten |
| `edel.server.jar` | `""` | Pfad zu `edel-lsp.jar` (leer = automatisch suchen) |
| `edel.java.home` | `""` | JDK für den Server (leer = `java` aus dem `PATH`) |

Voraussetzung: ein installiertes JDK (Java 24+), damit der Server läuft.
