package de.hazeberry.keyboard

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/** Welche Art Datei, und damit welcher Leseweg. */
enum class FileKind { TEXT, PDF, WORD, EXCEL, SLIDES, OPEN_DOCUMENT, ZIP, UNSUPPORTED }

/** Ergebnis für eine Datei oder einen ZIP-Eintrag: Text, oder ein Grund, warum nicht. */
class Part(val name: String, val text: String?, val truncated: Boolean = false, val problem: String? = null)

/** Ein Eintrag in einem ZIP-Archiv, wie er in der Auswahl angezeigt wird. */
class ZipItem(val path: String, val size: Long, val kind: FileKind, val problem: String?)

/** Ein Lesefehler mit einer Meldung, die direkt angezeigt werden kann. */
open class ReadProblem(message: String) : IOException(message)

/** Die Datei ist zu groß oder sprengt beim Entpacken die Grenzen (Schutz vor ZIP-Bomben). */
class TooLargeException(message: String) : ReadProblem(message)

/**
 * Holt Text aus Dateien: Textdateien, PDF, Word, Excel, PowerPoint, OpenDocument und ZIP.
 *
 * Kein Android-Code, damit es sich auf dem Rechner testen lässt. Nur das PDF-Lesen hängt an einer
 * Android-Bibliothek und wird deshalb als Funktion [pdfReader] hereingereicht
 * (Datei, maximale Zeichen) -> Text.
 */
