# Edel

**Edel** ist eine statisch typisierte, multiparadigmatische Programmiersprache,
deren sämtliche Schlüsselwörter deutsch sind. Sie läuft auf der JVM (Java 25+)
und wird von einem baumdurchlaufenden Interpreter ausgeführt.

*Edel is a statically typed, multi-paradigm programming language whose every
keyword is German. It runs on the JVM (Java 25+) via a tree-walking interpreter.*

---

## Bauen & Ausführen / Build & run

```bash
./gradlew build           # kompiliert, testet, erzeugt build/libs/edel.jar
./gradlew nativeCompile   # baut das edel-Werkzeug als GraalVM-Binärprogramm
./bin/edel starte beispiele/fakultät.edel
```

`./gradlew nativeCompile` (Aufgabe des offiziellen GraalVM-Plugins) erzeugt das
Binärprogramm unter `build/native/nativeCompile/edel`. `bin/edel` benutzt dieses
native Programm automatisch, falls vorhanden, sonst das Jar. *Run `./gradlew nativeCompile` once and the `edel` tool itself becomes a
native GraalVM executable.*

Befehle des `edel`-Werkzeugs / commands of the `edel` tool:

| Befehl | Wirkung |
|---|---|
| `edel starte <datei.edel>` | typprüfen **und** ausführen / type-check **and** run |
| `edel prüfe <datei.edel>` | nur typprüfen, Exitcode ≠ 0 bei Fehlern / type-check only |
| `edel übersetze <datei.edel>` | zu einer JVM-`.class`-Datei kompilieren / compile to a `.class` |
| `edel binär <datei.edel>` | zu einem **nativen Programm** kompilieren (GraalVM) / compile to a **native executable** |
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
`Nichts`, sowie `Liste<T>`, `Abbildung<S,T>`, `Paar<A,B>` und `Ergebnis<T>`
(siehe [Fehlerbehandlung](#fehlerbehandlung--error-handling)).

```
sei zahlen = Liste(1, 2, 3)
zahlen.hinzufügen(4)
sei noten = Abbildung(Paar("Anna", 1), Paar("Bert", 3))
drucke(noten.holen("Anna"))
```

Eingebaute Funktionen: `drucke` (ausgeben), `lies` (Zeile einlesen),
`länge` (Länge von Text/Liste/Abbildung).

### Nullsicherheit / Null safety

Edel ist **nullsicher nach Kotlin-Vorbild**: Typen sind standardmäßig
*nicht-nullbar*. Nur ein nullbarer Typ `T?` darf den Wert `nichts` annehmen, und
der Compiler verhindert, dass ein möglicherweise-`nichts`-Wert unsicher
verwendet wird.

```
sei name: Text  = "Edel"     // nie nichts
sei rest: Text? = nichts     // darf nichts sein
drucke(rest.länge())          // FEHLER: rest kann nichts sein
```

Drei Operatoren — wie in Kotlin — entschärfen einen nullbaren Wert:

```
rest?.länge()        // sicherer Aufruf  -> Ganzzahl?  (nichts, falls rest nichts ist)
rest ?: "leer"       // Elvis            -> Ersatzwert, falls rest nichts ist
rest!!               // Nicht-null-Zusicherung — wirft zur Laufzeit, falls nichts
```

**Smart Cast.** Nach einer Nullprüfung gilt eine stabile Variable (`sei` oder
Parameter) im betreffenden Zweig als nicht-nullbar — auch in `und`-Ketten und
nach einer abbrechenden Frühprüfung:

```
wenn name != nichts und name.länge() > 0 { ... }   // name rechts nicht-nullbar
wenn x == nichts { zurück }                         // danach ist x nicht-nullbar
```

*Types are non-nullable by default; `T?` is the nullable form. `?.`, `?:` and
`!!` work as in Kotlin, and the type checker smart-casts a stable variable to
non-null after a null check (including inside `und`-chains and after an
early-return guard). Nullable primitives compile to boxed JVM types
(`Ganzzahl?`→`Long`), so null safety holds in the interpreter, the bytecode and
the native binary alike.*

### Fehlerbehandlung / Error handling

Fehlbare Berechnungen liefern ein **`Ergebnis<T>`** — entweder einen Erfolg mit
einem Wert oder einen Fehlschlag mit einer Meldung. `Ergebnis<T>` ist (wie
`Liste<T>` oder `Paar<A, B>`) ein eingebauter generischer Typ; es braucht keine
benutzerdefinierten Generics.

```
funktion teilen(a: Ganzzahl, b: Ganzzahl): Ergebnis<Ganzzahl> {
    wenn b == 0 { zurück Fehler("Division durch null") }
    zurück Erfolg(a / b)
}
```

`Erfolg(wert)` und `Fehler("meldung")` erzeugen ein Ergebnis; ein `Fehler` passt
in jedes `Ergebnis<T>`. Fünf Methoden werten es aus:

```
e.istErfolg()      // bzw. e.istFehler()  -> Wahrheit
e.wert()           // der Erfolgswert     (wirft zur Laufzeit bei einem Fehlschlag)
e.meldung()        // die Fehlermeldung   (wirft bei einem Erfolg)
e.oderSonst(ersatz) // der Wert, oder `ersatz` bei einem Fehlschlag
```

*Fallible functions return `Ergebnis<T>` — a success carrying a value or a
failure carrying a message. `Erfolg`/`Fehler` build one (a `Fehler` is
assignable to any `Ergebnis<T>`); `istErfolg`/`istFehler`/`wert`/`meldung`/
`oderSonst` consume it. Like `Liste<T>`, it is a built-in generic — no
user-defined generics required — and works identically across interpreter,
bytecode and native binary.*

### Schlüsselwörter / Keywords

`sei ver funktion zurück wenn sonst solange für in von bis brich weiter
wähle fall wahr falsch nichts und oder nicht klasse datensatz aufzählung
schnittstelle neu dies erweitert erfüllt öffentlich privat geschützt
statisch importiere paket`

---

## Automatische Parallelisierung / Automatic parallelism

Edel parallelisiert geeignete `für`-Schleifen **automatisch** — ohne
Schlüsselwort, ohne Thread-Code. Der Compiler beweist die Unabhängigkeit der
Iterationen und erkennt **Reduktionen**: Schleifen, die äußere
`Ganzzahl`-Variablen über einen festen assoziativen Operator (`+` oder `*`)
fortschreiben.

```
ver summe = 0
für i von 1 bis 10000000 {
    summe = summe + quadratrest(i)   // quadratrest ist rein -> Iterationen unabhängig
}
```

Parallelisiert wird, wenn der Rumpf nur Akkumulatoren über `+`/`*` fortschreibt,
keine Ein-/Ausgabe macht, kein `brich`/`zurück` enthält und nur reine Funktionen
aufruft. Da `+` und `*` auf 64-Bit-Ganzzahlen assoziativ und kommutativ sind,
ist das Ergebnis **bitgleich** zur sequentiellen Ausführung — Gleitkomma-
Akkumulatoren bleiben deshalb sequentiell. `edel prüfe` zeigt die erkannten
Schleifen:

```
$ edel prüfe beispiele/parallel.edel
Keine Fehler gefunden.

2 Schleifen werden automatisch parallelisiert:
  [14:5] Reduktion ueber summe (+)
  [20:5] Reduktion ueber fakultät (*)
```

**Unabhängige Rekursion (fork/join).** Ebenso erkennt der Compiler binäre
Ausdrücke `A op B`, deren beide Operanden rein sind und Funktionsaufrufe
enthalten — etwa die beiden Aufrufe in `fib(n - 1) + fib(n - 2)`. Sie werden
nebenläufig im Fork-Join-Pool berechnet:

```
funktion fib(n: Ganzzahl): Ganzzahl {
    wenn n < 2 { zurück n }
    zurück fib(n - 1) + fib(n - 2)   // beide Aufrufe -> fork/join
}
```

Eine Granularitätsschranke (`getSurplusQueuedTaskCount`) sorgt dafür, dass nur
die oberen Rekursionsebenen forken und der Mehraufwand beschränkt bleibt.
*Parallelität beschleunigt um einen festen Faktor (×Kerne) — sie ersetzt keinen
besseren Algorithmus; naives `fib` bleibt exponentiell.*

**Parallel map (Streuung).** Eine `für von bis`-Schleife, die jede Iteration in
ein eigenes Listenelement am Schleifenindex schreibt, ist eine Streuung — die
Iterationen berühren disjunkte Elemente und sind daher unabhängig:

```
für i von 0 bis n {
    ergebnis.setze(i, teuer(i))   // Index i je Iteration eindeutig
}
```

**Unabhängige `sei`-Gruppen.** Aufeinanderfolgende reine `sei`-Bindungen, deren
Anfangswerte sich nicht gegenseitig referenzieren, werden nebenläufig berechnet
— die idiomatische Form für divide-and-conquer:

```
sei links  = sortiere(linkeHälfte)    // unabhängig -> parallel
sei rechts = sortiere(rechteHälfte)
```

Reduktionen, Gabelungen und `sei`-Gruppen wirken in **allen Ausführungsarten**
(`starte`, `übersetze`, `binär`) — im Bytecode entstehen daraus ein paralleler
`LongStream` bzw. `CompletableFuture`-Aufgaben. Streuschleifen parallelisiert
der Interpreter (das Bytecode-Backend kennt noch keine Listen).

*Edel auto-parallelizes provably-independent work with no keyword and no thread
code: reduction loops (associative `+`/`*` accumulation), independent pure
subexpressions like `fib(n-1) + fib(n-2)` (fork/join), parallel-map "scatter"
loops (each iteration writes a distinct list element), and independent `sei`
groups (concurrent bindings — the natural form for divide-and-conquer). Results
are bit-identical to sequential, in interpreter, bytecode and native alike.*

## Bytecode-Backend / Bytecode back-end

`edel übersetze` kompiliert ein Programm zu einer echten JVM-`.class`-Datei über
die Standardbibliotheks-**Class-File-API** (`java.lang.classfile`, JEP 484) —
ohne Drittanbieter-Abhängigkeit, Klassendateiversion 69 (Java 25).

```bash
./bin/edel übersetze beispiele/fibonacci.edel   # erzeugt fibonacci.class
java -cp beispiele fibonacci                    # läuft auf einer blanken JVM
```

Lexer, Parser und Typprüfer werden unverändert weiterverwendet — es kommt nur
ein zweites Backend hinzu. Edel-Typen werden auf native JVM-Typen abgebildet
(`Ganzzahl`→`long`, `Kommazahl`→`double`, `Wahrheit`→`boolean`,
`Zeichen`→`char`, `Text`→`String`), jede `funktion` wird zu einer `static`-Methode.
`datensatz`, `klasse`, `aufzählung` und `schnittstelle` werden zu eigenen
`.class`-Dateien, Lambdas zu `invokedynamic`-Aufrufstellen, `Liste`/`Abbildung`
auf `java.util.ArrayList`/`LinkedHashMap`; `übersetze` liefert daher eine
`.class` je Typ plus die Hauptklasse.

Das Backend deckt den **vollen Sprachumfang** ab: Funktionen, Grundtypen,
sämtlichen Kontrollfluss, Rekursion, `wähle`, **Datensätze**, **Klassen** (`neu`,
Felder, Methoden, `dies`, **Vererbung**), **Aufzählungen**, **Schnittstellen**
(`erfüllt`, virtuelle Methoden), **Lambdas** (gefangene Variablen) und
**Sammlungen** (`Liste`, `Abbildung`, `Paar`). Lediglich die Eingabe `lies`
bleibt dem Interpreter vorbehalten. Auch `beispiele/datensatz.edel` und
`beispiele/schnittstelle.edel` — die jedes Sprachmittel verwenden — werden so zu
nativen Programmen.

*`edel übersetze` compiles to real JVM `.class` files via the standard-library
Class-File API. It covers the **full language** — functions, primitive types,
control flow, recursion, `wähle`, records, classes (incl. inheritance), enums,
interfaces, lambdas and collections; only the `lies` input builtin stays
interpreter-only. Every example, including the feature-complete showcases,
compiles to a native binary.*

## Native Programme mit GraalVM / Native binaries with GraalVM

`edel binär` erzeugt mit **einem Befehl** ein eigenständiges natives
Programm: Edel-Quelltext wird zu Bytecode kompiliert und dann von GraalVMs
`native-image` zu einer echten Maschinencode-Datei übersetzt.

```bash
./bin/edel binär beispiele/fibonacci.edel   # -> ./beispiele/fibonacci (ELF)
./beispiele/fibonacci                       # läuft ohne JVM, ~Millisekunden Start
```

Der erzeugte Bytecode ist vollständig **GraalVM-kompatibel** — keine Reflexion,
kein dynamisches Laden von Klassen —, daher gelingt `native-image --no-fallback`
ohne jede zusätzliche Konfiguration. `native-image` wird über `JAVA_HOME`,
`GRAALVM_HOME`, die Umgebungsvariable `EDEL_NATIVE_IMAGE` oder den `PATH` gefunden.

*`edel binär` is the one-command path from source to a standalone native
executable. The generated bytecode uses no reflection or dynamic class loading,
so `native-image --no-fallback` succeeds with zero configuration. The `edel`
tool itself is also GraalVM-native — `./gradlew nativeCompile` builds it.*

## Editor-Unterstützung / Editor support

Edel bringt einen **Sprachserver** (Language Server Protocol) mit. Er nutzt
direkt den Compiler und bietet jedem LSP-fähigen Editor dieselben Funktionen:

- **Diagnosen** — Syntax- und Typfehler beim Tippen,
- **Hover** — statischer Typ bzw. Signatur unter dem Mauszeiger,
- **Gehe zu Definition** — Funktionen, Typen und lokale Bindungen,
- **Gliederung** — Funktionen, Klassen, Datensätze, Aufzählungen,
- **Vervollständigung** — Schlüsselwörter, Typen und Namen im Geltungsbereich.

```bash
./gradlew lspJar     # baut den Sprachserver: build/libs/edel-lsp.jar
```

Der Server läuft als JVM-Prozess (`java -jar edel-lsp.jar`) und spricht über
stdin/stdout. Er liegt in einem eigenen Gradle-Quellsatz, damit seine
LSP4J-Abhängigkeit nicht in das native `edel`-Werkzeug gelangt.

Eine **VS-Code-Erweiterung** unter `editors/vscode/` bündelt Syntaxhervorhebung
(TextMate-Grammatik) und den LSP-Client. *The bundled VS Code extension provides
syntax highlighting plus a language-server client; see `editors/vscode/README.md`.*

## Projektaufbau / Project layout

```
src/main/kotlin/edel/
  Main.kt          CLI: starte / prüfe / übersetze / binär / version
  lexer/           Token, TokenTyp, Lexer (UTF-8, Umlaute in Bezeichnern)
  parser/          Ast (versiegelte Hierarchie), Parser (recursive descent)
  semantik/        Typ, Resolver, Typpruefer (statische Prüfung + Typinferenz)
  laufzeit/        Wert, Umgebung, Interpreter (Baumdurchlauf)
  codegen/         Bytecodeerzeuger (JVM-.class über die Class-File-API)
  fehler/          Diagnose (quellortbezogene Fehlermeldungen)
src/lsp/kotlin/edel/lsp/
  Sprachserver.kt  LSP-Server + Einstiegspunkt (LSP4J, eigener Quellsatz)
  Dokumentdienst.kt  Diagnosen, Hover, Definition, Gliederung, Vervollständigung
  Quellanalyse.kt  Positionsumrechnung und Navigation im Syntaxbaum
src/test/kotlin/   JUnit-5-Tests je Phase + Golden-Output- und Bytecode-Tests
beispiele/         Beispielprogramme (*.edel) mit erwarteter Ausgabe (*.aus)
editors/vscode/    VS-Code-Erweiterung (Grammatik + LSP-Client)
```
