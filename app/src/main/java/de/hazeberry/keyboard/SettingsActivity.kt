package de.hazeberry.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

private const val EXAMPLE_SIGNATURE =
    "[Erinnerung an das Ziel]\n" +
        "Wir bauen eine GitHub Action, die bei jedem Push auf main die Tests ausführt " +
        "und bei Erfolg ein Release-Artefakt erzeugt.\n" +
        "Regeln: nur YAML in .github/workflows, keine Secrets außer GITHUB_TOKEN, Node 20.\n" +
        "Stand: Test-Job läuft, Release-Job fehlt noch.\n" +
        "Bleib bei diesem Ziel und frag nach, wenn etwas unklar ist."

class SettingsActivity : Activity() {

    private companion object {
        const val REQUEST_TERMUX = 7
    }

    private lateinit var store: SignatureStore
    private lateinit var systemStatus: TextView
    private lateinit var counterStatus: TextView
    private lateinit var enabledSwitch: Switch
    private lateinit var signatureField: EditText
    private lateinit var messagesField: EditText
    private lateinit var minutesField: EditText
    private lateinit var hapticsSwitch: Switch
    private lateinit var fileHeaderSwitch: Switch
    private lateinit var termuxSwitch: Switch
    private lateinit var termuxTriggerSwitch: Switch
    private lateinit var termuxActionField: EditText
    private lateinit var termuxStatus: TextView
    private lateinit var tokenField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SignatureStore(this)
        setContentView(buildUi())
        loadValues()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        saveValues()
        super.onPause()
    }

    // ------------------------------------------------------------------ Aufbau

    private fun buildUi(): View {
        systemStatus = TextView(this).apply { textSize = 15f }
        counterStatus = TextView(this).apply { textSize = 15f }
        enabledSwitch = Switch(this).apply { text = "Signatur automatisch einfügen" }
        hapticsSwitch = Switch(this).apply { text = "Vibration beim Tippen" }
        fileHeaderSwitch = Switch(this).apply { text = "Dateinamen über den Inhalt setzen" }
        termuxSwitch = Switch(this).apply { text = "Termux-Anbindung (Taste ▶)" }
        termuxTriggerSwitch = Switch(this).apply { text = "Kürzel wie ;;datum (nach der Leertaste)" }
        termuxActionField = input("Standard-Aktion beim Tippen auf ▶ (z. B. standard)")
        termuxStatus = TextView(this).apply { textSize = 15f }
        signatureField = input("Signatur (Ziel, Regeln, Stand)", multiline = true)
        messagesField = input("Nach wie vielen Nachrichten (0 = aus)", numeric = true)
        minutesField = input("Nach wie vielen Minuten (0 = aus)", numeric = true)
        tokenField = input("Token für Tasker (leer = Fernänderung gesperrt)")

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }

        page.addView(heading("Einrichtung"))
        page.addView(systemStatus)
        page.addView(button("Tastatur aktivieren") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        page.addView(button("Tastatur auswählen") { inputMethodManager().showInputMethodPicker() })

        page.addView(heading("Signatur"))
        page.addView(enabledSwitch)
        page.addView(signatureField)
        page.addView(button("Beispiel einfügen (GitHub Action)") {
            signatureField.setText(EXAMPLE_SIGNATURE)
        })

        page.addView(heading("Wann kommt die Signatur?"))
        page.addView(note("Sie wird der nächsten Nachricht vorangestellt, sobald du darin das erste Zeichen tippst."))
        page.addView(messagesField)
        page.addView(minutesField)
        page.addView(counterStatus)
        page.addView(button("Jetzt fällig machen") {
            store.pending = true
            refreshStatus()
        })
        page.addView(button("Zähler zurücksetzen") {
            store.reset()
            refreshStatus()
        })

        page.addView(button("Speichern") {
            saveValues()
            refreshStatus()
            Toast.makeText(this, "Gespeichert", Toast.LENGTH_SHORT).show()
        })

        page.addView(heading("Tastatur ausprobieren"))
        page.addView(input("Hier tippen", multiline = true))

        page.addView(heading("Dateien (Taste 📁)"))
        page.addView(
            note(
                "Fügt den Text von Dateien ein: Textdateien, PDF (auch eingescannt, per " +
                    "Texterkennung), Word, Excel und PowerPoint (neu und alt, auch mit Passwort), " +
                    "LibreOffice, RTF, E-Books, Outlook-Mails, Google Docs aus Drive, Bilder " +
                    "(Texterkennung) und Archive (ZIP, 7z, tar.gz …). Mehrere Dateien auf einmal " +
                    "gehen auch. Höchstens ${FilePickerActivity.MAX_CHARS / 1000}.000 Zeichen.\n\n" +
                    "Nimmt das Eingabefeld Dateien an, kannst du sie stattdessen anhängen oder " +
                    "über „Teilen“ an die App senden.\n\n" +
                    "Ohne Speicher-Berechtigung: Die App sieht nur die Dateien, die du antippst. " +
                    "Alles passiert auf dem Handy."
            )
        )
        page.addView(fileHeaderSwitch)

        page.addView(heading("Termux"))
        page.addView(
            note(
                "Mit Termux kann die Tastatur eigene Skripte ausführen: Tippen auf ▶ schickt den " +
                    "markierten Text (oder das ganze Feld) an die Standard-Aktion und setzt das Ergebnis " +
                    "ein. Lange drücken zeigt alle Aktionen. Kürzel wie ;;datum werden nach der Leertaste " +
                    "ersetzt.\n\n" +
                    "Die Skripte liegen in Termux unter ~/.tastatur/ (Text über stdin, Ergebnis über " +
                    "stdout). Die Tastatur selbst liest keine Dateien und hat kein Internet; was ein " +
                    "Skript tut, bestimmst du.\n\n" +
                    "Einrichten: 1. Termux von F-Droid oder GitHub installieren. 2. Einrichtungsskript " +
                    "kopieren, in Termux einfügen, Enter. 3. Berechtigung erteilen. 4. Verbindung testen."
            )
        )
        page.addView(termuxStatus)
        page.addView(termuxSwitch)
        page.addView(termuxTriggerSwitch)
        page.addView(termuxActionField)
        page.addView(button("Einrichtungsskript kopieren") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Termux-Einrichtung", TermuxLogic.SETUP_SCRIPT))
            Toast.makeText(this, "Kopiert. In Termux einfügen und Enter drücken.", Toast.LENGTH_LONG).show()
        })
        page.addView(button("Berechtigung für Termux erteilen") {
            if (!TermuxClient.isInstalled(this)) {
                Toast.makeText(this, "Termux ist nicht installiert.", Toast.LENGTH_LONG).show()
            } else {
                requestPermissions(arrayOf(TermuxClient.PERMISSION), REQUEST_TERMUX)
            }
        })
        page.addView(button("Verbindung testen") { testTermux() })

        page.addView(heading("Tasker"))
        page.addView(
            note(
                "Aktion \"Intent senden\", Ziel \"Broadcast-Empfänger\", Paket \"$packageName\".\n\n" +
                    "Signatur fällig:\n${TaskerReceiver.ACTION_SIGNATURE_DUE}\n\n" +
                    "Zähler zurücksetzen:\n${TaskerReceiver.ACTION_RESET}\n\n" +
                    "Signatur ändern (nur mit Token):\n${TaskerReceiver.ACTION_SET_SIGNATURE}\n" +
                    "Extras: token:<dein Token>  und  text:<neuer Text>\n\n" +
                    "Öffne diese App einmal, damit Android Broadcasts zustellt."
            ).apply { setTextIsSelectable(true) }
        )
        page.addView(tokenField)
        page.addView(button("Zufälligen Token erzeugen") {
            tokenField.setText(UUID.randomUUID().toString().replace("-", "").take(20))
        })

        page.addView(heading("Allgemein"))
        page.addView(hapticsSwitch)
        page.addView(
            note(
                "Alles bleibt auf dem Gerät. Die App hat keine Internet-Berechtigung. " +
                    "In Passwortfeldern wird nie gezählt oder eingefügt."
            )
        )

        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(page)
        }
    }

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 0, 0, dp(6))
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun input(
        hintText: String,
        multiline: Boolean = false,
        numeric: Boolean = false
    ): EditText = EditText(this).apply {
        hint = hintText
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) {
            minLines = 4
            gravity = Gravity.TOP
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ Daten

    private fun loadValues() {
        enabledSwitch.isChecked = store.enabled
        hapticsSwitch.isChecked = store.haptics
        fileHeaderSwitch.isChecked = store.fileHeader
        termuxSwitch.isChecked = store.termuxEnabled
        termuxTriggerSwitch.isChecked = store.termuxTriggers
        termuxActionField.setText(store.termuxAction)
        signatureField.setText(store.signature)
        messagesField.setText(store.messageInterval.toString())
        minutesField.setText(store.minuteInterval.toString())
        tokenField.setText(store.taskerToken)
    }

    private fun saveValues() {
        store.enabled = enabledSwitch.isChecked
        store.haptics = hapticsSwitch.isChecked
        store.fileHeader = fileHeaderSwitch.isChecked
        store.termuxEnabled = termuxSwitch.isChecked
        store.termuxTriggers = termuxTriggerSwitch.isChecked
        store.termuxAction = termuxActionField.text.toString().trim().ifEmpty { "standard" }
        store.signature = signatureField.text.toString().trim()
        store.messageInterval = messagesField.text.toString().toIntOrNull() ?: 0
        store.minuteInterval = minutesField.text.toString().toIntOrNull() ?: 0
        store.taskerToken = tokenField.text.toString().trim()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun testTermux() {
        saveValues()
        Toast.makeText(this, "Frage Termux …", Toast.LENGTH_SHORT).show()
        TermuxClient(this).run("Verbindungstest", TermuxLogic.testCommand(), null) { result ->
            val message = if (result.success) {
                val count = result.stdout.lines().getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                "Verbindung klappt. $count Aktion(en) in ~/.tastatur/aktionen."
            } else {
                TermuxLogic.describeFailure(result)
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            refreshStatus()
        }
    }

    private fun inputMethodManager(): InputMethodManager =
        getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun isEnabledInSystem(): Boolean =
        inputMethodManager().enabledInputMethodList.any { it.packageName == packageName }

    private fun isSelected(): Boolean {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?: return false
        return id.startsWith("$packageName/")
    }

    private fun refreshStatus() {
        systemStatus.text = when {
            !isEnabledInSystem() -> "Die Tastatur ist noch nicht aktiviert."
            !isSelected() -> "Aktiviert, aber nicht ausgewählt. Wähle sie beim Tippen über das Tastatursymbol."
            else -> "Die Tastatur ist aktiviert und ausgewählt."
        }
        termuxStatus.text = when {
            !TermuxClient.isInstalled(this) -> "Termux: nicht installiert."
            !TermuxClient.hasPermission(this) -> "Termux: installiert, Berechtigung fehlt noch."
            else -> "Termux: installiert, Berechtigung erteilt."
        }
        counterStatus.text =
            "Gezählt seit der letzten Signatur: ${store.count}\n" +
                "Signatur fällig: ${if (store.isDue()) "ja" else "nein"}"
    }
}
