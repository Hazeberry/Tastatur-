package de.hazeberry.keyboard

import de.hazeberry.keyboard.Cfb.Companion.le16
import de.hazeberry.keyboard.Cfb.Companion.le32
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.Locale
import java.util.TreeMap

/**
 * Text aus alten Office-Dateien (Word 97–2003 .doc, Excel 97–2003 .xls, PowerPoint 97–2003 .ppt).
 * Spezifikationen: [MS-DOC], [MS-XLS], [MS-PPT].
 */
object LegacyOffice {

    // ================================================================== Word (.doc)

    fun wordText(cfb: Cfb, maxChars: Int): String {
        val wd = cfb.stream("WordDocument") ?: throw ReadProblem("Das ist keine Word-Datei.")
        if (le16(wd, 0) != 0xA5EC) throw ReadProblem("Unbekanntes Word-Format.")
        val nFib = le16(wd, 2)
        val flags = le16(wd, 0x0A)
        if (flags and 0x0100 != 0) {
            throw ReadProblem(
                "Das Word-Dokument (altes .doc-Format) ist mit einem Passwort geschützt. " +
                    "Bitte ohne Passwort oder als .docx/PDF speichern."
            )
        }
        if (nFib < 0xC0) return word95Text(wd, flags, maxChars)

        val table = cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table")
            ?: throw ReadProblem("Beschädigte Word-Datei (Tabellen-Stream fehlt).")

        // FIB: fibBase (32) | csw | fibRgW | cslw | fibRgLw | cbRgFcLcb | fibRgFcLcb
        val csw = le16(wd, 32)
        var pos = 34 + csw * 2
        val cslw = le16(wd, pos)
        pos += 2
        val ccpText = le32(wd, pos + 3 * 4)
        pos += cslw * 4 + 2
        val fcClx = le32(wd, pos + 33 * 8)
        val lcbClx = le32(wd, pos + 33 * 8 + 4)
        if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > table.size) {
            throw ReadProblem("Beschädigte Word-Datei (Textverzeichnis fehlt).")
        }

