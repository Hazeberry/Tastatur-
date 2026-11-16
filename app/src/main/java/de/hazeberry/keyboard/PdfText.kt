package de.hazeberry.keyboard

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** Liest den Text eines PDFs mit PDFBox. Läuft komplett auf dem Gerät. */
object PdfText : PdfEngine {

    @Volatile
    private var initialized = false

    /** Muss einmal vor dem ersten Lesen auf dem Gerät laufen (lädt Schriftdaten aus den Assets). */
    fun init(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    private fun open(file: File, password: String?): PDDocument = try {
        // Große PDFs nicht komplett in den Arbeitsspeicher laden.
        PDDocument.load(file, password.orEmpty(), MemoryUsageSetting.setupMixed(16L * 1024 * 1024))
    } catch (e: InvalidPasswordException) {
        throw PasswordRequired(wrong = !password.isNullOrEmpty())
    }

    /** Text je Seite, bis etwa [maxChars] Zeichen zusammen sind. */
    override fun pages(file: File, password: String?, maxChars: Int): PdfPages {
        open(file, password).use { doc ->
            val stripper = PDFTextStripper()
            val texts = ArrayList<String>()
            var total = 0
            var page = 1
            while (page <= doc.numberOfPages && total < maxChars) {
                stripper.startPage = page
                stripper.endPage = page
                val text = stripper.getText(doc)
                texts.add(text)
                total += text.length
                page++
            }
            return PdfPages(texts, doc.numberOfPages)
        }
    }

    /** Für die Texterkennung: unverschlüsselte Kopie (Androids PdfRenderer kann keine Passwörter). */
    fun decryptedCopy(file: File, password: String?, out: File) {
        open(file, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
    }

    fun isEncrypted(file: File): Boolean = try {
        PDDocument.load(file, "", MemoryUsageSetting.setupTempFileOnly()).use { it.isEncrypted }
    } catch (e: InvalidPasswordException) {
        true
    }
}
