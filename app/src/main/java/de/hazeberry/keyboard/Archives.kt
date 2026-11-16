package de.hazeberry.keyboard

import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Eine Datei im Archiv. */
class ArchiveEntryInfo(val path: String, val size: Long, val encrypted: Boolean)

enum class ArchiveFormat { ZIP, SEVEN_Z, TAR, TAR_GZ, TAR_BZ2, TAR_XZ }

/** Für den Zugriff auf das Archiv fehlt das Passwort oder es ist falsch. */
class PasswordRequired(val wrong: Boolean) :
    ReadProblem(if (wrong) "Das Passwort ist falsch." else "Die Datei ist mit einem Passwort geschützt.")

/**
 * Einheitlicher Zugriff auf ZIP (auch verschlüsselt), 7z und tar (auch .tar.gz/.bz2/.xz).
 * Gelesen wird immer der Reihe nach, das funktioniert für alle Formate.
 */
abstract class Archive : Closeable {

    abstract fun entries(): List<ArchiveEntryInfo>

    /** Ruft [action] für jeden Eintrag aus [selected] auf, in Archiv-Reihenfolge. false = aufhören. */
    abstract fun forEach(selected: Set<String>, action: (ArchiveEntryInfo, InputStream) -> Boolean)

    companion object {
        fun open(file: File, format: ArchiveFormat, password: String?): Archive = when (format) {
            ArchiveFormat.ZIP -> ZipArchive(file, password)
            ArchiveFormat.SEVEN_Z -> SevenZArchive(file, password)
            else -> TarArchive(file, format)
        }

        /** Archivformat am Dateianfang erkennen (Name als Hilfe für tar-Kompression). */
        fun formatOf(head: ByteArray, name: String): ArchiveFormat? {
            fun at(i: Int, vararg b: Int) = head.size >= i + b.size && b.indices.all { head[i + it] == b[it].toByte() }
            val lower = name.lowercase()
            return when {
                at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) -> ArchiveFormat.ZIP
                at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> ArchiveFormat.SEVEN_Z
                at(257, 0x75, 0x73, 0x74, 0x61, 0x72) -> ArchiveFormat.TAR
                at(0, 0x1F, 0x8B) -> ArchiveFormat.TAR_GZ
                at(0, 0x42, 0x5A, 0x68) -> ArchiveFormat.TAR_BZ2
                at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> ArchiveFormat.TAR_XZ
                lower.endsWith(".tar") -> ArchiveFormat.TAR
                else -> null
            }
        }

        fun isJunk(path: String): Boolean {
            val name = path.substringAfterLast('/')
            return path.startsWith("__MACOSX/") || path.contains("/__MACOSX/") ||
                name == ".DS_Store" || name == "Thumbs.db" || name == "desktop.ini" ||
                path.startsWith(".git/") || path.contains("/.git/")
        }
    }
}

// ---------------------------------------------------------------------- ZIP

private class ZipArchive(file: File, private val password: String?) : Archive() {

    private val zip: net.lingala.zip4j.ZipFile
    private val headers: List<FileHeader>

    init {
        var z = net.lingala.zip4j.ZipFile(file, password?.toCharArray())
        z.setCharset(Charsets.UTF_8)
        var h = try {
            z.fileHeaders
        } catch (e: ZipException) {
            z.close()
            throw ReadProblem("Das Archiv ist beschädigt oder kein ZIP.")
        }
        // ZIPs aus dem Windows-Explorer nutzen oft CP437 statt UTF-8 für Dateinamen.
        if (h.any { it.fileName.contains('�') }) {
            z.close()
            z = net.lingala.zip4j.ZipFile(file, password?.toCharArray())
            z.setCharset(charset("IBM437"))
            h = z.fileHeaders
        }
        zip = z
        headers = h
    }

    override fun entries(): List<ArchiveEntryInfo> = headers
        .filter { !it.isDirectory && !isJunk(it.fileName) }
        .map { ArchiveEntryInfo(it.fileName, it.uncompressedSize, it.isEncrypted) }

