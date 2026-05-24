# Synthesized Review — PR #1 (modules)

Run: 20260523-094129-c087a50c
Scope: merge-base e11d20b → HEAD (modules branch, 2 commits, 18 files, ~970 changed lines)
Reviewers spawned: 9 (correctness, testing, maintainability, project-standards, agent-native, learnings, adversarial, api-contract, cli-readiness). All returned successfully.

## P1 — must fix before merge (2)

1. **Parallelanalyse purity/impurity lookups use short name** — `edel/src/main/kotlin/edel/semantik/Parallelanalyse.kt:128,400,441`. Sets `reineFunktionen` and `unrein` are keyed by FQN (built at line 425: `funktionen.map { it.name }.toSet()` where `it.name` is the FQN after Resolver pre-pass), but lookups use `ziel.name` (short name from source). Imported pure functions are classified as impure (parallelism silently suppressed). More dangerously, the `funktionIstUnrein` fixpoint at line 441 also misses transitive impurity: an imported wrapper that calls `drucke` would be classified as pure → loops calling it get parallelized → interleaved I/O. Confidence 0.95. Flagged by correctness + adversarial (×2). Fix: replace `ziel.name` with `ziel.aufgelöst ?: ziel.name` at all four sites (128, 400, 440, 441).

2. **LSP go-to-definition fails for any file with `paket`** — `edel/src/lsp/kotlin/edel/lsp/Dokumentdienst.kt:179,190`. Compares `it.name == bezeichner.name`. After Resolver pre-pass, top-level decl `name` is the FQN ("foo.start") while reference `bezeichner.name` is still the short name ("start"). Match always fails. Confidence 0.85. Flagged by api-contract. Fix: `it.name == (bezeichner.aufgelöst ?: bezeichner.name)`.

## P2 — should fix (16)

3. `führeZusammen .first { }` can crash with NoSuchElementException when a colliding file has zero declarations — `Resolver.kt:261`. correctness, 0.88. Replace with `firstOrNull { ... } ?: Position(1, 1, pfad)`.
4. Triple-parse of entry file per CLI invocation — `Main.kt:137,238` + analysiereProjekt — correctness, maintainability, adversarial, 0.92.
5. `analysiereProjekt` aborts on first parse error, hiding later errors — `Main.kt:62`. correctness, 0.85.
6. `darstelle(DatensatzWert)` and friends print FQN ("geometrie.Punkt(2, 3)") — `Wert.kt`. correctness, 0.80. User-visible behavior change; consider gated.
7. Implicit "must call führeZusammen first" contract undocumented; all downstream phases silently fall back to short name — `Ast.kt:22,64,120`. maintainability, 0.90.
8. LSP exposes FQNs in completion/documentSymbol/hover — `Dokumentdienst.kt:221+`. api-contract, 0.80.
9. FQN-collision test bypasses sammleProjekt; CLI path uncovered — `ModulTest.kt`. adversarial, 0.85.
10. All exit paths conflate to `exitProcess(1)`; agents can't programmatically distinguish failure modes — `Main.kt`. cli-readiness, 0.75.
11. Diagnostic test assertions too weak (`assertTrue(any { meldung contains })`) — `ModulTest.kt:112`. testing, 0.91.
12. Import-collides-with-local-decl branch in Resolver.führeZusammen untested — testing, 0.88.
13. Golden-example module test runs only interpreter, not bytecode — `ModulTest.kt:269`. testing, 0.87.
14. `BeispieleTest.goldeneAusgaben` uses non-recursive `listFiles` and silently misses `beispiele/module/main.edel` — `BeispieleTest.kt:21`. project-standards + testing, 0.93.
15. Multi-segment package (`paket a.b.c`) FQN encoding untested in bytecode — testing, 0.80.
16. New parser constraints for `paket`/`importiere` directives untested — `ParserTest.kt`. testing, 0.82.
17. `hilfe()` not updated to mention modules/`paket`/`importiere` — `Main.kt:430`. agent-native + cli-readiness, 0.97.
18. Import-resolution errors in `findeImportdatei`/`quellwurzel` omit line/column; `Programm.importe` drops the `Position` of each `importiere` token — `Main.kt:164` and `Resolver.kt:282`. agent-native + adversarial, 0.88.
19. `edel übersetze` doesn't clean stale `.class` files on package rename — `Main.kt:349`. cli-readiness, 0.80.
20. Bytecode `Bezeichner`-as-value path doesn't fall back to `aufgelöst` (locals-only today, blocks future function-as-value support in bytecode) — `Bytecodeerzeuger.kt:1392`. correctness, 0.75.

## P3 — discretion (13)

21. Dead `diagnosen` parameter on `NamensAuflöser` — `Resolver.kt:326`. maintainability, 0.97. safe_auto.
22. English KDoc on `ProjektAnalyse` in otherwise German codebase — `Main.kt:224`. maintainability + project-standards, 0.95.
23. English "entry-dir" phrase in German KDoc — `Main.kt:320`. project-standards, 0.80.
24. Import collision / not-found error positions fall back to (1,1) or first decl — `Resolver.kt:277`. agent-native + adversarial, 0.92.
25. `quellwurzel` computed redundantly in two CLI paths — `Main.kt:334`. maintainability, 0.78.
26. `führeZusammen` as Resolver companion obscures the mandatory two-step pipeline — `Resolver.kt:230`. maintainability, 0.65.
27. LSP `beschreibung(d)` lost `else -> d.name` fallback (future Deklaration subclass would throw MatchException) — `Dokumentdienst.kt:333`. correctness, 0.72. safe_auto (defensive).
28. TOCTOU between graph-walk parse and content read; uncaught IOException possible — `Main.kt:164`. adversarial, 0.65.
29. `binär` progress message goes to stdout, interleaves with the final `Erzeugt:` line — `Main.kt:385`. cli-readiness, 0.70.
30. Lambda body referencing an imported function untested across all backends — testing, 0.72.
31. Native binary path with dotted FQN main-class untested — testing, 0.70.
32. Source root can't be overridden (e.g., `--quellen`) — cli-readiness, 0.65.
33. `edel prüfe` outputs German prose only; no `--json` for agents — cli-readiness, 0.65.

## Residual risks (not findings)

- AST mutation under concurrent analysis (no immediate bug; latent if LSP becomes concurrent).
- analysiere shim relies on führeZusammen being a no-op for `<inline>` single-file path.
- Case-insensitive filesystems (macOS HFS+) could produce duplicate keys in `gesammelt`.
- GraalVM native-image may need reflection config for `EdelFunktionN` interfaces (untested under `--no-fallback` for module programs).

## Learnings

`docs/solutions/` doesn't exist — no prior patterns to surface.

## Verdict

**Ready with fixes.** The two P1 issues are real — Parallelanalyse's purity check has a concrete correctness violation (parallelized I/O), and LSP go-to-def silently breaks for any `paket` file. The rest is a long but predictable tail of testing gaps and ergonomics.
