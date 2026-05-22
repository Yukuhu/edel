package edel

import edel.codegen.Bytecodeerzeuger
import edel.fehler.NichtUnterstützt
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests des Bytecode-Backends: jedes Programm wird zu echtem JVM-Bytecode
 * uebersetzt, die `.class` in den Testprozess geladen und ausgefuehrt. Damit
 * deckt der Test zugleich ab, dass die erzeugten Klassen vom JVM-Verifizierer
 * akzeptiert werden.
 */
class CodegenTest {

    /** Laedt die erzeugten Klassen bei Bedarf (verschachtelte Datensaetze inbegriffen). */
    private class ByteKlassenlader(private val klassen: Map<String, ByteArray>) : ClassLoader() {
        private val geladen = HashMap<String, Class<*>>()
        override fun findClass(name: String): Class<*> {
            geladen[name]?.let { return it }
            val bytes = klassen[name] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size).also { geladen[name] = it }
        }
    }

    /** Uebersetzt Quelltext, fuehrt die erzeugte `start`-Methode aus, liefert deren Ausgabe. */
    private fun kompiliereUndLaufe(klassenname: String, quelle: String): String {
        val ergebnis = analysiere(quelle)
        assertEquals(emptyList(), ergebnis.diagnosen, "Quelle sollte fehlerfrei sein")
        val klassen = Bytecodeerzeuger(
            ergebnis.programm!!, ergebnis.symbole!!, klassenname, ergebnis.parallelplan!!,
        ).kompiliere()
        val klasse = ByteKlassenlader(klassen).loadClass(klassenname)

        val puffer = ByteArrayOutputStream()
        val vorher = System.out
        System.setOut(PrintStream(puffer, true, "UTF-8"))
        try {
            klasse.getMethod("start").invoke(null)
        } finally {
            System.setOut(vorher)
        }
        return puffer.toString("UTF-8")
    }

    @Test
    fun uebersetztSchleifeUndArithmetik() {
        val quelle = "funktion start() { ver s = 0 für i von 1 bis 5 { s = s + i } drucke(s) }"
        assertEquals("15\n", kompiliereUndLaufe("ProbeSchleife", quelle))
    }

    @Test
    fun uebersetztRekursion() {
        val quelle = "funktion fak(n: Ganzzahl): Ganzzahl { wenn n <= 1 { zurück 1 } " +
            "zurück n * fak(n - 1) }\n" +
            "funktion start() { drucke(fak(10)) }"
        assertEquals("3628800\n", kompiliereUndLaufe("ProbeRekursion", quelle))
    }

    @Test
    fun uebersetztWähleUndWahrheitswerte() {
        val quelle = "funktion ampel(n: Ganzzahl): Text { zurück wähle n { " +
            "fall 0 -> \"rot\" fall 1 -> \"gelb\" sonst -> \"grün\" } }\n" +
            "funktion start() { drucke(ampel(0)) drucke(ampel(1)) drucke(ampel(2)) " +
            "drucke(1 < 2 und 2 < 3) }"
        assertEquals("rot\ngelb\ngrün\nwahr\n", kompiliereUndLaufe("ProbeWähle", quelle))
    }

    @Test
    fun uebersetztKommazahlen() {
        val quelle = "funktion start() { sei x = 7.0 / 2.0 drucke(x) drucke(3 + 1.5) }"
        assertEquals("3.5\n4.5\n", kompiliereUndLaufe("ProbeKomma", quelle))
    }

    @Test
    fun uebersetztParalleleSummenreduktion() {
        // Wird zu LongStream.rangeClosed(..).parallel().map(..).sum() uebersetzt.
        val quelle = "funktion start() { ver s = 0 für i von 1 bis 1000000 { s = s + i } drucke(s) }"
        assertEquals("500000500000\n", kompiliereUndLaufe("ProbeParallelSumme", quelle))
    }

    @Test
    fun uebersetztParalleleProduktreduktion() {
        val quelle = "funktion start() { ver p = 1 für i von 1 bis 20 { p = p * i } drucke(p) }"
        assertEquals("2432902008176640000\n", kompiliereUndLaufe("ProbeParallelProdukt", quelle))
    }

    @Test
    fun uebersetztParallelisierteRekursion() {
        // fib(n-1) und fib(n-2) werden per CompletableFuture nebenlaeufig berechnet.
        val quelle = "funktion fib(n: Ganzzahl): Ganzzahl { wenn n < 2 { zurück n } " +
            "zurück fib(n - 1) + fib(n - 2) }\n" +
            "funktion start() { drucke(fib(30)) }"
        assertEquals("832040\n", kompiliereUndLaufe("ProbeGabelung", quelle))
    }

    @Test
    fun uebersetztNebenlaeufigeSeiGruppe() {
        // sei a/b/c werden je per CompletableFuture nebenlaeufig berechnet.
        val quelle = "funktion f(n: Ganzzahl): Ganzzahl { zurück n * n }\n" +
            "funktion start() { sei a = f(10) sei b = f(20) sei c = f(30) drucke(a + b + c) }"
        assertEquals("1400\n", kompiliereUndLaufe("ProbeSeiGruppe", quelle))
    }

    @Test
    fun uebersetztNullbareGanzzahlen() {
        // Ganzzahl? -> geboxtes Long, 'nichts' -> aconst_null, '?:' / '!!'.
        val quelle = "funktion start() { sei x: Ganzzahl? = nichts drucke(x ?: 42) " +
            "sei y: Ganzzahl? = 8 drucke(y!!) }"
        assertEquals("42\n8\n", kompiliereUndLaufe("ProbeNullbar", quelle))
    }

    @Test
    fun uebersetztDatensaetze() {
        // datensatz -> eigene .class; neu, Feldzugriff, Datensatz als Parameter/Rueckgabe.
        val quelle = "datensatz Punkt(x: Ganzzahl, y: Ganzzahl)\n" +
            "funktion verschoben(p: Punkt, dx: Ganzzahl): Punkt { zurück neu Punkt(p.x + dx, p.y) }\n" +
            "funktion start() { sei p = verschoben(neu Punkt(3, 4), 10) drucke(p.x) drucke(p) }"
        assertEquals("13\nPunkt(13, 4)\n", kompiliereUndLaufe("ProbeDatensatz", quelle))
    }

    @Test
    fun uebersetztKlassen() {
        // klasse -> eigene .class; neu, Methoden, dies, Feldzuweisung, invokevirtual.
        val quelle = "klasse Zähler {\n" +
            "    ver stand: Ganzzahl\n" +
            "    funktion erhöhe() { dies.stand = dies.stand + 1 }\n" +
            "    funktion wert(): Ganzzahl { zurück dies.stand }\n" +
            "}\n" +
            "funktion start() {\n" +
            "    sei z = neu Zähler(0)\n" +
            "    z.erhöhe() z.erhöhe() z.erhöhe()\n" +
            "    drucke(z.wert())\n" +
            "    drucke(z)\n" +
            "}"
        assertEquals("3\nZähler(stand=3)\n", kompiliereUndLaufe("ProbeKlasse", quelle))
    }

    @Test
    fun uebersetztKlasseMitInitialisierer() {
        // Feld mit Initialwert wird nicht Konstruktorparameter.
        val quelle = "klasse Konto {\n" +
            "    ver stand: Ganzzahl\n" +
            "    sei währung: Text = \"EUR\"\n" +
            "    funktion einzahlen(betrag: Ganzzahl) { dies.stand = dies.stand + betrag }\n" +
            "    funktion bericht(): Text { zurück dies.stand + \" \" + dies.währung }\n" +
            "}\n" +
            "funktion start() { sei k = neu Konto(100) k.einzahlen(50) drucke(k.bericht()) }"
        assertEquals("150 EUR\n", kompiliereUndLaufe("ProbeKonto", quelle))
    }

    @Test
    fun uebersetztAufzaehlungen() {
        // aufzählung -> eigene .class mit Singleton-Varianten; wähle ueber Varianten.
        val quelle = "aufzählung Ampel { Rot, Gelb, Grün }\n" +
            "funktion beschreibe(a: Ampel): Text {\n" +
            "    zurück wähle a { fall Ampel.Rot -> \"halt\" fall Ampel.Gelb -> \"achtung\" " +
            "sonst -> \"fahr\" }\n" +
            "}\n" +
            "funktion start() {\n" +
            "    drucke(beschreibe(Ampel.Rot))\n" +
            "    drucke(beschreibe(Ampel.Grün))\n" +
            "    drucke(Ampel.Gelb)\n" +
            "}"
        assertEquals("halt\nfahr\nAmpel.Gelb\n", kompiliereUndLaufe("ProbeAmpel", quelle))
    }

    @Test
    fun uebersetztVererbungUndSchnittstellen() {
        // schnittstelle -> JVM-Interface; erweitert -> extends; erfüllt -> implements.
        val quelle = "schnittstelle Form {\n" +
            "    funktion fläche(): Kommazahl\n" +
            "}\n" +
            "klasse Rechteck erfüllt Form {\n" +
            "    ver breite: Kommazahl\n" +
            "    ver höhe: Kommazahl\n" +
            "    funktion fläche(): Kommazahl { zurück dies.breite * dies.höhe }\n" +
            "}\n" +
            "klasse Quadrat erweitert Rechteck {\n" +
            "    funktion umfang(): Kommazahl { zurück 4.0 * dies.breite }\n" +
            "}\n" +
            "funktion flächeVon(f: Form): Kommazahl { zurück f.fläche() }\n" +
            "funktion start() {\n" +
            "    drucke(flächeVon(neu Rechteck(3.0, 4.0)))\n" +
            "    sei q = neu Quadrat(2.0, 2.0)\n" +
            "    drucke(q.fläche())\n" +
            "    drucke(q.umfang())\n" +
            "    sei f: Form = q\n" +
            "    drucke(f.fläche())\n" +
            "    drucke(q)\n" +
            "}"
        assertEquals(
            "12.0\n4.0\n8.0\n4.0\nQuadrat(breite=2.0, höhe=2.0)\n",
            kompiliereUndLaufe("ProbeForm", quelle),
        )
    }

    @Test
    fun uebersetztLambdas() {
        // Lambda -> invokedynamic; freie Variablen werden gefangen.
        val quelle = "funktion start() {\n" +
            "    sei verdopple = (n: Ganzzahl) -> n * 2\n" +
            "    drucke(verdopple(21))\n" +
            "    sei faktor = 10\n" +
            "    sei skaliere = (x: Ganzzahl) -> x * faktor\n" +
            "    drucke(skaliere(7))\n" +
            "}"
        assertEquals("42\n70\n", kompiliereUndLaufe("ProbeLambda", quelle))
    }

    @Test
    fun uebersetztLambdaAlsArgument() {
        // Funktionswert als Parameter und Aufruf ueber die Schnittstelle.
        val quelle = "funktion wende(f: (Ganzzahl) -> Ganzzahl, x: Ganzzahl): Ganzzahl { zurück f(x) }\n" +
            "funktion start() { drucke(wende((n: Ganzzahl) -> n + 1, 41)) }"
        assertEquals("42\n", kompiliereUndLaufe("ProbeLambdaArg", quelle))
    }

    @Test
    fun uebersetztListen() {
        // Liste -> ArrayList; Indexzugriff, länge, hinzufügen, für-in.
        val quelle = "funktion start() {\n" +
            "    sei zahlen = Liste(10, 20, 30)\n" +
            "    drucke(länge(zahlen))\n" +
            "    drucke(zahlen[1])\n" +
            "    zahlen.hinzufügen(40)\n" +
            "    ver summe = 0\n" +
            "    für z in zahlen { summe = summe + z }\n" +
            "    drucke(summe)\n" +
            "}"
        assertEquals("3\n20\n100\n", kompiliereUndLaufe("ProbeListe", quelle))
    }

    @Test
    fun uebersetztAbbildungen() {
        // Abbildung -> LinkedHashMap aus Paaren; holen, enthält, länge.
        val quelle = "funktion start() {\n" +
            "    sei noten = Abbildung(Paar(\"Anna\", 1), Paar(\"Bert\", 3))\n" +
            "    drucke(noten.holen(\"Anna\"))\n" +
            "    drucke(noten.enthält(\"Bert\"))\n" +
            "    drucke(länge(noten))\n" +
            "}"
        assertEquals("1\nwahr\n2\n", kompiliereUndLaufe("ProbeAbbildung", quelle))
    }

    @Test
    fun lehntNichtUnterstuetzteProgrammeAb() {
        // Eingabe ('lies') deckt das Bytecode-Backend nicht ab.
        val quelle = "funktion start() { sei zeile = lies()  drucke(zeile) }"
        val ergebnis = analysiere(quelle)
        assertEquals(emptyList(), ergebnis.diagnosen)
        assertFailsWith<NichtUnterstützt> {
            Bytecodeerzeuger(ergebnis.programm!!, ergebnis.symbole!!, "ProbeAblehnung").kompiliere()
        }
    }
}
