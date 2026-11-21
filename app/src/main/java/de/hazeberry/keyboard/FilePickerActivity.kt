package de.hazeberry.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.webkit.MimeTypeMap
import android.graphics.Typeface
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Durchsichtige Hilfs-Activity hinter der Taste 📁.
 *
 * Öffnet den Dateiauswahldialog von Android (Storage Access Framework), liest die gewählten
 * Dateien im Hintergrund und übergibt Text oder Anhänge über [FileBridge] an die Tastatur.
 *
 * Das Storage Access Framework braucht keine Speicher-Berechtigung. Die App bekommt nur Zugriff
 * auf die Dateien, die du antippst, und nur für diesen Moment. Der Dialog zeigt auch Google Drive,
 * Nextcloud und andere Cloud-Anbieter, falls sie auf dem Handy installiert sind.
 *
 * Der Ablauf läuft in einem Hintergrund-Thread. Fragen an dich (Passwort, Auswahl im Archiv,
 * Text oder Anhang) zeigt er als Dialog und wartet auf die Antwort ([ask]).
 */
class FilePickerActivity : Activity() {

    /** Eine gewählte Datei, so wie der Anbieter sie beschreibt. */
    private class Source(val uri: Uri, val name: String, val mime: String?, val virtual: Boolean)

    private enum class Mode { TEXT, ATTACH, SHARE }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: SignatureStore
    private var targetPackage: String? = null
    private var acceptedMimes: Array<String> = emptyArray()
    private var progressDialog: AlertDialog? = null

    @Volatile
    private var cancelled = false

    @Volatile
    private var waiting: CountDownLatch? = null

    private val workDir: File by lazy { File(cacheDir, "picker").apply { mkdirs() } }

    private val progress = object : Progress {
        override fun step(message: String) {
            main.post { progressDialog?.setMessage(message) }
        }
        override val cancelled: Boolean get() = this@FilePickerActivity.cancelled
    }

    private val reader: DocumentReader by lazy {
        DocumentReader(workDir, PdfText, ocrEngine ?: TesseractOcr(this), progress)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SignatureStore(this)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE)
        acceptedMimes = intent.getStringArrayExtra(EXTRA_MIME_TYPES) ?: emptyArray()

