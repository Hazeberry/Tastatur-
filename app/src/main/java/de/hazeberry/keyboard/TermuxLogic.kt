package de.hazeberry.keyboard

/** Ergebnis eines Termux-Aufrufs (Felder wie in Termux' Ergebnis-Bundle). */
data class TermuxResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    /** Interner Fehler von Termux; [RESULT_OK] = keiner. */
    val err: Int,
    val errmsg: String?,
    val stdoutTruncated: Boolean = false
) {
    val success: Boolean get() = err == RESULT_OK && exitCode == 0

    companion object {
        /** Activity.RESULT_OK, so meldet Termux "kein interner Fehler". */
        const val RESULT_OK = -1

        fun failure(message: String) = TermuxResult("", "", null, 1, message)
    }
}

/** Worauf sich ein Aufruf bezieht: die Markierung, das ganze Feld oder ein getipptes Kürzel. */
enum class TermuxMode { SELECTION, FIELD, TRIGGER }

/**
 * Merkt sich, wofür ein laufender Aufruf gestartet wurde. Das Ergebnis wird nur eingesetzt, wenn
 * App, Feld und Text noch genau so sind; sonst landet es in der Zwischenablage.
 */
data class TermuxTarget(val packageName: String?, val fieldId: Int, val mode: TermuxMode, val original: String)

/**
 * Regeln der Termux-Anbindung, ohne Android-Code (testbar auf dem Rechner).
 *
 * Konvention: Alles liegt in Termux unter `~/.tastatur/`. Die Tastatur greift nie selbst auf
 * Dateien zu, sondern bittet Termux (RUN_COMMAND), Skripte auszuführen.
 * - `~/.tastatur/aktionen/<name>`: Text kommt über stdin, Ergebnis über stdout, Exit-Code 0 = Erfolg.
 * - `~/.tastatur/kuerzel`: bekommt das Kürzel (ohne ";;") als erstes Argument.
 */
// Die Pfade gehören Termux, nicht dieser App; sie sind dort fest (Termux' eigene Konstanten).
@android.annotation.SuppressLint("SdCardPath")
object TermuxLogic {

    const val PACKAGE = "com.termux"
    const val HOME = "/data/data/com.termux/files/home"
    const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    const val DIR = "$HOME/.tastatur"
    const val ACTIONS_DIR = "$DIR/aktionen"
    const val TRIGGER_SCRIPT = "$DIR/kuerzel"

    /** Höchstens so viel Text geht an Termux (Grenze für Intents). */
    const val MAX_INPUT = 100_000

    /** Wie lange auf eine Antwort gewartet wird. */
    const val TIMEOUT_MS = 120_000L

