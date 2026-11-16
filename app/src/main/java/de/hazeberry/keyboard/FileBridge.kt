package de.hazeberry.keyboard

import android.os.SystemClock

/**
 * Übergabe zwischen [FilePickerActivity] und [KeyboardService].
 *
 * Eine Tastatur kann keinen Dateidialog öffnen und dessen Ergebnis empfangen. Deshalb liest die
 * Activity die gewählten Dateien und legt den Text hier ab. Sobald die App, aus der du 📁
 * angetippt hast, wieder ein Eingabefeld hat, holt die Tastatur ihn ab und tippt ihn ein.
 *
 * Beide Klassen laufen im selben Prozess. Der Text liegt nur im Arbeitsspeicher, nie auf der
 * Platte, und verfällt nach [MAX_AGE_MS], damit er nicht Stunden später überraschend auftaucht.
 */
object FileBridge {

    private const val MAX_AGE_MS = 3 * 60 * 1000L

    private var text: String? = null
    private var targetPackage: String? = null
    private var storedAt = 0L

    /** [forPackage]: die App, in die eingefügt werden soll. Null = in jede. */
    @Synchronized
    fun put(value: String, forPackage: String?) {
        text = value
        targetPackage = forPackage
        storedAt = SystemClock.elapsedRealtime()
    }

    /**
     * Liefert den wartenden Text genau einmal, aber nur für die richtige App.
     * In einer anderen App bleibt er liegen (bis er verfällt).
     */
    @Synchronized
    fun take(packageName: String?): String? {
        if (!hasPending()) {
            text = null
            return null
        }
        val target = targetPackage
        if (target != null && target != packageName) return null
        val value = text
        text = null
        return value
    }

    @Synchronized
    fun hasPending(): Boolean =
        text != null && SystemClock.elapsedRealtime() - storedAt <= MAX_AGE_MS
}