        // Clx: zuerst beliebig viele Prc (0x01), dann ein Pcdt (0x02) mit der Piece Table.
        var p = fcClx
        val end = fcClx + lcbClx
        while (p < end && table[p].toInt() == 0x01) {
            val cb = le16(table, p + 1).toShort().toInt()
            p += 3 + cb.coerceAtLeast(0)
        }
        if (p >= end || table[p].toInt() != 0x02) throw ReadProblem("Beschädigte Word-Datei (Piece Table fehlt).")
        val lcb = le32(table, p + 1)
        val plc = p + 5
        if (lcb < 4 || plc + lcb > table.size) throw ReadProblem("Beschädigte Word-Datei.")
        val pieces = (lcb - 4) / 12
        val raw = StringBuilder()
        val textEnd = if (ccpText > 0) ccpText else Int.MAX_VALUE
        // Etwas Puffer für Feldcodes, die beim Bereinigen wegfallen.
        val rawLimit = maxChars.toLong() * 2 + 1000
        for (i in 0 until pieces) {
            val cpStart = le32(table, plc + i * 4)
            val cpEnd = minOf(le32(table, plc + (i + 1) * 4), textEnd)
            if (cpStart >= textEnd || raw.length > rawLimit) break
            val count = cpEnd - cpStart
            if (count <= 0) continue
            val fcRaw = le32(table, plc + (pieces + 1) * 4 + i * 8 + 2)
            val compressed = fcRaw and 0x40000000 != 0
            val fc = fcRaw and 0x3FFFFFFF
            if (compressed) {
                val off = fc / 2
                val len = minOf(count, wd.size - off)
                if (off < 0 || len <= 0) continue
                raw.append(String(wd, off, len, CP1252))
            } else {
                val len = minOf(count * 2, (wd.size - fc) and 0x7FFFFFFE)
                if (fc < 0 || len <= 0) continue
                raw.append(String(wd, fc, len, Charsets.UTF_16LE))
            }
        }
        return cleanWordText(raw)
    }

    /** Word 6/95: Text liegt zusammenhängend zwischen fcMin und fcMac (Windows-1252). */
    private fun word95Text(wd: ByteArray, flags: Int, maxChars: Int): String {
        if (flags and 0x0004 != 0) {
            throw ReadProblem("Word-6/95-Dokument mit Schnellspeicherung wird nicht unterstützt. Bitte als .docx speichern.")
        }
        val fcMin = le32(wd, 0x18)
        val fcMac = le32(wd, 0x1C)
        if (fcMin < 0 || fcMac <= fcMin || fcMac > wd.size) throw ReadProblem("Beschädigte Word-Datei.")
        val len = minOf(fcMac - fcMin, maxChars * 2 + 1000)
        return cleanWordText(StringBuilder(String(wd, fcMin, len, CP1252)))
    }

    /** Word-Steuerzeichen in normalen Text übersetzen, Feldcodes entfernen. */
    private fun cleanWordText(raw: CharSequence): String {
        val out = StringBuilder(raw.length)
        // Je Feld: true = noch im Feldcode (unsichtbar), false = im Feldergebnis (sichtbar)
        val fields = ArrayDeque<Boolean>()
        var lastWasCell = false
        for (c in raw) {
            when (c) {
                '\u0013' -> { fields.addLast(true); continue }
                '\u0014' -> { if (fields.isNotEmpty()) { fields.removeLast(); fields.addLast(false) }; continue }
                '\u0015' -> { if (fields.isNotEmpty()) fields.removeLast(); continue }
            }
            if (fields.any { it }) continue
            if (c == '\u0007') {
                if (lastWasCell) {
                    // Zeilenende einer Tabelle
                    while (out.isNotEmpty() && out.last() == '\t') out.setLength(out.length - 1)
                    out.append('\n')
                    lastWasCell = false
                } else {
                    out.append('\t')
                    lastWasCell = true
                }
                continue
            }
            lastWasCell = false
            when (c) {
                '\r', '\u000B', '\u000C' -> out.append('\n')
                '\t' -> out.append('\t')
                '\u001E' -> out.append('-')
                ' ' -> out.append(' ')
                '\u001F' -> {}
                else -> if (c >= ' ') out.append(c)
            }
        }
        return out.toString()
    }

    // ================================================================== Excel (.xls)

    fun excelText(cfb: Cfb, maxChars: Int): String {
        val wb = cfb.stream("Workbook")
            ?: if (cfb.has("Book")) throw ReadProblem("Excel 5/95-Format wird nicht unterstützt. Bitte als .xlsx speichern.")
            else throw ReadProblem("Das ist keine Excel-Datei.")

        val sst = ArrayList<String>()
        val sheets = ArrayList<Pair<Int, String>>() // BOF-Position, Name
        val formats = HashMap<Int, String>()
        val xfFormat = ArrayList<Int>()
        var date1904 = false
        val cells = HashMap<Int, TreeMap<Int, TreeMap<Int, String>>>() // Blatt-Position -> Zeile -> Spalte
        var currentSheet = -1
        var pendingString: Pair<Int, Int>? = null // Formel mit Text-Ergebnis wartet auf STRING

        fun put(row: Int, col: Int, value: String) {
            if (currentSheet < 0 || value.isEmpty()) return
            cells.getOrPut(currentSheet) { TreeMap() }.getOrPut(row) { TreeMap() }[col] = value
        }
        fun number(ixfe: Int, value: Double): String {
            val fmt = xfFormat.getOrNull(ixfe) ?: 0
            return ExcelFormats.format(value, fmt, formats[fmt], date1904)
        }

        var p = 0
        while (p + 4 <= wb.size) {
            val type = le16(wb, p)
            val len = le16(wb, p + 2)
            val body = p + 4
            if (body + len > wb.size) break
            when (type) {
                0x0809 -> { // BOF
                    if (sheets.any { it.first == p }) currentSheet = p
                }
                0x002F -> throw ReadProblem(
                    "Die Excel-Datei (altes .xls-Format) ist mit einem Passwort geschützt. " +
                        "Bitte ohne Passwort oder als .xlsx speichern."
                )
                0x0022 -> date1904 = le16(wb, body) == 1
                0x041E -> { // FORMAT
                    val id = le16(wb, body)
                    formats[id] = xlUnicodeString(wb, body + 2, body + len)
                }
                0x00E0 -> xfFormat.add(le16(wb, body + 2)) // XF
                0x0085 -> { // BOUNDSHEET
                    val pos = le32(wb, body)
                    val kind = wb[body + 5].toInt() and 0xFF
                    val cch = wb[body + 6].toInt() and 0xFF
                    val high = wb[body + 7].toInt() and 0x01 != 0
                    val name = chars(wb, body + 8, cch, high, body + len)
                    if (kind == 0) sheets.add(pos to name)
                }
                0x00FC -> { // SST, mit folgenden CONTINUE-Datensätzen
                    val chunks = arrayListOf(wb.copyOfRange(body, body + len))
                    var q = body + len
                    while (q + 4 <= wb.size && le16(wb, q) == 0x003C) {
                        val l = le16(wb, q + 2)
                        if (q + 4 + l > wb.size) break
                        chunks.add(wb.copyOfRange(q + 4, q + 4 + l))
                        q += 4 + l
                    }
                    readSst(chunks, sst)
                }
                0x00FD -> put(le16(wb, body), le16(wb, body + 2), sst.getOrNull(le32(wb, body + 6)).orEmpty())
                0x0204 -> put(le16(wb, body), le16(wb, body + 2), xlUnicodeString(wb, body + 6, body + len))
                0x0203 -> put(le16(wb, body), le16(wb, body + 2), number(le16(wb, body + 4), double(wb, body + 6)))
                0x027E -> put(le16(wb, body), le16(wb, body + 2), number(le16(wb, body + 4), rk(le32(wb, body + 6))))
                0x00BD -> { // MULRK
                    val row = le16(wb, body)
                    val first = le16(wb, body + 2)
                    val count = (len - 6) / 6
                    for (i in 0 until count) {
                        val o = body + 4 + i * 6
                        put(row, first + i, number(le16(wb, o), rk(le32(wb, o + 2))))
                    }
                }
                0x0006 -> { // FORMULA: zwischengespeichertes Ergebnis
                    val row = le16(wb, body)
                    val col = le16(wb, body + 2)
                    val v = body + 6
                    if (le16(wb, v + 6) == 0xFFFF) {
                        when (wb[v].toInt() and 0xFF) {
                            0 -> pendingString = row to col
                            1 -> put(row, col, if (wb[v + 2].toInt() != 0) "WAHR" else "FALSCH")
                            2 -> put(row, col, "#FEHLER")
                        }
                    } else {
                        put(row, col, number(le16(wb, body + 4), double(wb, v)))
                    }
                }
                0x0207 -> { // STRING (Ergebnis der vorigen Formel)
                    pendingString?.let { (r, c) -> put(r, c, xlUnicodeString(wb, body, body + len)) }
                    pendingString = null
                }
                0x0205 -> { // BOOLERR
                    val v = wb[body + 6].toInt() and 0xFF
                    val isError = wb[body + 7].toInt() != 0
                    put(le16(wb, body), le16(wb, body + 2), if (isError) "#FEHLER" else if (v != 0) "WAHR" else "FALSCH")
                }
            }
            p = body + len
        }

        val out = StringBuilder()
        for ((pos, name) in sheets.sortedBy { it.first }) {
            if (out.length > maxChars) break
            ExcelFormats.render(name, cells[pos] ?: continue, out)
        }
        return out.toString()
    }

    /** Liest die Shared String Table, die über CONTINUE-Datensätze verteilt sein kann. */
    private fun readSst(chunks: List<ByteArray>, out: MutableList<String>) {
        val r = ChunkReader(chunks)
        r.skip(4) // cstTotal
        val unique = r.u32()
        var i = 0
        while (i < unique && i < 5_000_000 && !r.done) {
            if (r.atChunkEnd) r.nextChunk()
            val cch = r.u16()
            val flags = r.u8()
            var high = flags and 0x01 != 0
            val runs = if (flags and 0x08 != 0) r.u16() else 0
            val ext = if (flags and 0x04 != 0) r.u32() else 0
            val sb = StringBuilder(cch)
            var left = cch
            while (left > 0 && !r.done) {
                if (r.atChunkEnd) {
                    r.nextChunk()
                    if (r.done) break
                    high = r.u8() and 0x01 != 0 // neuer Zeichenmodus nach Fortsetzung
                }
                sb.append(if (high) r.u16().toChar() else CP1252_TABLE[r.u8()])
                left--
            }
            r.skipAcross(runs * 4 + ext)
            out.add(sb.toString())
            i++
        }
    }

    private class ChunkReader(private val chunks: List<ByteArray>) {
        private var c = 0
        private var pos = 0
        val done get() = c >= chunks.size
        val atChunkEnd get() = !done && pos >= chunks[c].size
        fun nextChunk() { c++; pos = 0 }
        fun u8(): Int {
            while (atChunkEnd) nextChunk()
            if (done) return 0
            return chunks[c][pos++].toInt() and 0xFF
        }
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Int = u16() or (u16() shl 16)
        fun skip(n: Int) { repeat(n) { u8() } }
        fun skipAcross(n: Int) { if (n > 0) skip(minOf(n, 10_000_000)) }
    }

    private fun xlUnicodeString(b: ByteArray, off: Int, end: Int): String {
        val cch = le16(b, off)
        val high = b.getOrNull(off + 2)?.toInt()?.and(0x01) == 1
        return chars(b, off + 3, cch, high, end)
    }

    private fun chars(b: ByteArray, off: Int, cch: Int, high: Boolean, end: Int): String {
        val limit = minOf(end, b.size)
        return if (high) {
            val len = minOf(cch * 2, limit - off).coerceAtLeast(0) and 0x7FFFFFFE
            String(b, off, len, Charsets.UTF_16LE)
        } else {
            val len = minOf(cch, limit - off).coerceAtLeast(0)
            String(b, off, len, CP1252)
        }
    }

    private fun double(b: ByteArray, off: Int): Double {
        val lo = le32(b, off).toLong() and 0xFFFFFFFFL
        val hi = le32(b, off + 4).toLong() and 0xFFFFFFFFL
        return java.lang.Double.longBitsToDouble((hi shl 32) or lo)
    }

    /** RK: kompakt gespeicherte Zahl (Ganzzahl oder obere 30 Bit eines Double, optional /100). */
    private fun rk(v: Int): Double {
        val value = if (v and 0x02 != 0) {
            (v shr 2).toDouble()
        } else {
            java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        }
        return if (v and 0x01 != 0) value / 100 else value
    }

    // ================================================================== PowerPoint (.ppt)

    fun slidesText(cfb: Cfb, maxChars: Int): String {
        if (cfb.has("EncryptedSummary")) {
            throw ReadProblem(
                "Die PowerPoint-Datei (altes .ppt-Format) ist mit einem Passwort geschützt. " +
                    "Bitte ohne Passwort oder als .pptx/PDF speichern."
            )
        }
        val doc = cfb.stream("PowerPoint Document") ?: throw ReadProblem("Das ist keine PowerPoint-Datei.")
        currentSlidesText(cfb, doc, maxChars)?.let { return it }
        // Notlösung ohne gültiges Persistenz-Verzeichnis: alle Textatome der Reihe nach.
        val out = StringBuilder()
        val seen = HashSet<String>()
        var slide = 0

        fun text(s: String, fromList: Boolean) {
            val t = s.replace('\r', '\n').replace('\u000B', '\n').trim()
            if (t.isEmpty()) return
            if (!fromList && t in seen) return
            seen.add(t)
            out.append(t).append('\n')
        }

        fun walk(start: Int, end: Int, depth: Int, slideList: Boolean) {
            var p = start
            while (p + 8 <= end && out.length <= maxChars) {
                val verInst = le16(doc, p)
                val type = le16(doc, p + 2)
                val len = le32(doc, p + 4)
                val body = p + 8
                if (len < 0 || body + len > end) break
                val ver = verInst and 0x0F
                val instance = verInst ushr 4
                if (ver == 0x0F) {
                    when {
                        type == 0x03F8 || type == 0x0FC9 || type == 0x03F0 -> {} // Master, Handzettel, Notizen
                        type == 0x0FF0 && instance != 0 -> {} // Textliste der Master/Notizen
                        depth < 20 -> walk(body, body + len, depth + 1, slideList || type == 0x0FF0)
                    }
                } else {
                    when (type) {
                        0x03F3 -> if (slideList) { // SlidePersistAtom: neue Folie
                            slide++
                            if (out.isNotEmpty()) out.append('\n')
                            out.append("Folie ").append(slide).append('\n')
                        }
                        0x0FA0 -> text(String(doc, body, len and 0x7FFFFFFE, Charsets.UTF_16LE), slideList)
                        0x0FA8 -> text(String(doc, body, len, CP1252), slideList)
                    }
                }
                p = body + len
            }
        }
        walk(0, doc.size, 0, false)
        return out.toString()
    }

    /**
     * Liest nur den aktuellen Stand: PowerPoint hängt beim Schnellspeichern Änderungen an und
     * lässt alte Fassungen in der Datei. Welche Datensätze gelten, steht im Persistenz-Verzeichnis
     * (Current User -> UserEditAtom-Kette -> PersistDirectoryAtom).
     */
    private fun currentSlidesText(cfb: Cfb, doc: ByteArray, maxChars: Int): String? {
        val user = cfb.stream("Current User") ?: return null
        var edit = le32(user, 16)
        val persist = HashMap<Int, Int>()
        var docRef = -1
        var guard = 0
        while (edit in 0 until doc.size - 8 && guard++ < 1000) {
            if (le16(doc, edit + 2) != 0x0FF5) return null // UserEditAtom
            val body = edit + 8
            if (docRef < 0) docRef = le32(doc, body + 16)
            val dir = le32(doc, body + 12)
            if (dir < 0 || dir + 8 > doc.size || le16(doc, dir + 2) != 0x1772) return null // PersistDirectoryAtom
            val dirLen = le32(doc, dir + 4)
            var p = dir + 8
            val end = minOf(dir + 8 + dirLen, doc.size)
            while (p + 4 <= end) {
                val v = le32(doc, p)
                val id = v and 0xFFFFF
                val count = v ushr 20
                p += 4
                for (i in 0 until count) {
                    if (p + 4 > end) break
                    // Neuere Bearbeitungen kommen zuerst und gewinnen.
                    persist.putIfAbsent(id + i, le32(doc, p))
                    p += 4
                }
            }
            val last = le32(doc, body + 8)
            if (last <= 0 || last == edit) break
            edit = last
        }
        val docOffset = persist[docRef] ?: return null
        if (docOffset < 0 || docOffset + 8 > doc.size || le16(doc, docOffset + 2) != 0x03E8) return null

        // Folienliste im Document-Container: SlidePersistAtom, danach die Platzhaltertexte
        class Slide(val ref: Int) { val texts = ArrayList<String>() }
        val slides = ArrayList<Slide>()
        fun children(start: Int, end: Int, action: (type: Int, instance: Int, body: Int, len: Int) -> Unit) {
            var p = start
            while (p + 8 <= end) {
                val verInst = le16(doc, p)
                val type = le16(doc, p + 2)
                val len = le32(doc, p + 4)
                if (len < 0 || p + 8 + len > end) break
                action(type, verInst ushr 4, p + 8, len)
                p += 8 + len
            }
        }
        fun atomText(type: Int, body: Int, len: Int): String? = when (type) {
            0x0FA0 -> String(doc, body, len and 0x7FFFFFFE, Charsets.UTF_16LE)
            0x0FA8 -> String(doc, body, len, CP1252)
            else -> null
        }?.replace('\r', '\n')?.replace('\u000B', '\n')?.trim()?.takeIf { it.isNotEmpty() }

        val docLen = le32(doc, docOffset + 4)
        children(docOffset + 8, minOf(docOffset + 8 + docLen, doc.size)) { type, instance, body, len ->
            if (type == 0x0FF0 && instance == 0) {
                children(body, body + len) { t, _, b, l ->
                    if (t == 0x03F3) slides.add(Slide(le32(doc, b)))
                    else atomText(t, b, l)?.let { slides.lastOrNull()?.texts?.add(it) }
                }
            }
        }
        if (slides.isEmpty()) return null

        // Textfelder, die direkt auf der Folie liegen (nicht in der Folienliste)
        fun walk(start: Int, end: Int, depth: Int, into: MutableList<String>) {
            children(start, end) { type, _, body, len ->
                val ver = le16(doc, body - 8) and 0x0F
                if (ver == 0x0F && depth < 30) walk(body, body + len, depth + 1, into)
                else atomText(type, body, len)?.let(into::add)
            }
        }
        val out = StringBuilder()
        for ((i, slide) in slides.withIndex()) {
            if (out.length > maxChars) break
            val texts = LinkedHashSet(slide.texts)
            persist[slide.ref]?.let { off ->
                if (off >= 0 && off + 8 <= doc.size && le16(doc, off + 2) == 0x03EE) {
                    val drawn = ArrayList<String>()
                    walk(off + 8, minOf(off + 8 + le32(doc, off + 4), doc.size), 0, drawn)
                    texts.addAll(drawn)
                }
            }
            out.append("Folie ").append(i + 1).append('\n')
            texts.forEach { out.append(it).append('\n') }
            out.append('\n')
        }
        return out.toString()
    }

    private val CP1252 = charset("windows-1252")
    private val CP1252_TABLE: CharArray = String(ByteArray(256) { it.toByte() }, CP1252).toCharArray()
}

