package edel

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden-Output-Tests: jede `.edel`-Datei im Verzeichnis `beispiele` mit einer
 * gleichnamigen `.aus`-Datei wird ausgefuehrt und ihre Ausgabe verglichen.
 */
class BeispieleTest {

    private val beispiele = File("beispiele")

    @TestFactory
    fun goldeneAusgaben(): List<DynamicTest> {
        val edelDateien = beispiele
            .listFiles { datei -> datei.extension == "edel" }
            ?.sortedBy { it.name }
            ?: emptyList()
        return edelDateien.mapNotNull { edel ->
            val erwartet = File(beispiele, edel.nameWithoutExtension + ".aus")
            if (!erwartet.isFile) return@mapNotNull null
            DynamicTest.dynamicTest(edel.name) {
                val ausgabe = StringBuilder()
                interpretiere(edel.readText()) { ausgabe.appendLine(it) }
                assertEquals(erwartet.readText(), ausgabe.toString(), "Ausgabe von ${edel.name}")
            }
        }
    }

    @Test
    fun fehlerbeispielWirdAlsTypfehlerGemeldet() {
        val datei = File(beispiele, "fehler_typ.edel")
        assertTrue(datei.isFile, "beispiele/fehler_typ.edel fehlt")
        val diagnosen = analysiere(datei.readText()).diagnosen
        assertTrue(diagnosen.isNotEmpty(), "fehler_typ.edel sollte einen Typfehler melden")
    }
}
