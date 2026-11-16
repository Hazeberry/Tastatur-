package de.hazeberry.keyboard

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/** Welche Art Datei, und damit welcher Leseweg. */
enum class FileKind {
    TEXT, PDF, WORD, EXCEL, SLIDES, OPEN_DOCUMENT, LEGACY_OFFICE, RTF, EPUB,
    ARCHIVE, COMPRESSED, IMAGE, UNSUPPORTED
}

/** Ergebnis für eine Datei oder einen Archiv-Eintrag: Text, oder ein Grund, warum nicht. */
data class Part(
    val name: String,
    val text: String?,
    val truncated: Boolean = false,
    val problem: String? = null,
    /** Für die Datei fehlt ein Passwort (oder es war falsch). */
    val needsPassword: Boolean = false,
    val wrongPassword: Boolean = false,
    /** Text stammt (teilweise) aus der Texterkennung. */
    val ocr: Boolean = false,
    /** Bei Archiven: die einzelnen Dateien, für die Zusammenfassung. */
    val children: List<Part> = emptyList()
)

/** Ein Eintrag in einem Archiv, wie er in der Auswahl angezeigt wird. */
class ArchiveItem(val path: String, val size: Long, val kind: FileKind, val problem: String?, val encrypted: Boolean) {
    val readable get() = problem == null
    /** Bilder (Texterkennung dauert) sind nicht vorausgewählt. */
    val preselected get() = readable && kind != FileKind.IMAGE
}

/** Ein Lesefehler mit einer Meldung, die direkt angezeigt werden kann. */
open class ReadProblem(message: String) : IOException(message)

/** Die Datei ist zu groß oder sprengt beim Entpacken die Grenzen (Schutz vor ZIP-Bomben). */
class TooLargeException(message: String) : ReadProblem(message)

/** Der Nutzer hat abgebrochen. */
class CancelledException : IOException("Abgebrochen")

/** Fortschritt und Abbruch für lange Vorgänge (Texterkennung, große Archive). */
interface Progress {
    fun step(message: String) {}
    val cancelled: Boolean get() = false

    fun check() { if (cancelled) throw CancelledException() }

    companion object {
        val NONE = object : Progress {}
    }
}

/** Text je PDF-Seite. Leere Seiten sind vermutlich eingescannt. */
class PdfPages(val texts: List<String>, val pageCount: Int)

interface PdfEngine {
    /** Liest Seite für Seite, bis etwa [maxChars] Zeichen da sind. Wirft [PasswordRequired]. */
    fun pages(file: File, password: String?, maxChars: Int): PdfPages
}

interface OcrEngine {
    fun image(file: File, progress: Progress): String

    /** Erkennt Text auf den PDF-Seiten [pages] (0-basiert). [onPage] false = aufhören. */
    fun pdfPages(file: File, password: String?, pages: List<Int>, progress: Progress, onPage: (Int, String) -> Boolean)
}

/**
 * Holt Text aus Dateien: Text, PDF, Word/Excel/PowerPoint (neu und alt, auch mit Passwort),
 * OpenDocument, RTF, EPUB, Outlook-Mails, Archive (ZIP, 7z, tar, gz ...) und Bilder.
 *
 * Kein Android-Code, damit es sich auf dem Rechner testen lässt. PDF und Texterkennung hängen an
 * Android-Bibliotheken und werden deshalb als [pdf] und [ocr] hereingereicht.
 */
