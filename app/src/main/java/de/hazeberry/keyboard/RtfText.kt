package de.hazeberry.keyboard

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Text aus RTF-Dateien. RTF ist Text mit Steuerwörtern (\par, \b ...) und Gruppen ({...}).
 * Formatierung fällt weg, Absätze, Tabs, Sonderzeichen und Unicode bleiben.
 */
object RtfText {

    /** Gruppen, deren Inhalt kein sichtbarer Text ist. */
    private val SKIP = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "themedata", "colorschememapping",
        "latentstyles", "datastore", "xmlnstbl", "listtable", "listoverridetable", "rsidtbl", "generator",
        "mmathPr", "header", "footer", "headerl", "headerr", "headerf", "footerl", "footerr", "footerf",
        "filetbl", "revtbl", "pgdsctbl", "userprops", "fldinst", "nonshppict", "bkmkstart", "bkmkend",
        "shpinst", "sp", "sn", "sv", "xe", "tc"
    )

    private class State(var skip: Boolean, var uc: Int)

    fun toText(bytes: ByteArray, maxChars: Int): String {
        val out = StringBuilder()
        val pending = ByteArrayOutputStream() // \'hh-Bytes, werden gesammelt dekodiert
        var charset: Charset = WIN1252
        val stack = ArrayDeque<State>()
        var state = State(skip = false, uc = 1)
        var skipChars = 0 // Ersatzzeichen nach \uN überspringen
        var groupStart = false
        var i = 0
        val n = bytes.size

        fun flush() {
            if (pending.size() > 0) {
                if (!state.skip) out.append(String(pending.toByteArray(), charset))
                pending.reset()
            }
        }
        fun emit(s: String) {
            flush()
            if (!state.skip && out.length <= maxChars) out.append(s)
        }

        while (i < n && out.length <= maxChars) {
            val c = bytes[i].toInt() and 0xFF
            when {
                c == '{'.code -> {
                    flush()
                    stack.addLast(state)
                    state = State(state.skip, state.uc)
                    groupStart = true
                    i++
                    continue
                }
                c == '}'.code -> {
                    flush()
                    state = stack.removeLastOrNull() ?: state
                    skipChars = 0
                    i++
                }
                c == '\\'.code && i + 1 < n -> {
                    val next = bytes[i + 1].toInt() and 0xFF
                    when {
                        next == '\''.code && i + 3 < n -> {
                            val hex = String(bytes, i + 2, 2, Charsets.US_ASCII).toIntOrNull(16)
                            i += 4
                            if (skipChars > 0) { skipChars--; continue }
                            if (hex != null && !state.skip) pending.write(hex)
                        }
                        next == '*'.code -> {
                            // Optionale Zieldaten, die ein Leser nicht kennen muss.
                            if (groupStart) state.skip = true
                            i += 2
                        }
                        Character.isLetter(next) -> {
                            var j = i + 1
                            while (j < n && Character.isLetter(bytes[j].toInt())) j++
                            val word = String(bytes, i + 1, j - i - 1, Charsets.US_ASCII)
                            var k = j
                            if (k < n && (bytes[k] == '-'.code.toByte() || Character.isDigit(bytes[k].toInt()))) {
                                k++
                                while (k < n && Character.isDigit(bytes[k].toInt())) k++
                            }
                            val param = if (k > j) String(bytes, j, k - j, Charsets.US_ASCII).toIntOrNull() else null
                            if (k < n && bytes[k] == ' '.code.toByte()) k++
                            i = k
                            if (groupStart && word in SKIP) state.skip = true
                            when (word) {
                                "ansicpg" -> param?.let { cp -> codepage(cp)?.let { charset = it } }
                                "par", "line", "sect", "page", "row" -> emit("\n")
                                "tab", "cell" -> emit("\t")
                                "emdash" -> emit("—")
                                "endash" -> emit("–")
                                "bullet" -> emit("•")
                                "lquote" -> emit("‘")
                                "rquote" -> emit("’")
                                "ldblquote" -> emit("“")
                                "rdblquote" -> emit("”")
                                "uc" -> state.uc = param ?: 1
                                "u" -> if (param != null) {
                                    val code = if (param < 0) param + 65536 else param
                                    emit(code.toChar().toString())
                                    skipChars = state.uc
                                }
                                "bin" -> i += (param ?: 0).coerceAtLeast(0)
                            }
                        }
                        else -> {
                            // Steuersymbole
                            when (next.toChar()) {
                                '\\', '{', '}' -> if (skipChars > 0) skipChars-- else emit(next.toChar().toString())
                                '~' -> emit(" ")
                                '_' -> emit("-")
                                '\n', '\r' -> emit("\n")
                            }
                            i += 2
                        }
                    }
                }
                c == '\r'.code || c == '\n'.code -> i++
                else -> {
                    if (skipChars > 0) {
                        skipChars--
                    } else if (!state.skip) {
                        if (c >= 0x80) pending.write(c) else emit(c.toChar().toString())
                    }
                    i++
                }
            }
            groupStart = false
        }
        flush()
        return out.toString().replace(" ", " ")
    }

    private fun codepage(cp: Int): Charset? = try {
        when (cp) {
            65001 -> Charsets.UTF_8
            else -> Charset.forName("windows-$cp")
        }
    } catch (_: Exception) {
        try { Charset.forName("cp$cp") } catch (_: Exception) { null }
    }

    private val WIN1252: Charset = charset("windows-1252")
}
