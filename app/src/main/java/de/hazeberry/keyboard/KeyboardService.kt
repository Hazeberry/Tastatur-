package de.hazeberry.keyboard

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

private enum class Page { LETTERS, SYMBOLS, SYMBOLS2, EMOJI, TERMUX }
private enum class Shift { OFF, ONCE, LOCK }
private enum class Kind { LETTER, SPECIAL, ACCENT }

private class Palette(night: Boolean) {
    val background = if (night) 0xFF1A1719.toInt() else 0xFFE9E2E7.toInt()
    val key = if (night) 0xFF3A3238.toInt() else 0xFFFFFFFF.toInt()
    val keyPressed = if (night) 0xFF5B4A57.toInt() else 0xFFD8CCD5.toInt()
    val special = if (night) 0xFF2A2428.toInt() else 0xFFCFC3CC.toInt()
    val accent = 0xFF9C5C85.toInt()
    val text = if (night) 0xFFF3EDF1.toInt() else 0xFF1E1A1D.toInt()
    val hint = if (night) 0xFFB8A9B3.toInt() else 0xFF6B5E67.toInt()
}

private val EMOJIS = listOf(
    "😀", "😂", "😊", "😍", "🤔", "😅", "😉", "😎", "🙂", "😢",
    "👍", "👎", "🙏", "👏", "🔥", "✅", "❌", "⚠️", "💡", "🚀",
    "🎉", "❤️", "💬", "📌", "📎", "🔧", "🐛", "📝", "📦", "🔒"
)

class KeyboardService : InputMethodService() {

    companion object {
        /** Nur für Tests: Termux-Aufrufe abfangen. */
        @Volatile
        internal var testTermux: TermuxRunner? = null
    }

    private lateinit var store: SignatureStore
    private lateinit var palette: Palette

    private var keysContainer: LinearLayout? = null
    private var statusView: TextView? = null
    private var shiftKey: KeyView? = null
    private val letterKeys = mutableListOf<KeyView>()

    private var page = Page.LETTERS
    private var shift = Shift.OFF
    private var lastShiftTap = 0L

    /** Hatte das Eingabefeld zuletzt Text? Der Wechsel auf "leer" gilt als gesendete Nachricht. */
    private var fieldHadText = false

    // ------------------------------------------------------------------ Termux-Zustand
    private val termux: TermuxRunner by lazy { testTermux ?: TermuxClient(this) }

    /** Was die Tastatur zuletzt selbst getippt hat (für Kürzel; nie Text, den die App vorgibt). */
    private val typedBuffer = StringBuilder()

    /** Laufende Termux-Aufrufe und der Name des letzten, für die Statuszeile. */
    private var termuxJobs = 0
    private var termuxLabel: String? = null

    /** Aktionsliste aus Termux (null = wird geladen) bzw. Fehlermeldung dazu. */
    private var termuxActions: List<String>? = null
    private var termuxActionsError: String? = null

    /** Ob die Leiste mit Termux-Taste gebaut wurde (Einstellung kann sich ändern). */
    private var barHasTermux = false

    override fun onCreate() {
        super.onCreate()
        store = SignatureStore(this)
        palette = Palette(isNight())
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setInputView(onCreateInputView())
    }

    // ------------------------------------------------------------------ Oberfläche

    override fun onCreateInputView(): View {
        palette = Palette(isNight())

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(palette.hint)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(status, LinearLayout.LayoutParams(0, dp(36), 1f))
        barHasTermux = store.termuxEnabled
        val width = if (barHasTermux) 46 else 52
        if (barHasTermux) {
            // Tippen: Standard-Aktion. Lange drücken: Liste aller Aktionen.
            bar.addView(smallKey("▶", width, onLongPress = { showTermuxPage() }) { runDefaultAction() })
        }
        bar.addView(smallKey("📁", width) { openFilePicker() })
        bar.addView(smallKey("Sig", width) { insertSignatureManually() })
        bar.addView(smallKey("🌐", width) { showKeyboardPicker() })

        val keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(bar)
        container.addView(keys)

        keysContainer = keys
        statusView = status
        render()
        refreshStatus()
        return container
    }

