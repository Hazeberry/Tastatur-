package de.hazeberry.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class TermuxLogicTest {

    // ------------------------------------------------------------------ Kürzel

    @Test
    fun triggerOnlyWhenTypedAndAtWordStart() {
        assertEquals("datum", TermuxLogic.findTrigger("Heute ist ;;datum", "ist ;;datum"))
        assertEquals("=23*7", TermuxLogic.findTrigger(";;=23*7", ";;=23*7"))
        // Nicht selbst getippt (z. B. von der App vorgegebener Text)
        assertNull(TermuxLogic.findTrigger("Heute ist ;;datum", "x"))
        // Mitten im Wort, z. B. in Code
        assertNull(TermuxLogic.findTrigger("a;;datum", "a;;datum"))
        // Zu lang oder leer
        assertNull(TermuxLogic.findTrigger(";;" + "x".repeat(41), ";;" + "x".repeat(41)))
        assertNull(TermuxLogic.findTrigger("Text ;;", "Text ;;"))
    }

    @Test
    fun actionNames() {
        assertTrue(TermuxLogic.isValidActionName("standard"))
        assertTrue(TermuxLogic.isValidActionName("Übersetzen ins Englische"))
        assertTrue(TermuxLogic.isValidActionName("v2.zusammenfassen"))
        for (bad in listOf("", ".versteckt", "../etc", "a/b", "a..b", "-x", "x".repeat(70))) {
            assertFalse(bad, TermuxLogic.isValidActionName(bad))
        }
        assertEquals(
            listOf("liste", "standard", "Zitat"),
            TermuxLogic.parseActionList("standard\nZitat\n\nliste\nbackup~\n../böse\nstandard\n")
        )
    }

    @Test
    fun outputAndApplyRules() {
        assertEquals("a\nb", TermuxLogic.normalizeOutput("a\nb\n"))
        assertEquals("a\n", TermuxLogic.normalizeOutput("a\n\n"))
        assertEquals("a", TermuxLogic.normalizeOutput("a\r\n"))
        val t = TermuxTarget("com.chat", 7, TermuxMode.FIELD, "hallo")
        assertTrue(TermuxLogic.canApply(t, "com.chat", 7, "hallo"))
        assertFalse(TermuxLogic.canApply(t, "com.chat", 7, "hallo!"))
        assertFalse(TermuxLogic.canApply(t, "com.other", 7, "hallo"))
        assertFalse(TermuxLogic.canApply(t, "com.chat", 8, "hallo"))
    }

    @Test
    fun failureMessages() {
        assertTrue(TermuxLogic.describeFailure(TermuxResult("", "", null, 2,
            "Termux: allow-external-apps property is not set to true")).contains("Einrichtungsskript"))
        assertEquals("Skript nicht gefunden. Liegt es in ~/.tastatur/?",
            TermuxLogic.describeFailure(TermuxResult("", "bash: x: No such file or directory", 127, -1, null)))
        assertEquals("Skript-Fehler: Unbekanntes Kürzel: foo",
            TermuxLogic.describeFailure(TermuxResult("", "\nUnbekanntes Kürzel: foo\n", 1, -1, null)))
        assertTrue(TermuxResult("x", "", 0, -1, null).success)
        assertFalse(TermuxResult("x", "", 1, -1, null).success)
        assertFalse(TermuxResult("x", "", 0, 2, "Fehler").success)
    }

    // ------------------------------------------------------------------ Das Einrichtungsskript wirklich ausführen

    private fun bash(home: File, vararg args: String, stdin: String = ""): Triple<Int, String, String> {
        val p = ProcessBuilder(listOf("bash") + args)
            .directory(home)
            .apply { environment()["HOME"] = home.path; environment()["LANG"] = "C.UTF-8" }
            .start()
        p.outputStream.use { it.write(stdin.toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        assertTrue("bash hängt", p.waitFor(20, TimeUnit.SECONDS))
        return Triple(p.exitValue(), out, err)
    }

    @Test
    fun setupScriptAndExamplesWork() {
        assumeTrue("bash nicht vorhanden", File("/bin/bash").exists() || File("/usr/bin/bash").exists())
        val home = Files.createTempDirectory("termuxhome").toFile()
        try {
            val setup = File(home, "setup.sh").apply { writeText(TermuxLogic.SETUP_SCRIPT) }
            val (code, out, err) = bash(home, setup.path)
            assertEquals(err, 0, code)
            assertTrue(out, out.contains("Fertig."))
            assertEquals("allow-external-apps = true\n", File(home, ".termux/termux.properties").readText())

            // Zweimal ausführen ändert nichts (keine doppelte Zeile, eigene Skripte bleiben).
            File(home, ".tastatur/aktionen/standard").writeText("echo eigen")
            bash(home, setup.path)
            assertEquals("allow-external-apps = true\n", File(home, ".termux/termux.properties").readText())
            assertEquals("echo eigen", File(home, ".tastatur/aktionen/standard").readText())
            File(home, ".tastatur/aktionen/standard").delete()
            bash(home, setup.path)

            val actions = File(home, ".tastatur/aktionen")
            assertEquals(listOf("grossbuchstaben", "liste", "sortieren", "standard", "zitat"),
                TermuxLogic.parseActionList(actions.list()!!.joinToString("\n")))

            fun action(name: String, input: String) = bash(home, File(actions, name).path, stdin = input).second
            assertEquals("Hallo Welt!\n\nZweiter Absatz\n", action("standard", "Hallo   Welt!   \n\n\n\nZweiter  Absatz  \n"))
            // Umlaute werden groß; für "ß" gibt es in sed keinen Großbuchstaben.
            assertEquals("GRÜßE ÄUßERN\n", action("grossbuchstaben", "grüße äußern\n"))
            assertEquals("- eins\n\n- zwei\n", action("liste", "eins\n\nzwei\n"))
            assertEquals("> a\n> b\n", action("zitat", "a\nb\n"))
            assertEquals("a\nb\nc\n", action("sortieren", "c\na\nb\n"))

            val kuerzel = File(home, ".tastatur/kuerzel").path
            assertTrue(bash(home, kuerzel, "datum").second.matches(Regex("\\d\\d\\.\\d\\d\\.\\d{4}\n")))
            assertEquals("161\n", bash(home, kuerzel, "=23*7").second)
            assertEquals("2.5\n", bash(home, kuerzel, "=5/2").second)
            assertEquals("1.5\n", bash(home, kuerzel, "=1,5").second)
            // Nur Rechnungen, keine Befehle
            val (badCode, badOut, badErr) = bash(home, kuerzel, "=system(\"id\")")
            assertEquals(1, badCode)
            assertEquals("", badOut)
            assertTrue(badErr.contains("Ungültige Rechnung"))
            val (unknownCode, _, unknownErr) = bash(home, kuerzel, "gibtsnicht")
            assertEquals(1, unknownCode)
            assertEquals("Skript-Fehler: Unbekanntes Kürzel: gibtsnicht",
                TermuxLogic.describeFailure(TermuxResult("", unknownErr, unknownCode, -1, null)))
        } finally {
            home.deleteRecursively()
        }
    }
}
