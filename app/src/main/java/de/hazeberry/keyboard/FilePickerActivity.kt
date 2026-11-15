package de.hazeberry.keyboard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.InputStreamReader

/**
 * Durchsichtige Hilfs-Activity: öffnet den Dateiauswahldialog von Android (Storage Access
 * Framework), liest die gewählte Textdatei und übergibt sie über [FileBridge] an die Tastatur.
 *
 * Das Storage Access Framework braucht keine Speicher-Berechtigung. Die App bekommt nur Zugriff
 * auf die eine Datei, die du antippst, und nur für diesen Moment. Der Dialog zeigt auch Google
 * Drive, Nextcloud und andere Cloud-Anbieter, falls sie auf dem Handy installiert sind.
 */
class FilePickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nach einer Drehung oder einem Neustart des Prozesses nicht noch einmal öffnen.
        if (savedInstanceState != null) return

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Textdateien haben je nach Endung (.md, .yml, .kt ...) sehr unterschiedliche
            // MIME-Typen. Deshalb alles anzeigen und den Inhalt selbst prüfen.
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQUEST_PICK)
        } catch (e: Exception) {
            toast("Auf diesem Gerät gibt es keinen Dateiauswahldialog.")
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) {
            data?.data?.let { handle(it) }
        }
        finish()
    }

    private fun handle(uri: Uri) {
        val read = try {
            readText(uri)
        } catch (e: Exception) {
            toast("Datei konnte nicht gelesen werden.")
            return
        }
        when {
            read == null -> toast("Das ist keine Textdatei.")
            read.text.isBlank() -> toast("Die Datei ist leer.")
            else -> {
                FileBridge.put(read.text)
                toast(
                    if (read.truncated) {
                        "Datei gekürzt auf $MAX_CHARS Zeichen. Tippe in das Eingabefeld."
                    } else {
                        "Datei bereit. Tippe in das Eingabefeld."
                    }
                )
            }
        }
    }

    private class ReadResult(val text: String, val truncated: Boolean)

    /** Liest höchstens [MAX_CHARS] Zeichen als UTF-8. Gibt null zurück, wenn es keine Textdatei ist. */
    private fun readText(uri: Uri): ReadResult? {
        val stream = contentResolver.openInputStream(uri) ?: throw IllegalStateException("kein Stream")
        stream.use { input ->
            val reader = InputStreamReader(input, Charsets.UTF_8)
            // Ein Zeichen mehr lesen, um zu erkennen, ob die Datei länger ist.
            val buffer = CharArray(MAX_CHARS + 1)
            var filled = 0
            while (filled < buffer.size) {
                val n = reader.read(buffer, filled, buffer.size - filled)
                if (n < 0) break
                filled += n
            }
            val truncated = filled > MAX_CHARS
            var length = if (truncated) MAX_CHARS else filled
            // Kein Emoji in der Mitte durchschneiden.
            if (truncated && length > 0 && Character.isHighSurrogate(buffer[length - 1])) length--

            // Binärdateien erkennen: Nullbytes oder viele ungültige UTF-8-Folgen.
            var bad = 0
            for (i in 0 until length) {
                val c = buffer[i]
                if (c == '\u0000') return null
                if (c == '�') bad++
            }
            if (length > 0 && bad * 100 > length) return null

            return ReadResult(String(buffer, 0, length), truncated)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REQUEST_PICK = 1

        /** Obergrenze, damit die Übergabe an das Eingabefeld sicher funktioniert. */
        const val MAX_CHARS = 50_000
    }
}