    private fun render() {
        val keys = keysContainer ?: return
        keys.removeAllViews()
        letterKeys.clear()
        shiftKey = null

        when (page) {
            Page.LETTERS -> {
                keys.addView(row("qwertzuiopü".map { letterKey(it) }))
                keys.addView(row("asdfghjklöä".map { letterKey(it) }))
                val sk = key(shiftLabel(), 1.5f, Kind.SPECIAL) { onShiftTap() }
                shiftKey = sk
                keys.addView(
                    row(
                        listOf(sk) +
                            "yxcvbnmß".map { letterKey(it) } +
                            key("⌫", 1.5f, Kind.SPECIAL, repeat = true) { backspace() }
                    )
                )
                keys.addView(bottomRow("123") { setPage(Page.SYMBOLS) })
                refreshShiftKey()
            }

            Page.SYMBOLS -> {
                keys.addView(row("1234567890".map { charKey(it.toString()) }))
                keys.addView(row("@#€%&-+()/".map { charKey(it.toString()) }))
                keys.addView(
                    row(
                        listOf(key("=\\<", 1.5f, Kind.SPECIAL) { setPage(Page.SYMBOLS2) }) +
                            "*\"':;!?".map { charKey(it.toString()) } +
                            key("⌫", 1.5f, Kind.SPECIAL, repeat = true) { backspace() }
                    )
                )
                keys.addView(bottomRow("ABC") { setPage(Page.LETTERS) })
            }

            Page.SYMBOLS2 -> {
                keys.addView(row("[]{}<>^~|\\".map { charKey(it.toString()) }))
                keys.addView(row("\$`=_+-#%*&".map { charKey(it.toString()) }))
                keys.addView(
                    row(
                        listOf(key("123", 1.5f, Kind.SPECIAL) { setPage(Page.SYMBOLS) }) +
                            ".,?!'\";".map { charKey(it.toString()) } +
                            key("⌫", 1.5f, Kind.SPECIAL, repeat = true) { backspace() }
                    )
                )
                keys.addView(bottomRow("ABC") { setPage(Page.LETTERS) })
            }

            Page.TERMUX -> renderTermuxPage(keys)

            Page.EMOJI -> {
                EMOJIS.chunked(10).forEach { chunk ->
                    keys.addView(row(chunk.map { e -> key(e, 1f, Kind.LETTER) { type(e) } }))
                }
                keys.addView(
                    row(
                        listOf(
                            key("ABC", 2f, Kind.SPECIAL) { setPage(Page.LETTERS) },
                            key("␣", 4f, Kind.LETTER) { onChar(" ") },
                            key("⌫", 2f, Kind.SPECIAL, repeat = true) { backspace() }
                        )
                    )
                )
            }
        }
    }

    private fun bottomRow(switchLabel: String, onSwitch: () -> Unit): LinearLayout {
        val items = mutableListOf<View>()
        items += key(switchLabel, 1.5f, Kind.SPECIAL) { onSwitch() }
        items += key("☺", 1.2f, Kind.SPECIAL) { setPage(Page.EMOJI) }
        items += charKey(",")
        items += key("␣", 4f, Kind.LETTER) { onChar(" ") }
        items += charKey(".")
        items += key("⏎", 1.6f, Kind.ACCENT) { enter() }
        return row(items)
    }

    private fun row(views: List<View>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun key(
        label: String,
        weight: Float = 1f,
        kind: Kind = Kind.LETTER,
        repeat: Boolean = false,
        onLongPress: (() -> Unit)? = null,
        onTap: () -> Unit
    ): KeyView = KeyView(label, weight, kind, repeat, onLongPress, onTap)

    private fun charKey(s: String): KeyView = key(s, 1f, Kind.LETTER) { onChar(s) }

    private fun letterKey(c: Char): KeyView {
        val k = key(shiftedText(c), 1f, Kind.LETTER) { onLetter(c) }
        k.letter = c
        letterKeys += k
        return k
    }

    private fun smallKey(
        label: String,
        widthDp: Int = 52,
        onLongPress: (() -> Unit)? = null,
        onTap: () -> Unit
    ): KeyView {
        val k = key(label, 1f, Kind.SPECIAL, onLongPress = onLongPress, onTap = onTap)
        k.textSize = 14f
        k.layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(32)).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
        return k
    }

