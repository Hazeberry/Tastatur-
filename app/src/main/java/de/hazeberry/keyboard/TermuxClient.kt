package de.hazeberry.keyboard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Führt Befehle in Termux aus (austauschbar für Tests). */
interface TermuxRunner {
    /** [callback] kommt immer auf dem Hauptthread, genau einmal (auch bei Fehler und Zeitüberschreitung). */
    fun run(label: String, args: List<String>, stdin: String?, callback: (TermuxResult) -> Unit)
}

/**
 * Spricht Termux über dessen offizielle Schnittstelle `com.termux.RUN_COMMAND` an.
 *
 * Voraussetzungen auf dem Gerät: Termux (F-Droid oder GitHub), `allow-external-apps = true` in
 * `~/.termux/termux.properties` und die Berechtigung [PERMISSION] für diese App.
 * Termux schickt das Ergebnis (stdout, stderr, Exit-Code) über einen PendingIntent an
 * [TermuxResultReceiver] zurück.
 */
class TermuxClient(context: Context) : TermuxRunner {

    private val app = context.applicationContext

    override fun run(label: String, args: List<String>, stdin: String?, callback: (TermuxResult) -> Unit) {
        val id = nextId.incrementAndGet()
        pending[id] = callback
        main.postDelayed({ deliver(id, TermuxResult.failure("Termux hat nicht rechtzeitig geantwortet.")) }, TermuxLogic.TIMEOUT_MS)

        if (!isInstalled(app)) return fail(id, "Termux ist nicht installiert.")
        if (!hasPermission(app)) {
            return fail(id, "Die Tastatur darf Termux noch nicht steuern. Bitte in den Einstellungen die Berechtigung erteilen.")
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        // Explizit an den eigenen, nicht exportierten Empfänger; veränderbar, damit Termux das
        // Ergebnis anhängen kann.
        val result = PendingIntent.getBroadcast(
            app, id, Intent(app, TermuxResultReceiver::class.java).putExtra(EXTRA_ID, id), flags
        )
        val intent = Intent(ACTION_RUN_COMMAND)
            .setClassName(TermuxLogic.PACKAGE, SERVICE)
            .putExtra(EXTRA_PATH, TermuxLogic.BASH)
            .putExtra(EXTRA_ARGUMENTS, args.toTypedArray())
            .putExtra(EXTRA_WORKDIR, TermuxLogic.HOME)
            .putExtra(EXTRA_BACKGROUND, true)
            .putExtra(EXTRA_LABEL, "Hazeberry Keyboard: $label")
            .putExtra(EXTRA_PENDING_INTENT, result)
        if (stdin != null) intent.putExtra(EXTRA_STDIN, stdin)

        try {
            // Termux' RunCommandService startet sich selbst als Vordergrunddienst.
            val started = app.startForegroundService(intent)
            if (started == null) fail(id, "Termux ist nicht installiert oder zu alt (nötig: 0.109 oder neuer).")
        } catch (e: SecurityException) {
            fail(id, "Die Tastatur darf Termux noch nicht steuern. Bitte in den Einstellungen die Berechtigung erteilen.")
        } catch (e: Exception) {
            fail(id, "Termux konnte nicht gestartet werden (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    private fun fail(id: Int, message: String) {
        main.post { deliver(id, TermuxResult.failure(message)) }
    }

    companion object {
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val SERVICE = "com.termux.app.RunCommandService"
        const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        internal const val EXTRA_ID = "de.hazeberry.keyboard.extra.TERMUX_ID"

        private val main = Handler(Looper.getMainLooper())
        private val nextId = AtomicInteger((System.currentTimeMillis() and 0xFFFF).toInt())
        private val pending = ConcurrentHashMap<Int, (TermuxResult) -> Unit>()

        /** Liefert das Ergebnis aus; spätere Ergebnisse für dieselbe Nummer werden verworfen. */
        fun deliver(id: Int, result: TermuxResult) {
            pending.remove(id)?.invoke(result)
        }

        fun isInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(TermuxLogic.PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}

/** Empfängt das Ergebnis von Termux (nur über unseren eigenen PendingIntent erreichbar). */
class TermuxResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(TermuxClient.EXTRA_ID, -1)
        val bundle = intent.getBundleExtra("result")
        if (bundle == null) {
            TermuxClient.deliver(id, TermuxResult.failure("Leere Antwort von Termux."))
            return
        }
        val stdout = bundle.getString("stdout").orEmpty()
        val original = bundle.getString("stdout_original_length")?.toIntOrNull()
        TermuxClient.deliver(
            id,
            TermuxResult(
                stdout = stdout,
                stderr = bundle.getString("stderr").orEmpty(),
                exitCode = if (bundle.containsKey("exitCode")) bundle.getInt("exitCode") else null,
                err = bundle.getInt("err", TermuxResult.RESULT_OK),
                errmsg = bundle.getString("errmsg"),
                stdoutTruncated = original != null && original > stdout.length
            )
        )
    }
}
