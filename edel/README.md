# Edel

**Edel** ist eine statisch typisierte, multiparadigmatische Programmiersprache,
deren sämtliche Schlüsselwörter deutsch sind. Sie läuft auf der JVM (Java 25+)
und wird von einem baumdurchlaufenden Interpreter ausgeführt.

*Edel is a statically typed, multi-paradigm programming language whose every
keyword is German. It runs on the JVM (Java 25+) via a tree-walking interpreter.*

---

## Bauen & Ausführen / Build & run

```bash
./gradlew build          # kompiliert, testet, erzeugt build/libs/edel.jar
./bin/edel starte beispiele/fakultät.edel
```

Befehle des `edel`-Werkzeugs / commands of the `edel` tool:

| Befehl | Wirkung |
|---|---|
| `edel starte <datei.edel>` | typprüfen **und** ausführen / type-check **and** run |
| `edel prüfe <datei.edel>` | nur typprüfen, Exitcode ≠ 0 bei Fehlern / type-check only |
| `edel version` | Versionsnummer / version |
| `edel hilfe` | Hilfetext / help |

Die Implementierung ist in idiomatischem Kotlin geschrieben; nur die *Sprache
Edel selbst* ist deutsch.

---

## Sprachführung / Language tour

### Bindungen / Bindings

`sei` bindet unveränderlich, `ver` (von *veränderlich*) bindet veränderlich.
Lokale Typen werden aus dem Anfangswert abgeleitet.

```
sei name = "Edel"     // unveränderlich / immutable
ver zähler = 0        // veränderlich / mutable
zähler = zähler + 1
```

### Funktionen / Functions

Parametertypen und Rückgabetyp sind explizit. `start()` ist der Einstiegspunkt.

```
funktion quadrat(n: Ganzzahl): Ganzzahl {
    zurück n * n
}

funktion start() {
    drucke(quadrat(9))
}
```

### Kontrollfluss / Control flow

```
wenn n <= 1 { zurück 1 } sonst wenn n < 10 { drucke("klein") } sonst { drucke("groß") }

solange zähler < 3 { zähler = zähler + 1 }

für x in Liste(1, 2, 3) { drucke(x) }   // über eine Sammlung
für i von 1 bis 10 { drucke(i) }        // Zahlenbereich, beide Grenzen inklusive
```

`brich` bricht eine Schleife ab, `weiter` springt zur nächsten Iteration.

### Datenstrukturen / Data structures

```
datensatz Punkt(x: Ganzzahl, y: Ganzzahl)          // unveränderlicher Verbund

aufzählung Ampel { Rot, Gelb, Grün }                // Aufzählung

klasse Konto {                                      // veränderliches Objekt
    ver stand: Ganzzahl                             // Feld ohne Wert -> Konstruktorparameter
    funktion einzahlen(betrag: Ganzzahl) {
        dies.stand = dies.stand + betrag
    }
}

schnittstelle Form { funktion fläche(): Kommazahl } // Schnittstelle
klasse Kreis erfüllt Form { /* ... */ }
klasse Quadrat erweitert Rechteck { /* ... */ }     // Einfachvererbung
```

Erzeugt werden Datensätze und Objekte mit `neu`: `neu Punkt(3, 4)`.

### Funktionen als Werte & Musterabgleich / First-class functions & matching

```
sei verdopple = (n: Ganzzahl) -> n * 2

sei text = wähle note {
    fall 1 -> "sehr gut"
    fall 2 -> "gut"
    sonst  -> "..."
}
```

`wähle` ist ein Ausdruck und verlangt stets einen `sonst`-Zweig.

### Eingebaute Typen / Built-in types

`Ganzzahl` (64-Bit), `Kommazahl` (64-Bit), `Text`, `Wahrheit`, `Zeichen`,
`Nichts`, sowie `Liste<T>`, `Abbildung<S,T>` und `Paar<A,B>`.

```
sei zahlen = Liste(1, 2, 3)
zahlen.hinzufügen(4)
sei noten = Abbildung(Paar("Anna", 1), Paar("Bert", 3))
drucke(noten.holen("Anna"))
```

Eingebaute Funktionen: `drucke` (ausgeben), `lies` (Zeile einlesen),
`länge` (Länge von Text/Liste/Abbildung).

### Schlüsselwörter / Keywords

`sei ver funktion zurück wenn sonst solange für in von bis brich weiter
wähle fall wahr falsch nichts und oder nicht klasse datensatz aufzählung
schnittstelle neu dies erweitert erfüllt öffentlich privat geschützt
statisch importiere paket`

---

## Projektaufbau / Project layout

```
src/main/kotlin/edel/
  Main.kt          CLI: starte / prüfe / version
  lexer/           Token, TokenTyp, Lexer (UTF-8, Umlaute in Bezeichnern)
  parser/          Ast (versiegelte Hierarchie), Parser (recursive descent)
  semantik/        Typ, Resolver, Typpruefer (statische Prüfung + Typinferenz)
  laufzeit/        Wert, Umgebung, Interpreter (Baumdurchlauf)
  fehler/          Diagnose (quellortbezogene Fehlermeldungen)
src/test/kotlin/   JUnit-5-Tests je Phase + Golden-Output-Tests
beispiele/         Beispielprogramme (*.edel) mit erwarteter Ausgabe (*.aus)
```

## Phase 2 — Bytecode-Backend (geplant)

Die nächste Ausbaustufe ergänzt `src/main/kotlin/edel/codegen/` und nutzt die
Standardbibliotheks-**Class-File-API** (`java.lang.classfile`, JEP 484), um aus
demselben typgeprüften Syntaxbaum `.class`-Dateien zu erzeugen — ein neuer
Befehl `edel übersetze`. Lexer, Parser und Typprüfer bleiben unverändert; es
kommt nur ein zweites Backend hinzu.