        // Nach einem Neustart des Prozesses ist das Ergebnis ohnehin verloren.
        if (savedInstanceState != null) {
            finish()
            return
        }
        PdfText.init(this)
        workDir.listFiles()?.forEach { it.delete() }
        AttachmentProvider.cleanup(this)
        if (intent.getBooleanExtra(EXTRA_NO_PICKER, false)) return

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
        waiting?.countDown()
        progressDialog?.dismiss()
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
        start(uris)
    }

    /** Startet die Verarbeitung der gewählten Dateien (auch von Tests aufgerufen). */
    internal fun start(uris: List<Uri>) {
        showProgress(if (uris.size == 1) "Datei wird gelesen …" else "${uris.size} Dateien werden gelesen …")
        thread(name = "file-reader") {
            try {
                run(uris.map(::describe))
            } catch (e: CancelledException) {
                onMain { finish() }
            } catch (e: Throwable) {
                val message = "Unerwarteter Fehler (${e.message ?: e.javaClass.simpleName})."
                onMain { deliver(listOf(Part("Dateien", null, problem = message))) }
            } finally {
                workDir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    private fun run(sources: List<Source>) {
        when (chooseMode(sources)) {
            Mode.TEXT -> readAsText(sources)
            Mode.ATTACH -> attach(sources, share = false)
            Mode.SHARE -> attach(sources, share = true)
            null -> onMain { finish() }
        }
    }

    // ------------------------------------------------------------------ Text oder Anhang?

    private fun mimeOf(source: Source): String {
        val m = source.mime?.lowercase()
        if (!m.isNullOrEmpty() && m != "application/octet-stream") return m
        val ext = source.name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: COMMON_MIME[ext] ?: "application/octet-stream"
    }

    /** Teilen-Intent an genau die App, aus der 📁 kam. [uris] null: nur zum Prüfen. */
    private fun shareIntent(sources: List<Source>, uris: List<Uri>?): Intent? {
        val target = targetPackage ?: return null
        val mimes = sources.map(::mimeOf).distinct()
        val type = mimes.singleOrNull()
            ?: mimes.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" }
            ?: "*/*"
        val intent = if (sources.size == 1) {
            Intent(Intent.ACTION_SEND).apply { uris?.let { putExtra(Intent.EXTRA_STREAM, it[0]) } }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                uris?.let { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(it)) }
            }
        }
        intent.type = type
        intent.setPackage(target)
        return intent
    }

    private fun chooseMode(sources: List<Source>): Mode? {
        val anyVirtual = sources.any { it.virtual }
        val canAttach = !anyVirtual && acceptedMimes.isNotEmpty() && sources.all { s ->
            acceptedMimes.any { ClipDescription.compareMimeTypes(mimeOf(s), it) }
        }
        val canShare = !anyVirtual && shareIntent(sources, null)?.let {
            packageManager.queryIntentActivities(it, 0).isNotEmpty()
        } == true
        if (!canAttach && !canShare) return Mode.TEXT

        val onlyImages = sources.all { mimeOf(it).startsWith("image/") }
        val options = ArrayList<Pair<Mode, String>>()
        options.add(Mode.TEXT to if (onlyImages) "Text im Bild erkennen und einfügen" else "Inhalt als Text einfügen")
        if (canAttach) options.add(Mode.ATTACH to if (sources.size == 1) "Als Datei anhängen" else "Als Dateien anhängen")
        if (canShare) options.add(Mode.SHARE to "An ${appLabel()} senden (über „Teilen“, evtl. neuer Chat)")

        val choice = ask<Int> { answer ->
            AlertDialog.Builder(this, dialogTheme())
                .setTitle(if (sources.size == 1) sources[0].name else "${sources.size} Dateien")
                .setItems(options.map { it.second }.toTypedArray()) { _, which -> answer(which) }
                .setNegativeButton("Abbrechen") { _, _ -> answer(null) }
                .setOnCancelListener { answer(null) }
                .show()
        } ?: return null
        return options[choice].first
    }

    private fun appLabel(): String {
        val target = targetPackage ?: return "die App"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(target, 0)).toString()
        } catch (e: Exception) {
            "die App"
        }
    }

    // ------------------------------------------------------------------ Anhänge

    private fun attach(sources: List<Source>, share: Boolean) {
        showProgressFromWorker("Datei wird vorbereitet …")
        val stored = try {
            sources.map { s -> open(s).first.use { AttachmentProvider.store(this, it, s.name, mimeOf(s)) } to s }
        } catch (e: Exception) {
            val message = (e as? ReadProblem)?.message ?: "Die Datei konnte nicht vorbereitet werden."
            return onMain { deliver(listOf(Part(sources.first().name, null, problem = message))) }
        }
        if (share) {
            val intent = shareIntent(sources, stored.map { it.first }) ?: return onMain { finish() }
            intent.clipData = ClipData.newUri(contentResolver, sources[0].name, stored[0].first).apply {
                stored.drop(1).forEach { addItem(ClipData.Item(it.first)) }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            onMain {
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    toast("Senden an ${appLabel()} hat nicht geklappt.")
                }
                finish()
            }
        } else {
            FileBridge.putAttachments(stored.map { (uri, s) -> PendingAttachment(uri, mimeOf(s), s.name) }, targetPackage)
            onMain {
                toast(if (stored.size == 1) "Anhang bereit. Tippe ins Eingabefeld." else "${stored.size} Anhänge bereit. Tippe ins Eingabefeld.")
                finish()
            }
        }
    }

    // ------------------------------------------------------------------ Text

    private fun readAsText(sources: List<Source>) {
        val single = sources.singleOrNull()
        if (single != null) {
            val (tmp, name, mime) = copy(single, MAX_COPY_BYTES) ?: return
            if (reader.detect(tmp, name, mime).first == FileKind.ARCHIVE) {
                readArchive(single, tmp, name)
                return
            }
            val part = readWithPassword(single.name, tmp, name, mime, MAX_CHARS)
            onMain { deliver(listOf(part)) }
            return
        }
        val parts = ArrayList<Part>()
        var remaining = MAX_CHARS
        for ((i, source) in sources.withIndex()) {
            progress.check()
            if (remaining <= 0) {
                parts.add(Part(source.name, null, problem = "Nicht mehr eingefügt, die Höchstlänge ist erreicht."))
                continue
            }
            progress.step("Lese ${source.name} (${i + 1} von ${sources.size}) …")
            val (tmp, name, mime) = copy(source, MAX_COPY_BYTES, report = { parts.add(it) }) ?: continue
            val part = readWithPassword(source.name, tmp, name, mime, remaining)
            tmp.delete()
            remaining -= part.text?.length ?: 0
            parts.add(part)
        }
        onMain { deliver(parts) }
    }

    /** Liest, und fragt bei Bedarf nach dem Passwort (wiederholt, wenn es falsch war). */
    private fun readWithPassword(display: String, tmp: File, name: String, mime: String?, maxChars: Int): Part {
        var password: String? = null
        while (true) {
            val part = reader.readFile(tmp, name, mime, maxChars, password).copy(name = display)
            if (!part.needsPassword) return part
            password = askPassword(display, wrong = part.wrongPassword)
                ?: return part.copy(problem = "Übersprungen, die Datei ist passwortgeschützt.")
            showProgressFromWorker("$display wird gelesen …")
        }
    }

    private fun readArchive(source: Source, tmp: File, name: String) {
        val skipped = Part(source.name, null, problem = "Übersprungen, das Archiv ist passwortgeschützt.")
        var password: String? = null
        var items: List<ArchiveItem>
        while (true) {
            try {
                items = reader.listArchive(tmp, name, password)
                break
            } catch (e: PasswordRequired) {
                password = askPassword(source.name, e.wrong) ?: return onMain { deliver(listOf(skipped)) }
            } catch (e: ReadProblem) {
                return onMain { deliver(listOf(Part(source.name, null, problem = e.message))) }
            }
        }
        val readable = items.filter { it.readable }
        if (readable.isEmpty()) {
            return onMain { deliver(listOf(Part(source.name, null, problem = "Im Archiv ist keine lesbare Datei."))) }
        }
        val selected = askSelection(source.name, readable, items.size - readable.size) ?: return onMain { finish() }
        if (password == null && readable.any { it.path in selected && it.encrypted }) {
            password = askPassword(source.name, wrong = false) ?: return onMain { deliver(listOf(skipped)) }
        }
        while (true) {
            showProgressFromWorker("${selected.size} Datei(en) werden gelesen …")
            try {
                val parts = reader.readArchive(tmp, name, selected, MAX_CHARS, password)
                onMain { deliver(parts) }
                return
            } catch (e: PasswordRequired) {
                password = askPassword(source.name, e.wrong) ?: return onMain { deliver(listOf(skipped)) }
            } catch (e: ReadProblem) {
                return onMain { deliver(listOf(Part(source.name, null, problem = e.message))) }
            }
        }
    }

    // ------------------------------------------------------------------ Fragen an den Nutzer

    /**
     * Zeigt auf dem Hauptthread einen Dialog und wartet im Hintergrund auf die Antwort.
     * [show] ruft `answer` auf (null = abgebrochen); nur der erste Aufruf zählt.
     */
    private fun <T> ask(show: (answer: (T?) -> Unit) -> Unit): T? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<T?>()
        waiting = latch
        main.post {
            if (cancelled || isFinishing || isDestroyed) {
                latch.countDown()
                return@post
            }
            hideProgress()
            var done = false
            show { value ->
                if (!done) {
                    done = true
                    result.set(value)
                    latch.countDown()
                }
            }
        }
        latch.await()
        waiting = null
        if (cancelled) throw CancelledException()
        return result.get()
    }

    private fun askPassword(name: String, wrong: Boolean): String? = ask<String> { answer ->
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Passwort"
            isSingleLine = true
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle(if (wrong) "Falsches Passwort" else "Passwort nötig")
            .setMessage(
                (if (wrong) "Das Passwort für „$name“ stimmt nicht. Noch einmal versuchen?"
                else "„$name“ ist mit einem Passwort geschützt.") +
                    "\n\nDas Passwort wird nur zum Öffnen benutzt und nicht gespeichert."
            )
            .setView(box)
            .setPositiveButton("Öffnen") { _, _ -> answer(field.text.toString()) }
            .setNegativeButton("Überspringen") { _, _ -> answer(null) }
            .setOnCancelListener { answer(null) }
            .show()
        field.requestFocus()
    }

    private fun askSelection(name: String, items: List<ArchiveItem>, hidden: Int): Set<String>? =
        ask<Set<String>> { answer ->
            val labels = items.map { item ->
                buildString {
                    append(item.path).append("  ·  ").append(size(item.size))
                    if (item.kind == FileKind.IMAGE) append("  ·  Texterkennung")
                    if (item.encrypted) append("  ·  🔒")
                }
            }.toTypedArray()
            val checked = BooleanArray(items.size) { items[it].preselected }
            val title = if (hidden > 0) "Aus $name einfügen ($hidden nicht lesbar)" else "Aus $name einfügen"
            val dialog = AlertDialog.Builder(this, dialogTheme())
                .setTitle(title)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("Einfügen", null)
                .setNeutralButton("Alle an/aus", null)
                .setNegativeButton("Abbrechen") { _, _ -> answer(null) }
                .setOnCancelListener { answer(null) }
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
                    answer(selected)
                    dialog.dismiss()
                }
            }
            dialog.show()
        }

    // ------------------------------------------------------------------ Dateien öffnen

    /**
     * Kopiert die Datei in den Arbeitsordner. Gibt (Datei, Name für die Erkennung, MIME) zurück
     * oder null, wenn das nicht ging (dann ist das Problem über [report] gemeldet bzw. angezeigt).
     */
    private fun copy(source: Source, max: Long, report: ((Part) -> Unit)? = null): Triple<File, String, String?>? {
        fun fail(message: String): Triple<File, String, String?>? {
            val part = Part(source.name, null, problem = message)
            if (report != null) report(part) else onMain { deliver(listOf(part)) }
            return null
        }
        reader.unsupportedReason(source.name, source.mime)?.let { return fail(it) }
        return try {
            val (input, mime) = open(source)
            val tmp = File.createTempFile("pick", ".bin", workDir)
            val complete = input.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var complete = true
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        if (total + n > max) {
                            out.write(buf, 0, (max - total).toInt())
                            complete = false
                            break
                        }
                        out.write(buf, 0, n)
                        total += n
                        progress.check()
                    }
                    complete
                }
            }
            val name = detectionName(source, mime)
            // Zu große Textdateien: der Anfang reicht. Alles andere wäre kaputt.
            if (!complete && reader.detect(tmp, name, mime).first != FileKind.TEXT) {
                tmp.delete()
                return fail("Zu groß (über ${max / 1_000_000} MB).")
            }
            Triple(tmp, name, mime)
        } catch (e: CancelledException) {
            throw e
        } catch (e: ReadProblem) {
            fail(e.message ?: "Konnte nicht gelesen werden.")
        } catch (e: SecurityException) {
            fail("Kein Zugriff auf die Datei.")
        } catch (e: Exception) {
            fail("Konnte nicht gelesen werden (${e.message ?: e.javaClass.simpleName}).")
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
    private fun detectionName(source: Source, mime: String?): String {
        if (!source.virtual) return source.name
        return when (mime) {
            "text/plain" -> "${source.name}.txt"
            DocumentReader.MIME_DOCX -> "${source.name}.docx"
            DocumentReader.MIME_XLSX -> "${source.name}.xlsx"
            DocumentReader.MIME_PPTX -> "${source.name}.pptx"
            "application/pdf" -> "${source.name}.pdf"
            else -> source.name
        }
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

    // ------------------------------------------------------------------ Ergebnis

    private fun deliver(parts: List<Part>) {
        hideProgress()
        val ok = parts.filter { it.text != null }

        // Probleme einzelner Dateien in Archiven mit anzeigen.
        val flat = parts.flatMap { p ->
            if (p.children.isEmpty()) listOf(p) else p.children.map { it.copy(name = "${p.name} › ${it.name}") }
        }
        val failed = flat.filter { it.text == null }
        val cut = flat.filter { it.text != null && it.truncated }
        val ocrUsed = flat.any { it.ocr } || parts.any { it.ocr }
        val lines = ArrayList<String>()
        cut.forEach { lines.add("• ${it.name}: gekürzt (höchstens ${MAX_CHARS / 1000}.000 Zeichen insgesamt)") }
        failed.forEach { lines.add("• ${it.name}: ${it.problem ?: "nicht lesbar"}") }
        val notes = ArrayList(lines.take(MAX_LINES))
        if (lines.size > MAX_LINES) notes.add("… und ${lines.size - MAX_LINES} weitere")
        val notesText = (notes + if (ocrUsed) listOf("Ein Teil des Textes stammt aus der Texterkennung und kann Fehler enthalten.") else emptyList())
            .joinToString("\n")

        when {
            ok.isEmpty() -> showSummary("Nichts eingefügt", notesText)
            store.filePreview -> showPreview(ok, notesText)
            else -> {
                // Ohne Vorschau: sofort einfügen, mit der zuletzt benutzten Abfrage.
                val query = FileCompose.find(store.queryTemplates, store.lastQuery)
                FileBridge.put(FileCompose.build(query, ok, store.fileHeader), targetPackage)
                if (notes.isEmpty()) {
                    val base = if (ok.size == 1) "Datei bereit" else "${ok.size} Dateien bereit"
                    toast(if (ocrUsed) "$base (Texterkennung, bitte prüfen). Tippe ins Eingabefeld." else "$base. Tippe ins Eingabefeld.")
                    finish()
                } else {
                    val title = if (ok.size == 1) "1 Datei bereit" else "${ok.size} Dateien bereit"
                    showSummary(title, notesText + "\n\nTippe ins Eingabefeld, falls die Tastatur nicht von selbst erscheint.")
                }
            }
        }
    }

    private fun showSummary(title: String, message: String) {
        lastSummary = message
        AlertDialog.Builder(this, dialogTheme())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * Vorschau vor dem Einfügen: Abfrage wählen, Dateien an- oder abwählen, fertigen Text ansehen,
     * dann einfügen oder in die Zwischenablage kopieren.
     */
    private fun showPreview(ok: List<Part>, notesText: String) {
        val templates = store.queryTemplates
        var query: QueryTemplate? = FileCompose.find(templates, store.lastQuery)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setPadding(0, dp(12), 0, dp(2))
            alpha = 0.7f
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        // Abfrage wählen
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val none = RadioButton(this).apply { text = "Keine Abfrage"; tag = "query:"; id = View.generateViewId() }
        group.addView(none)
        val byId = HashMap<Int, QueryTemplate?>().apply { put(none.id, null) }
        for (t in templates) {
            val rb = RadioButton(this).apply { text = t.name; tag = "query:${t.name}"; id = View.generateViewId() }
            group.addView(rb)
            byId[rb.id] = t
            if (t == query) group.check(rb.id)
        }
        if (query == null) group.check(none.id)
        root.addView(label("Abfrage davorsetzen"))
        root.addView(group)

        // Dateien wählen (bei mehreren; bei sehr vielen, z. B. aus einem Archiv, ist die Auswahl
        // schon im Archiv-Dialog passiert und die Liste wäre endlos)
        val checks = ArrayList<CheckBox>()
        if (ok.size in 2..MAX_PREVIEW_FILES) {
            root.addView(label("Dateien"))
            for ((i, part) in ok.withIndex()) {
                val box = CheckBox(this).apply {
                    text = "${part.name} · ${FileCompose.count(part.text!!.length)} Zeichen"
                    tag = "file:$i"
                    isChecked = true
                }
                checks.add(box)
                root.addView(box)
            }
        }

        if (notesText.isNotBlank()) {
            root.addView(label("Hinweise"))
            root.addView(TextView(this).apply { text = notesText; textSize = 13f; tag = "notes" })
        }

        val counter = TextView(this).apply { tag = "counter"; textSize = 13f }
        val preview = TextView(this).apply {
            tag = "preview"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0x1A808080)
            setTextIsSelectable(true)
        }
        root.addView(label("Fertiger Text"))
        root.addView(counter)
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        fun selected(): List<Part> = if (checks.isEmpty()) ok else ok.filterIndexed { i, _ -> checks[i].isChecked }
        fun composed(): String = FileCompose.build(query, selected(), store.fileHeader)

        val title = if (ok.size == 1) "1 Datei bereit" else "${ok.size} Dateien bereit"
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Einfügen", null)
            .setNeutralButton("In Zwischenablage", null)
            .setNegativeButton("Abbrechen") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()

        fun refresh() {
            val text = composed()
            counter.text = "${FileCompose.count(text.length)} Zeichen"
            preview.text = if (text.isEmpty()) "Keine Datei ausgewählt." else FileCompose.preview(text)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = text.isNotEmpty()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = text.isNotEmpty()
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            query = byId[checkedId]
            store.lastQuery = query?.name.orEmpty()
            refresh()
        }
        checks.forEach { it.setOnCheckedChangeListener { _, _ -> refresh() } }

        dialog.setOnShowListener {
            // Diese Knöpfe schließen den Dialog nicht von selbst, damit ein Fehler sichtbar bleibt.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = composed()
                if (text.isEmpty()) return@setOnClickListener
                FileBridge.put(text, targetPackage)
                toast(
                    if (ok.any { it.ocr }) "Bereit (Texterkennung, bitte prüfen). Tippe ins Eingabefeld."
                    else "Bereit. Tippe ins Eingabefeld."
                )
                dialog.dismiss()
                finish()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val text = composed()
                if (text.isEmpty()) return@setOnClickListener
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Dateiinhalt", text))
                } catch (e: Exception) {
                    toast("Kopieren hat nicht geklappt (Text zu groß?).")
                    return@setOnClickListener
                }
                toast("In die Zwischenablage kopiert. Zum Einfügen ins Feld tippen und „Einfügen“ wählen.")
                dialog.dismiss()
                finish()
            }
            refresh()
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ Hilfen

    private fun showProgress(message: String) {
        hideProgress()
        progressDialog = AlertDialog.Builder(this, dialogTheme())
            .setMessage(message)
            .setNegativeButton("Abbrechen") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .show()
    }

    private fun showProgressFromWorker(message: String) = onMain { showProgress(message) }

    private fun cancel() {
        cancelled = true
        waiting?.countDown()
        finish()
    }

    private fun hideProgress() {
        progressDialog?.setOnCancelListener(null)
        progressDialog?.dismiss()
        progressDialog = null
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

    private fun size(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.GERMANY, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    private fun toast(message: String) {
        lastToast = message
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_PACKAGE = "de.hazeberry.keyboard.extra.PACKAGE"
        const val EXTRA_MIME_TYPES = "de.hazeberry.keyboard.extra.MIME_TYPES"

        /** Nur für Tests: keinen Dateidialog öffnen, Dateien kommen über [start]. */
        internal const val EXTRA_NO_PICKER = "de.hazeberry.keyboard.extra.NO_PICKER"

        private const val REQUEST_PICK = 1

        /** Höchstens so viele Zeichen werden insgesamt eingefügt. */
        const val MAX_CHARS = 100_000

        /** Größere Dateien werden nicht gelesen (Textdateien: nur der Anfang). */
        private const val MAX_COPY_BYTES = 100_000_000L

        private const val MAX_LINES = 15

        /** Bis zu so vielen Dateien gibt es in der Vorschau einzelne Häkchen. */
        private const val MAX_PREVIEW_FILES = 30

        /** Für Tests: eine andere Texterkennung einsetzen. */
        @Volatile
        internal var ocrEngine: OcrEngine? = null

        /** Für Tests: zuletzt gezeigte Meldungen. */
        @Volatile
        internal var lastToast: String? = null

        @Volatile
        internal var lastSummary: String? = null

        /** Rückfall, wenn weder Anbieter noch System den Typ kennen. */
        private val COMMON_MIME = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
            "webp" to "image/webp", "heic" to "image/heic", "pdf" to "application/pdf", "txt" to "text/plain",
            "md" to "text/markdown", "csv" to "text/csv", "zip" to "application/zip",
            "docx" to DocumentReader.MIME_DOCX, "xlsx" to DocumentReader.MIME_XLSX, "pptx" to DocumentReader.MIME_PPTX
        )

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
