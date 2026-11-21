package de.hazeberry.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileComposeTest {

    private fun part(name: String, text: String?) = Part(name, text, problem = if (text == null) "x" else null)

    // ------------------------------------------------------------------ Vorlagen lesen

    @Test
    fun defaultTemplates() {
        val t = FileCompose.parseTemplates(FileCompose.DEFAULT_TEMPLATES)
        assertEquals(listOf("Zusammenfassen", "Analysieren", "Fragen finden"), t.map { it.name })
        assertTrue(t[0].text.startsWith("Fasse den folgenden Text"))
        assertTrue(t.all { it.text.isNotBlank() && !it.text.contains("##") })
    }

    @Test
    fun customTemplatesWithManyLinesAndCrLf() {
        val source = "## Übersetzen\r\nÜbersetze ins Englische.\r\nBehalte die Absätze.\r\n\r\n##  Kurz  \r\nSag es in einem Satz.\r\n"
        val t = FileCompose.parseTemplates(source)
        assertEquals(
            listOf(
                QueryTemplate("Übersetzen", "Übersetze ins Englische.\nBehalte die Absätze."),
                QueryTemplate("Kurz", "Sag es in einem Satz.")
            ),
            t
        )
    }

    @Test
    fun brokenTemplatesAreSkipped() {
        // Ohne Text, ohne Namen und Text vor der ersten Überschrift fallen weg.
        val source = "Vorwort, das niemand braucht\n## Leer\n\n## \nOhne Namen\n## Gut\nText\n"
        assertEquals(listOf(QueryTemplate("Gut", "Text")), FileCompose.parseTemplates(source))
    }

    @Test
    fun textWithoutHeadingIsOneTemplate() {
        assertEquals(
            listOf(QueryTemplate("Abfrage", "Fasse zusammen.\nKurz.")),
            FileCompose.parseTemplates("  Fasse zusammen.\nKurz.  \n")
        )
        assertTrue(FileCompose.parseTemplates("").isEmpty())
        assertTrue(FileCompose.parseTemplates(" \n\t ").isEmpty())
    }

    @Test
    fun limitsAndMarkdownLookalikes() {
        val long = "x".repeat(100)
        assertEquals(FileCompose.MAX_NAME, FileCompose.parseTemplates("## $long\nText").single().name.length)
        val many = (1..50).joinToString("\n") { "## V$it\nText $it" }
        assertEquals(FileCompose.MAX_TEMPLATES, FileCompose.parseTemplates(many).size)
        // "###" und "#" sind Markdown-Überschriften im Text, keine neue Vorlage.
        val t = FileCompose.parseTemplates("## A\n### Unterpunkt\n# Titel\nText").single()
        assertEquals("### Unterpunkt\n# Titel\nText", t.text)
    }

    @Test
    fun findByName() {
        val t = FileCompose.parseTemplates(FileCompose.DEFAULT_TEMPLATES)
        assertEquals("Analysieren", FileCompose.find(t, "Analysieren")?.name)
        assertNull(FileCompose.find(t, ""))
        assertNull(FileCompose.find(t, null))
        assertNull(FileCompose.find(t, "gibt es nicht"))
    }

    // ------------------------------------------------------------------ Zusammensetzen

    @Test
    fun queryComesFirstThenSeparatorThenFiles() {
        val q = QueryTemplate("Z", "Fasse zusammen.")
        assertEquals(
            "Fasse zusammen.\n\n———\n\n[Datei: a.txt]\nAlpha\n\n[Datei: b.md]\nBeta",
            FileCompose.build(q, listOf(part("a.txt", "Alpha"), part("b.md", "Beta")), forceHeaders = false)
        )
    }

    @Test
    fun singleFileHeaderFollowsSetting() {
        val one = listOf(part("a.txt", "Alpha"))
        assertEquals("Alpha", FileCompose.build(null, one, forceHeaders = false))
        assertEquals("[Datei: a.txt]\nAlpha", FileCompose.build(null, one, forceHeaders = true))
        assertEquals("Q\n\n———\n\nAlpha", FileCompose.build(QueryTemplate("n", "Q"), one, forceHeaders = false))
    }

    @Test
    fun filesWithoutTextAreIgnoredAndNoFilesMeansEmpty() {
        val mixed = listOf(part("kaputt.pdf", null), part("a.txt", "Alpha"))
        assertEquals("Alpha", FileCompose.build(null, mixed, false))
        assertEquals("", FileCompose.build(QueryTemplate("n", "Q"), listOf(part("kaputt.pdf", null)), false))
        assertEquals("", FileCompose.build(QueryTemplate("n", "Q"), emptyList(), false))
    }

    // ------------------------------------------------------------------ Vorschau

    @Test
    fun previewCutsAtLineBoundaryAndNeverBreaksEmoji() {
        assertEquals("kurz", FileCompose.preview("kurz"))
        val lines = (1..100).joinToString("\n") { "Zeile $it" }
        val p = FileCompose.preview(lines, 50)
        assertTrue(p, p.endsWith("\n…"))
        assertTrue(p, p.length <= 53)
        assertTrue(p, !p.removeSuffix("\n…").endsWith(" ") && lines.startsWith(p.removeSuffix("\n…")))
        // Surrogatpaar genau an der Grenze
        val emoji = "a".repeat(9) + "🚀" + "b".repeat(50)
        val e = FileCompose.preview(emoji, 10)
        assertTrue(e, !e.contains('\uD83D') || e.contains("🚀"))
    }

    @Test
    fun germanThousands() {
        assertEquals("0", FileCompose.count(0))
        assertEquals("999", FileCompose.count(999))
        assertEquals("1.000", FileCompose.count(1000))
        assertEquals("12.345", FileCompose.count(12345))
        assertEquals("1.234.567", FileCompose.count(1234567))
    }
}
