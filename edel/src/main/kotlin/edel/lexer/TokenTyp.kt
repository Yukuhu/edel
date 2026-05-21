package edel.lexer

/** Alle Tokenarten der Sprache Edel. */
enum class TokenTyp {
    // Schluesselwoerter
    SEI, VER, FUNKTION, ZURÜCK, WENN, SONST, SOLANGE, FÜR, IN, VON, BIS,
    BRICH, WEITER, WÄHLE, FALL, WAHR, FALSCH, NICHTS, UND, ODER, NICHT,
    KLASSE, DATENSATZ, AUFZÄHLUNG, SCHNITTSTELLE, NEU, DIES,
    ERWEITERT, ERFÜLLT, ÖFFENTLICH, PRIVAT, GESCHÜTZT, STATISCH,
    IMPORTIERE, PAKET,

    // Literale und Namen
    GANZZAHL_LITERAL, KOMMAZAHL_LITERAL, TEXT_LITERAL, ZEICHEN_LITERAL,
    BEZEICHNER,

    // Operatoren
    PLUS, MINUS, STERN, SCHRÄGSTRICH, PROZENT,
    ZUWEISUNG, GLEICH, UNGLEICH, KLEINER, KLEINER_GLEICH, GRÖSSER, GRÖSSER_GLEICH,
    PFEIL,

    // Nullsicherheit
    FRAGE, FRAGE_PUNKT, ELVIS, DOPPEL_AUSRUF,

    // Satzzeichen
    KLAMMER_AUF, KLAMMER_ZU, GESCHWEIFT_AUF, GESCHWEIFT_ZU,
    ECKIG_AUF, ECKIG_ZU, KOMMA, PUNKT, DOPPELPUNKT,

    DATEI_ENDE,
}

/** Abbildung der deutschen Schluesselwoerter auf ihren Tokentyp. */
val SCHLÜSSELWÖRTER: Map<String, TokenTyp> = mapOf(
    "sei" to TokenTyp.SEI,
    "ver" to TokenTyp.VER,
    "funktion" to TokenTyp.FUNKTION,
    "zurück" to TokenTyp.ZURÜCK,
    "wenn" to TokenTyp.WENN,
    "sonst" to TokenTyp.SONST,
    "solange" to TokenTyp.SOLANGE,
    "für" to TokenTyp.FÜR,
    "in" to TokenTyp.IN,
    "von" to TokenTyp.VON,
    "bis" to TokenTyp.BIS,
    "brich" to TokenTyp.BRICH,
    "weiter" to TokenTyp.WEITER,
    "wähle" to TokenTyp.WÄHLE,
    "fall" to TokenTyp.FALL,
    "wahr" to TokenTyp.WAHR,
    "falsch" to TokenTyp.FALSCH,
    "nichts" to TokenTyp.NICHTS,
    "und" to TokenTyp.UND,
    "oder" to TokenTyp.ODER,
    "nicht" to TokenTyp.NICHT,
    "klasse" to TokenTyp.KLASSE,
    "datensatz" to TokenTyp.DATENSATZ,
    "aufzählung" to TokenTyp.AUFZÄHLUNG,
    "schnittstelle" to TokenTyp.SCHNITTSTELLE,
    "neu" to TokenTyp.NEU,
    "dies" to TokenTyp.DIES,
    "erweitert" to TokenTyp.ERWEITERT,
    "erfüllt" to TokenTyp.ERFÜLLT,
    "öffentlich" to TokenTyp.ÖFFENTLICH,
    "privat" to TokenTyp.PRIVAT,
    "geschützt" to TokenTyp.GESCHÜTZT,
    "statisch" to TokenTyp.STATISCH,
    "importiere" to TokenTyp.IMPORTIERE,
    "paket" to TokenTyp.PAKET,
)
