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

    private lateinit var store: SignatureStore
    private lateinit var systemStatus: TextView
    private lateinit var counterStatus: TextView
    private lateinit var enabledSwitch: Switch
    private lateinit var signatureField: EditText
    private lateinit var messagesField: EditText
    private lateinit var minutesField: EditText
    private lateinit var hapticsSwitch: Switch
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
        signatureField.setText(store.signature)
        messagesField.setText(store.messageInterval.toString())
        minutesField.setText(store.minuteInterval.toString())
        tokenField.setText(store.taskerToken)
    }

    private fun saveValues() {
        store.enabled = enabledSwitch.isChecked
        store.haptics = hapticsSwitch.isChecked
        store.signature = signatureField.text.toString().trim()
        store.messageInterval = messagesField.text.toString().toIntOrNull() ?: 0
        store.minuteInterval = minutesField.text.toString().toIntOrNull() ?: 0
        store.taskerToken = tokenField.text.toString().trim()
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
        counterStatus.text =
            "Gezählt seit der letzten Signatur: ${store.count}\n" +
                "Signatur fällig: ${if (store.isDue()) "ja" else "nein"}"
    }
}
