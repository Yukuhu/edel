# Edel — a German-keyword programming language for the JVM

## Context

A brand-new programming language whose every keyword/reserved word is German,
running on a modern JVM (Java 25+). The working directory
(`/var/home/andre/german`) starts empty — this is a fully greenfield project.

Design decisions agreed with the user:

- **Name:** `Edel` — source files `*.edel`, CLI command `edel`.
- **Execution model:** tree-walking **interpreter first**, JVM-bytecode backend as a
  later phase. Shared front-end (lexer → parser → type checker), swappable back-end.
- **Type system:** statically typed, multi-paradigm (OO + first-class functions +
  pattern matching), with **local type inference** (explicit signatures, inferred locals).
- **Initial scope:** core language **+ user-defined data structures** (records, classes,
  enums) and built-in collections. *No* user-defined generics in phase 1.
- **Implementation language:** **Kotlin** (sealed classes make the AST/type code concise).
- **Keyword orthography:** proper German with umlauts and ß (e.g. `für`, `während`,
  `öffentlich`); lexer is fully UTF-8.

Outcome: a working `edel` interpreter that runs German-keyword programs, with a
documented path to a bytecode compiler.

## Proposed keyword set (refine before coding the lexer)

| Edel | Role |
|---|---|
| `sei` / `variable` | immutable binding (val) / mutable binding (var) |
| `funktion`, `haupt` | function declaration; `haupt()` is the entry-point convention |
| `zurück` | return |
| `wenn` / `sonst` | if / else (`sonst wenn` for else-if) |
| `solange` | while |
| `für` … `in`, `von` … `bis` | for-each over a collection; numeric range |
| `brich` / `weiter` | break / continue |
| `wähle` / `fall` | pattern-match expression / case arm |
| `wahr` / `falsch` / `nichts` | true / false / null-unit value |
| `und` / `oder` / `nicht` | logical operators (native German words) |
| `klasse` / `datensatz` / `aufzählung` / `schnittstelle` | class / record / enum / interface |
| `neu` / `dies` | constructor call / this |
| `erweitert` / `erfüllt` | extends / implements |
| `öffentlich` / `privat` / `geschützt` / `statisch` | visibility & static |
| `importiere` / `paket` | import / package |

Built-in types: `Ganzzahl` (i64), `Kommazahl` (f64), `Text`, `Wahrheit`, `Zeichen`,
`Nichts`, `Liste<T>`, `Abbildung<S,T>`, `Paar<A,B>`.
Built-in functions: `drucke` (print), `lies` (read line), `länge` (length).
CLI subcommands are German: `edel starte <datei>`, `edel prüfe <datei>` (type-check
only), `edel version`. Phase 2 adds `edel übersetze <datei>` (compile to `.class`).

## Project layout (created under `/var/home/andre/german`)

```
edel/
  settings.gradle.kts, build.gradle.kts      Gradle Kotlin DSL, JDK 25 toolchain,
  gradle/wrapper/…                            Shadow plugin for a fat runnable jar
  bin/edel                                    launcher script (java -jar …)
  src/main/kotlin/edel/
    Main.kt                                   CLI: starte / prüfe / version
    lexer/    Token.kt, TokenTyp.kt, Lexer.kt
    parser/   Ast.kt (sealed AST), Parser.kt  recursive-descent
    semantik/ Typ.kt, Resolver.kt, Typpruefer.kt
    laufzeit/ Wert.kt, Umgebung.kt, Interpreter.kt
    fehler/   Diagnose.kt                      source-located error reporting
  src/test/kotlin/edel/                        JUnit 5 unit + golden-output tests
  beispiele/ *.edel                            example programs
  README.md                                    language tour in German + English
```

The interpreter implementation stays in idiomatic Kotlin/English; only the *Edel
language* surface is German. Example programs and the README language tour are German.

## Implementation steps (Phase 1 — interpreter)

1. **Scaffold.** Gradle Kotlin-DSL project, Gradle wrapper, JDK 25 toolchain
   (`jvmToolchain(25)`), Kotlin `jvmTarget` at the highest the installed Kotlin
   supports (generated jar still runs on JVM 25+). Shadow plugin → `edel.jar`.
   `bin/edel` launcher. JUnit 5 wired up.
