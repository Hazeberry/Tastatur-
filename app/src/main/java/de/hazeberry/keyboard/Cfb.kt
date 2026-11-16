package de.hazeberry.keyboard

/**
 * Minimaler Leser für "Compound File Binary" (auch OLE2), das Containerformat von alten
 * Office-Dateien (.doc, .xls, .ppt) und von passwortgeschützten neuen (.docx, .xlsx, .pptx).
 *
 * Ein Compound File ist ein kleines Dateisystem in der Datei: Sektoren, eine Belegungstabelle
 * (FAT) und ein Verzeichnis mit "Streams". Spezifikation: [MS-CFB].
 *
 * Liest nur, schreibt nie. Alle Zugriffe sind gegen kaputte oder manipulierte Dateien abgesichert
 * (Grenzen, Zyklen in Sektorketten).
 */
class Cfb(private val data: ByteArray) {

    class Entry(
        val name: String,
        val type: Int,
        val start: Int,
        val size: Long,
        val left: Int,
        val right: Int,
        val child: Int
    )

    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private val miniStream: ByteArray
    val entries: List<Entry>

    init {
        if (!isCfb(data)) throw ReadProblem("Keine Office-Datei im alten Format.")
        val sectorShift = u16(0x1E)
        if (sectorShift != 9 && sectorShift != 12) throw ReadProblem("Beschädigte Office-Datei.")
        sectorSize = 1 shl sectorShift
        miniSectorSize = 1 shl u16(0x20).coerceIn(1, 12)
        miniCutoff = u32(0x38).toLong() and 0xFFFFFFFFL

        // FAT-Sektoren: die ersten 109 stehen im Kopf, weitere in einer DIFAT-Kette.
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) {
            val s = u32(0x4C + i * 4)
            if (s >= 0) fatSectors.add(s)
        }
        var difat = u32(0x44)
        var guard = 0
        val perSector = sectorSize / 4
        while (difat >= 0 && guard++ < 100_000) {
            val off = offset(difat)
            if (off + sectorSize > data.size) break
            for (i in 0 until perSector - 1) {
                val s = le32(off + i * 4)
                if (s >= 0) fatSectors.add(s)
            }
            difat = le32(off + (perSector - 1) * 4)
        }
        val fatList = IntArray(fatSectors.size * perSector)
        var n = 0
        for (s in fatSectors) {
            val off = offset(s)
            if (off + sectorSize > data.size) break
            for (i in 0 until perSector) fatList[n++] = le32(off + i * 4)
        }
        fat = fatList.copyOf(n)

        val dir = chain(u32(0x30), -1)
        val list = ArrayList<Entry>()
        var p = 0
        while (p + 128 <= dir.size) {
            val nameLen = le16(dir, p + 64).coerceIn(0, 64)
            val name = String(dir, p, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            val type = dir[p + 66].toInt() and 0xFF
            val start = le32(dir, p + 116)
            // Bei Version 3 sind nur die unteren 32 Bit gültig.
            val size = le32(dir, p + 120).toLong() and 0xFFFFFFFFL
            list.add(Entry(name, type, start, size, le32(dir, p + 68), le32(dir, p + 72), le32(dir, p + 76)))
            p += 128
        }
        entries = list

        val mf = chain(u32(0x3C), -1)
        miniFat = IntArray(mf.size / 4) { le32(mf, it * 4) }
        val root = entries.firstOrNull { it.type == 5 }
        miniStream = if (root != null) chain(root.start, root.size) else ByteArray(0)
    }

    /** Direkte Kinder des Wurzelverzeichnisses (nicht die eingebetteter Objekte). */
    val rootChildren: List<Entry> by lazy {
        val root = entries.indexOfFirst { it.type == 5 }
        if (root < 0) emptyList() else children(root)
    }

    /** Kinder eines Verzeichniseintrags; sie bilden einen Binärbaum über left/right. */
    fun children(index: Int): List<Entry> {
        val out = ArrayList<Entry>()
        val visited = HashSet<Int>()
        val stack = ArrayDeque<Int>()
        entries.getOrNull(index)?.child?.let { if (it >= 0) stack.addLast(it) }
        while (stack.isNotEmpty() && out.size < 100_000) {
            val i = stack.removeLast()
            if (i < 0 || i >= entries.size || !visited.add(i)) continue
            val e = entries[i]
            out.add(e)
            if (e.left >= 0) stack.addLast(e.left)
            if (e.right >= 0) stack.addLast(e.right)
        }
        return out
    }

    fun has(name: String): Boolean = find(name) != null

    /** Inhalt eines Streams im Wurzelverzeichnis (Groß-/Kleinschreibung egal) oder null. */
    fun stream(name: String): ByteArray? = find(name)?.let { read(it) }

    fun read(e: Entry): ByteArray =
        if (e.size < miniCutoff) miniChain(e.start, e.size) else chain(e.start, e.size)

    private fun find(name: String): Entry? =
        rootChildren.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) }

    private fun offset(sector: Int): Int = (sector + 1) * sectorSize

    /** Folgt einer Sektorkette. [size] < 0: bis zum Kettenende. */
    private fun chain(start: Int, size: Long): ByteArray {
        if (size > MAX_STREAM) throw TooLargeException("Die Office-Datei ist zu groß.")
        // Ohne Größenangabe (Verzeichnis, Mini-FAT): bis zum Kettenende, höchstens so groß wie die Datei.
        val limit = if (size < 0) data.size.toLong() else size
        val out = java.io.ByteArrayOutputStream(if (size in 0..Int.MAX_VALUE) size.toInt() else 4096)
        var s = start
        var steps = 0
        while (s >= 0 && s < fat.size && out.size() < limit && steps++ <= fat.size) {
            val off = offset(s)
            if (off >= data.size) break
            val len = minOf(sectorSize.toLong(), limit - out.size(), (data.size - off).toLong()).toInt()
            out.write(data, off, len)
            s = fat[s]
        }
        return out.toByteArray()
    }

    private fun miniChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(size.toInt().coerceAtLeast(0))
        var s = start
        var steps = 0
        while (s >= 0 && s < miniFat.size && out.size() < size && steps++ <= miniFat.size) {
            val off = s * miniSectorSize
            if (off >= miniStream.size) break
            val len = minOf(miniSectorSize.toLong(), size - out.size(), (miniStream.size - off).toLong()).toInt()
            out.write(miniStream, off, len)
            s = miniFat[s]
        }
        return out.toByteArray()
    }

    private fun u16(off: Int) = le16(data, off)
    private fun u32(off: Int) = le32(data, off)
    private fun le32(off: Int) = le32(data, off)

    companion object {
        private const val MAX_STREAM = 200_000_000L

        private val MAGIC = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        )

        fun isCfb(data: ByteArray): Boolean =
            data.size >= 512 && (0 until 8).all { data[it] == MAGIC[it] }

        fun le16(b: ByteArray, off: Int): Int =
            if (off < 0 || off + 2 > b.size) 0
            else (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        fun le32(b: ByteArray, off: Int): Int =
            if (off < 0 || off + 4 > b.size) -1
            else (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}