/** Zahlen und Datumswerte aus Excel lesbar machen (für .xls und .xlsx). */
object ExcelFormats {

    fun format(value: Double, formatId: Int, formatCode: String?, date1904: Boolean): String =
        if (isDate(formatId, formatCode) && value >= 0 && value < 2_958_466) date(value, date1904) else number(value)

    /** Zahl ohne "1.0E7" und ohne Rundungsrauschen (Excel rechnet mit 15 Stellen). */
    fun number(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return value.toLong().toString()
        return BigDecimal(value).round(MathContext(15)).stripTrailingZeros().toPlainString()
    }

    /**
     * Ein Blatt als Text: Zeilen untereinander, Zellen mit Tab getrennt. Leere Zellen bleiben als
     * leere Spalte erhalten, damit die Spalten untereinander passen. Links beginnt es bei der
     * ersten benutzten Spalte.
     */
    fun render(name: String, rows: Map<Int, Map<Int, String>>, out: StringBuilder) {
        val used = rows.values.filter { it.isNotEmpty() }
        if (used.isEmpty()) return
        val minCol = used.minOf { it.keys.min() }
        out.append("Tabelle: ").append(name).append('\n')
        for ((_, cols) in rows.toSortedMap()) {
            if (cols.isEmpty()) continue
            var current = minCol
            for ((col, value) in cols.toSortedMap()) {
                repeat((col - current).coerceIn(0, 50)) { out.append('\t') }
                out.append(value.replace('\n', ' ').replace('\t', ' '))
                current = col
            }
            out.append('\n')
        }
        out.append('\n')
    }

