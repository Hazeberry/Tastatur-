package de.hazeberry.keyboard

import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
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
import org.robolectric.shadows.ShadowToast
import java.lang.reflect.Proxy

/** Termux-Anbindung auf dem simulierten Android: Aufruf, Rückgabe, Taste, Kürzel, Schutzregeln. */
@RunWith(RobolectricTestRunner::class)
class TermuxAndroidTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /** Termux-Ersatz: merkt sich Aufrufe, der Test beantwortet sie. */
    private class FakeTermux : TermuxRunner {
        class Call(val label: String, val args: List<String>, val stdin: String?, val callback: (TermuxResult) -> Unit)
        val calls = ArrayList<Call>()
        override fun run(label: String, args: List<String>, stdin: String?, callback: (TermuxResult) -> Unit) {
            calls.add(Call(label, args, stdin, callback))
        }
        fun answer(stdout: String, exit: Int = 0, stderr: String = "") =
            calls.removeAt(0).callback(TermuxResult(stdout, stderr, exit, TermuxResult.RESULT_OK, null))
    }

    /** Ein Eingabefeld mit Text, Cursor und Markierung, wie eine App es der Tastatur zeigt. */
    private class FakeEditor(initial: String) {
        val text = StringBuilder(initial)
        var start = initial.length
        var end = initial.length

        val connection: InputConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)
        ) { _, m, a ->
            when (m.name) {
                "commitText" -> {
                    val s = (a[0] as CharSequence).toString()
                    text.replace(start, end, s); start += s.length; end = start; true
                }
                "setSelection" -> { start = a[0] as Int; end = a[1] as Int; true }
                "deleteSurroundingText" -> {
                    val n = minOf(a[0] as Int, start)
                    text.delete(start - n, start); start -= n; end -= n; true
                }
                "getTextBeforeCursor" -> text.substring(maxOf(0, start - (a[0] as Int)), start)
                "getTextAfterCursor" -> text.substring(end, minOf(text.length, end + (a[0] as Int)))
                "getSelectedText" -> if (start == end) null else text.substring(start, end)
                "getExtractedText" -> ExtractedText().apply { this.text = this@FakeEditor.text.toString() }
                "beginBatchEdit", "endBatchEdit" -> true
                else -> when (m.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    else -> null
                }
            }
        } as InputConnection

        override fun toString() = text.toString()
    }

    private lateinit var fake: FakeTermux

    @Before
    fun setUp() {
        fake = FakeTermux()
        KeyboardService.testTermux = fake
        SignatureStore(context).apply { termuxEnabled = true; termuxTriggers = true; termuxAction = "standard" }
    }

    @After
    fun tearDown() {
        KeyboardService.testTermux = null
    }

    private fun keyboard(editor: FakeEditor, type: Int = InputType.TYPE_CLASS_TEXT): KeyboardService {
        val service = Robolectric.buildService(KeyboardService::class.java).create().get()
        val info = EditorInfo().apply { packageName = "com.example.chat"; inputType = type; fieldId = 42 }
        fun set(name: String, value: Any?) =
            InputMethodService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        set("mStartedInputConnection", editor.connection)
        set("mInputEditorInfo", info)
        return service
    }

    private fun typeChars(service: KeyboardService, s: String) = s.forEach { service.onChar(it.toString()) }

    // ------------------------------------------------------------------ Aktionen

    @Test
    fun actionReplacesWholeField() {
        val editor = FakeEditor("hallo welt")
        keyboard(editor).runAction("standard")
        val call = fake.calls.single()
        assertEquals(listOf("${TermuxLogic.ACTIONS_DIR}/standard"), call.args)
        assertEquals("hallo welt", call.stdin)
        fake.answer("HALLO WELT\n")
        assertEquals("HALLO WELT", editor.toString())
    }

    @Test
    fun actionReplacesOnlySelection() {
        val editor = FakeEditor("eins zwei drei").apply { start = 5; end = 9 }
        keyboard(editor).runAction("grossbuchstaben")
        assertEquals("zwei", fake.calls.single().stdin)
        fake.answer("ZWEI\n")
        assertEquals("eins ZWEI drei", editor.toString())
    }

    @Test
    fun emptyFieldInsertsOutput() {
        val editor = FakeEditor("")
        keyboard(editor).runAction("vorlage")
        assertEquals("", fake.calls.single().stdin)
        fake.answer("Sehr geehrte Damen und Herren,\n")
        assertEquals("Sehr geehrte Damen und Herren,", editor.toString())
    }

    @Test
    fun changedTextGoesToClipboardInstead() {
        val editor = FakeEditor("Entwurf")
        keyboard(editor).runAction("standard")
        editor.text.append(" geändert") // Nutzer tippt weiter, während Termux arbeitet
        editor.start = editor.text.length; editor.end = editor.start
        fake.answer("ERGEBNIS\n")
        assertEquals("Entwurf geändert", editor.toString())
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!
        assertEquals("ERGEBNIS", clip.getItemAt(0).text.toString())
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Zwischenablage"))
    }

    @Test
    fun emptyOutputNeverDeletesText() {
        val editor = FakeEditor("wichtiger Text")
        keyboard(editor).runAction("kaputt")
        fake.answer("")
        assertEquals("wichtiger Text", editor.toString())
        assertTrue(ShadowToast.getTextOfLatestToast().contains("nichts ausgegeben"))
    }

    @Test
    fun scriptErrorIsShown() {
        val editor = FakeEditor("x")
        keyboard(editor).runAction("standard")
        fake.answer("", exit = 1, stderr = "curl: (6) Could not resolve host")
        assertEquals("x", editor.toString())
        assertEquals("Skript-Fehler: curl: (6) Could not resolve host", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun passwordFieldsAreLocked() {
        val editor = FakeEditor("geheim")
        keyboard(editor, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).runAction("standard")
        assertTrue(fake.calls.isEmpty())
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Passwortfeldern"))
    }

    // ------------------------------------------------------------------ Kürzel

    @Test
    fun triggerAfterSpaceIsReplaced() {
        val editor = FakeEditor("Heute ist ")
        val service = keyboard(editor)
        typeChars(service, ";;datum ")
        val call = fake.calls.single()
        assertEquals(listOf(TermuxLogic.TRIGGER_SCRIPT, "datum"), call.args)
        assertNull(call.stdin)
        fake.answer("16.11.2026\n")
        assertEquals("Heute ist 16.11.2026 ", editor.toString())
    }

    @Test
    fun triggerNotFiredForPrefilledTextOrWhenOff() {
        val editor = FakeEditor("Vorgabe ;;datum")
        typeChars(keyboard(editor), " ")
        assertTrue("Von der App vorgegebener Text darf nichts auslösen", fake.calls.isEmpty())

        SignatureStore(context).termuxTriggers = false
        val other = FakeEditor("")
        typeChars(keyboard(other), ";;datum ")
        assertTrue(fake.calls.isEmpty())
        assertEquals(";;datum ", other.toString())
    }

    @Test
    fun triggerKeepsTextWhenUserTypedOn() {
        val editor = FakeEditor("")
        val service = keyboard(editor)
        typeChars(service, ";;zeit ")
        typeChars(service, "Uhr") // weitergetippt, bevor Termux antwortet
        fake.answer("14:05\n")
        assertEquals(";;zeit Uhr", editor.toString())
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!
        assertEquals("14:05", clip.getItemAt(0).text.toString())
    }

    // ------------------------------------------------------------------ Echter Client: Intent und Rückgabe

    @Test
    fun clientBuildsTermuxIntentAndReceivesResult() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = TermuxLogic.PACKAGE })
        shadowOf(context).grantPermissions(TermuxClient.PERMISSION)
        var result: TermuxResult? = null
        TermuxClient(context).run("Test", listOf("-c", "echo ok"), "eingabe") { result = it }

        val intent = shadowOf(context).nextStartedService!!
        assertEquals(TermuxClient.ACTION_RUN_COMMAND, intent.action)
        assertEquals(TermuxLogic.PACKAGE, intent.component!!.packageName)
        assertEquals(TermuxClient.SERVICE, intent.component!!.className)
        assertEquals(TermuxLogic.BASH, intent.getStringExtra(TermuxClient.EXTRA_PATH))
        assertEquals(listOf("-c", "echo ok"), intent.getStringArrayExtra(TermuxClient.EXTRA_ARGUMENTS)!!.toList())
        assertEquals("eingabe", intent.getStringExtra(TermuxClient.EXTRA_STDIN))
        assertEquals(TermuxLogic.HOME, intent.getStringExtra(TermuxClient.EXTRA_WORKDIR))
        assertTrue(intent.getBooleanExtra(TermuxClient.EXTRA_BACKGROUND, false))

        // So antwortet Termux: das Ergebnis-Bundle an den PendingIntent anhängen.
        @Suppress("DEPRECATION")
        val pending = intent.getParcelableExtra<PendingIntent>(TermuxClient.EXTRA_PENDING_INTENT)!!
        val reply = Intent(shadowOf(pending).savedIntent).putExtra("result", Bundle().apply {
            putString("stdout", "ok\n")
            putString("stdout_original_length", "3")
            putString("stderr", "")
            putInt("exitCode", 0)
            putInt("err", TermuxResult.RESULT_OK)
        })
        TermuxResultReceiver().onReceive(context, reply)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(result)
        assertTrue(result!!.success)
        assertEquals("ok\n", result!!.stdout)
    }

    @Test
    fun clientReportsMissingTermuxAndPermission() {
        var result: TermuxResult? = null
        TermuxClient(context).run("Test", listOf("-c", "true"), null) { result = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Termux ist nicht installiert.", result!!.errmsg)

        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = TermuxLogic.PACKAGE })
        result = null
        TermuxClient(context).run("Test", listOf("-c", "true"), null) { result = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(result!!.errmsg!!.contains("Berechtigung"))
        assertNull(shadowOf(context).nextStartedService)
    }
}
