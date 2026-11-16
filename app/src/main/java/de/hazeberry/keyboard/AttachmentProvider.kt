package de.hazeberry.keyboard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.UUID

/**
 * Stellt Dateien, die als Anhang in eine App gehen, über eine content://-Adresse bereit.
 *
 * Nicht exportiert: Andere Apps kommen nur an eine Datei, wenn die Tastatur ihnen dafür
 * ausdrücklich eine Leseberechtigung gibt (beim Einfügen oder Teilen), und nur lesend.
 * Die Kopien liegen im Cache der App und werden nach einem Tag gelöscht.
 */
class AttachmentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val segments = uri.pathSegments
        if (uri.authority != AUTHORITY || segments.size != 2) throw FileNotFoundException()
        val (id, name) = segments
        if (!ID.matches(id) || name.contains('/') || name.startsWith(".")) throw FileNotFoundException()
        val root = dir(ctx).canonicalFile
        val file = File(File(root, id), name).canonicalFile
        if (file.parentFile?.parentFile != root || !file.isFile) throw FileNotFoundException()
        return file
    }

    override fun getType(uri: Uri): String? = try {
        File(fileFor(uri).parentFile, MIME_FILE).readText().ifBlank { null }
    } catch (e: Exception) {
        null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Nur lesend")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = fileFor(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        })
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "de.hazeberry.keyboard.files"
        private const val MIME_FILE = ".mime"
        private val ID = Regex("[0-9a-f-]{36}")
        private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
        const val MAX_BYTES = 100_000_000L

        fun dir(context: Context) = File(context.cacheDir, "shared")

        /** Kopiert [input] in den Ablageordner und gibt die content://-Adresse zurück. */
        fun store(context: Context, input: InputStream, name: String, mime: String): Uri {
            val id = UUID.randomUUID().toString()
            val folder = File(dir(context), id).apply { mkdirs() }
            val safe = safeName(name)
            val file = File(folder, safe)
            try {
                file.outputStream().use { DocumentReader.copyLimited(input, it, MAX_BYTES) }
                File(folder, MIME_FILE).writeText(mime)
            } catch (e: Exception) {
                folder.deleteRecursively()
                throw e
            }
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(id).appendPath(safe).build()
        }

        /** Entfernt Kopien, die älter als einen Tag sind. */
        fun cleanup(context: Context, now: Long = System.currentTimeMillis()) {
            dir(context).listFiles()?.forEach { folder ->
                if (now - folder.lastModified() > MAX_AGE_MS) folder.deleteRecursively()
            }
        }

        fun safeName(name: String): String {
            val cleaned = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().trimStart('.')
            return cleaned.take(120).ifEmpty { "Datei" }
        }
    }
}
