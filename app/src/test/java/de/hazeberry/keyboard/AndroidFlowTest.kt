package de.hazeberry.keyboard

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.inputmethodservice.InputMethodService
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.Proxy

/**
 * Tests auf dem simulierten Android (Robolectric): Auswahl-Activity mit ihren Dialogen,
 * Übergabe an die Tastatur und der Datei-Anbieter für Anhänge.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidFlowTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var dir: File

    @Before
    fun setUp() {
        FileBridge.clear()
        FilePickerActivity.lastToast = null
        FilePickerActivity.lastSummary = null
        FilePickerActivity.ocrEngine = object : OcrEngine {
            override fun image(file: File, progress: Progress) = "Text aus dem Bild"
            override fun pdfPages(file: File, password: String?, pages: List<Int>, progress: Progress, onPage: (Int, String) -> Boolean) {}
        }
        dir = File(context.cacheDir, "testfiles").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        FilePickerActivity.ocrEngine = null
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ Hilfen

    private fun idleUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Zeitüberschreitung: $what")
    }

    private fun dialogTitled(prefix: String): AlertDialog? {
        val d = ShadowAlertDialog.getLatestAlertDialog() ?: return null
        val title = shadowOf(d).title?.toString().orEmpty()
        return if (d.isShowing && title.startsWith(prefix)) d else null
    }

    private fun waitForDialog(prefix: String): AlertDialog {
        var found: AlertDialog? = null
        idleUntil("Dialog „$prefix“") { dialogTitled(prefix)?.also { found = it } != null }
        return found!!
    }

    private fun launch(vararg files: File, mimeTypes: Array<String> = emptyArray()): FilePickerActivity {
        val intent = Intent(context, FilePickerActivity::class.java)
            .putExtra(FilePickerActivity.EXTRA_PACKAGE, "com.example.chat")
            .putExtra(FilePickerActivity.EXTRA_MIME_TYPES, mimeTypes)
            .putExtra(FilePickerActivity.EXTRA_NO_PICKER, true)
        val activity = Robolectric.buildActivity(FilePickerActivity::class.java, intent).setup().get()
        activity.start(files.map { Uri.fromFile(it) })
        return activity
    }

    private fun write(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

    private fun encryptedZip(name: String, password: String): File {
        val f = File(dir, name)
        val zip = net.lingala.zip4j.ZipFile(f, password.toCharArray())
        for ((n, t) in listOf("plan.md" to "# Geheimer Plan", "notiz.txt" to "Nur für mich")) {
            zip.addStream(ByteArrayInputStream(t.toByteArray()), ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                fileNameInZip = n
            })
        }
        zip.close()
        return f
    }

    private fun enterPassword(dialog: AlertDialog, password: String) {
        val box = shadowOf(dialog).view as FrameLayout
        (box.getChildAt(0) as EditText).setText(password)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }

    // ------------------------------------------------------------------ Auswahl-Activity

    @Test
    fun textFileEndsUpInBridge() {
        val activity = launch(write("notizen.md", "# Einkauf\n- Milch".toByteArray()))
        idleUntil("Text bereit") { FileBridge.hasPending() && activity.isFinishing }
        assertEquals("[Datei: notizen.md]\n# Einkauf\n- Milch", FileBridge.take("com.example.chat"))
        assertEquals("Datei bereit. Tippe ins Eingabefeld.", FilePickerActivity.lastToast)
    }

    @Test
    fun encryptedZipAsksSelectionThenPasswordAndRetriesWrongOne() {
        val activity = launch(encryptedZip("geheim.zip", "sesam"))

        val selection = waitForDialog("Aus geheim.zip einfügen")
        // Nur plan.md behalten
        selection.listView.performItemClick(selection.listView, 1, 1)
        selection.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        enterPassword(waitForDialog("Passwort nötig"), "falsch")
        enterPassword(waitForDialog("Falsches Passwort"), "sesam")

        idleUntil("Text bereit") { FileBridge.hasPending() && activity.isFinishing }
        assertEquals("[Datei: plan.md]\n# Geheimer Plan", FileBridge.take("com.example.chat"))
    }

    @Test
    fun skippingPasswordReportsIt() {
        val activity = launch(encryptedZip("zu.zip", "pw"), write("offen.txt", "offen".toByteArray()))
        // Mehrfachauswahl: Archive werden ohne Auswahldialog gelesen, aber nach dem Passwort gefragt.
        waitForDialog("Passwort nötig").getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idleUntil("Zusammenfassung") { FilePickerActivity.lastSummary != null }
        assertTrue(FilePickerActivity.lastSummary!!, FilePickerActivity.lastSummary!!.contains("zu.zip: Übersprungen, die Datei ist passwortgeschützt."))
        assertEquals("[Datei: offen.txt]\noffen", FileBridge.take("com.example.chat"))
        assertTrue(!activity.isFinishing) // Zusammenfassung wartet auf OK
    }

    @Test
    fun passwordPdfThroughActivity() {
        val f = File(dir, "vertrag.pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
            val page = com.tom_roush.pdfbox.pdmodel.PDPage()
            doc.addPage(page)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(72f, 700f)
                cs.showText("Vertragstext")
                cs.endText()
            }
            doc.protect(com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                "owner", "4711", com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission()
            ).apply { encryptionKeyLength = 128 })
            doc.save(f)
        }
        val activity = launch(f)
        enterPassword(waitForDialog("Passwort nötig"), "4711")
        idleUntil("Text bereit") { FileBridge.hasPending() && activity.isFinishing }
        assertEquals("[Datei: vertrag.pdf]\nVertragstext", FileBridge.take("com.example.chat"))
    }

    @Test
    fun unsupportedFileShowsSummaryAndNothingPending() {
        launch(write("lied.mp3", ByteArray(100)))
        val summary = waitForDialog("Nichts eingefügt")
        assertNotNull(summary)
        assertTrue(FilePickerActivity.lastSummary!!.contains("Audio"))
        assertTrue(!FileBridge.hasPending())
    }

    @Test
    fun imageCanBeAttachedWhenFieldAcceptsImages() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        val activity = launch(write("foto.png", png), mimeTypes = arrayOf("image/*"))
        val choice = waitForDialog("foto.png")
        val items = (0 until choice.listView.adapter.count).map { choice.listView.adapter.getItem(it).toString() }
        assertEquals(listOf("Text im Bild erkennen und einfügen", "Als Datei anhängen"), items)
        shadowOf(choice).clickOnItem(1)

        idleUntil("Anhang bereit") { FileBridge.hasPending() && activity.isFinishing }
        val attachments = FileBridge.takeAttachments("com.example.chat")!!
        assertEquals(1, attachments.size)
        assertEquals("image/png", attachments[0].mime)
        assertEquals("content", attachments[0].uri.scheme)
        // Der Datei-Anbieter liefert genau die Bytes der Datei.
        val bytes = context.contentResolver.openInputStream(attachments[0].uri)!!.use { it.readBytes() }
        assertTrue(bytes.contentEquals(png))
        assertEquals("image/png", context.contentResolver.getType(attachments[0].uri))
    }

    @Test
    fun imageAsTextUsesOcr() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val activity = launch(write("scan.png", png))
        idleUntil("Text bereit") { FileBridge.hasPending() && activity.isFinishing }
        assertEquals("[Datei: scan.png]\nText aus dem Bild", FileBridge.take("com.example.chat"))
        assertTrue(FilePickerActivity.lastToast!!.contains("Texterkennung"))
    }

    // ------------------------------------------------------------------ Datei-Anbieter

    @Test
    fun providerRejectsTraversalAndHiddenFiles() {
        val uri = AttachmentProvider.store(context, ByteArrayInputStream("hallo".toByteArray()), "../../geheim.txt", "text/plain")
        assertEquals("geheim.txt", uri.lastPathSegment) // Pfadanteile entfernt
        val id = uri.pathSegments[0]
        for (bad in listOf(
            "content://${AttachmentProvider.AUTHORITY}/$id/.mime",
            "content://${AttachmentProvider.AUTHORITY}/$id/..%2F..%2Fshared_prefs",
            "content://${AttachmentProvider.AUTHORITY}/nicht-uuid/geheim.txt"
        )) {
            try {
                context.contentResolver.openInputStream(Uri.parse(bad))!!.close()
                throw AssertionError("hätte abgelehnt werden müssen: $bad")
            } catch (_: java.io.FileNotFoundException) {
            }
        }
        context.contentResolver.query(uri, null, null, null, null)!!.use { c ->
            c.moveToFirst()
            assertEquals("geheim.txt", c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)))
            assertEquals(5L, c.getLong(c.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE)))
        }
        AttachmentProvider.cleanup(context, now = System.currentTimeMillis() + 25 * 60 * 60 * 1000L)
        assertTrue(AttachmentProvider.dir(context).listFiles().isNullOrEmpty())
    }

    // ------------------------------------------------------------------ Tastatur

    private class Recorder {
        val text = StringBuilder()
        val contents = ArrayList<Pair<InputContentInfo, Int>>()
        var accept = true
        val connection: InputConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)
        ) { _, method, args ->
            when (method.name) {
                "commitText" -> { text.append(args[0] as CharSequence); true }
                "commitContent" -> { contents.add(args[0] as InputContentInfo to args[1] as Int); accept }
                "getTextBeforeCursor", "getTextAfterCursor" -> ""
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    else -> null
                }
            }
        } as InputConnection
    }

    private fun keyboardWith(info: EditorInfo, recorder: Recorder): KeyboardService {
        val service = Robolectric.buildService(KeyboardService::class.java).create().get()
        fun set(name: String, value: Any?) {
            InputMethodService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        set("mStartedInputConnection", recorder.connection)
        set("mInputEditorInfo", info)
        return service
    }

    private fun editor(pkg: String, type: Int = InputType.TYPE_CLASS_TEXT, mimes: Array<String>? = null) =
        EditorInfo().apply { packageName = pkg; inputType = type; contentMimeTypes = mimes }

    @Test
    fun keyboardInsertsOnlyIntoTheRightAppAndNeverIntoPasswords() {
        FileBridge.put("Dateiinhalt", "com.example.chat")

        val other = Recorder()
        keyboardWith(editor("com.other"), other).also { it.insertPendingFile(editor("com.other")) }
        assertEquals("", other.text.toString())

        val pwInfo = editor("com.example.chat", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val pw = Recorder()
        keyboardWith(pwInfo, pw).also { it.insertPendingFile(pwInfo) }
        assertEquals("", pw.text.toString())
        assertTrue(FileBridge.hasPending())

        val chatInfo = editor("com.example.chat")
        val chat = Recorder()
        keyboardWith(chatInfo, chat).also { it.insertPendingFile(chatInfo) }
        assertEquals("Dateiinhalt", chat.text.toString())
        assertTrue(!FileBridge.hasPending())
    }

    @Test
    fun keyboardCommitsAcceptedAttachmentsOnly() {
        val img = AttachmentProvider.store(context, ByteArrayInputStream(byteArrayOf(1)), "a.png", "image/png")
        val pdf = AttachmentProvider.store(context, ByteArrayInputStream(byteArrayOf(2)), "b.pdf", "application/pdf")
        FileBridge.putAttachments(
            listOf(PendingAttachment(img, "image/png", "a.png"), PendingAttachment(pdf, "application/pdf", "b.pdf")),
            "com.example.chat"
        )
        val info = editor("com.example.chat", mimes = arrayOf("image/*"))
        val rec = Recorder()
        keyboardWith(info, rec).insertPendingFile(info)
        assertEquals(1, rec.contents.size)
        assertEquals(img, rec.contents[0].first.contentUri)
        assertEquals(InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, rec.contents[0].second)
        assertEquals("Die App hat den Anhang nicht angenommen.", ShadowToast.getTextOfLatestToast())
        assertNull(FileBridge.takeAttachments("com.example.chat"))
    }
}
