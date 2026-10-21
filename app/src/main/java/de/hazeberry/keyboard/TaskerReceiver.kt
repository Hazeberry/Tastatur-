package de.hazeberry.keyboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.security.MessageDigest

/**
 * Schnittstelle für Tasker (Aktion "Intent senden", Ziel "Broadcast-Empfänger",
 * Paket "de.hazeberry.keyboard").
 *
 * - ACTION_SIGNATURE_DUE:  Signatur beim nächsten Nachrichtenbeginn einfügen
 * - ACTION_RESET:          Zähler und Flag zurücksetzen
 * - ACTION_SET_SIGNATURE:  Signaturtext ersetzen (Extras: token, text). Nur mit gültigem Token,
 *                          damit keine fremde App Text in deine Chats schieben kann.
 */
class TaskerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val store = SignatureStore(context)
        when (intent.action) {
            ACTION_SIGNATURE_DUE -> store.pending = true
            ACTION_RESET -> store.reset()
            ACTION_SET_SIGNATURE -> {
                val expected = store.taskerToken
                val given = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (text != null && expected.isNotBlank() && tokensMatch(expected, given)) {
                    store.signature = text
                }
            }
        }
    }

    private fun tokensMatch(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        const val ACTION_SIGNATURE_DUE = "de.hazeberry.keyboard.ACTION_SIGNATURE_DUE"
        const val ACTION_RESET = "de.hazeberry.keyboard.ACTION_RESET"
        const val ACTION_SET_SIGNATURE = "de.hazeberry.keyboard.ACTION_SET_SIGNATURE"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_TEXT = "text"
    }
}