2. **Finalize keyword table**, then encode it as a `String → TokenTyp` map.
3. **Lexer** (`lexer/`). UTF-8 source → token stream: identifiers (umlauts allowed),
   German keywords, integer/float/string/char literals, operators (`+ - * / % == != < <= > >=`,
   assignment `=`, arrow `->` for function types/lambdas), `//` and `/* */` comments.
   Each token carries line/column for diagnostics.
4. **AST + Parser** (`parser/`). `Ast.kt`: sealed hierarchies for expressions,
   statements, declarations. `Parser.kt`: recursive descent with precedence climbing
   for expressions; parses bindings, `wenn/sonst`, `solange`, `für`, `funktion`,
   `datensatz`, `klasse`, `aufzählung`, `wähle`, lambdas.
5. **Semantic analysis** (`semantik/`). `Typ.kt` models built-in types, function
   types, and user types. `Resolver.kt` builds lexical scopes and resolves names.
   `Typpruefer.kt` does static checking with local inference (variable types inferred
   from initializers; function params/returns explicit) and reports errors via `Diagnose`.
6. **Tree-walking interpreter** (`laufzeit/`). `Wert.kt` runtime values, `Umgebung.kt`
   lexical environments, `Interpreter.kt` evaluates the type-checked AST. Built-in
   functions registered in a root environment.
7. **Data structures.** `datensatz` (immutable records with positional fields),
   `klasse` (mutable, methods, single inheritance via `erweitert`, `schnittstelle`/
   `erfüllt`), `aufzählung` (enums, usable in `wähle`), built-in `Liste`/`Abbildung`/
   `Paar` with literal syntax and core methods.
8. **Multi-paradigm features.** First-class functions and lambdas, immutability via
   `sei`, and `wähle` pattern matching over literals and enum variants with `sonst`.
9. **CLI** (`Main.kt`). `edel starte` (type-check then interpret), `edel prüfe`
   (type-check only, exit non-zero on errors), `edel version`.
10. **Tests, examples, docs.** Unit tests per stage; golden-output tests that run
    `beispiele/*.edel` and compare stdout. README with a German+English language tour.

## Phase 2 (bytecode back-end — designed, not built now)

Add `src/main/kotlin/edel/codegen/` using the standard-library **Class-File API**
(`java.lang.classfile`, JEP 484, finalized in JDK 24, stable in 25 — no third-party
dependency). Walk the *same* type-checked AST to emit `.class` files at class-file
version 69 (Java 25). Map Edel types to JVM types (`Ganzzahl`→`long`, `Text`→`String`,
records→`record` classes, etc.). Wire up `edel übersetze`. The lexer, parser, and
type checker are reused unchanged — only a new back-end.

## Example program

```
funktion fakultät(n: Ganzzahl): Ganzzahl {
    wenn n <= 1 {
        zurück 1
    }
    zurück n * fakultät(n - 1)
}

datensatz Punkt(x: Ganzzahl, y: Ganzzahl)

funktion haupt() {
    sei zahlen = Liste(1, 2, 3, 4, 5)
    für z in zahlen {
        drucke("fakultät(" + z + ") = " + fakultät(z))
    }
    sei p = neu Punkt(3, 4)
    drucke("Punkt: " + p.x + ", " + p.y)
}
```

## Verification

- `cd edel && ./gradlew build` — compiles and runs all unit tests.
- `./gradlew test` golden tests run every `beispiele/*.edel` and diff stdout.
- `bin/edel starte beispiele/fakultät.edel` prints the expected factorial output.
- `bin/edel starte beispiele/datensatz.edel` exercises records, classes, enums,
  `Liste`, lambdas, and `wähle`.
- `bin/edel prüfe beispiele/fehler_typ.edel` (a deliberately ill-typed sample) reports
  a German type error with line/column and exits non-zero.
- `bin/edel version` prints the version; unknown subcommands print German usage help.

## Open question carried into Step 2

The keyword table above is a *proposal*. Step 2 confirms exact spellings with the user
(e.g. `variable` vs. a `sei wandelbar` modifier; `haupt` vs. `start` as entry point)
before the lexer hard-codes them.
