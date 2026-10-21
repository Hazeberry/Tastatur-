package de.hazeberry.keyboard

import android.annotation.SuppressLint
import android.content.Context
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
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView

private enum class Page { LETTERS, SYMBOLS, SYMBOLS2, EMOJI }
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
        bar.addView(smallKey("Sig") { insertSignatureManually() })
        bar.addView(smallKey("🌐") { showKeyboardPicker() })

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
        onTap: () -> Unit
    ): KeyView = KeyView(label, weight, kind, repeat, onTap)

    private fun charKey(s: String): KeyView = key(s, 1f, Kind.LETTER) { onChar(s) }

    private fun letterKey(c: Char): KeyView {
        val k = key(shiftedText(c), 1f, Kind.LETTER) { onLetter(c) }
        k.letter = c
        letterKeys += k
        return k
    }

    private fun smallKey(label: String, onTap: () -> Unit): KeyView {
        val k = key(label, 1f, Kind.SPECIAL, onTap = onTap)
        k.textSize = 14f
        k.layoutParams = LinearLayout.LayoutParams(dp(52), dp(32)).apply {
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
        private val onTap: () -> Unit
    ) : TextView(this@KeyboardService) {

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
                    onTap()
                    if (repeat) this@KeyView.postDelayed(repeater, 380)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    this@KeyView.removeCallbacks(repeater)
                }
            }
            return true
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(repeater)
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
    }

    private fun onLetter(c: Char) {
        type(shiftedText(c))
        if (shift == Shift.ONCE) setShift(Shift.OFF)
    }

    private fun onChar(s: String) {
        type(s)
        updateAutoCap()
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
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

    private fun showKeyboardPicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    // ------------------------------------------------------------------ Status

    private fun statusText(): String = when {
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
        val empty = isFieldEmpty()
        if (!isSensitiveField() && empty && fieldHadText) {
            store.registerMessageSent()
            refreshStatus()
        }
        fieldHadText = !empty
    }
}
