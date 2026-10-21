package de.hazeberry.keyboard

import android.content.Context

/**
 * Alle Einstellungen und der Zählerstand. Liegt in SharedPreferences,
 * bleibt also nach einem Neustart erhalten und verlässt das Gerät nie.
 */
class SignatureStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Hauptschalter für das automatische Einfügen. */
    var enabled: Boolean
        get() = prefs.getBoolean(K_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(K_ENABLED, value).apply()
        }

    var signature: String
        get() = prefs.getString(K_SIGNATURE, "") ?: ""
        set(value) {
            prefs.edit().putString(K_SIGNATURE, value).apply()
        }

    /** Nach wie vielen gesendeten Nachrichten die Signatur fällig wird. 0 = aus. */
    var messageInterval: Int
        get() = prefs.getInt(K_MESSAGES, 5)
        set(value) {
            prefs.edit().putInt(K_MESSAGES, value.coerceAtLeast(0)).apply()
        }

    /** Nach wie vielen Minuten seit der letzten Signatur sie wieder fällig wird. 0 = aus. */
    var minuteInterval: Int
        get() = prefs.getInt(K_MINUTES, 0)
        set(value) {
            prefs.edit().putInt(K_MINUTES, value.coerceAtLeast(0)).apply()
        }

    var haptics: Boolean
        get() = prefs.getBoolean(K_HAPTICS, true)
        set(value) {
            prefs.edit().putBoolean(K_HAPTICS, value).apply()
        }

    /** Wer die Signatur per Tasker ändern will, muss dieses Token mitschicken. Leer = gesperrt. */
    var taskerToken: String
        get() = prefs.getString(K_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(K_TOKEN, value).apply()
        }

    var count: Int
        get() = prefs.getInt(K_COUNT, 0)
        private set(value) {
            prefs.edit().putInt(K_COUNT, value).apply()
        }

    /** Wird z. B. von Tasker gesetzt: die Signatur ist beim nächsten Nachrichtenbeginn fällig. */
    var pending: Boolean
        get() = prefs.getBoolean(K_PENDING, false)
        set(value) {
            prefs.edit().putBoolean(K_PENDING, value).apply()
        }

    private var lastInsertedAt: Long
        get() = prefs.getLong(K_LAST, 0L)
        set(value) {
            prefs.edit().putLong(K_LAST, value).apply()
        }

    /** Eine Nachricht gilt als gesendet. */
    fun registerMessageSent() {
        count += 1
        if (lastInsertedAt == 0L) lastInsertedAt = System.currentTimeMillis()
    }

    fun isDue(now: Long = System.currentTimeMillis()): Boolean {
        if (!enabled || signature.isBlank()) return false
        if (pending) return true
        if (messageInterval > 0 && count >= messageInterval) return true
        val last = lastInsertedAt
        if (minuteInterval > 0 && last > 0L && now - last >= minuteInterval * 60_000L) return true
        return false
    }

    /** Wie viele Nachrichten noch bis zur Signatur, oder null, wenn nicht nach Anzahl gezählt wird. */
    fun messagesUntilDue(): Int? =
        if (messageInterval > 0) (messageInterval - count).coerceAtLeast(0) else null

    /** Gibt die Signatur zurück, wenn sie fällig ist, und setzt dann Zähler und Flag zurück. */
    fun takeDueSignature(): String? {
        if (!isDue()) return null
        reset()
        return signature
    }

    /** Zähler, Flag und Zeitbasis zurücksetzen (z. B. nach manuellem Einfügen). */
    fun reset() {
        count = 0
        pending = false
        lastInsertedAt = System.currentTimeMillis()
    }

    private companion object {
        const val PREFS = "hazeberry_keyboard"
        const val K_ENABLED = "enabled"
        const val K_SIGNATURE = "signature"
        const val K_MESSAGES = "message_interval"
        const val K_MINUTES = "minute_interval"
        const val K_HAPTICS = "haptics"
        const val K_TOKEN = "tasker_token"
        const val K_COUNT = "count"
        const val K_PENDING = "pending"
        const val K_LAST = "last_inserted_at"
    }
}