    override fun forEach(selected: Set<String>, action: (ArchiveEntryInfo, InputStream) -> Boolean) {
        for (h in headers) {
            if (h.isDirectory || h.fileName !in selected) continue
            if (h.isEncrypted && password.isNullOrEmpty()) throw PasswordRequired(wrong = false)
            val info = ArchiveEntryInfo(h.fileName, h.uncompressedSize, h.isEncrypted)
            val goOn = try {
                zip.getInputStream(h).use { action(info, it) }
            } catch (e: ZipException) {
                if (h.isEncrypted && (e.type == ZipException.Type.WRONG_PASSWORD ||
                        e.type == ZipException.Type.CHECKSUM_MISMATCH)
                ) throw PasswordRequired(wrong = true)
                throw e
            }
            if (!goOn) break
        }
    }

    private fun isJunk(path: String) = Archive.isJunk(path)

    override fun close() = zip.close()
}

// ---------------------------------------------------------------------- 7z

private class SevenZArchive(file: File, private val password: String?) : Archive() {

    private val seven: SevenZFile = try {
        SevenZFile.builder().setFile(file)
            .apply { if (!password.isNullOrEmpty()) setPassword(password) }
            // Schutz vor Archiven, die beim Entpacken riesige Wörterbücher verlangen
            .setMaxMemoryLimitKb(256 * 1024)
            .get()
    } catch (e: PasswordRequiredException) {
        throw PasswordRequired(wrong = false)
    } catch (e: IOException) {
        // Verschlüsselte Kopfdaten mit falschem Passwort ergeben Datenmüll.
        if (!password.isNullOrEmpty()) throw PasswordRequired(wrong = true)
        throw ReadProblem("Das 7z-Archiv ist beschädigt (${e.message}).")
    }

    private val all = seven.entries.toList()

    private fun encrypted(e: org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry): Boolean =
        try {
            e.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true
        } catch (_: Exception) {
            false
        }

    override fun entries(): List<ArchiveEntryInfo> = all
        .filter { !it.isDirectory && it.hasStream() && !Archive.isJunk(it.name) }
        .map { ArchiveEntryInfo(it.name, it.size, encrypted(it)) }

    override fun forEach(selected: Set<String>, action: (ArchiveEntryInfo, InputStream) -> Boolean) {
        val stream = object : InputStream() {
            override fun read(): Int = seven.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = seven.read(b, off, len)
        }
        while (true) {
            val e = try {
                seven.nextEntry ?: break
            } catch (ex: PasswordRequiredException) {
                throw PasswordRequired(wrong = false)
            }
            if (e.isDirectory || !e.hasStream() || e.name !in selected) continue
            val enc = encrypted(e)
            if (enc && password.isNullOrEmpty()) throw PasswordRequired(wrong = false)
            val goOn = try {
                action(ArchiveEntryInfo(e.name, e.size, enc), stream)
            } catch (ex: PasswordRequiredException) {
                throw PasswordRequired(wrong = false)
            } catch (ex: IOException) {
                if (enc && ex !is ReadProblem && ex !is CancelledException) throw PasswordRequired(wrong = true)
                throw ex
            }
            if (!goOn) break
        }
    }

    override fun close() = seven.close()
}

// ---------------------------------------------------------------------- tar

private class TarArchive(private val file: File, private val format: ArchiveFormat) : Archive() {

    private fun open(): TarArchiveInputStream {
        val raw = BufferedInputStream(file.inputStream())
        val decompressed: InputStream = try {
            when (format) {
                ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(raw, true)
                ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(raw, true)
                ArchiveFormat.TAR_XZ -> XZCompressorInputStream(raw, true)
                else -> raw
            }
        } catch (e: IOException) {
            raw.close()
            throw ReadProblem("Das Archiv ist beschädigt.")
        }
        return TarArchiveInputStream(decompressed)
    }

    private fun clean(name: String) = name.removePrefix("./")

    override fun entries(): List<ArchiveEntryInfo> = open().use { tar ->
        val list = ArrayList<ArchiveEntryInfo>()
        while (true) {
            val e = tar.nextEntry ?: break
            if (!e.isFile) continue
            val name = clean(e.name)
            if (!Archive.isJunk(name)) list.add(ArchiveEntryInfo(name, e.size, false))
            if (list.size > 100_000) break
        }
        list
    }

    override fun forEach(selected: Set<String>, action: (ArchiveEntryInfo, InputStream) -> Boolean) {
        open().use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val name = clean(e.name)
                if (!e.isFile || name !in selected) continue
                if (!action(ArchiveEntryInfo(name, e.size, false), tar)) break
            }
        }
    }

    override fun close() {}
}
