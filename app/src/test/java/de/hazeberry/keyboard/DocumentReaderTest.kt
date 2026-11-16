package de.hazeberry.keyboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentReaderTest {

    private lateinit var dir: File
    private lateinit var reader: DocumentReader
    private var pdfCalls = 0

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("docreader").toFile()
        reader = DocumentReader(dir, fakePdf("PDF-Inhalt") { pdfCalls++ })
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ Hilfen

    private fun fakePdf(text: String, onCall: () -> Unit = {}) = object : PdfEngine {
        override fun pages(file: File, password: String?, maxChars: Int): PdfPages {
            onCall()
            return PdfPages(listOf(text), 1)
        }
    }

    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, b) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(b)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun xml(s: String) = s.trimIndent().toByteArray(Charsets.UTF_8)

    private val docx get() = zipBytes(
        "[Content_Types].xml" to xml("<Types/>"),
        "word/document.xml" to xml(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>Hallo</w:t></w:r><w:r><w:t xml:space="preserve"> Welt</w:t></w:r></w:p>
                <w:p><w:r><w:t>Zweite</w:t><w:tab/><w:t>Zeile äöü</w:t></w:r></w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
                <w:p><w:r><w:delText>gelöscht</w:delText></w:r></w:p>
              </w:body>
            </w:document>
            """
        )
    )

    // ------------------------------------------------------------------ Textdateien

    @Test
    fun utf8Text() {
        val p = reader.readFile(file("a.md", "# Titel\nÄrger 🚀".toByteArray()), "a.md", null, 1000)
        assertEquals("# Titel\nÄrger 🚀", p.text)
        assertFalse(p.truncated)
    }

    @Test
    fun windows1252Fallback() {
        val bytes = "Grüße aus Köln".toByteArray(charset("windows-1252"))
        assertEquals("Grüße aus Köln", reader.decodeText(bytes))
    }

    @Test
    fun utf16WithBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hallo Ü".toByteArray(Charsets.UTF_16LE)
        assertEquals("Hallo Ü", reader.decodeText(bytes))
    }

    @Test
    fun utf8BomIsRemoved() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "x".toByteArray()
        assertEquals("x", reader.decodeText(bytes))
    }

    @Test
    fun binaryIsRejected() {
        val p = reader.readFile(file("data.dat", byteArrayOf(1, 2, 0, 3, 4)), "data.dat", null, 1000)
        assertNull(p.text)
        assertNotNull(p.problem)
    }

    @Test
    fun longTextIsTruncatedWithoutBreakingEmoji() {
        val text = "a".repeat(9) + "🚀" + "b".repeat(100)
        val p = reader.readFile(file("t.txt", text.toByteArray()), "t.txt", null, 10)
        assertEquals("a".repeat(9), p.text)
        assertTrue(p.truncated)
    }

    @Test
    fun cutInsideMultibyteCharIsNotBinary() {
        // 3 Byte pro Zeichen, die Lesegrenze schneidet mitten in ein Zeichen.
        val text = "€".repeat(5000)
        val p = reader.readFile(file("e.txt", text.toByteArray()), "e.txt", null, 1000)
        assertEquals("€".repeat(1000), p.text)
        assertTrue(p.truncated)
    }

    @Test
    fun emptyFile() {
        val p = reader.readFile(file("leer.txt", ByteArray(0)), "leer.txt", null, 100)
        assertNull(p.text)
        assertEquals("Die Datei ist leer.", p.problem)
    }

    // ------------------------------------------------------------------ Formate

    @Test
    fun kinds() {
        assertEquals(FileKind.PDF, reader.kindOf("x.PDF", null))
        assertEquals(FileKind.WORD, reader.kindOf("x.docx", null))
        assertEquals(FileKind.EXCEL, reader.kindOf("x.xlsx", null))
        assertEquals(FileKind.SLIDES, reader.kindOf("x.pptx", null))
        assertEquals(FileKind.OPEN_DOCUMENT, reader.kindOf("x.odt", null))
        assertEquals(FileKind.ARCHIVE, reader.kindOf("x.zip", null))
        assertEquals(FileKind.ARCHIVE, reader.kindOf("x.tar.gz", null))
        assertEquals(FileKind.COMPRESSED, reader.kindOf("log.txt.gz", null))
        assertEquals(FileKind.LEGACY_OFFICE, reader.kindOf("alt.doc", null))
        assertEquals(FileKind.RTF, reader.kindOf("brief.rtf", null))
        assertEquals(FileKind.EPUB, reader.kindOf("buch.epub", null))
        assertEquals(FileKind.TEXT, reader.kindOf("x.yml", null))
        assertEquals(FileKind.TEXT, reader.kindOf("Makefile", null))
        assertEquals(FileKind.PDF, reader.kindOf("Rechnung", "application/pdf"))
        assertEquals(FileKind.IMAGE, reader.kindOf("foto.jpg", null))
        assertEquals(FileKind.IMAGE, reader.kindOf("bild", "image/png"))
        assertEquals(FileKind.UNSUPPORTED, reader.kindOf("lied.mp3", null))
        assertNotNull(reader.unsupportedReason("a.rar", null))
        assertNull(reader.unsupportedReason("a.txt", null))
    }

    @Test
    fun word() {
        val p = reader.readFile(file("d.docx", docx), "d.docx", null, 1000)
        assertEquals("Hallo Welt\nZweite\tZeile äöü\nA1\tB1\n", p.text!! + "\n")
        assertFalse(p.text!!.contains("gelöscht"))
    }

    @Test
    fun wordTruncated() {
        val p = reader.readFile(file("d.docx", docx), "d.docx", null, 5)
        assertEquals("Hallo", p.text)
        assertTrue(p.truncated)
    }

    @Test
    fun excel() {
        val bytes = zipBytes(
            "xl/workbook.xml" to xml(
                """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="Kosten" sheetId="1" r:id="rId2"/>
                    <sheet name="Notizen" sheetId="2" r:id="rId1"/>
                  </sheets>
                </workbook>
                """
            ),
            "xl/_rels/workbook.xml.rels" to xml(
                """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="ws" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="ws" Target="worksheets/sheet2.xml"/>
                </Relationships>
                """
            ),
            "xl/sharedStrings.xml" to xml(
                """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>Miete</t></si>
                  <si><r><t>Strom</t></r><r><t> &amp; Gas</t></r></si>
                </sst>
                """
            ),
            "xl/worksheets/sheet1.xml" to xml(
                """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="1"><c r="A1" t="inlineStr"><is><t>Notiz</t></is></c></row>
                </sheetData></worksheet>
                """
            ),
            "xl/worksheets/sheet2.xml" to xml(
                """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><f>1+1</f><v>900</v></c></row>
                  <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>120.5</v></c></row>
                </sheetData></worksheet>
                """
            )
        )
        val p = reader.readFile(file("k.xlsx", bytes), "k.xlsx", null, 1000)
        assertEquals(
            "Tabelle: Kosten\nMiete\t900\nStrom & Gas\t120.5\n\nTabelle: Notizen\nNotiz",
            p.text
        )
    }

    @Test
    fun slides() {
        fun slide(t: String) = xml(
            """
            <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                   xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>$t</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld>
            </p:sld>
            """
        )
        val bytes = zipBytes(
            "ppt/slides/slide10.xml" to slide("Zehn"),
            "ppt/slides/slide2.xml" to slide("Zwei"),
            "ppt/slides/slide1.xml" to slide("Eins")
        )
        val p = reader.readFile(file("s.pptx", bytes), "s.pptx", null, 1000)
        assertEquals("Folie 1\nEins\n\nFolie 2\nZwei\n\nFolie 10\nZehn", p.text)
    }

    @Test
    fun openDocument() {
        val bytes = zipBytes(
            "mimetype" to "application/vnd.oasis.opendocument.text".toByteArray(),
            "content.xml" to xml(
                """
                <office:document-content
                    xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0">
                  <office:body><office:text>
                    <text:h>Überschrift</text:h>
                    <text:p>Ein<text:s text:c="3"/>Satz<text:tab/>mit <text:span>Span</text:span></text:p>
                    <table:table><table:table-row>
                      <table:table-cell><text:p>X</text:p></table:table-cell>
                      <table:table-cell><text:p>Y</text:p></table:table-cell>
                    </table:table-row></table:table>
                  </office:text></office:body>
                </office:document-content>
                """
            )
        )
        val p = reader.readFile(file("o.odt", bytes), "o.odt", null, 1000)
        assertEquals("Überschrift\nEin   Satz\tmit Span\nX\tY", p.text)
    }

    @Test
    fun pdfGoesToPdfReader() {
        val p = reader.readFile(file("a.pdf", "%PDF-1.4".toByteArray()), "a.pdf", null, 1000)
        assertEquals("PDF-Inhalt", p.text)
        assertEquals(1, pdfCalls)
    }

    @Test
    fun emptyPdfExplainsScan() {
        val r = DocumentReader(dir, fakePdf("\n\n"))
        val p = r.readFile(file("scan.pdf", "%PDF".toByteArray()), "scan.pdf", null, 1000)
        assertNull(p.text)
        assertTrue(p.problem!!.contains("eingescannt"))
    }

    @Test
    fun brokenDocxGivesProblemNotCrash() {
        val p = reader.readFile(file("kaputt.docx", "kein zip".toByteArray()), "kaputt.docx", null, 1000)
        assertNull(p.text)
        assertNotNull(p.problem)
    }

    @Test
    fun xxeIsNotResolved() {
        val secret = file("geheim.txt", "GEHEIM".toByteArray())
        val bytes = zipBytes(
            "word/document.xml" to xml(
                """
                <?xml version="1.0"?>
                <!DOCTYPE d [<!ENTITY x SYSTEM "${secret.toURI()}">]>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>vor &x; nach</w:t></w:r></w:p></w:body>
                </w:document>
                """
            )
        )
        val p = reader.readFile(file("x.docx", bytes), "x.docx", null, 1000)
        assertFalse(p.text.orEmpty().contains("GEHEIM"))
    }

    // ------------------------------------------------------------------ ZIP

    @Test
    fun zipListAndRead() {
        val bytes = zipBytes(
            "projekt/README.md" to "# Projekt".toByteArray(),
            "projekt/src/main.kt" to "fun main() {}".toByteArray(),
            "projekt/doc.docx" to docx,
            "projekt/bild.png" to byteArrayOf(1, 2, 3),
            "projekt/innen.zip" to zipBytes("a.txt" to "a".toByteArray()),
            "__MACOSX/projekt/._README.md" to byteArrayOf(0, 1),
            "projekt/.DS_Store" to byteArrayOf(0, 0)
        )
        val zip = file("p.zip", bytes)
        val items = reader.listArchive(zip, "p.zip")
        assertEquals(
            listOf("projekt/README.md", "projekt/src/main.kt", "projekt/doc.docx", "projekt/bild.png", "projekt/innen.zip"),
            items.map { it.path }
        )
        // Bilder brauchen Texterkennung (hier keine), ZIP im ZIP wird gelesen.
        val readable = items.filter { it.problem == null }.map { it.path }
        assertEquals(listOf("projekt/README.md", "projekt/src/main.kt", "projekt/doc.docx", "projekt/innen.zip"), readable)

        val parts = reader.readArchive(zip, "p.zip", setOf("projekt/README.md", "projekt/doc.docx", "projekt/innen.zip"), 1000)
        assertEquals(3, parts.size)
        assertEquals("# Projekt", parts[0].text)
        assertTrue(parts[1].text!!.startsWith("Hallo Welt"))
        assertEquals("[Datei: a.txt]\na", parts[2].text)
    }

    @Test
    fun zipRespectsTotalLimit() {
        val bytes = zipBytes(
            "a.txt" to "a".repeat(8).toByteArray(),
            "b.txt" to "b".repeat(8).toByteArray(),
            "c.txt" to "c".repeat(8).toByteArray()
        )
        val parts = reader.readArchive(file("l.zip", bytes), "l.zip", setOf("a.txt", "b.txt", "c.txt"), 12)
        assertEquals("a".repeat(8), parts[0].text)
        assertEquals("b".repeat(4), parts[1].text)
        assertTrue(parts[1].truncated)
        assertNull(parts[2].text)
    }

    @Test
    fun zipInMultiSelectionIsCombined() {
        val bytes = zipBytes("x.txt" to "X".toByteArray(), "y.txt" to "Y".toByteArray())
        val p = reader.readFile(file("m.zip", bytes), "m.zip", null, 1000)
        assertEquals("[Datei: x.txt]\nX\n\n[Datei: y.txt]\nY", p.text)
    }

    @Test
    fun zipBombEntryIsSkipped() {
        // 50 MB Nullen packen sich auf wenige KB, sind entpackt aber über der Grenze.
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("bombe.docx"))
            val zeros = ByteArray(1024 * 1024)
            repeat(50) { z.write(zeros) }
            z.closeEntry()
            z.putNextEntry(ZipEntry("ok.txt"))
            z.write("ok".toByteArray())
            z.closeEntry()
        }
        val parts = reader.readArchive(file("b.zip", bos.toByteArray()), "b.zip", setOf("bombe.docx", "ok.txt"), 1000)
        assertNull(parts[0].text)
        assertTrue(parts[0].problem!!.contains("Zu groß"))
        assertEquals("ok", parts[1].text)
    }
}