    private val ACTION_NAME = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._ -]{0,63}")
    private val TRIGGER = Regex("(?:^|\\s);;(\\S{1,40})$")

    fun isValidActionName(name: String): Boolean = ACTION_NAME.matches(name) && !name.contains("..")

    /** Ausgabe von `ls -1` in gültige Aktionsnamen. */
    fun parseActionList(stdout: String): List<String> = stdout.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && isValidActionName(it) && !it.endsWith("~") }
        .distinct()
        .sortedBy { it.lowercase() }
        .take(100)
        .toList()

    fun actionCommand(name: String): List<String> {
        require(isValidActionName(name)) { "Ungültiger Aktionsname" }
        return listOf("$ACTIONS_DIR/$name")
    }

    fun listCommand(): List<String> = listOf("-c", "ls -1 \"$ACTIONS_DIR\" 2>/dev/null")

    fun testCommand(): List<String> = listOf("-c", "echo ok; ls -1 \"$ACTIONS_DIR\" 2>/dev/null | wc -l")

    fun triggerCommand(token: String): List<String> = listOf(TRIGGER_SCRIPT, token)

    /**
     * Erkennt ein Kürzel direkt vor dem Cursor, z. B. "Heute ist ;;datum".
     * [before]: Text vor dem Cursor. [typed]: was die Tastatur zuletzt selbst getippt hat; das
     * Kürzel muss darin vorkommen, damit nie Text ausgelöst wird, den eine App vorgegeben hat.
     */
    fun findTrigger(before: CharSequence, typed: CharSequence): String? {
        val token = TRIGGER.find(before)?.groupValues?.get(1) ?: return null
        return if (typed.endsWith(";;$token")) token else null
    }

    /** Skripte enden meist mit einem Zeilenumbruch (echo, printf ...); genau einer fällt weg. */
    fun normalizeOutput(stdout: String): String = when {
        stdout.endsWith("\r\n") -> stdout.dropLast(2)
        stdout.endsWith("\n") -> stdout.dropLast(1)
        else -> stdout
    }

    /** Darf das Ergebnis jetzt eingesetzt werden? */
    fun canApply(target: TermuxTarget, packageName: String?, fieldId: Int, current: String?): Boolean =
        target.packageName == packageName && target.fieldId == fieldId && current == target.original

    /** Kurze, verständliche Fehlermeldung. */
    fun describeFailure(result: TermuxResult): String {
        val errmsg = result.errmsg.orEmpty()
        if (result.err != TermuxResult.RESULT_OK) {
            return when {
                errmsg.contains("allow-external-apps") ->
                    "Termux erlaubt noch keine fremden Apps. Das Einrichtungsskript aus den Einstellungen in Termux ausführen."
                errmsg.isNotBlank() -> errmsg.lineSequence().first { it.isNotBlank() }.take(200)
                else -> "Termux meldet einen Fehler."
            }
        }
        val firstError = result.stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200)
        return when {
            result.exitCode == 127 || (firstError?.contains("No such file") == true) ->
                "Skript nicht gefunden. Liegt es in ~/.tastatur/?"
            firstError != null -> "Skript-Fehler: $firstError"
            else -> "Das Skript ist mit Fehler ${result.exitCode} beendet worden."
        }
    }

    /** In Termux einfügen und ausführen: richtet Ordner, Beispiele und die Erlaubnis ein. */
    val SETUP_SCRIPT: String = """
        # Einrichtung für Hazeberry Keyboard: in Termux einfügen, Enter drücken.
        mkdir -p ~/.tastatur/aktionen ~/.termux
        grep -qs '^allow-external-apps *= *true' ~/.termux/termux.properties \
          || echo 'allow-external-apps = true' >> ~/.termux/termux.properties
        command -v termux-reload-settings >/dev/null && termux-reload-settings

        # Aktionen: Text kommt über stdin, Ergebnis über stdout. Name der Datei = Name der Aktion.
        [ -e ~/.tastatur/aktionen/standard ] || cat > ~/.tastatur/aktionen/standard <<'SKRIPT'
        # Standard-Aktion (Tippen auf ▶): Leerzeichen am Zeilenende und doppelte Leerzeichen
        # entfernen, mehrere Leerzeilen zu einer zusammenfassen.
        sed -e 's/[[:space:]]*$//' -e 's/  */ /g' | cat -s
        SKRIPT

        [ -e ~/.tastatur/aktionen/grossbuchstaben ] || cat > ~/.tastatur/aktionen/grossbuchstaben <<'SKRIPT'
        sed 's/.*/\U&/'
        SKRIPT

        [ -e ~/.tastatur/aktionen/sortieren ] || cat > ~/.tastatur/aktionen/sortieren <<'SKRIPT'
        sort
        SKRIPT

        [ -e ~/.tastatur/aktionen/liste ] || cat > ~/.tastatur/aktionen/liste <<'SKRIPT'
        # Jede nicht leere Zeile wird ein Markdown-Listenpunkt.
        sed '/[^[:space:]]/s/^/- /'
        SKRIPT

        [ -e ~/.tastatur/aktionen/zitat ] || cat > ~/.tastatur/aktionen/zitat <<'SKRIPT'
        sed 's/^/> /'
        SKRIPT

        # Kürzel: ";;name" tippen, dann Leertaste. ${'$'}1 ist das Kürzel ohne ";;".
        [ -e ~/.tastatur/kuerzel ] || cat > ~/.tastatur/kuerzel <<'SKRIPT'
        case "${'$'}1" in
          datum) date +%d.%m.%Y ;;
          iso)   date +%F ;;
          zeit)  date +%H:%M ;;
          =*)    ausdruck="${'$'}{1#=}"
                 # Nur Zahlen und Rechenzeichen, damit nichts anderes ausgeführt wird.
                 muster='^[0-9+*/().,%^ -]+${'$'}'
                 [[ ${'$'}ausdruck =~ ${'$'}muster ]] || { echo "Ungültige Rechnung" >&2; exit 1; }
                 awk "BEGIN { printf \"%.10g\n\", (${'$'}{ausdruck//,/.}) }" ;;
          *)     echo "Unbekanntes Kürzel: ${'$'}1" >&2; exit 1 ;;
        esac
        SKRIPT

        echo "Fertig. Aktionen: ${'$'}(ls ~/.tastatur/aktionen | tr '\n' ' ')"
    """.trimIndent()
}
