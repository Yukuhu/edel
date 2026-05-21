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
./gradlew nativeCompile   # baut das edel-Werkzeug selbst als GraalVM-Binärprogramm (build/edel)
./bin/edel starte beispiele/fakultät.edel
```

`bin/edel` benutzt automatisch das native `build/edel`, falls vorhanden, sonst
das Jar. *Run `./gradlew nativeCompile` once and the `edel` tool itself becomes a
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

Beides wirkt in **allen Ausführungsarten**: im Interpreter (`starte`), im
JVM-Bytecode (`übersetze`) und im nativen Programm (`binär`) — im Bytecode
entstehen daraus ein paralleler `LongStream` bzw. `CompletableFuture`-Aufgaben.

*Edel auto-parallelizes provably-independent work with no keyword and no thread
code: reduction loops (associative `+`/`*` accumulation) and independent pure
subexpressions such as the two recursive calls in `fib(n-1) + fib(n-2)`
(fork/join, with a granularity cutoff). Results are bit-identical to sequential
execution, in the interpreter, the JVM bytecode, and the native binary alike.*

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

Das Backend deckt den **Sprachkern** ab: Funktionen, Grundtypen, sämtlichen
Kontrollfluss, Rekursion und `wähle` über Grundwerte. Programme mit Klassen,
Datensätzen, Aufzählungen, Lambdas oder Sammlungen lehnt `übersetze` mit einer
klaren Meldung ab — sie laufen weiterhin über `edel starte`.

*`edel übersetze` compiles to a real JVM `.class` file via the standard-library
Class-File API. The core language (functions, primitive types, control flow,
recursion, `wähle`) compiles to native bytecode; programs using classes,
records, enums, lambdas or collections are rejected with a clear message and
remain runnable via the interpreter.*

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
src/test/kotlin/   JUnit-5-Tests je Phase + Golden-Output- und Bytecode-Tests
beispiele/         Beispielprogramme (*.edel) mit erwarteter Ausgabe (*.aus)
```
