package de.hazeberry.keyboard

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Prüft [PdfText.extract] ohne Android-Gerät: PDF erzeugen, dann Text herauslesen. */
class PdfBoxTest {

    private fun makePdf(file: File, pages: List<String>, userPassword: String? = null) {
        PDDocument().use { doc ->
            for (text in pages) {
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText(text)
                    cs.endText()
                }
            }
            if (userPassword != null) {
                val policy = StandardProtectionPolicy("owner", userPassword, AccessPermission())
                policy.encryptionKeyLength = 128
                doc.protect(policy)
            }
            doc.save(file)
        }
    }

    private fun extract(file: File, max: Int = 100_000): String = PdfText.extract(file, max)

    @Test
    fun extractsText() {
        val f = Files.createTempFile("t", ".pdf").toFile()
        makePdf(f, listOf("Erste Seite", "Zweite Seite"))
        val text = extract(f)
        assertTrue(text, text.contains("Erste Seite"))
        assertTrue(text, text.contains("Zweite Seite"))
        f.delete()
    }

    @Test
    fun encryptedWithoutUserPasswordStillReadable() {
        val f = Files.createTempFile("e", ".pdf").toFile()
        makePdf(f, listOf("Geschützt aber lesbar"), userPassword = "")
        assertTrue(extract(f).contains("lesbar"))
        f.delete()
    }

    @Test
    fun stopsReadingAfterEnoughText() {
        val f = Files.createTempFile("viele", ".pdf").toFile()
        makePdf(f, (1..50).map { "Seite $it" })
        val text = extract(f, max = 20)
        assertTrue(text, text.contains("Seite 1"))
        assertTrue(text, !text.contains("Seite 10"))
        f.delete()
    }

    @Test(expected = ReadProblem::class)
    fun passwordProtectedThrows() {
        val f = Files.createTempFile("p", ".pdf").toFile()
        makePdf(f, listOf("geheim"), userPassword = "1234")
        try {
            extract(f)
        } finally {
            f.delete()
        }
    }

    @Test
    fun emptyPdfHasNoText() {
        val f = Files.createTempFile("leer", ".pdf").toFile()
        PDDocument().use { it.addPage(PDPage()); it.save(f) }
        assertEquals("", extract(f).trim())
        f.delete()
    }
}