class DocumentReader(
    private val tempDir: File,
    private val pdf: PdfEngine,
    private val ocr: OcrEngine? = null,
    private val progress: Progress = Progress.NONE
) {

    // ================================================================== Dateiart

    /** Dateiart nach Name und MIME-Typ (ohne die Datei anzusehen). */
    fun kindOf(name: String, mime: String?): FileKind {
        kindForName(name)?.let { return it }
        val m = mime?.lowercase().orEmpty().substringBefore(';').trim()
        return when {
            m == "application/pdf" -> FileKind.PDF
            m == "application/zip" || m == "application/x-zip-compressed" || m == "application/x-7z-compressed" ||
                m == "application/x-tar" || m == "application/x-gtar" -> FileKind.ARCHIVE
            m == "application/gzip" || m == "application/x-gzip" || m == "application/x-bzip2" ||
                m == "application/x-xz" -> FileKind.COMPRESSED
            m == MIME_DOCX -> FileKind.WORD
            m == MIME_XLSX -> FileKind.EXCEL
            m == MIME_PPTX -> FileKind.SLIDES
            m.startsWith("application/vnd.oasis.opendocument.") -> FileKind.OPEN_DOCUMENT
            m == "application/msword" || m == "application/vnd.ms-excel" ||
                m == "application/vnd.ms-powerpoint" || m == "application/vnd.ms-outlook" -> FileKind.LEGACY_OFFICE
            m == "application/rtf" || m == "text/rtf" -> FileKind.RTF
            m == "application/epub+zip" -> FileKind.EPUB
            m.startsWith("image/") && m != "image/svg+xml" -> FileKind.IMAGE
            m.startsWith("audio/") || m.startsWith("video/") -> FileKind.UNSUPPORTED
            m == "application/vnd.rar" || m == "application/x-rar-compressed" -> FileKind.UNSUPPORTED
            else -> FileKind.TEXT
        }
    }

    private fun kindForName(name: String): FileKind? {
        val lower = name.lowercase()
        if (TAR_SUFFIXES.any { lower.endsWith(it) }) return FileKind.ARCHIVE
        return when (val ext = lower.substringAfterLast('.', "")) {
            "pdf" -> FileKind.PDF
            "docx", "docm", "dotx", "dotm" -> FileKind.WORD
            "xlsx", "xlsm", "xltx", "xltm" -> FileKind.EXCEL
            "pptx", "pptm", "ppsx", "potx" -> FileKind.SLIDES
            "odt", "ott", "ods", "ots", "odp", "otp", "odg" -> FileKind.OPEN_DOCUMENT
            "doc", "dot", "xls", "xlt", "ppt", "pps", "pot", "msg" -> FileKind.LEGACY_OFFICE
            "rtf" -> FileKind.RTF
            "epub" -> FileKind.EPUB
            "zip", "7z", "tar" -> FileKind.ARCHIVE
            "gz", "bz2", "xz" -> FileKind.COMPRESSED
            "png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff" -> FileKind.IMAGE
            in UNSUPPORTED_REASONS.keys -> FileKind.UNSUPPORTED
            else -> null
        }
    }

    /** Erklärung für nicht lesbare Dateiarten, oder null. */
    fun unsupportedReason(name: String, mime: String?): String? {
        if (kindOf(name, mime) != FileKind.UNSUPPORTED) return null
        val ext = name.substringAfterLast('.', "").lowercase()
        UNSUPPORTED_REASONS[ext]?.let { return it }
        val m = mime?.lowercase().orEmpty()
        return when {
            m.startsWith("audio/") || m.startsWith("video/") -> REASON_MEDIA
            m.contains("rar") -> REASON_RAR
            else -> "Dieses Dateiformat wird nicht unterstützt."
        }
    }

    /**
     * Dateiart am Inhalt erkennen. Hilft bei Dateien ohne oder mit falscher Endung
     * (z. B. ".doc", die in Wahrheit RTF ist, oder Downloads ohne Endung).
     */
    fun detect(file: File, name: String, mime: String?): Pair<FileKind, String?> {
        val byName = kindOf(name, mime)
        val head = file.inputStream().use { readUpTo(it, 1024) }
        val sniffed = sniff(head, file)
        val kind = when {
            sniffed == null -> byName
            sniffed.first == FileKind.COMPRESSED && byName == FileKind.ARCHIVE -> FileKind.ARCHIVE
            // "Irgendein ZIP", aber der Name sagt genauer, welches ZIP-basierte Format
            sniffed.first == FileKind.ARCHIVE && byName in ZIP_BASED -> byName
            else -> sniffed.first
        }
        val reason = sniffed?.second ?: if (kind == FileKind.UNSUPPORTED) unsupportedReason(name, mime) else null
        return kind to reason
    }

    private fun sniff(head: ByteArray, file: File): Pair<FileKind, String?>? {
        fun at(i: Int, vararg b: Int) = head.size >= i + b.size && b.indices.all { head[i + it] == b[it].toByte() }
        fun ascii(i: Int, s: String) = at(i, *s.map { it.code }.toIntArray())
        return when {
            ascii(0, "%PDF-") -> FileKind.PDF to null
            at(0, 0x50, 0x4B, 0x03, 0x04) -> zipKind(file) to null
            at(0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) -> FileKind.LEGACY_OFFICE to null
            ascii(0, "{\\rtf") -> FileKind.RTF to null
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) || ascii(257, "ustar") -> FileKind.ARCHIVE to null
            at(0, 0x1F, 0x8B, 0x08) || (ascii(0, "BZh") && at(4, 0x31, 0x41, 0x59, 0x26, 0x53, 0x59)) ||
                at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> FileKind.COMPRESSED to null
            at(0, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> FileKind.UNSUPPORTED to REASON_RAR
            at(0, 0x89, 0x50, 0x4E, 0x47) || at(0, 0xFF, 0xD8, 0xFF) || ascii(0, "GIF87a") || ascii(0, "GIF89a") ||
                (ascii(0, "RIFF") && ascii(8, "WEBP")) || ascii(0, "II*\u0000") || ascii(0, "MM\u0000*") ||
                ascii(4, "ftypheic") || ascii(4, "ftypheix") || ascii(4, "ftypmif1") || ascii(4, "ftypavif") ->
                FileKind.IMAGE to null
            ascii(4, "ftyp") || (ascii(0, "ID3") && head.size > 4 && head[3] in 2..4 && head[4] == 0.toByte()) ||
                at(0, 0x4F, 0x67, 0x67, 0x53, 0x00) || ascii(0, "fLaC") ||
                (ascii(0, "RIFF") && (ascii(8, "WAVE") || ascii(8, "AVI "))) || at(0, 0x1A, 0x45, 0xDF, 0xA3) ->
                FileKind.UNSUPPORTED to REASON_MEDIA
            else -> null
        }
    }

    /** ZIP-Container genauer bestimmen: Office, OpenDocument, EPUB oder normales Archiv. */
    private fun zipKind(file: File): FileKind = try {
        ZipFile(file).use { zip ->
            val mimetype = zip.getEntry("mimetype")?.let { e ->
                zip.getInputStream(e).use { String(readUpTo(it, 200), Charsets.US_ASCII).trim() }
            }
            val names = zip.entries().asSequence().take(5000).map { it.name }.toList()
            when {
                names.any { it.startsWith("word/") && it.endsWith(".xml") } && zip.getEntry("word/document.xml") != null -> FileKind.WORD
                names.any { it.startsWith("xl/worksheets/") } -> FileKind.EXCEL
                names.any { it.startsWith("ppt/slides/") } -> FileKind.SLIDES
                mimetype == "application/epub+zip" -> FileKind.EPUB
                mimetype?.startsWith("application/vnd.oasis.opendocument") == true -> FileKind.OPEN_DOCUMENT
                else -> FileKind.ARCHIVE
            }
        }
    } catch (e: Exception) {
        FileKind.ARCHIVE // z. B. verschlüsselt oder Dateinamen nicht in UTF-8
    }

    // ================================================================== Einzelne Datei

    /**
     * Liest eine (bereits lokal kopierte) Datei. Archive werden komplett gelesen; für eine
     * Auswahl der Einträge [listArchive] und [readArchive] benutzen.
     */
    fun readFile(
        file: File,
        name: String,
        mime: String?,
        maxChars: Int,
        password: String? = null,
        depth: Int = 0
    ): Part {
        return try {
            progress.check()
            val (kind, reason) = detect(file, name, mime)
            when (kind) {
                FileKind.UNSUPPORTED -> Part(name, null, problem = reason ?: unsupportedReason(name, mime))
                FileKind.TEXT -> {
                    val (text, cut) = readPlainText(file, maxChars)
                        ?: return Part(name, null, problem = "Keine Textdatei (Binärdaten).")
                    finish(name, text, maxChars, cut, "Die Datei ist leer.")
                }
                FileKind.PDF -> pdfText(file, name, maxChars, password)
                FileKind.IMAGE -> imageText(file, name, maxChars)
                FileKind.LEGACY_OFFICE -> legacyOffice(file, name, maxChars, password, depth)
                FileKind.WORD, FileKind.EXCEL, FileKind.SLIDES -> {
                    if (isCfbFile(file)) return legacyOffice(file, name, maxChars, password, depth)
                    val text = ZipFile(file).use {
                        when (kind) {
                            FileKind.WORD -> wordText(it, maxChars + 1)
                            FileKind.EXCEL -> excelText(it, maxChars + 1)
                            else -> slidesText(it, maxChars + 1)
                        }
                    }
                    finish(name, text, maxChars, false, "Das Dokument enthält keinen Text.")
                }
                FileKind.OPEN_DOCUMENT -> finish(
                    name, ZipFile(file).use { openDocumentText(it, maxChars + 1) }, maxChars, false,
                    "Das Dokument enthält keinen Text."
                )
                FileKind.RTF -> {
                    if (file.length() > MAX_ENTRY_BYTES) throw TooLargeException("Die RTF-Datei ist zu groß.")
                    finish(name, RtfText.toText(file.readBytes(), maxChars + 1), maxChars, false, "Das Dokument enthält keinen Text.")
                }
                FileKind.EPUB -> finish(name, ZipFile(file).use { epubText(it, maxChars + 1) }, maxChars, false, "Das E-Book enthält keinen Text.")
                FileKind.ARCHIVE -> {
                    if (depth >= 2) return Part(name, null, problem = "Archiv in Archiv in Archiv wird nicht geöffnet.")
                    val all = listArchive(file, name, password).filter { it.preselected }.map { it.path }.toSet()
                    if (all.isEmpty()) return Part(name, null, problem = "Im Archiv ist keine lesbare Datei.")
                    combine(name, readArchive(file, name, all, maxChars, password, depth))
                }
                FileKind.COMPRESSED -> decompressed(file, name, maxChars, password, depth)
            }
        } catch (e: PasswordRequired) {
            Part(name, null, problem = e.message, needsPassword = true, wrongPassword = e.wrong)
        } catch (e: ReadProblem) {
            Part(name, null, problem = e.message)
        } catch (e: CancelledException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Part(name, null, problem = "Die Datei ist zu groß für den Arbeitsspeicher.")
        } catch (e: Exception) {
            Part(name, null, problem = "Konnte nicht gelesen werden (${describe(e)}).")
        }
    }

    private fun finish(name: String, raw: String, maxChars: Int, alreadyCut: Boolean, emptyProblem: String): Part {
        val text = raw.trim('\n', '\r', ' ', '\t', '\u000C')
        if (text.isEmpty()) return Part(name, null, problem = emptyProblem)
        // Leser liefern bis zu maxChars + 1 Zeichen: mehr als maxChars heißt, es ging weiter.
        val cut = text.length > maxChars || raw.length > maxChars
        return Part(name, if (cut) safeCut(text, maxChars) else text, truncated = cut || alreadyCut)
    }

    private fun isCfbFile(file: File): Boolean =
        file.inputStream().use { readUpTo(it, 8) }.let { it.size == 8 && Cfb.isCfb(it + ByteArray(504)) }

    // ================================================================== PDF und Bilder

    private fun pdfText(file: File, name: String, maxChars: Int, password: String?): Part {
        val pages = pdf.pages(file, password, maxChars + 1)
        val texts = pages.texts.toMutableList()
        val scanned = texts.indices.filter { i -> texts[i].count { !it.isWhitespace() } < MIN_PAGE_CHARS }
        var ocrUsed = false
        var ocrSkipped = 0
        if (scanned.isNotEmpty() && ocr != null) {
            val todo = scanned.take(MAX_OCR_PAGES)
            ocrSkipped = scanned.size - todo.size
            var total = texts.sumOf { it.length }
            ocr.pdfPages(file, password, todo, progress) { page, recognized ->
                if (recognized.isNotBlank()) {
                    texts[page] = recognized
                    ocrUsed = true
                    total += recognized.length
                }
                total <= maxChars
            }
        }
        val raw = texts.joinToString("\n\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n")
        val empty = if (ocr != null) {
            "Kein Text gefunden, auch die Texterkennung hat nichts erkannt."
        } else {
            "Das PDF enthält keinen Text, vermutlich ist es eingescannt."
        }
        return finish(name, raw, maxChars, ocrSkipped > 0, empty).copy(ocr = ocrUsed)
    }

    private fun imageText(file: File, name: String, maxChars: Int): Part {
        val engine = ocr ?: return Part(name, null, problem = "Bilder enthalten keinen Text, Texterkennung ist nicht verfügbar.")
        val text = engine.image(file, progress)
        return finish(name, text, maxChars, false, "Auf dem Bild wurde kein Text erkannt.").copy(ocr = true)
    }

    // ================================================================== Alte Office-Dateien und Outlook

    private fun legacyOffice(file: File, name: String, maxChars: Int, password: String?, depth: Int): Part {
        if (file.length() > MAX_LEGACY_BYTES) throw TooLargeException("Die Office-Datei ist zu groß.")
        val cfb = Cfb(file.readBytes())
        if (OfficeCrypto.isEncryptedPackage(cfb)) {
            // Passwortgeschützte .docx/.xlsx/.pptx: entschlüsseln, dann normal lesen.
            val plain = File.createTempFile("dec", ".zip", tempDir)
            try {
                OfficeCrypto.decrypt(cfb, password, plain)
                return readFile(plain, name, null, maxChars, null, depth)
            } finally {
                plain.delete()
            }
        }
        val text = when {
            cfb.has("WordDocument") -> LegacyOffice.wordText(cfb, maxChars + 1)
            cfb.has("Workbook") || cfb.has("Book") -> LegacyOffice.excelText(cfb, maxChars + 1)
            cfb.has("PowerPoint Document") -> LegacyOffice.slidesText(cfb, maxChars + 1)
            cfb.rootChildren.any { it.name.startsWith("__substg1.0_") } -> outlookText(cfb)
            else -> throw ReadProblem("Unbekanntes altes Office-Format.")
        }
        return finish(name, text, maxChars, false, "Das Dokument enthält keinen Text.")
    }

    /** Outlook-Mail (.msg): Kopfzeilen und Text. */
    private fun outlookText(cfb: Cfb): String {
        fun prop(id: String): String? {
            cfb.stream("__substg1.0_${id}001F")?.let { return String(it, Charsets.UTF_16LE).trimEnd('\u0000') }
            cfb.stream("__substg1.0_${id}001E")?.let { return String(it, WIN1252).trimEnd('\u0000') }
            return null
        }
        val out = StringBuilder()
        prop("0037")?.let { out.append("Betreff: ").append(it).append('\n') }
        // Exchange-interne Adressen ("/O=.../CN=...") sind für niemanden lesbar.
        val address = (prop("5D01") ?: prop("0C1F"))?.takeIf { it.contains('@') && !it.startsWith("/") }
        val from = listOfNotNull(prop("0C1A"), address?.let { "<$it>" }).distinct().joinToString(" ")
        if (from.isNotBlank()) out.append("Von: ").append(from).append('\n')
        prop("0E04")?.takeIf { it.isNotBlank() }?.let { out.append("An: ").append(it).append('\n') }
        prop("0E03")?.takeIf { it.isNotBlank() }?.let { out.append("Cc: ").append(it).append('\n') }
        val attachments = cfb.entries.withIndex()
            .filter { it.value.type == 1 && it.value.name.startsWith("__attach_version1.0_") }
            .mapNotNull { (i, _) ->
                cfb.children(i).firstOrNull { it.name.equals("__substg1.0_3707001F", true) }
                    ?.let { String(cfb.read(it), Charsets.UTF_16LE).trimEnd('\u0000') }
            }
        if (attachments.isNotEmpty()) out.append("Anhänge: ").append(attachments.joinToString(", ")).append('\n')
        out.append('\n')
        val body = prop("1000")
            ?: cfb.stream("__substg1.0_10130102")?.let { HtmlText.toText(String(it, Charsets.UTF_8)) }
        out.append(body.orEmpty())
        return out.toString()
    }

    // ================================================================== Textdateien

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
            ?: decode(bytes, start, WIN1252, true, strict = false)
            ?: return null
        return text.takeIf(::looksLikeText)
    }

    private fun decode(
        bytes: ByteArray,
        offset: Int,
        charset: Charset,
        endOfInput: Boolean,
        strict: Boolean = true
    ): String? {
        val action = if (strict) CodingErrorAction.REPORT else CodingErrorAction.REPLACE
        val decoder = charset.newDecoder().onMalformedInput(action).onUnmappableCharacter(action)
        val input = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
        val output = CharBuffer.allocate((bytes.size - offset) + 16)
        if (decoder.decode(input, output, endOfInput).isError) return null
        if (endOfInput && decoder.flush(output).isError) return null
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
            if (c < ' ' && c != '\n' && c != '\r' && c != '\t' && c != '\u000C' && c != '\u001B') control++
        }
        return control * 100 <= sample.length * 2
    }

    // ================================================================== Office (OOXML)

    private fun wordText(zip: ZipFile, maxChars: Int): String {
        val entry = zip.getEntry("word/document.xml") ?: throw ReadProblem("Beschädigte Word-Datei.")
        val out = Limited(maxChars)
        parse(zip, entry, object : TextHandler(out) {
            var cellDepth = 0
            var inRun = 0
            var inNumPr = false
            var level = 0
            override fun start(local: String, attrs: Attributes) {
                when (local) {
                    "r" -> inRun++
                    "t" -> capture = true
                    // <w:tab/> im Textlauf ist ein Tab; in <w:tabs> nur die Definition eines Tabstopps.
                    "tab" -> if (inRun > 0) out.append('\t')
                    "br", "cr" -> if (inRun > 0) out.append('\n')
                    "tc" -> cellDepth++
                    "numPr" -> { inNumPr = true; level = 0 }
                    "ilvl" -> if (inNumPr) level = attrs.value("val")?.toIntOrNull() ?: 0
                }
            }
            override fun end(local: String) {
                when (local) {
                    "r" -> inRun--
                    "t" -> capture = false
                    "numPr" -> {
                        // Aufzählung: Einrückung je Ebene, dann ein Spiegelstrich
                        inNumPr = false
                        out.append("  ".repeat(level.coerceIn(0, 8)) + "• ")
                    }
                    "p" -> out.append(if (cellDepth > 0) " " else "\n")
                    "tc" -> { cellDepth--; out.trimEnd(); out.append('\t') }
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
        // Gemeinsame Texte (die meisten Textzellen verweisen hierauf)
        val shared = ArrayList<String>()
        zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
            val current = StringBuilder()
            parse(zip, entry, object : TextHandler(Limited(Int.MAX_VALUE)) {
                var inT = false
                var inPhonetic = false
                override fun start(local: String, attrs: Attributes) {
                    when (local) {
                        "si" -> current.setLength(0)
                        "rPh" -> inPhonetic = true
                        "t" -> inT = !inPhonetic
                    }
                }
                override fun end(local: String) {
                    when (local) {
                        "t" -> inT = false
                        "rPh" -> inPhonetic = false
                        "si" -> shared.add(current.toString())
                    }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inT) current.appendRange(ch, start, start + length)
                }
            })
        }
        // Zahlenformate, um Datumswerte zu erkennen
        val formats = HashMap<Int, String>()
        val xfFormat = ArrayList<Int>()
        zip.getEntry("xl/styles.xml")?.let { entry ->
            parse(zip, entry, object : TextHandler(Limited(Int.MAX_VALUE)) {
                var inCellXfs = false
                override fun start(local: String, attrs: Attributes) {
                    when (local) {
                        "numFmt" -> attrs.value("numFmtId")?.toIntOrNull()?.let { formats[it] = attrs.value("formatCode").orEmpty() }
                        "cellXfs" -> inCellXfs = true
                        "xf" -> if (inCellXfs) xfFormat.add(attrs.value("numFmtId")?.toIntOrNull() ?: 0)
                    }
                }
                override fun end(local: String) {
                    if (local == "cellXfs") inCellXfs = false
                }
            })
        }
        var date1904 = false
        zip.getEntry("xl/workbook.xml")?.let { entry ->
            parse(zip, entry, object : TextHandler(Limited(Int.MAX_VALUE)) {
                override fun start(local: String, attrs: Attributes) {
                    if (local == "workbookPr") {
                        val v = attrs.value("date1904")
                        date1904 = v == "1" || v == "true"
                    }
                }
            })
        }

        val out = StringBuilder()
        for ((title, path) in excelSheets(zip)) {
            if (out.length > maxChars) break
            val entry = zip.getEntry(path) ?: continue
            val rows = TreeMap<Int, TreeMap<Int, String>>()
            var collected = 0
            parse(zip, entry, object : TextHandler(Limited(Int.MAX_VALUE)) {
                var type: String? = null
                var style = 0
                var row = -1
                var col = -1
                var nextCol = 0
                val value = StringBuilder()
                var inValue = false
                override fun start(local: String, attrs: Attributes) {
                    when (local) {
                        "row" -> {
                            row = (attrs.value("r")?.toIntOrNull() ?: (row + 2)) - 1
                            nextCol = 0
                        }
                        "c" -> {
                            type = attrs.value("t")
                            style = attrs.value("s")?.toIntOrNull() ?: 0
                            col = ExcelFormats.columnOf(attrs.value("r")).takeIf { it >= 0 } ?: nextCol
                            value.setLength(0)
                        }
                        "v", "t" -> inValue = true
                    }
                }
                override fun end(local: String) {
                    when (local) {
                        "v", "t" -> inValue = false
                        "c" -> {
                            val raw = value.toString()
                            val cell = when (type) {
                                "s" -> shared.getOrNull(raw.trim().toIntOrNull() ?: -1).orEmpty()
                                "b" -> if (raw.trim() == "1") "WAHR" else "FALSCH"
                                "str", "inlineStr", "e", "d" -> raw
                                else -> raw.trim().toDoubleOrNull()?.let {
                                    val fmt = xfFormat.getOrNull(style) ?: 0
                                    ExcelFormats.format(it, fmt, formats[fmt], date1904)
                                } ?: raw
                            }
                            if (cell.isNotEmpty() && row >= 0) {
                                rows.getOrPut(row) { TreeMap() }[col] = cell
                                collected += cell.length + 1
                                if (collected > maxChars) throw StopParsing()
                            }
                            nextCol = col + 1
                        }
                    }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inValue) value.appendRange(ch, start, start + length)
                }
            })
            ExcelFormats.render(title, rows, out)
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

    // ================================================================== OpenDocument

    private fun openDocumentText(zip: ZipFile, maxChars: Int): String {
        zip.getEntry("META-INF/manifest.xml")?.let { m ->
            val manifest = zip.getInputStream(m).use { String(readUpTo(it, 2_000_000), Charsets.UTF_8) }
            if (manifest.contains("encryption-data")) {
                throw ReadProblem(
                    "Passwortgeschützte OpenDocument-Dateien werden nicht unterstützt. " +
                        "Bitte ohne Passwort oder als PDF speichern."
                )
            }
        }
        val entry = zip.getEntry("content.xml") ?: throw ReadProblem("Beschädigte OpenDocument-Datei.")
        val out = Limited(maxChars)
        parse(zip, entry, object : TextHandler(out) {
            var cellDepth = 0
            var textDepth = 0
            override fun start(local: String, attrs: Attributes) {
                when (local) {
                    "p", "h" -> { textDepth++; capture = true }
                    "s" -> {
                        val n = attrs.getValue(NS_ODF_TEXT, "c")?.toIntOrNull() ?: 1
                        out.append(" ".repeat(n.coerceIn(1, 50)))
                    }
                    "tab" -> out.append('\t')
                    "line-break" -> out.append('\n')
                    "list-item" -> out.append("• ")
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
                    "table-cell", "covered-table-cell" -> { cellDepth--; out.trimEnd(); out.append('\t') }
                    "table-row" -> { out.trimEnd(); out.append('\n') }
                }
            }
        })
        return out.toString()
    }

    // ================================================================== EPUB

    private fun epubText(zip: ZipFile, maxChars: Int): String {
        fun read(path: String): String? = zip.getEntry(path)?.let { e ->
            zip.getInputStream(e).use { String(readUpTo(CountingStream(it, MAX_ENTRY_BYTES), MAX_ENTRY_BYTES), Charsets.UTF_8) }
        }
        val container = read("META-INF/container.xml")
        val opfPath = container?.let { Regex("full-path=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        val chapters = ArrayList<String>()
        if (opfPath != null) {
            val opf = read(opfPath).orEmpty()
            val base = opfPath.substringBeforeLast('/', "")
            val items = HashMap<String, String>()
            Regex("<(?:\\w+:)?item\\b[^>]*>").findAll(opf).forEach { m ->
                val tag = m.value
                val id = Regex("\\bid=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
                val href = Regex("\\bhref=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
                if (id != null && href != null) items[id] = href
            }
            Regex("<(?:\\w+:)?itemref\\b[^>]*idref=\"([^\"]+)\"").findAll(opf).forEach { m ->
                items[m.groupValues[1]]?.let { href ->
                    val decoded = try { URLDecoder.decode(href.substringBefore('#'), "UTF-8") } catch (_: Exception) { href }
                    chapters.add(normalizePath(if (base.isEmpty()) decoded else "$base/$decoded"))
                }
            }
        }
        if (chapters.isEmpty()) {
            zip.entries().asSequence().map { it.name }
                .filter { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }
                .sorted().toCollection(chapters)
        }
        val out = StringBuilder()
        for (chapter in chapters) {
            if (out.length > maxChars) break
            progress.check()
            val html = read(chapter) ?: continue
            val text = HtmlText.toText(html)
            if (text.isNotBlank()) out.append(text).append("\n\n")
        }
        return out.toString()
    }

    private fun normalizePath(path: String): String {
        val parts = ArrayList<String>()
        for (p in path.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }

    // ================================================================== Archive

    /** Archivformat am Inhalt (oder Namen) erkennen. */
    fun archiveFormat(file: File, name: String): ArchiveFormat {
        val head = file.inputStream().use { readUpTo(it, 512) }
        return Archive.formatOf(head, name) ?: throw ReadProblem("Unbekanntes Archivformat.")
    }

    /** Listet die Dateien im Archiv, jeweils mit Dateiart und ggf. dem Grund, warum nicht lesbar. */
    fun listArchive(file: File, name: String, password: String? = null): List<ArchiveItem> =
        Archive.open(file, archiveFormat(file, name), password).use { archive ->
            archive.entries().take(MAX_ARCHIVE_ENTRIES).map { e ->
                val fileName = e.path.substringAfterLast('/')
                val kind = kindOf(fileName, null)
                val problem = when {
                    kind == FileKind.UNSUPPORTED -> unsupportedReason(fileName, null)
                    kind == FileKind.IMAGE && ocr == null -> "Bild, Texterkennung nicht verfügbar."
                    e.size > MAX_ENTRY_BYTES -> "Zu groß (über ${MAX_ENTRY_BYTES / 1_000_000} MB entpackt)."
                    else -> null
                }
                ArchiveItem(e.path, e.size, kind, problem, e.encrypted)
            }
        }

    /**
     * Liest die gewählten Einträge, in Archiv-Reihenfolge, bis [maxChars] Zeichen erreicht sind.
     * Wirft [PasswordRequired], wenn ein gewählter Eintrag verschlüsselt ist.
     */
    fun readArchive(
        file: File,
        name: String,
        selected: Set<String>,
        maxChars: Int,
        password: String? = null,
        depth: Int = 0
    ): List<Part> {
        val parts = ArrayList<Part>()
        var remaining = maxChars
        var unpacked = 0L
        var index = 0
        Archive.open(file, archiveFormat(file, name), password).use { archive ->
            archive.forEach(selected) { info, stream ->
                progress.check()
                index++
                if (selected.size > 3) progress.step("Lese ${info.path.substringAfterLast('/')} ($index von ${selected.size}) …")
                when {
                    remaining <= 0 ->
                        parts.add(Part(info.path, null, problem = "Nicht mehr eingefügt, die Höchstlänge ist erreicht."))
                    unpacked > MAX_TOTAL_UNPACKED ->
                        parts.add(Part(info.path, null, problem = "Übersprungen, das Archiv ist entpackt zu groß."))
                    else -> {
                        val part = readEntry(info, stream, remaining, depth) { unpacked += it }
                        remaining -= part.text?.length ?: 0
                        parts.add(part)
                    }
                }
                true
            }
        }
        return parts
    }

    private fun readEntry(info: ArchiveEntryInfo, stream: InputStream, maxChars: Int, depth: Int, count: (Long) -> Unit): Part {
        val fileName = info.path.substringAfterLast('/')
        val kind = kindOf(fileName, null)
        if (kind == FileKind.UNSUPPORTED) return Part(info.path, null, problem = unsupportedReason(fileName, null))
        val tmp = File.createTempFile("entry", ".bin", tempDir)
        return try {
            val written = tmp.outputStream().use { out ->
                if (kind == FileKind.TEXT) {
                    // Textdateien: nur so viel wie gebraucht (+1 Byte, um "gekürzt" zu erkennen).
                    val bytes = readUpTo(stream, maxChars.toLong() * 3 + 5)
                    out.write(bytes)
                    bytes.size.toLong()
                } else {
                    copyLimited(stream, out, MAX_ENTRY_BYTES)
                }
            }
            count(written)
            readFile(tmp, fileName, null, maxChars, null, depth + 1).copy(name = info.path)
        } catch (e: ReadProblem) {
            if (e is PasswordRequired) throw e
            Part(info.path, null, problem = e.message)
        } finally {
            tmp.delete()
        }
    }

    /** Fasst die Teile eines Archivs zu einem Teil zusammen (für Archive in der Mehrfachauswahl). */
    fun combine(name: String, parts: List<Part>): Part {
        val ok = parts.filter { it.text != null }
        if (ok.isEmpty()) {
            return Part(name, null, problem = "Im Archiv ist keine lesbare Datei.", children = parts)
        }
        val text = ok.joinToString("\n\n") { "[Datei: ${it.name}]\n${it.text}" }
        return Part(name, text, truncated = parts.any { it.truncated }, ocr = parts.any { it.ocr }, children = parts)
    }

    /** Einzelne komprimierte Datei (.gz, .bz2, .xz): entpacken, dann den Inhalt lesen. */
    private fun decompressed(file: File, name: String, maxChars: Int, password: String?, depth: Int): Part {
        if (depth >= 2) return Part(name, null, problem = "Zu tief verschachtelt.")
        val inner = name.replace(Regex("\\.(gz|bz2|xz)$", RegexOption.IGNORE_CASE), "").ifEmpty { "Datei" }
        val tmp = File.createTempFile("unpacked", ".bin", tempDir)
        try {
            BufferedInputStream(file.inputStream()).use { raw ->
                val head = raw.run { mark(8); val b = ByteArray(6); val n = read(b); reset(); b.copyOf(maxOf(n, 0)) }
                val stream: InputStream = when {
                    head.size >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() -> GzipCompressorInputStream(raw, true)
                    head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "BZh" -> BZip2CompressorInputStream(raw, true)
                    else -> XZCompressorInputStream(raw, true)
                }
                tmp.outputStream().use { copyLimited(stream, it, MAX_TOTAL_UNPACKED) }
            }
            return readFile(tmp, inner, null, maxChars, password, depth + 1).copy(name = name)
        } finally {
            tmp.delete()
        }
    }

    // ================================================================== XML-Hilfen

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

        /** Attribut nach lokalem Namen, egal mit welchem Präfix. */
        fun Attributes.value(local: String): String? {
            for (i in 0 until length) {
                val l = getLocalName(i).ifEmpty { getQName(i).substringAfter(':') }
                if (l == local) return getValue(i)
            }
            return null
        }

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
            try {
                parser.parse(InputSource(CountingStream(raw, MAX_ENTRY_BYTES)), handler)
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

    // ================================================================== Konstanten und Hilfen

    companion object {
        const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

        const val MAX_ARCHIVE_ENTRIES = 2000
        const val MAX_ENTRY_BYTES = 40_000_000L
        const val MAX_TOTAL_UNPACKED = 200_000_000L
        const val MAX_LEGACY_BYTES = 60_000_000L

        /** Höchstens so viele Seiten eines PDFs per Texterkennung (dauert je Seite ein paar Sekunden). */
        const val MAX_OCR_PAGES = 40
        /** Seiten mit weniger Zeichen gelten als eingescannt. */
        const val MIN_PAGE_CHARS = 10

        private const val NS_RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val NS_ODF_TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"

        private val SLIDE = Regex("ppt/slides/slide(\\d+)\\.xml")
        private val SHEET = Regex("xl/worksheets/sheet(\\d+)\\.xml")
        private val ZIP_BASED = setOf(FileKind.WORD, FileKind.EXCEL, FileKind.SLIDES, FileKind.OPEN_DOCUMENT, FileKind.EPUB)
        private val TAR_SUFFIXES = listOf(".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tbz", ".tar.xz", ".txz")
        private val WIN1252: Charset = charset("windows-1252")

        private const val REASON_RAR =
            "RAR-Archive werden nicht unterstützt (geschlossenes Format, aktuelles RAR5 ist ohne die " +
                "WinRAR-Bibliothek nicht lesbar). Bitte als ZIP oder 7z packen."
        private const val REASON_MEDIA =
            "Audio und Video enthalten keinen Text. Eine Abschrift ginge nur mit Spracherkennung."

        private val UNSUPPORTED_REASONS: Map<String, String> = buildMap {
            put("rar", REASON_RAR)
            listOf("zst", "lz", "lz4", "lzma", "cab", "arj", "ace", "dmg")
                .forEach { put(it, "Dieses Archivformat wird nicht unterstützt. Bitte als ZIP, 7z oder tar.gz packen.") }
            listOf("mp3", "m4a", "wav", "ogg", "opus", "flac", "aac", "amr", "mp4", "mkv", "mov", "avi", "webm", "3gp")
                .forEach { put(it, REASON_MEDIA) }
            val binary = "Programm- oder Binärdatei, enthält keinen lesbaren Text."
            listOf("apk", "aab", "exe", "dll", "so", "bin", "class", "dex", "jar", "o", "pyc", "iso", "img", "db", "sqlite")
                .forEach { put(it, binary) }
            listOf("ttf", "otf", "woff", "woff2").forEach { put(it, "Schriftdateien enthalten keinen Text.") }
            listOf("wps", "wpd", "pages", "numbers", "key")
                .forEach { put(it, "Dieses Dokumentformat wird nicht unterstützt. Bitte als PDF oder .docx exportieren.") }
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

/** HTML grob in lesbaren Text umwandeln (für EPUB und HTML-Mails). */
object HtmlText {

    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
        "euro" to "€", "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»",
        "bdquo" to "„", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "middot" to "·", "bull" to "•",
        "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç", "shy" to ""
    )

    private fun codePoint(cp: Int): String? = runCatching { String(Character.toChars(cp)) }.getOrNull()

    fun toText(html: String): String {
        var s = html.replace(Regex("\\s+"), " ")
        s = s.replace(Regex("(?is)<(script|style|head|title)\\b[^>]*>.*?</\\1\\s*>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)<li\\b[^>]*>"), "\n• ")
        s = s.replace(Regex("(?i)</t[dh]\\s*>"), "\t")
        s = s.replace(Regex("(?i)</?(p|div|h[1-6]|tr|blockquote|section|article|pre|table|ul|ol|dl|dt|dd|header|footer|figure)\\b[^>]*>"), "\n")
        s = s.replace(Regex("<[^>]*>"), "")
        s = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let(::codePoint)
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.let(::codePoint)
                else -> ENTITIES[e]
            } ?: m.value
        }
        return s.lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