    /** Spaltenbuchstaben aus einem Zellbezug wie "AB12" (0-basiert), oder -1. */
    fun columnOf(ref: String?): Int {
        if (ref.isNullOrEmpty()) return -1
        var col = 0
        var any = false
        for (c in ref) {
            if (c in 'A'..'Z') { col = col * 26 + (c - 'A' + 1); any = true } else break
        }
        return if (any) col - 1 else -1
    }

    fun isDate(id: Int, code: String?): Boolean {
        if (id in 14..22 || id in 45..47) return true
        if (code.isNullOrBlank()) return false
        val cleaned = code
            .replace(Regex("\"[^\"]*\""), "")
            .replace(Regex("\\[[^\\]]*]"), "")
            .replace(Regex("\\\\."), "")
            .substringBefore(';')
        return cleaned.any { it in "dmyhsDMYHSJT" } && !cleaned.contains("general", ignoreCase = true)
    }

    /** Excel-Seriennummer als deutsches Datum (mit Uhrzeit, wenn vorhanden). */
    fun date(serial: Double, date1904: Boolean): String {
        val days = Math.floor(serial).toLong()
        var seconds = Math.round((serial - days) * 86_400)
        var d = days
        if (seconds >= 86_400) { d++; seconds -= 86_400 }
        val time = if (seconds > 0) {
            val h = seconds / 3600
            val m = seconds / 60 % 60
            val s = seconds % 60
            if (s != 0L) "%02d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%02d:%02d".format(Locale.ROOT, h, m)
        } else ""
        if (!date1904 && d == 0L) return time.ifEmpty { "0" }
        val dateValue = if (date1904) {
            LocalDate.of(1904, 1, 1).plusDays(d)
        } else if (d < 60) {
            LocalDate.of(1899, 12, 31).plusDays(d)
        } else {
            // Excel hält 1900 fälschlich für ein Schaltjahr (29.02.1900 = 60).
            LocalDate.of(1899, 12, 30).plusDays(d)
        }
        val text = "%02d.%02d.%04d".format(Locale.ROOT, dateValue.dayOfMonth, dateValue.monthValue, dateValue.year)
        return if (time.isEmpty()) text else "$text $time"
    }
}
