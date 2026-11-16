package de.hazeberry.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Durchsichtige Hilfs-Activity hinter der Taste 📁.
 *
 * Öffnet den Dateiauswahldialog von Android (Storage Access Framework), liest die gewählten
 * Dateien im Hintergrund und übergibt den Text über [FileBridge] an die Tastatur.
 *
 * Das Storage Access Framework braucht keine Speicher-Berechtigung. Die App bekommt nur Zugriff
 * auf die Dateien, die du antippst, und nur für diesen Moment. Der Dialog zeigt auch Google Drive,
 * Nextcloud und andere Cloud-Anbieter, falls sie auf dem Handy installiert sind.
 */
class FilePickerActivity : Activity() {

    /** Eine gewählte Datei, so wie der Anbieter sie beschreibt. */
    private class Source(
        val uri: Uri,
        val name: String,
        val mime: String?,
        val virtual: Boolean
    )

    private val main = Handler(Looper.getMainLooper())
    private lateinit var reader: DocumentReader
    private lateinit var store: SignatureStore
    private var targetPackage: String? = null
    private var progress: AlertDialog? = null

    @Volatile
    private var cancelled = false

    private val workDir: File by lazy { File(cacheDir, "picker").apply { mkdirs() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SignatureStore(this)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE)
        reader = DocumentReader(workDir) { file, max -> PdfText.read(this, file, max) }

        // Nach einem Neustart des Prozesses ist das Ergebnis ohnehin verloren.
        if (savedInstanceState != null) {
            finish()
            return
        }
        cleanWorkDir()

        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Textdateien haben je nach Endung (.md, .yml, .kt ...) sehr unterschiedliche
            // MIME-Typen. Deshalb alles anzeigen und den Inhalt selbst prüfen.
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(pick, REQUEST_PICK)
        } catch (e: Exception) {
            toast("Auf diesem Gerät gibt es keinen Dateiauswahldialog.")
            finish()
        }
    }

    override fun onDestroy() {
        cancelled = true
        progress?.dismiss()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null) {
            finish()
            return
        }
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        if (uris.isEmpty()) data.data?.let(uris::add)
        if (uris.isEmpty()) {
            finish()
            return
        }

        showProgress(if (uris.size == 1) "Datei wird gelesen …" else "${uris.size} Dateien werden gelesen …")
        thread(name = "file-reader") {
            val sources = uris.map(::describe)
            val single = sources.singleOrNull()
            if (single != null && reader.kindOf(single.name, single.mime) == FileKind.ZIP) {
                prepareZip(single)
            } else {
                val parts = readAll(sources)
                onMain { deliver(parts) }
            }
        }
    }

    // ------------------------------------------------------------------ Lesen

    private fun readAll(sources: List<Source>): List<Part> {
        val parts = ArrayList<Part>()
        var remaining = MAX_CHARS
        for (source in sources) {
            if (cancelled) break
            if (remaining <= 0) {
                parts.add(Part(source.name, null, problem = "Nicht mehr eingefügt, die Höchstlänge ist erreicht."))
                continue
            }
            val part = readSource(source, remaining)
            remaining -= part.text?.length ?: 0
            parts.add(part)
        }
        return parts
    }

    private fun readSource(source: Source, maxChars: Int): Part {
        var tmp: File? = null
        return try {
            reader.unsupportedReason(source.name, source.mime)?.let {
                return Part(source.name, null, problem = it)
            }
            val (input, mime) = open(source)
            val kind = reader.kindOf(source.name, mime)
            // Textdateien nur so weit kopieren, wie gelesen wird; alles andere ganz.
            val limit = if (kind == FileKind.TEXT) maxChars.toLong() * 3 + 5 else MAX_COPY_BYTES
            tmp = copyToTemp(input, limit, failIfLonger = kind != FileKind.TEXT)
            reader.readFile(tmp, detectionName(source, mime), mime, maxChars)
                .let { Part(source.name, it.text, it.truncated, it.problem) }
        } catch (e: ReadProblem) {
            Part(source.name, null, problem = e.message)
        } catch (e: SecurityException) {
            Part(source.name, null, problem = "Kein Zugriff auf die Datei.")
        } catch (e: Exception) {
            Part(source.name, null, problem = "Konnte nicht gelesen werden (${e.message ?: e.javaClass.simpleName}).")
        } finally {
            tmp?.delete()
        }
    }

    /**
     * Öffnet die Datei. Google Docs, Tabellen und Präsentationen aus Google Drive sind keine
     * echten Dateien ("virtuell"). Die exportieren wir in ein lesbares Format.
     */
    private fun open(source: Source): Pair<InputStream, String?> {
        if (!source.virtual) {
            try {
                contentResolver.openInputStream(source.uri)?.let { return it to source.mime }
            } catch (e: FileNotFoundException) {
                // Vielleicht doch virtuell, unten versuchen.
            }
        }
        val offered = contentResolver.getStreamTypes(source.uri, "*/*")?.toList().orEmpty()
        val chosen = EXPORT_PREFERENCE.firstOrNull { it in offered }
            ?: throw ReadProblem("Die Datei lässt sich nicht öffnen.")
        val fd = contentResolver.openTypedAssetFileDescriptor(source.uri, chosen, null)
            ?: throw ReadProblem("Die Datei lässt sich nicht öffnen.")
        return fd.createInputStream() to chosen
    }

    /** Für die Formaterkennung: bei exportierten Google-Dateien passt die Endung zum Exportformat. */
    private fun detectionName(source: Source, mime: String?): String = when (mime) {
        "text/plain" -> if (source.virtual) "${source.name}.txt" else source.name
        DocumentReader.MIME_DOCX -> if (source.virtual) "${source.name}.docx" else source.name
        DocumentReader.MIME_XLSX -> if (source.virtual) "${source.name}.xlsx" else source.name
        DocumentReader.MIME_PPTX -> if (source.virtual) "${source.name}.pptx" else source.name
        "application/pdf" -> if (source.virtual) "${source.name}.pdf" else source.name
        else -> source.name
    }

    private fun copyToTemp(input: InputStream, limit: Long, failIfLonger: Boolean): File {
        val tmp = File.createTempFile("pick", ".bin", workDir)
        try {
            input.use { inp ->
                tmp.outputStream().use { out ->
                    if (failIfLonger) {
                        DocumentReader.copyLimited(inp, out, limit)
                    } else {
                        out.write(DocumentReader.readUpTo(inp, limit))
                    }
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        return tmp
    }

    private fun describe(uri: Uri): Source {
        var name: String? = null
        var virtual = false
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0) name = c.getString(n)
                    val f = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                    if (f >= 0 && !c.isNull(f)) {
                        virtual = c.getInt(f) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0
                    }
                }
            }
        } catch (e: Exception) {
            // Name bleibt unbekannt.
        }
        val mime = try { contentResolver.getType(uri) } catch (e: Exception) { null }
        return Source(uri, name ?: uri.lastPathSegment ?: "Datei", mime, virtual)
    }

    // ------------------------------------------------------------------ ZIP

    private fun prepareZip(source: Source) {
        var tmp: File? = null
        try {
            val (input, _) = open(source)
            tmp = copyToTemp(input, MAX_COPY_BYTES, failIfLonger = true)
            val items = reader.listZip(tmp)
            val readable = items.filter { it.problem == null }
            val file = tmp
            onMain {
                if (readable.isEmpty()) {
                    file.delete()
                    deliver(listOf(Part(source.name, null, problem = "Im Archiv ist keine lesbare Datei.")))
                } else {
                    askZipSelection(source.name, file, readable, items.size - readable.size)
                }
            }
        } catch (e: Exception) {
            tmp?.delete()
            val message = (e as? ReadProblem)?.message ?: "Das Archiv konnte nicht geöffnet werden."
            onMain { deliver(listOf(Part(source.name, null, problem = message))) }
        }
    }

    private fun askZipSelection(name: String, file: File, items: List<ZipItem>, hidden: Int) {
        hideProgress()
        val labels = items.map { "${it.path}  ·  ${size(it.size)}" }.toTypedArray()
        val checked = BooleanArray(items.size) { true }
        val title = if (hidden > 0) "Aus $name einfügen ($hidden nicht lesbar)" else "Aus $name einfügen"

        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Einfügen", null)
            .setNeutralButton("Alle an/aus", null)
            .setNegativeButton("Abbrechen") { _, _ -> file.delete(); finish() }
            .setOnCancelListener { file.delete(); finish() }
            .create()
        dialog.setOnShowListener {
            // Diese Knöpfe sollen den Dialog nicht schließen.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val target = checked.any { !it }
                for (i in checked.indices) {
                    checked[i] = target
                    dialog.listView.setItemChecked(i, target)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = items.filterIndexed { i, _ -> checked[i] }.map { it.path }.toSet()
                if (selected.isEmpty()) {
                    toast("Nichts ausgewählt.")
                    return@setOnClickListener
                }
                dialog.setOnCancelListener(null)
                dialog.dismiss()
                showProgress("${selected.size} Datei(en) werden gelesen …")
                thread(name = "zip-reader") {
                    val parts = try {
                        reader.readZip(file, selected, MAX_CHARS)
                    } catch (e: Exception) {
                        listOf(Part(name, null, problem = "Das Archiv konnte nicht gelesen werden."))
                    } finally {
                        file.delete()
                    }
                    onMain { deliver(parts) }
                }
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ Ergebnis

    private fun deliver(parts: List<Part>) {
        hideProgress()
        val ok = parts.filter { it.text != null }
        if (ok.isNotEmpty()) {
            val headers = ok.size > 1 || store.fileHeader
            val text = ok.joinToString("\n\n") { if (headers) "[Datei: ${it.name}]\n${it.text}" else it.text!! }
            FileBridge.put(text, targetPackage)
        }

        val failed = parts.filter { it.text == null }
        val cut = ok.filter { it.truncated }
        if (failed.isEmpty() && cut.isEmpty()) {
            toast(if (ok.size == 1) "Datei bereit. Tippe ins Eingabefeld." else "${ok.size} Dateien bereit. Tippe ins Eingabefeld.")
            finish()
            return
        }

        val lines = ArrayList<String>()
        cut.forEach { lines.add("• ${it.name}: gekürzt (höchstens ${MAX_CHARS / 1000}.000 Zeichen insgesamt)") }
        failed.forEach { lines.add("• ${it.name}: ${it.problem ?: "nicht lesbar"}") }
        val shown = lines.take(MAX_LINES).toMutableList()
        if (lines.size > MAX_LINES) shown.add("… und ${lines.size - MAX_LINES} weitere")

        val title = when {
            ok.isEmpty() -> "Nichts eingefügt"
            ok.size == 1 -> "1 Datei bereit"
            else -> "${ok.size} Dateien bereit"
        }
        val footer = if (ok.isEmpty()) "" else "\n\nTippe ins Eingabefeld, falls die Tastatur nicht von selbst erscheint."
        AlertDialog.Builder(this, dialogTheme())
            .setTitle(title)
            .setMessage(shown.joinToString("\n") + footer)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    // ------------------------------------------------------------------ Hilfen

    private fun showProgress(message: String) {
        hideProgress()
        progress = AlertDialog.Builder(this, dialogTheme())
            .setMessage(message)
            .setOnCancelListener { cancelled = true; finish() }
            .show()
    }

    private fun hideProgress() {
        progress?.setOnCancelListener(null)
        progress?.dismiss()
        progress = null
    }

    private fun onMain(block: () -> Unit) {
        main.post { if (!cancelled && !isFinishing && !isDestroyed) block() }
    }

    private fun dialogTheme(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) {
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        } else {
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        }
    }

    private fun cleanWorkDir() {
        workDir.listFiles()?.forEach { it.delete() }
    }

    private fun size(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.GERMANY, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_PACKAGE = "de.hazeberry.keyboard.extra.PACKAGE"

        private const val REQUEST_PICK = 1

        /** Höchstens so viele Zeichen werden insgesamt eingefügt. */
        const val MAX_CHARS = 100_000

        /** Größere Dateien werden gar nicht erst kopiert. */
        private const val MAX_COPY_BYTES = 100_000_000L

        private const val MAX_LINES = 15

        /** Exportformate für Google-Dateien, bestes zuerst. */
        private val EXPORT_PREFERENCE = listOf(
            "text/plain",
            DocumentReader.MIME_XLSX,
            DocumentReader.MIME_DOCX,
            DocumentReader.MIME_PPTX,
            "application/pdf",
            "text/csv"
        )
    }
}