class DocumentReader(
    private val tempDir: File,
    private val pdfReader: (File, Int) -> String
) {

    // ------------------------------------------------------------------ Dateiart

    fun kindOf(name: String, mime: String?): FileKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        kindForExtension(ext)?.let { return it }
        val m = mime?.lowercase().orEmpty()
        return when {
            m == "application/pdf" -> FileKind.PDF
            m == "application/zip" || m == "application/x-zip-compressed" -> FileKind.ZIP
            m == MIME_DOCX -> FileKind.WORD
            m == MIME_XLSX -> FileKind.EXCEL
            m == MIME_PPTX -> FileKind.SLIDES
            m.startsWith("application/vnd.oasis.opendocument.") -> FileKind.OPEN_DOCUMENT
            m.startsWith("image/") && m != "image/svg+xml" -> FileKind.UNSUPPORTED
            m.startsWith("audio/") || m.startsWith("video/") -> FileKind.UNSUPPORTED
            m == "application/msword" || m == "application/vnd.ms-excel" ||
                m == "application/vnd.ms-powerpoint" -> FileKind.UNSUPPORTED
            else -> FileKind.TEXT
        }
    }

    private fun kindForExtension(ext: String): FileKind? = when (ext) {
        "pdf" -> FileKind.PDF
        "docx", "docm", "dotx", "dotm" -> FileKind.WORD
        "xlsx", "xlsm", "xltx", "xltm" -> FileKind.EXCEL
        "pptx", "pptm", "ppsx", "potx" -> FileKind.SLIDES
        "odt", "ott", "ods", "ots", "odp", "otp" -> FileKind.OPEN_DOCUMENT
        "zip" -> FileKind.ZIP
        in UNSUPPORTED_REASONS.keys -> FileKind.UNSUPPORTED
        else -> null
    }

    /** Erklärung für nicht lesbare Dateiarten, oder null. */
    fun unsupportedReason(name: String, mime: String?): String? {
        if (kindOf(name, mime) != FileKind.UNSUPPORTED) return null
        val ext = name.substringAfterLast('.', "").lowercase()
        UNSUPPORTED_REASONS[ext]?.let { return it }
        val m = mime?.lowercase().orEmpty()
        return when {
            m.startsWith("image/") -> "Bilder enthalten keinen Text."
            m.startsWith("audio/") || m.startsWith("video/") -> "Audio und Video enthalten keinen Text."
            else -> "Altes Office-Format (.doc/.xls/.ppt) wird nicht unterstützt. Bitte als .docx/.xlsx/.pptx oder PDF speichern."
        }
    }

    // ------------------------------------------------------------------ Einzelne Datei

    /** Liest eine (bereits lokal kopierte) Datei. ZIP-Archive bitte über [listZip]/[readZip]. */
    fun readFile(file: File, name: String, mime: String?, maxChars: Int): Part {
        return try {
            when (val kind = kindOf(name, mime)) {
                FileKind.UNSUPPORTED -> Part(name, null, problem = unsupportedReason(name, mime))
                FileKind.ZIP -> {
                    val all = listZip(file).filter { it.problem == null }.map { it.path }.toSet()
                    combineZip(name, readZip(file, all, maxChars))
                }
                else -> withoutZip(file, name, kind, maxChars)
            }
        } catch (e: ReadProblem) {
            Part(name, null, problem = e.message)
        } catch (e: Exception) {
            Part(name, null, problem = "Konnte nicht gelesen werden (${describe(e)}).")
        }
    }

    private fun withoutZip(file: File, name: String, kind: FileKind, maxChars: Int): Part {
        val raw: String = when (kind) {
            FileKind.TEXT -> {
                val (text, cut) = readPlainText(file, maxChars)
                    ?: return Part(name, null, problem = "Keine Textdatei (Binärdaten).")
                return finish(name, text, maxChars, cut, emptyProblem = "Die Datei ist leer.")
            }
            FileKind.PDF -> pdfReader(file, maxChars + 1)
            FileKind.WORD -> ZipFile(file).use { wordText(it, maxChars + 1) }
            FileKind.EXCEL -> ZipFile(file).use { excelText(it, maxChars + 1) }
            FileKind.SLIDES -> ZipFile(file).use { slidesText(it, maxChars + 1) }
            FileKind.OPEN_DOCUMENT -> ZipFile(file).use { openDocumentText(it, maxChars + 1) }
            FileKind.ZIP, FileKind.UNSUPPORTED -> error("nicht hier")
        }
        val empty = if (kind == FileKind.PDF) {
            "Das PDF enthält keinen Text, vermutlich ist es eingescannt. Texterkennung (OCR) gibt es nicht."
        } else {
            "Das Dokument enthält keinen Text."
        }
        return finish(name, raw, maxChars, false, empty)
    }

    private fun finish(name: String, raw: String, maxChars: Int, alreadyCut: Boolean, emptyProblem: String): Part {
        val text = raw.trim('\n', '\r', ' ', '\t', '\u000C')
        if (text.isEmpty()) return Part(name, null, problem = emptyProblem)
        // Leser liefern bis zu maxChars + 1 Zeichen: mehr als maxChars heißt, es ging weiter.
        val cut = text.length > maxChars || raw.length > maxChars
        return Part(name, if (cut) safeCut(text, maxChars) else text, truncated = cut || alreadyCut)
    }

    // ------------------------------------------------------------------ Textdateien

    /**
     * Liest höchstens [maxChars] Zeichen. Erkennt UTF-8, UTF-16 (mit BOM) und fällt bei
     * ungültigem UTF-8 auf Windows-1252 zurück (ältere deutsche Textdateien).
     * Gibt null zurück, wenn es nach Binärdaten aussieht. Zweiter Wert: wurde gekürzt.
     */
    fun readPlainText(file: File, maxChars: Int): Pair<String, Boolean>? {
        val limit = maxChars.toLong() * 3 + 4
        val bytes = file.inputStream().use { readUpTo(it, limit) }
        val cutBytes = file.length() > bytes.size
        val text = decodeText(bytes, endOfInput = !cutBytes) ?: return null
        return if (text.length > maxChars) safeCut(text, maxChars) to true else text to cutBytes
    }

    fun decodeText(bytes: ByteArray, endOfInput: Boolean = true): String? {
        if (bytes.isEmpty()) return ""
        // BOM
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return decode(bytes, 2, Charsets.UTF_16LE, endOfInput)?.takeIf(::looksLikeText)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return decode(bytes, 2, Charsets.UTF_16BE, endOfInput)?.takeIf(::looksLikeText)
        }
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) 3 else 0

        val head = minOf(bytes.size, 8000)
        for (i in start until head) if (bytes[i] == 0.toByte()) return null

        val text = decode(bytes, start, Charsets.UTF_8, endOfInput)
            ?: decode(bytes, start, Charset.forName("windows-1252"), true, strict = false)
            ?: return null
        return text.takeIf(::looksLikeText)
    }

    /** Streng dekodieren: bei ungültigen Bytes null. Ein angeschnittenes Zeichen am Ende ist erlaubt. */
    private fun decode(
        bytes: ByteArray,
        offset: Int,
        charset: Charset,
        endOfInput: Boolean,
        strict: Boolean = true
    ): String? {
        val action = if (strict) CodingErrorAction.REPORT else CodingErrorAction.REPLACE
        val decoder = charset.newDecoder()
            .onMalformedInput(action)
            .onUnmappableCharacter(action)
        val input = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
        val output = CharBuffer.allocate((bytes.size - offset) + 16)
        val result = decoder.decode(input, output, endOfInput)
        if (result.isError) return null
        if (endOfInput) {
            if (decoder.flush(output).isError) return null
        }
        output.flip()
        return output.toString()
    }

    /** Zu viele Steuerzeichen bedeuten Binärdaten. */
    private fun looksLikeText(s: String): Boolean {
        if (s.isEmpty()) return true
        val sample = s.take(8000)
        var control = 0
        for (c in sample) {
            if (c == '\u0000') return false
            if (c < ' ' && c != '\n' && c != '\r' && c != '\t' && c != '\u000C') control++
        }
        return control * 100 <= sample.length * 2
    }

    // ------------------------------------------------------------------ Office (OOXML)

    private fun wordText(zip: ZipFile, maxChars: Int): String {
        val entry = zip.getEntry("word/document.xml") ?: throw IOException("word/document.xml fehlt")
        val out = Limited(maxChars)
        parse(zip, entry, object : TextHandler(out) {
            var cellDepth = 0
            override fun start(local: String, attrs: Attributes) {
                when (local) {
                    "t" -> capture = true
                    "tab" -> out.append('\t')
                    "br", "cr" -> out.append('\n')
                    "tc" -> cellDepth++
                }
            }
            override fun end(local: String) {
                when (local) {
                    "t" -> capture = false
                    "p" -> out.append(if (cellDepth > 0) " " else "\n")
                    "tc" -> { cellDepth--; out.append('\t') }
                    "tr" -> { out.trimEnd(); out.append('\n') }
                }
            }
        })
        return out.toString()
    }

    private fun slidesText(zip: ZipFile, maxChars: Int): String {
        val slides = zip.entries().asSequence()
            .map { it.name }
            .mapNotNull { n -> SLIDE.matchEntire(n)?.let { it.groupValues[1].toInt() to n } }
            .sortedBy { it.first }
            .toList()
        val out = Limited(maxChars)
        for ((number, path) in slides) {
            if (out.full) break
            out.append("Folie $number\n")
            parse(zip, zip.getEntry(path), object : TextHandler(out) {
                override fun start(local: String, attrs: Attributes) {
                    if (local == "t") capture = true
                    if (local == "br") out.append('\n')
                }
                override fun end(local: String) {
                    if (local == "t") capture = false
                    if (local == "p") out.append('\n')
                }
            })
            out.append('\n')
        }
        return out.toString()
    }

    private fun excelText(zip: ZipFile, maxChars: Int): String {
        // Gemeinsame Texte (die meisten Zellen mit Text verweisen hierauf)
        val shared = ArrayList<String>()
        zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
            val current = StringBuilder()
            parse(zip, entry, object : TextHandler(Limited(Int.MAX_VALUE)) {
                var inT = false
                override fun start(local: String, attrs: Attributes) {
                    if (local == "si") current.setLength(0)
                    if (local == "t") inT = true
                }
                override fun end(local: String) {
                    if (local == "t") inT = false
                    if (local == "si") shared.add(current.toString())
                }
                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inT) current.appendRange(ch, start, start + length)
                }
            })
        }
        val sheets = excelSheets(zip)

        val out = Limited(maxChars)
        for ((title, path) in sheets) {
            if (out.full) break
            val entry = zip.getEntry(path) ?: continue
            out.append("Tabelle: $title\n")
            parse(zip, entry, object : TextHandler(out) {
                var type: String? = null
                val value = StringBuilder()
                var inValue = false
                var rowHasCell = false
                override fun start(local: String, attrs: Attributes) {
                    when (local) {
                        "row" -> rowHasCell = false
                        "c" -> { type = attrs.getValue("t"); value.setLength(0) }
                        "v", "t" -> inValue = true
                    }
                }
                override fun end(local: String) {
                    when (local) {
                        "v", "t" -> inValue = false
                        "c" -> {
                            val raw = value.toString()
                            val cell = if (type == "s") shared.getOrNull(raw.trim().toIntOrNull() ?: -1).orEmpty() else raw
                            if (rowHasCell) out.append('\t')
                            out.append(cell.replace('\n', ' '))
                            rowHasCell = true
                        }
                        "row" -> if (rowHasCell) out.append('\n')
                    }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inValue) value.appendRange(ch, start, start + length)
                }
            })
            out.append('\n')
        }
        return out.toString()
    }

    /** Blätter in der Reihenfolge der Arbeitsmappe: (Name, Pfad im Archiv). */
    private fun excelSheets(zip: ZipFile): List<Pair<String, String>> {
        val declared = ArrayList<Pair<String, String?>>() // Name, Relations-Id
        zip.getEntry("xl/workbook.xml")?.let { entry ->
            parse(zip, entry, object : TextHandler(Limited(Int.MAX_VALUE)) {
                override fun start(local: String, attrs: Attributes) {
                    if (local == "sheet") {
                        declared.add(
                            (attrs.getValue("name") ?: "Blatt ${declared.size + 1}") to
                                attrs.getValue(NS_RELATIONSHIPS, "id")
                        )
                    }
                }
            })
        }
        val targets = HashMap<String, String>()
        zip.getEntry("xl/_rels/workbook.xml.rels")?.let { entry ->
            parse(zip, entry, object : TextHandler(Limited(Int.MAX_VALUE)) {
                override fun start(local: String, attrs: Attributes) {
                    if (local == "Relationship") {
                        val id = attrs.getValue("Id") ?: return
                        val target = attrs.getValue("Target") ?: return
                        targets[id] = if (target.startsWith("/")) target.drop(1) else "xl/$target"
                    }
                }
            })
        }
        val resolved = declared.mapNotNull { (name, id) -> targets[id]?.let { name to it } }
        if (resolved.isNotEmpty()) return resolved
        // Notlösung ohne Relationen: nach Dateinamen sortiert.
        return zip.entries().asSequence()
            .map { it.name }
            .mapNotNull { n -> SHEET.matchEntire(n)?.let { it.groupValues[1].toInt() to n } }
            .sortedBy { it.first }
            .map { (number, path) -> "Blatt $number" to path }
            .toList()
    }

    // ------------------------------------------------------------------ OpenDocument

    private fun openDocumentText(zip: ZipFile, maxChars: Int): String {
        val entry = zip.getEntry("content.xml") ?: throw IOException("content.xml fehlt")
        val out = Limited(maxChars)
        parse(zip, entry, object : TextHandler(out) {
            var cellDepth = 0
            var textDepth = 0
            override fun start(local: String, attrs: Attributes) {
                when (local) {
                    "p", "h" -> { textDepth++; capture = true }
                    "s" -> {
                        val n = attrs.getValue("urn:oasis:names:tc:opendocument:xmlns:text:1.0", "c")
                            ?.toIntOrNull() ?: 1
                        out.append(" ".repeat(n.coerceIn(1, 50)))
                    }
                    "tab" -> out.append('\t')
                    "line-break" -> out.append('\n')
                    "table-cell", "covered-table-cell" -> cellDepth++
                    "page" -> out.append("\n")
                }
            }
            override fun end(local: String) {
                when (local) {
                    "p", "h" -> {
                        textDepth--
                        capture = textDepth > 0
                        out.append(if (cellDepth > 0) " " else "\n")
                    }
                    "table-cell", "covered-table-cell" -> { cellDepth--; out.append('\t') }
                    "table-row" -> { out.trimEnd(); out.append('\n') }
                }
            }
        })
        return out.toString()
    }

    // ------------------------------------------------------------------ ZIP

    /** Listet alle Dateien im Archiv. Ordner und Systemmüll (__MACOSX, .DS_Store) fallen weg. */
    fun listZip(file: File): List<ZipItem> = openZip(file).use { zip ->
        val items = ArrayList<ZipItem>()
        for (entry in zip.entries()) {
            if (entry.isDirectory || isJunk(entry.name)) continue
            if (items.size >= MAX_ZIP_ENTRIES) break
            val name = entry.name.substringAfterLast('/')
            val kind = kindOf(name, null)
            val problem = when {
                kind == FileKind.ZIP -> "ZIP im ZIP wird nicht geöffnet."
                kind == FileKind.UNSUPPORTED -> unsupportedReason(name, null)
                entry.size > MAX_ENTRY_BYTES -> "Zu groß (über ${MAX_ENTRY_BYTES / 1_000_000} MB entpackt)."
                else -> null
            }
            items.add(ZipItem(entry.name, entry.size, kind, problem))
        }
        items
    }

    /** Liest die gewählten Einträge, in Archiv-Reihenfolge, bis [maxChars] Zeichen erreicht sind. */
    fun readZip(file: File, selected: Set<String>, maxChars: Int): List<Part> = openZip(file).use { zip ->
        val parts = ArrayList<Part>()
        var remaining = maxChars
        var unpacked = 0L
        for (entry in zip.entries()) {
            if (entry.name !in selected || entry.isDirectory) continue
            if (remaining <= 0) {
                parts.add(Part(entry.name, null, problem = "Nicht mehr eingefügt, die Höchstlänge ist erreicht."))
                continue
            }
            if (unpacked > MAX_TOTAL_UNPACKED) {
                parts.add(Part(entry.name, null, problem = "Übersprungen, das Archiv ist entpackt zu groß."))
                continue
            }
            val part = readZipEntry(zip, entry, remaining) { unpacked += it }
            remaining -= part.text?.length ?: 0
            parts.add(part)
        }
        parts
    }

    private fun readZipEntry(zip: ZipFile, entry: ZipEntry, maxChars: Int, count: (Long) -> Unit): Part {
        val name = entry.name.substringAfterLast('/')
        val kind = kindOf(name, null)
        if (kind == FileKind.ZIP) return Part(entry.name, null, problem = "ZIP im ZIP wird nicht geöffnet.")
        if (kind == FileKind.UNSUPPORTED) return Part(entry.name, null, problem = unsupportedReason(name, null))
        return try {
            if (kind == FileKind.TEXT) {
                // Textdateien direkt lesen, nur so viel wie gebraucht wird.
                val limit = maxChars.toLong() * 3 + 4
                val bytes = zip.getInputStream(entry).use { readUpTo(it, limit + 1) }
                count(bytes.size.toLong())
                val more = bytes.size > limit
                val data = if (more) bytes.copyOf(limit.toInt()) else bytes
                val text = decodeText(data, endOfInput = !more)
                    ?: return Part(entry.name, null, problem = "Keine Textdatei (Binärdaten).")
                finish(entry.name, text, maxChars, more, "Die Datei ist leer.")
            } else {
                val tmp = File.createTempFile("entry", ".bin", tempDir)
                try {
                    val written = zip.getInputStream(entry).use { input ->
                        tmp.outputStream().use { copyLimited(input, it, MAX_ENTRY_BYTES) }
                    }
                    count(written)
                    val p = readFile(tmp, name, null, maxChars)
                    Part(entry.name, p.text, p.truncated, p.problem)
                } finally {
                    tmp.delete()
                }
            }
        } catch (e: ReadProblem) {
            Part(entry.name, null, problem = e.message)
        } catch (e: Exception) {
            Part(entry.name, null, problem = "Konnte nicht gelesen werden (${describe(e)}).")
        }
    }

    /** Fasst die Teile eines ZIPs zu einem Teil zusammen (für ZIPs bei Mehrfachauswahl). */
    private fun combineZip(name: String, parts: List<Part>): Part {
        val ok = parts.filter { it.text != null }
        if (ok.isEmpty()) return Part(name, null, problem = "Im Archiv ist keine lesbare Datei.")
        val text = ok.joinToString("\n\n") { "[Datei: ${it.name}]\n${it.text}" }
        return Part(name, text, truncated = parts.any { it.truncated || it.text == null })
    }

    /** Öffnet das ZIP. Dateinamen von Windows-ZIPs sind oft nicht UTF-8, dann CP437. */
    private fun openZip(file: File): ZipFile {
        val zip = ZipFile(file, Charsets.UTF_8)
        return try {
            zip.entries().asSequence().forEach { it.name }
            zip
        } catch (e: IllegalArgumentException) {
            zip.close()
            ZipFile(file, Charset.forName("IBM437"))
        }
    }

    private fun isJunk(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return path.startsWith("__MACOSX/") || path.contains("/__MACOSX/") ||
            name == ".DS_Store" || name == "Thumbs.db" || name == "desktop.ini" ||
            path.startsWith(".git/") || path.contains("/.git/")
    }

    // ------------------------------------------------------------------ XML-Hilfen

    /** Text sammeln mit Obergrenze. Ist sie erreicht, wird das Parsen abgebrochen. */
    private class Limited(private val max: Int) {
        private val sb = StringBuilder()
        val full get() = sb.length >= max
        fun append(c: Char) { if (!full) sb.append(c) }
        fun append(s: String) {
            if (full) return
            val room = max - sb.length
            sb.append(if (s.length <= room) s else s.substring(0, room))
        }
        fun append(ch: CharArray, start: Int, length: Int) {
            if (full) return
            sb.appendRange(ch, start, start + minOf(length, max - sb.length))
        }
        fun trimEnd() {
            while (sb.isNotEmpty() && (sb.last() == '\t' || sb.last() == ' ')) sb.setLength(sb.length - 1)
        }
        override fun toString() = sb.toString()
    }

    private class StopParsing : RuntimeException()

    private abstract class TextHandler(private val out: Limited) : DefaultHandler() {
        var capture = false
        open fun start(local: String, attrs: Attributes) {}
        open fun end(local: String) {}

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            start(local(localName, qName), attributes)
        }
        override fun endElement(uri: String?, localName: String?, qName: String?) {
            end(local(localName, qName))
            if (out.full) throw StopParsing()
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capture) out.append(ch, start, length)
        }
        // Keine externen Entitäten auflösen (Schutz vor XXE).
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            InputSource(ByteArrayInputStream(ByteArray(0)))

        private fun local(localName: String?, qName: String?): String =
            if (!localName.isNullOrEmpty()) localName else qName.orEmpty().substringAfter(':')
    }

    private fun parse(zip: ZipFile, entry: ZipEntry, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        for (feature in listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities"
        )) {
            try { factory.setFeature(feature, false) } catch (_: Exception) { }
        }
        val parser = factory.newSAXParser()
        zip.getInputStream(entry).use { raw ->
            val limited = CountingStream(raw, MAX_ENTRY_BYTES)
            try {
                parser.parse(InputSource(limited), handler)
            } catch (_: StopParsing) {
                // Obergrenze erreicht, Text reicht.
            }
        }
    }

    /** Bricht ab, wenn mehr als [max] Bytes kommen (Schutz vor ZIP-Bomben). */
    private class CountingStream(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var count = 0L
        private fun add(n: Int): Int {
            if (n > 0) {
                count += n
                if (count > max) throw TooLargeException("Das Dokument ist entpackt zu groß.")
            }
            return n
        }
        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = add(super.read(b, off, len))
        override fun close() { /* Der Aufrufer schließt. */ }
    }

    // ------------------------------------------------------------------ Kleinkram

    companion object {
        const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val NS_RELATIONSHIPS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

        const val MAX_ZIP_ENTRIES = 2000
        const val MAX_ENTRY_BYTES = 40_000_000L
        const val MAX_TOTAL_UNPACKED = 200_000_000L

        private val SLIDE = Regex("ppt/slides/slide(\\d+)\\.xml")
        private val SHEET = Regex("xl/worksheets/sheet(\\d+)\\.xml")

        private val UNSUPPORTED_REASONS: Map<String, String> = buildMap {
            val oldOffice = "Altes Office-Format wird nicht unterstützt. Bitte als .docx/.xlsx/.pptx oder PDF speichern."
            listOf("doc", "xls", "ppt", "pps").forEach { put(it, oldOffice) }
            put("rtf", "RTF wird nicht unterstützt. Bitte als .docx, .odt oder .txt speichern.")
            val archive = "Nur ZIP-Archive werden unterstützt."
            listOf("rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst").forEach { put(it, archive) }
            val image = "Bilder enthalten keinen Text."
            listOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "ico")
                .forEach { put(it, image) }
            val media = "Audio und Video enthalten keinen Text."
            listOf("mp3", "m4a", "wav", "ogg", "opus", "flac", "aac", "mp4", "mkv", "mov", "avi", "webm", "3gp")
                .forEach { put(it, media) }
            val binary = "Programm- oder Binärdatei, enthält keinen lesbaren Text."
            listOf("apk", "aab", "exe", "dll", "so", "bin", "class", "dex", "jar", "o", "pyc", "iso", "img", "db", "sqlite")
                .forEach { put(it, binary) }
            listOf("ttf", "otf", "woff", "woff2").forEach { put(it, "Schriftdateien enthalten keinen Text.") }
            put("epub", "E-Books (EPUB) werden nicht unterstützt.")
        }

        fun readUpTo(input: InputStream, limit: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (total < limit) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            return out.toByteArray()
        }

        /** Kopiert höchstens [max] Bytes. Mehr: [TooLargeException]. Gibt die Bytezahl zurück. */
        fun copyLimited(input: InputStream, output: java.io.OutputStream, max: Long): Long {
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw TooLargeException("Zu groß (über ${max / 1_000_000} MB).")
                output.write(buf, 0, n)
            }
            return total
        }

        /** Kürzt, ohne ein Emoji (Surrogatpaar) zu zerschneiden. */
        fun safeCut(s: String, max: Int): String {
            if (s.length <= max) return s
            var end = max
            if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
            return s.substring(0, end)
        }

        private fun describe(e: Exception): String =
            e.message?.takeIf { it.isNotBlank() }?.take(120) ?: e.javaClass.simpleName
    }
}
