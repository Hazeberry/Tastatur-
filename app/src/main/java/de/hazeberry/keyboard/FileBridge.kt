package de.hazeberry.keyboard

import android.os.SystemClock

/**
 * Übergabe zwischen [FilePickerActivity] und [KeyboardService].
 *
 * Eine Tastatur kann keinen Dateidialog öffnen und dessen Ergebnis empfangen. Deshalb liest die
 * Activity die gewählte Datei und legt den Text hier ab. Sobald die Tastatur wieder im
 * Eingabefeld erscheint, holt sie ihn ab und tippt ihn ein.
 *
 * Beide Klassen laufen im selben Prozess. Der Text liegt nur im Arbeitsspeicher, nie auf der
 * Platte, und verfällt nach [MAX_AGE_MS], damit er nicht Stunden später überraschend in einem
 * anderen Feld auftaucht.
 */
object FileBridge {

    private const val MAX_AGE_MS = 2 * 60 * 1000L

    private var text: String? = null
    private var storedAt = 0L

    @Synchronized
    fun put(value: String) {
        text = value
        storedAt = SystemClock.elapsedRealtime()
    }

    /** Liefert den wartenden Text genau einmal, oder null, wenn keiner da oder er zu alt ist. */
    @Synchronized
    fun take(): String? {
        val value = text
        val fresh = SystemClock.elapsedRealtime() - storedAt <= MAX_AGE_MS
        text = null
        return if (fresh) value else null
    }

    @Synchronized
    fun clear() {
        text = null
    }

    @Synchronized
    fun hasPending(): Boolean =
        text != null && SystemClock.elapsedRealtime() - storedAt <= MAX_AGE_MS
}