    private fun makeBackground(kind: Kind): StateListDrawable {
        val base = when (kind) {
            Kind.LETTER -> palette.key
            Kind.SPECIAL -> palette.special
            Kind.ACCENT -> palette.accent
        }
        fun shape(color: Int) = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(color)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(palette.keyPressed))
            addState(intArrayOf(), shape(base))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class KeyView(
        label: String,
        weight: Float,
        private val kind: Kind,
        private val repeat: Boolean,
        private val onLongPress: (() -> Unit)?,
        private val onTap: () -> Unit
    ) : TextView(this@KeyboardService) {

        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            if (store.haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke()
        }

        var letter: Char? = null

        private val repeater = object : Runnable {
            override fun run() {
                onTap()
                this@KeyView.postDelayed(this, 45)
            }
        }

        init {
            text = label
            gravity = Gravity.CENTER
            textSize = if (kind == Kind.LETTER) 20f else 16f
            setTextColor(if (kind == Kind.ACCENT) 0xFFFFFFFF.toInt() else palette.text)
            background = makeBackground(kind)
            layoutParams = LinearLayout.LayoutParams(0, dp(50), weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            setOnTouchListener { v, event -> handleTouch(v, event) }
        }

        private fun handleTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    if (store.haptics) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (onLongPress != null) {
                        // Mit Langdruck: erst beim Loslassen entscheiden, was gemeint war.
                        longPressed = false
                        this@KeyView.postDelayed(longPress, 450)
                    } else {
                        onTap()
                        if (repeat) this@KeyView.postDelayed(repeater, 380)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    this@KeyView.removeCallbacks(repeater)
                    if (onLongPress != null) {
                        this@KeyView.removeCallbacks(longPress)
                        if (!longPressed) onTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    this@KeyView.removeCallbacks(repeater)
                    this@KeyView.removeCallbacks(longPress)
                }
            }
            return true
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(repeater)
            removeCallbacks(longPress)
            super.onDetachedFromWindow()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun setPage(p: Page) {
        page = p
        keysContainer?.post { render() }
    }

    // ------------------------------------------------------------------ Umschalt-Taste

    private fun shiftedText(c: Char): String =
        if (shift != Shift.OFF && c != 'ß') c.uppercaseChar().toString() else c.toString()

    private fun shiftLabel(): String = if (shift == Shift.LOCK) "⇪" else "⇧"

    private fun updateLetterLabels() {
        letterKeys.forEach { k -> k.letter?.let { k.text = shiftedText(it) } }
    }

    private fun refreshShiftKey() {
        val k = shiftKey ?: return
        k.text = shiftLabel()
        k.background = makeBackground(if (shift == Shift.OFF) Kind.SPECIAL else Kind.ACCENT)
        k.setTextColor(if (shift == Shift.OFF) palette.text else 0xFFFFFFFF.toInt())
    }

    private fun setShift(s: Shift) {
        if (s == shift) return
        shift = s
        updateLetterLabels()
        refreshShiftKey()
    }

    private fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        val doubleTap = now - lastShiftTap < 350
        lastShiftTap = now
        setShift(
            when {
                shift == Shift.LOCK -> Shift.OFF
                doubleTap -> Shift.LOCK
                shift == Shift.ONCE -> Shift.OFF
                else -> Shift.ONCE
            }
        )
    }

    /** Großbuchstabe am Satzanfang, wenn das Feld das wünscht. */
    private fun updateAutoCap() {
        if (shift == Shift.LOCK) return
        val info = currentInputEditorInfo
        val ic = currentInputConnection
        val caps = if (info != null && ic != null && info.inputType != InputType.TYPE_NULL) {
            ic.getCursorCapsMode(info.inputType)
        } else {
            0
        }
        setShift(if (caps != 0) Shift.ONCE else Shift.OFF)
    }

    // ------------------------------------------------------------------ Eingabe

    private fun isFieldEmpty(): Boolean {
        val ic = currentInputConnection ?: return true
        return ic.getTextBeforeCursor(1, 0).isNullOrEmpty() &&
            ic.getTextAfterCursor(1, 0).isNullOrEmpty()
    }

    /** Passwortfelder werden nie gezählt und nie mit einer Signatur versehen. */
    private fun isSensitiveField(): Boolean {
        val t = currentInputEditorInfo?.inputType ?: return false
        val variation = t and InputType.TYPE_MASK_VARIATION
        return when (t and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** Tippt Text. Ist das Feld leer und die Signatur fällig, kommt sie davor. */
    private fun type(text: String) {
        val ic = currentInputConnection ?: return
        if (!isSensitiveField() && isFieldEmpty()) {
            store.takeDueSignature()?.let {
                ic.commitText(it + "\n\n", 1)
                refreshStatus()
            }
        }
        ic.commitText(text, 1)
        fieldHadText = true
        typedBuffer.append(text)
        if (typedBuffer.length > 80) typedBuffer.delete(0, typedBuffer.length - 80)
    }

    private fun onLetter(c: Char) {
        type(shiftedText(c))
        if (shift == Shift.ONCE) setShift(Shift.OFF)
    }

    internal fun onChar(s: String) {
        // Kürzel wie ";;datum": vor dem Leerzeichen prüfen, danach normal weitertippen.
        val trigger = if (s == " ") pendingTrigger() else null
        type(s)
        updateAutoCap()
        trigger?.let { runTrigger(it) }
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        if (typedBuffer.isNotEmpty()) typedBuffer.setLength(typedBuffer.length - 1)
        if (ic.getSelectedText(0).isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
        } else {
            ic.commitText("", 1)
        }
        // Selbst leer gelöscht zählt nicht als gesendete Nachricht.
        fieldHadText = !isFieldEmpty()
        updateAutoCap()
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noEnterAction = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        updateAutoCap()
    }

    private fun insertSignatureManually() {
        val ic = currentInputConnection ?: return
        if (store.signature.isBlank() || isSensitiveField()) return
        ic.commitText(store.signature, 1)
        fieldHadText = true
        store.reset()
        refreshStatus()
    }

    /** Öffnet den Dateidialog. Das Ergebnis kommt über [FileBridge] zurück (siehe unten). */
    private fun openFilePicker() {
        val intent = Intent(this, FilePickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Nur in diese App wird der Dateiinhalt später eingefügt.
            .putExtra(FilePickerActivity.EXTRA_PACKAGE, currentInputEditorInfo?.packageName)
            // Welche Dateitypen das Eingabefeld als Anhang annimmt (oft keine).
            .putExtra(FilePickerActivity.EXTRA_MIME_TYPES, currentInputEditorInfo?.contentMimeTypes ?: emptyArray<String>())
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Dateidialog konnte nicht geöffnet werden.", Toast.LENGTH_SHORT)
                .show()
        }
    }

    /**
     * Tippt zuvor gewählte Dateien ein, sobald die App, aus der 📁 kam, wieder ein Eingabefeld
     * hat. Das klappt auch, wenn die Tastatur dabei noch nicht sichtbar ist.
     */
    internal fun insertPendingFile(info: EditorInfo?) {
        val ic = currentInputConnection
        if (!FileBridge.hasPending() || info == null || ic == null) return
        if (info.inputType == InputType.TYPE_NULL) return
        // Dateiinhalt gehört nie in ein Passwortfeld. Er wartet, bis ein normales Feld kommt.
        if (isSensitiveField()) return
        FileBridge.take(info.packageName)?.let { text ->
            type(text)
            updateAutoCap()
        }
        FileBridge.takeAttachments(info.packageName)?.let { list ->
            val accepted = info.contentMimeTypes.orEmpty()
            var rejected = 0
            for (a in list) {
                val fits = accepted.any { ClipDescription.compareMimeTypes(a.mime, it) }
                val ok = fits && ic.commitContent(
                    InputContentInfo(a.uri, ClipDescription(a.label, arrayOf(a.mime)), null),
                    InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                    null
                )
                if (!ok) rejected++
            }
            if (rejected > 0) {
                Toast.makeText(
                    this,
                    "Die App hat ${if (rejected == 1) "den Anhang" else "$rejected Anhänge"} nicht angenommen.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showKeyboardPicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    // ------------------------------------------------------------------ Termux

    /** Tippen auf ▶: die Standard-Aktion aus den Einstellungen. */
    private fun runDefaultAction() {
        val name = store.termuxAction.trim()
        if (!TermuxLogic.isValidActionName(name)) {
            toast("Ungültiger Name der Standard-Aktion. Bitte in den Einstellungen prüfen.")
            return
        }
        runAction(name)
    }

    /** Lange auf ▶ drücken: Aktionen aus Termux laden und als Tastenseite zeigen. */
    private fun showTermuxPage() {
        termuxActions = null
        termuxActionsError = null
        setPage(Page.TERMUX)
        startTermux("Aktionen laden", TermuxLogic.listCommand(), null) { result ->
            if (result.success) {
                termuxActions = TermuxLogic.parseActionList(result.stdout)
                if (termuxActions!!.isEmpty()) {
                    termuxActionsError = "Keine Aktionen in ~/.tastatur/aktionen. Einrichtungsskript in den Einstellungen."
                }
            } else {
                termuxActions = emptyList()
                termuxActionsError = TermuxLogic.describeFailure(result)
            }
            if (page == Page.TERMUX) render()
        }
    }

    private fun renderTermuxPage(keys: LinearLayout) {
        val actions = termuxActions
        val message = when {
            actions == null -> "Lade Aktionen aus Termux …"
            termuxActionsError != null -> termuxActionsError
            else -> null
        }
        if (message != null) {
            keys.addView(TextView(this).apply {
                text = message
                setTextColor(palette.hint)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(16), dp(12), dp(16))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4 * 54)))
        } else {
            // Bis zu 12 Aktionen sichtbar, drei je Zeile.
            actions!!.take(12).chunked(3).forEach { chunk ->
                val views = chunk.map { name ->
                    key(name, 1f, Kind.SPECIAL) {
                        setPage(Page.LETTERS)
                        runAction(name)
                    }.apply { textSize = 14f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                }
                keys.addView(row(views + List(3 - views.size) { spacer() }))
            }
        }
        keys.addView(
            row(
                listOf(
                    key("ABC", 2f, Kind.SPECIAL) { setPage(Page.LETTERS) },
                    key("↻", 1f, Kind.SPECIAL) { showTermuxPage() }
                )
            )
        )
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    }

    /**
     * Führt eine Aktion aus: auf der Markierung, sonst auf dem ganzen Feld. Bei leerem Feld
     * bekommt das Skript keinen Text, und seine Ausgabe wird eingefügt.
     */
    internal fun runAction(name: String) {
        val ic = currentInputConnection ?: return
        if (isSensitiveField()) {
            toast("Termux-Aktionen sind in Passwortfeldern gesperrt.")
            return
        }
        val selected = ic.getSelectedText(0)?.toString()
        val (mode, input) = if (!selected.isNullOrEmpty()) {
            TermuxMode.SELECTION to selected
        } else {
            TermuxMode.FIELD to (fullFieldText() ?: return)
        }
        if (input.length > TermuxLogic.MAX_INPUT) {
            toast("Zu viel Text für Termux (höchstens ${TermuxLogic.MAX_INPUT / 1000}.000 Zeichen).")
            return
        }
        val target = targetFor(mode, input)
        startTermux(name, TermuxLogic.actionCommand(name), input) { applyTermuxResult(target, it) }
    }

    /** Kürzel vor dem Cursor, das der Nutzer gerade selbst getippt hat, oder null. */
    private fun pendingTrigger(): String? {
        if (!store.termuxEnabled || !store.termuxTriggers || isSensitiveField()) return null
        val before = currentInputConnection?.getTextBeforeCursor(48, 0) ?: return null
        return TermuxLogic.findTrigger(before, typedBuffer)
    }

    private fun runTrigger(token: String) {
        // Erwartet vor dem Cursor: das Kürzel und das eben getippte Leerzeichen.
        val target = targetFor(TermuxMode.TRIGGER, ";;$token ")
        startTermux(";;$token", TermuxLogic.triggerCommand(token), null) { applyTermuxResult(target, it) }
    }

    private fun targetFor(mode: TermuxMode, original: String) = TermuxTarget(
        currentInputEditorInfo?.packageName, currentInputEditorInfo?.fieldId ?: 0, mode, original
    )

    private fun startTermux(label: String, args: List<String>, stdin: String?, done: (TermuxResult) -> Unit) {
        termuxJobs++
        termuxLabel = label
        refreshStatus()
        termux.run(label, args, stdin) { result ->
            termuxJobs = (termuxJobs - 1).coerceAtLeast(0)
            refreshStatus()
            done(result)
        }
    }

    /** Setzt das Ergebnis nur ein, wenn sich App, Feld und Text seit dem Start nicht geändert haben. */
    private fun applyTermuxResult(target: TermuxTarget, result: TermuxResult) {
        if (!result.success) {
            toast(TermuxLogic.describeFailure(result))
            return
        }
        val text = TermuxLogic.normalizeOutput(result.stdout)
        if (text.isBlank() && target.mode != TermuxMode.TRIGGER && target.original.isNotBlank()) {
            toast("Das Skript hat nichts ausgegeben. Der Text bleibt unverändert.")
            return
        }
        if (result.stdoutTruncated) toast("Ausgabe gekürzt (Termux liefert höchstens 100 KB).")

        val ic = currentInputConnection
        val info = currentInputEditorInfo
        val current = when (target.mode) {
            TermuxMode.SELECTION -> ic?.getSelectedText(0)?.toString()
            TermuxMode.FIELD -> fullFieldText()
            TermuxMode.TRIGGER -> ic?.getTextBeforeCursor(target.original.length, 0)?.toString()
        }
        if (ic == null || isSensitiveField() ||
            !TermuxLogic.canApply(target, info?.packageName, info?.fieldId ?: 0, current)
        ) {
            copyToClipboard(text)
            toast("Der Text hat sich inzwischen geändert. Das Ergebnis liegt in der Zwischenablage.")
            return
        }
        ic.beginBatchEdit()
        when (target.mode) {
            TermuxMode.SELECTION -> ic.commitText(text, 1)
            TermuxMode.FIELD -> if (target.original.isEmpty()) {
                type(text)
            } else {
                ic.setSelection(0, target.original.length)
                ic.commitText(text, 1)
            }
            TermuxMode.TRIGGER -> {
                ic.deleteSurroundingText(target.original.length, 0)
                ic.commitText("$text ", 1)
            }
        }
        ic.endBatchEdit()
        fieldHadText = !isFieldEmpty()
        updateAutoCap()
    }

    /** Ganzer Text des Felds (oder null, wenn die App ihn nicht herausgibt). */
    private fun fullFieldText(): String? {
        val ic = currentInputConnection ?: return null
        val request = ExtractedTextRequest().apply { hintMaxChars = TermuxLogic.MAX_INPUT + 1 }
        ic.getExtractedText(request, 0)?.text?.let { return it.toString() }
        val before = ic.getTextBeforeCursor(TermuxLogic.MAX_INPUT + 1, 0) ?: return null
        val selected = ic.getSelectedText(0) ?: ""
        val after = ic.getTextAfterCursor(TermuxLogic.MAX_INPUT + 1, 0) ?: ""
        return "$before$selected$after"
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Termux", text))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ Status

    private fun statusText(): String = when {
        termuxJobs > 0 -> "⏳ Termux: ${termuxLabel ?: "läuft"} …"
        FileBridge.hasPending() -> "📁 Datei wartet auf ein Eingabefeld in der App"
        !store.enabled || store.signature.isBlank() -> "Signatur aus"
        store.isDue() -> "Signatur fällig: kommt vor die nächste Nachricht"
        else -> store.messagesUntilDue()?.let { "Noch $it Nachricht(en) bis zur Signatur" }
            ?: "Signatur nach Zeitplan oder per Tasker"
    }

    private fun refreshStatus() {
        statusView?.text = statusText()
    }

    // ------------------------------------------------------------------ Nachrichten erkennen

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Termux-Taste ein- oder ausgeschaltet: Leiste neu aufbauen.
        if (store.termuxEnabled != barHasTermux) setInputView(onCreateInputView())
        fieldHadText = !isFieldEmpty()
        if (!restarting) {
            val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
            page = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) {
                Page.SYMBOLS
            } else {
                Page.LETTERS
            }
        }
        render()
        updateAutoCap()
        refreshStatus()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        typedBuffer.setLength(0)
        insertPendingFile(attribute)
    }

    /**
     * Die Tastatur sieht den Senden-Button der App nicht. Als Näherung gilt:
     * Das Feld war gefüllt und ist jetzt leer, also wurde die Nachricht abgeschickt.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        // Markiert oder Cursor deutlich zurückgesetzt: getippte Folge gilt nicht mehr für Kürzel.
        if (newSelStart != newSelEnd || oldSelStart - newSelStart > 2) typedBuffer.setLength(0)
        val empty = isFieldEmpty()
        if (!isSensitiveField() && empty && fieldHadText) {
            store.registerMessageSent()
            refreshStatus()
        }
        fieldHadText = !empty
    }
}
