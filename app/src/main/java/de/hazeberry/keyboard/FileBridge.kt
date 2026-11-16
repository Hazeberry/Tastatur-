package de.hazeberry.keyboard

import android.net.Uri
import android.os.SystemClock

/** Eine Datei, die die Tastatur als Anhang einfügen soll. */
class PendingAttachment(val uri: Uri, val mime: String, val label: String)

/**
 * Übergabe zwischen [FilePickerActivity] und [KeyboardService].
 *
 * Eine Tastatur kann keinen Dateidialog öffnen und dessen Ergebnis empfangen. Deshalb liest die
 * Activity die gewählten Dateien und legt Text oder Anhänge hier ab. Sobald die App, aus der du 📁
 * angetippt hast, wieder ein Eingabefeld hat, holt die Tastatur sie ab und fügt sie ein.
 *
 * Beide Klassen laufen im selben Prozess. Der Text liegt nur im Arbeitsspeicher und verfällt
 * nach [MAX_AGE_MS], damit er nicht Stunden später überraschend auftaucht.
 */
object FileBridge {

    private const val MAX_AGE_MS = 3 * 60 * 1000L

    private var text: String? = null
    private var attachments: List<PendingAttachment>? = null
    private var targetPackage: String? = null
    private var storedAt = 0L

    /** [forPackage]: die App, in die eingefügt werden soll. Null = in jede. */
    @Synchronized
    fun put(value: String, forPackage: String?) {
        text = value
        attachments = null
        targetPackage = forPackage
        storedAt = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun putAttachments(list: List<PendingAttachment>, forPackage: String?) {
        text = null
        attachments = list
        targetPackage = forPackage
        storedAt = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun hasPending(): Boolean =
        (text != null || attachments != null) && SystemClock.elapsedRealtime() - storedAt <= MAX_AGE_MS

    private fun expireIfOld() {
        if (SystemClock.elapsedRealtime() - storedAt > MAX_AGE_MS) {
            text = null
            attachments = null
        }
    }

    private fun matches(packageName: String?): Boolean {
        val target = targetPackage
        return target == null || target == packageName
    }

    /**
     * Liefert den wartenden Text genau einmal, aber nur für die richtige App.
     * In einer anderen App bleibt er liegen (bis er verfällt).
     */
    @Synchronized
    fun take(packageName: String?): String? {
        expireIfOld()
        if (text == null || !matches(packageName)) return null
        return text.also { text = null }
    }

    @Synchronized
    fun takeAttachments(packageName: String?): List<PendingAttachment>? {
        expireIfOld()
        if (attachments == null || !matches(packageName)) return null
        return attachments.also { attachments = null }
    }

    /** Nur für Tests. */
    @Synchronized
    fun clear() {
        text = null
        attachments = null
    }
}
