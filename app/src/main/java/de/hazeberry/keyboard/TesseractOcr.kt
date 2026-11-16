package de.hazeberry.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Texterkennung (OCR) mit Tesseract, komplett auf dem Gerät. Sprachdaten für Deutsch und
 * Englisch liegen in der App (assets/tessdata, "tessdata_fast", Apache-2.0).
 *
 * Für eingescannte PDF-Seiten rendert Androids PdfRenderer die Seite als Bild.
 */
class TesseractOcr(context: Context) : OcrEngine {

    private val app = context.applicationContext
    private val dataDir = File(app.filesDir, "tesseract")
    private val workDir = File(app.cacheDir, "picker")

    /** Kopiert die Sprachdaten beim ersten Mal (oder nach einem Update) aus der App. */
    private fun ensureData() {
        val tessdata = File(dataDir, "tessdata").apply { mkdirs() }
        val marker = File(dataDir, "version")
        if (marker.exists() && marker.readText() == DATA_VERSION &&
            LANGUAGES.all { File(tessdata, "$it.traineddata").length() > 0 }
        ) return
        for (lang in LANGUAGES) {
            val target = File(tessdata, "$lang.traineddata")
            val tmp = File(tessdata, "$lang.tmp")
            app.assets.open("tessdata/$lang.traineddata").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw ReadProblem("Texterkennung konnte nicht eingerichtet werden.")
            }
        }
        marker.writeText(DATA_VERSION)
    }

    private inline fun <T> withApi(block: (TessBaseAPI) -> T): T {
        ensureData()
        val api = TessBaseAPI()
        try {
            if (!api.init(dataDir.absolutePath, LANGUAGES.joinToString("+"), TessBaseAPI.OEM_LSTM_ONLY)) {
                throw ReadProblem("Texterkennung konnte nicht gestartet werden.")
            }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            return block(api)
        } finally {
            api.recycle()
        }
    }

    override fun image(file: File, progress: Progress): String {
        progress.step("Texterkennung im Bild …")
        val bitmap = decodeImage(file) ?: throw ReadProblem("Das Bild konnte nicht geöffnet werden.")
        return try {
            withApi { api ->
                api.setImage(bitmap)
                api.getUTF8Text().orEmpty()
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun pdfPages(
        file: File,
        password: String?,
        pages: List<Int>,
        progress: Progress,
        onPage: (Int, String) -> Boolean
    ) {
        progress.step("Texterkennung wird vorbereitet …")
        // PdfRenderer kann keine verschlüsselten PDFs öffnen: vorher entschlüsselte Kopie.
        var copy: File? = null
        val source = if (PdfText.isEncrypted(file)) {
            File.createTempFile("ocr", ".pdf", workDir).also {
                copy = it
                PdfText.decryptedCopy(file, password, it)
            }
        } else {
            file
        }
        try {
            val fd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: Exception) {
                fd.close()
                throw ReadProblem("Das PDF lässt sich für die Texterkennung nicht öffnen.")
            }
            try {
                withApi { api ->
                    for ((n, index) in pages.withIndex()) {
                        progress.check()
                        if (index >= renderer.pageCount) break
                        progress.step("Texterkennung: Seite ${index + 1} (${n + 1} von ${pages.size}) …")
                        val bitmap = renderPage(renderer, index)
                        val text = try {
                            api.setImage(bitmap)
                            api.getUTF8Text().orEmpty()
                        } finally {
                            bitmap.recycle()
                        }
                        if (!onPage(index, text)) break
                    }
                }
            } finally {
                renderer.close()
            }
        } finally {
            copy?.delete()
        }
    }

    /** Seite mit etwa 300 dpi rendern, aber höchstens [MAX_PIXELS] Pixel (Arbeitsspeicher). */
    private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val w = page.width.coerceAtLeast(1)
            val h = page.height.coerceAtLeast(1)
            val scale = minOf(300f / 72f, sqrt(MAX_PIXELS / (w.toFloat() * h)))
            val bitmap = Bitmap.createBitmap(
                max(1, (w * scale).toInt()), max(1, (h * scale).toInt()), Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            return bitmap
        } finally {
            page.close()
        }
    }

    /** Bild laden, verkleinert auf höchstens [MAX_PIXELS] Pixel, gedreht nach EXIF. */
    private fun decodeImage(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > MAX_PIXELS) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        ) ?: return null
        val degrees = try {
            when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (degrees == 0f) return bitmap
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    companion object {
        private val LANGUAGES = listOf("deu", "eng")
        private const val DATA_VERSION = "tessdata_fast-4.1.0"
        private const val MAX_PIXELS = 12_000_000f
    }
}
