package de.hazeberry.keyboard

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** Liest den Text eines PDFs mit PDFBox. Läuft komplett auf dem Gerät. */
object PdfText {

    @Volatile
    private var initialized = false

    /** Liest Seite für Seite, bis etwa [maxChars] Zeichen zusammen sind. */
    fun read(context: Context, file: File, maxChars: Int): String {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
        return extract(file, maxChars)
    }

    /** Der eigentliche Lesevorgang, ohne Android-Context (deshalb auch auf dem Rechner testbar). */
    fun extract(file: File, maxChars: Int): String {
        val document = try {
            // Große PDFs nicht komplett in den Arbeitsspeicher laden.
            PDDocument.load(file, MemoryUsageSetting.setupMixed(16L * 1024 * 1024))
        } catch (e: InvalidPasswordException) {
            throw ReadProblem("Das PDF ist mit einem Passwort geschützt.")
        }
        document.use { doc ->
            val stripper = PDFTextStripper()
            val out = StringBuilder()
            var page = 1
            while (page <= doc.numberOfPages && out.length < maxChars) {
                stripper.startPage = page
                stripper.endPage = page
                out.append(stripper.getText(doc))
                page++
            }
            return out.toString()
        }
    }
}
