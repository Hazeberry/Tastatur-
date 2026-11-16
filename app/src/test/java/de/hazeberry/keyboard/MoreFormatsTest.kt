package de.hazeberry.keyboard

import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Alte Office-Formate, Passwörter, RTF, EPUB, Archive, Erkennung am Inhalt, Texterkennung. */
class MoreFormatsTest {

    private lateinit var dir: File
    private lateinit var reader: DocumentReader

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("formats").toFile()
        reader = DocumentReader(dir, PdfText)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sample(name: String) = File("src/test/resources/samples/$name")
    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }
    private fun read(f: File, password: String? = null, max: Int = 100_000) = reader.readFile(f, f.name, null, max, password)

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, b) in entries) {
                z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    // ================================================================== Alte Office-Formate

    @Test
    fun legacyWord() {
        val p = read(sample("SampleDoc.doc"))
        assertEquals("I am a test document\nThis is page 1\nI am Calibri (Body) in font size 11\n\n\nThis is page two\nIt’s Arial Black in 16 point\nIt’s also in blue", p.text)
    }

    @Test
    fun legacyWordTableAndUnicode() {
        val table = read(sample("simple-table.doc")).text!!
        assertTrue(table, table.contains("Cell 1,1\tCell 1,2\tCell 1,3\nCell 2,1\tCell 2,2\tCell 2,3\nThis text is below the table."))
        val unicode = read(sample("HeaderFooterUnicode.doc")).text!!
        assertTrue(unicode, unicode.contains("GBP - £") && unicode.contains("EUR - €") && unicode.contains("L'Avare ou l'École du mensonge"))
    }

    @Test
    fun legacyExcel() {
        val p = read(sample("SampleSS.xls"))
        assertEquals(
            "Tabelle: First Sheet\nTest spreadsheet\n2nd row\t2nd row 2nd column\nThis one is red\n\n" +
                "Tabelle: Sheet Number 2\nStart of 2nd sheet\nSheet 2 row 2\nI'm in bold blue, on a yellow background\n" +
                "cb=1\tcb=10\tcb=2\tcb=sum\n1\t10\t2\t13",
            p.text
        )
    }

    @Test
    fun legacyExcelDates() {
        val p = read(sample("DateFormats.xls")).text!!
        assertTrue(p, p.contains("US date\t10.08.2007"))
    }

    @Test
    fun legacyPowerPointOnlyCurrentVersion() {
        val p = read(sample("basic_test_ppt_file.ppt"))
        assertEquals(
            "Folie 1\nThis is a test title\nThis is a test subtitle\nThis is on page 1\n\n" +
                "Folie 2\nThis is the title on page 2\nThis is page two\nIt has several blocks of text\nNone of them have formatting",
            p.text
        )
        val box = read(sample("with_textbox.ppt")).text!!
        assertTrue(box, box.contains("Hello, World!!!") && box.contains("Plain Text"))
    }

    @Test
    fun legacyEncryptedGivesClearMessage() {
        for (name in listOf("password_tika_binaryrc4.doc", "password.xls", "Password_Protected-hello.ppt")) {
            val p = read(sample(name))
            assertNull(name, p.text)
            assertTrue(name + ": " + p.problem, p.problem!!.contains("Passwort"))
        }
    }

    @Test
    fun outlookMessage() {
        val p = read(sample("quick.msg"))
        assertEquals("Betreff: Test the content transformer\nVon: Kevin Roast\nAn: Kevin Roast\n\nThe quick brown fox jumps over the lazy dog", p.text)
    }

    @Test
    fun cfbIsRobustAgainstGarbage() {
        val bytes = sample("SampleDoc.doc").readBytes()
        // Sektorverweise und Verzeichnis zerstören: darf nicht abstürzen oder hängen.
        for (offset in listOf(0x30, 0x2C, 0x4C, 0x200, 0x1000)) {
            val broken = bytes.copyOf()
            for (i in 0 until 16) broken[offset + i] = 0xFF.toByte()
            val p = read(file("kaputt$offset.doc", broken))
            assertTrue(p.text != null || p.problem != null)
        }
    }

    // ================================================================== Passwörter: Office, ZIP, 7z, PDF

    @Test
    fun encryptedDocxNeedsPassword() {
        val f = sample("bug53475-password-is-pass.docx")
        val without = read(f)
        assertTrue(without.needsPassword)
        assertFalse(without.wrongPassword)
        val wrong = read(f, "falsch")
        assertTrue(wrong.needsPassword && wrong.wrongPassword)
        assertEquals("The is a password protected document.", read(f, "pass").text)
    }

    @Test
    fun writeProtectedOfficeOpensWithoutAsking() {
        // Nur Schreibschutz: Office verschlüsselt dann mit einem festen Standardpasswort.
        assertEquals("Agile protected document", read(sample("protected_agile.docx")).text)
        assertTrue(read(sample("protect.xlsx")).text!!.startsWith("Tabelle: Лист1"))
    }

    private fun encryptedZip(method: EncryptionMethod): File {
        val f = File(dir, "geheim-$method.zip")
        val zip = net.lingala.zip4j.ZipFile(f, "sesam".toCharArray())
        val params = ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = method
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            fileNameInZip = "notiz.txt"
        }
        zip.addStream(ByteArrayInputStream("Streng geheim".toByteArray()), params)
        zip.close()
        return f
    }

    @Test
    fun encryptedZipAesAndZipCrypto() {
        for (method in listOf(EncryptionMethod.AES, EncryptionMethod.ZIP_STANDARD)) {
            val f = encryptedZip(method)
            val items = reader.listArchive(f, f.name)
            assertEquals(listOf("notiz.txt"), items.map { it.path })
            assertTrue(items[0].encrypted)
            val without = read(f)
            assertTrue("$method", without.needsPassword && !without.wrongPassword)
            val wrong = read(f, "falsch")
            assertTrue("$method", wrong.needsPassword && wrong.wrongPassword)
            assertEquals("[Datei: notiz.txt]\nStreng geheim", read(f, "sesam").text)
        }
    }

    @Test
    fun encrypted7z() {
        val f = sample("bla.encrypted.7z")
        assertTrue(read(f).needsPassword)
        assertTrue(read(f, "bar").wrongPassword)
        val text = read(f, "foo").text!!
        assertTrue(text, text.startsWith("[Datei: test1.xml]\n<?xml"))
    }

    // ================================================================== Archive

    private fun tar(out: OutputStream, vararg entries: Pair<String, ByteArray>) {
        TarArchiveOutputStream(out).use { t ->
            for ((n, b) in entries) {
                t.putArchiveEntry(TarArchiveEntry(n).apply { size = b.size.toLong() })
                t.write(b)
                t.closeArchiveEntry()
            }
        }
    }

    @Test
    fun tarGzBz2Xz() {
        val content = arrayOf("./src/a.kt" to "fun a() = 1".toByteArray(), "README.md" to "# Hallo".toByteArray())
        val files = listOf(
            File(dir, "p.tar").also { f -> f.outputStream().use { tar(it, *content) } },
            File(dir, "p.tar.gz").also { f -> f.outputStream().use { tar(GzipCompressorOutputStream(it), *content) } },
            File(dir, "p.tgz").also { f -> f.outputStream().use { tar(GzipCompressorOutputStream(it), *content) } },
            File(dir, "p.tar.bz2").also { f -> f.outputStream().use { tar(BZip2CompressorOutputStream(it), *content) } },
            File(dir, "p.tar.xz").also { f -> f.outputStream().use { tar(XZCompressorOutputStream(it), *content) } }
        )
        for (f in files) {
            assertEquals(f.name, listOf("src/a.kt", "README.md"), reader.listArchive(f, f.name).map { it.path })
            assertEquals(f.name, "[Datei: src/a.kt]\nfun a() = 1\n\n[Datei: README.md]\n# Hallo", read(f).text)
        }
    }

    @Test
    fun singleCompressedFiles() {
        val text = "Zeile 1\nZeile 2 äöü"
        val gz = File(dir, "log.txt.gz").also { f -> GzipCompressorOutputStream(f.outputStream()).use { it.write(text.toByteArray()) } }
        val bz = File(dir, "log.txt.bz2").also { f -> BZip2CompressorOutputStream(f.outputStream()).use { it.write(text.toByteArray()) } }
        val xz = File(dir, "log.txt.xz").also { f -> XZCompressorOutputStream(f.outputStream()).use { it.write(text.toByteArray()) } }
        for (f in listOf(gz, bz, xz)) assertEquals(f.name, text, read(f).text)
        // tar.gz ohne passende Endung wird am Inhalt erkannt.
        val hidden = File(dir, "paket.gz").also { f ->
            f.outputStream().use { tar(GzipCompressorOutputStream(it), "x.txt" to "X".toByteArray()) }
        }
        assertEquals("[Datei: x.txt]\nX", read(hidden).text)
    }

    @Test
    fun sevenZip() {
        val f = File(dir, "p.7z")
        SevenZOutputFile(f).use { out ->
            for ((n, b) in listOf("doc/a.txt" to "Alpha", "doc/b.md" to "Beta")) {
                out.putArchiveEntry(SevenZArchiveEntry().apply { name = n; size = b.length.toLong() })
                out.write(b.toByteArray())
                out.closeArchiveEntry()
            }
        }
        assertEquals(listOf("doc/a.txt", "doc/b.md"), reader.listArchive(f, f.name).map { it.path })
        assertEquals(listOf("[Datei: doc/b.md]\nBeta"), reader.readArchive(f, f.name, setOf("doc/b.md"), 1000).map { "[Datei: ${it.name}]\n${it.text}" })
    }

    @Test
    fun windowsZipWithCp437Names() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos, charset("IBM437")).use { z ->
            z.putNextEntry(ZipEntry("Grüße.txt")); z.write("Hallo".toByteArray()); z.closeEntry()
        }
        val f = file("win.zip", bos.toByteArray())
        assertEquals(listOf("Grüße.txt"), reader.listArchive(f, f.name).map { it.path })
        assertEquals("[Datei: Grüße.txt]\nHallo", read(f).text)
    }

    @Test
    fun archiveInArchiveInArchiveStops() {
        val inner = zipBytes("tief.txt" to "tief".toByteArray())
        val middle = zipBytes("innen.zip" to inner)
        val outer = file("aussen.zip", zipBytes("mitte.zip" to middle))
        val p = read(outer)
        assertNull(p.text)
        val problems = p.children.flatMap { listOf(it) + it.children }.mapNotNull { it.problem }
        assertTrue(problems.toString(), problems.any { it.contains("Archiv in Archiv in Archiv") })
    }

    // ================================================================== RTF, EPUB, HTML, OpenDocument

    @Test
    fun rtf() {
        val rtf = """{\rtf1\ansi\ansicpg1252\deff0{\fonttbl{\f0 Arial;}}{\colortbl;\red255\green0\blue0;}
{\*\generator Test;}\f0 Gr\'fc\'dfe aus K\'f6ln\par
Zweite \b fett\b0  Zeile\tab Tab\par
Unicode: §u8364? Euro\par
{\field{\*\fldinst HYPERLINK "http://x"}{\fldrslt Link}}\par
}""".replace("§", "\\")
        assertEquals("Grüße aus Köln\nZweite fett Zeile\tTab\nUnicode: € Euro\nLink", read(file("brief.rtf", rtf.toByteArray())).text)
    }

    @Test
    fun epubFollowsSpineOrder() {
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf"><manifest>
            <item id="c1" href="text/eins.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="text/zwei%20b.xhtml" media-type="application/xhtml+xml"/>
            </manifest><spine><itemref idref="c2"/><itemref idref="c1"/></spine></package>"""
        val bytes = zipBytes(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray(),
            "OEBPS/text/eins.xhtml" to "<html><head><title>T</title></head><body><h1>Kapitel 1</h1><p>Erster&nbsp;Absatz.</p></body></html>".toByteArray(),
            "OEBPS/text/zwei b.xhtml" to "<html><body><p>Vorwort</p></body></html>".toByteArray()
        )
        assertEquals("Vorwort\n\nKapitel 1\n\nErster Absatz.", read(file("buch.epub", bytes)).text)
    }

    @Test
    fun html() {
        assertEquals(
            "Hallo Welt\n\n• eins\n• zwei\n€",
            HtmlText.toText("<p>Hallo&nbsp;<b>Welt</b></p><ul><li>eins</li><li>zwei</li></ul>&#8364;&#99999999;".replace("&#99999999;", ""))
        )
        // Ungültige Zeichennummern bleiben stehen statt abzustürzen.
        assertEquals("a &#99999999; b", HtmlText.toText("a &#99999999; b"))
    }

    @Test
    fun encryptedOpenDocumentGivesMessage() {
        val bytes = zipBytes(
            "mimetype" to "application/vnd.oasis.opendocument.text".toByteArray(),
            "META-INF/manifest.xml" to "<manifest><file-entry><encryption-data/></file-entry></manifest>".toByteArray(),
            "content.xml" to "kaputt".toByteArray()
        )
        assertTrue(read(file("geheim.odt", bytes)).problem!!.contains("Passwortgeschützte OpenDocument"))
    }

    // ================================================================== Word- und Excel-Feinheiten

    @Test
    fun wordListsAndTabStops() {
        val xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:pPr><w:tabs><w:tab w:val="left" w:pos="720"/></w:tabs></w:pPr><w:r><w:t>Ohne Tab-Müll</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Punkt eins</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Unterpunkt</w:t></w:r></w:p>
            </w:body></w:document>"""
        val p = read(file("liste.docx", zipBytes("word/document.xml" to xml.toByteArray())))
        assertEquals("Ohne Tab-Müll\n• Punkt eins\n  • Unterpunkt", p.text)
    }

    @Test
    fun excelDatesGapsAndNumbers() {
        val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val bytes = zipBytes(
            "xl/workbook.xml" to """<workbook xmlns="$ns"><sheets><sheet name="Daten" sheetId="1"/></sheets></workbook>""".toByteArray(),
            "xl/styles.xml" to """<styleSheet xmlns="$ns"><numFmts count="1"><numFmt numFmtId="164" formatCode="dd/mm/yyyy\ hh:mm"/></numFmts>
                <cellStyleXfs><xf numFmtId="14"/></cellStyleXfs>
                <cellXfs><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="164"/><xf numFmtId="4"/></cellXfs></styleSheet>""".toByteArray(),
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="$ns"><sheetData>
                <row r="2"><c r="B2" t="inlineStr"><is><t>Start</t></is></c><c r="D2" s="1"><v>45000</v></c></row>
                <row r="3"><c r="C3" s="2"><v>45000.5</v></c><c r="D3" s="3"><v>0.30000000000000004</v></c></row>
                <row r="4"><c r="B4" t="b"><v>1</v></c><c r="C4"><v>10000000</v></c></row>
                </sheetData></worksheet>""".toByteArray()
        )
        assertEquals(
            "Tabelle: Blatt 1\nStart\t\t15.03.2023\n\t15.03.2023 12:00\t0.3\nWAHR\t10000000",
            read(file("t.xlsx", bytes)).text
        )
    }

    @Test
    fun excelFormatsHelpers() {
        assertEquals("01.01.1900", ExcelFormats.date(1.0, false))
        assertEquals("01.03.1900", ExcelFormats.date(61.0, false))
        assertEquals("02.01.1904", ExcelFormats.date(1.0, true))
        assertEquals("12:30", ExcelFormats.date(0.5208333333333334, false))
        assertTrue(ExcelFormats.isDate(0, "[\$-407]DD.MM.YYYY"))
        assertFalse(ExcelFormats.isDate(0, "#,##0.00 \"Tage\""))
        assertFalse(ExcelFormats.isDate(0, "0.00E+00"))
        assertEquals("1234.5", ExcelFormats.number(1234.5))
        assertEquals("-7", ExcelFormats.number(-7.0))
        assertEquals("0.1", ExcelFormats.number(0.1))
    }

    // ================================================================== Erkennung am Inhalt

    @Test
    fun detectsByContent() {
        val docx = zipBytes("[Content_Types].xml" to "<Types/>".toByteArray(), "word/document.xml" to
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Echt docx</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
        // .docx mit falscher Endung .doc
        assertEquals("Echt docx", read(file("falsch.doc", docx)).text)
        // RTF mit Endung .doc (kommt häufig vor)
        assertEquals("RTF drin", read(file("brief.doc", "{\\rtf1\\ansi RTF drin\\par}".toByteArray())).text)
        // Datei ohne Endung, die ein Word-97-Dokument ist
        assertTrue(read(file("download", sample("SampleDoc.doc").readBytes())).text!!.startsWith("I am a test document"))
        // Kein Fehlalarm bei Text, der zufällig so beginnt oder Kennungen enthält
        assertEquals("ID3 tags erklärt", read(file("notiz", "ID3 tags erklärt".toByteArray())).text)
        assertEquals("Beispiel: %PDF-1.4 steht am Anfang", read(file("hinweis.txt", "Beispiel: %PDF-1.4 steht am Anfang".toByteArray())).text)
        assertEquals("BZh, GIF8 und Rar! sind nur Wörter", read(file("w", "BZh, GIF8 und Rar! sind nur Wörter".toByteArray())).text)
    }

    @Test
    fun rarAndMediaExplained() {
        val rar = file("x.bin", byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00))
        assertTrue(read(rar).problem!!.contains("RAR"))
        assertTrue(read(file("lied.mp3", ByteArray(10))).problem!!.contains("Audio"))
    }

    // ================================================================== Texterkennung (mit Attrappe)

    private class FakeOcr : OcrEngine {
        val pdfCalls = ArrayList<List<Int>>()
        var images = 0
        override fun image(file: File, progress: Progress): String { images++; return "Text aus Bild" }
        override fun pdfPages(file: File, password: String?, pages: List<Int>, progress: Progress, onPage: (Int, String) -> Boolean) {
            pdfCalls.add(pages)
            for (p in pages) if (!onPage(p, "Erkannt $p")) break
        }
    }

    @Test
    fun ocrOnlyForScannedPages() {
        val ocr = FakeOcr()
        val pdf = object : PdfEngine {
            override fun pages(file: File, password: String?, maxChars: Int) = PdfPages(listOf("Normale Seite mit Text", "", "  \n "), 3)
        }
        val r = DocumentReader(dir, pdf, ocr)
        val p = r.readFile(file("scan.pdf", "%PDF-1.4".toByteArray()), "scan.pdf", null, 1000)
        assertEquals(listOf(listOf(1, 2)), ocr.pdfCalls)
        assertEquals("Normale Seite mit Text\n\nErkannt 1\n\nErkannt 2", p.text)
        assertTrue(p.ocr)
    }

    @Test
    fun ocrForImagesAndWithoutEngine() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ocr = FakeOcr()
        val p = DocumentReader(dir, PdfText, ocr).readFile(file("foto", png), "foto", null, 1000)
        assertEquals("Text aus Bild", p.text)
        assertTrue(p.ocr)
        assertEquals(1, ocr.images)
        assertNotNull(read(file("foto.png", png)).problem)
    }

    @Test
    fun cancelStopsReading() {
        val cancelled = object : Progress { override val cancelled = true }
        val r = DocumentReader(dir, PdfText, null, cancelled)
        try {
            r.readFile(file("a.txt", "x".toByteArray()), "a.txt", null, 100)
            throw AssertionError("hätte abbrechen müssen")
        } catch (_: CancelledException) {
        }
    }
}
