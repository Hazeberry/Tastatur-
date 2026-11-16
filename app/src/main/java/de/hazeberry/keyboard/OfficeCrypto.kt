package de.hazeberry.keyboard

import de.hazeberry.keyboard.Cfb.Companion.le16
import de.hazeberry.keyboard.Cfb.Companion.le32
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.SAXParserFactory

/**
 * Entschlüsselt passwortgeschützte Word-, Excel- und PowerPoint-Dateien (.docx/.xlsx/.pptx).
 *
 * Solche Dateien sind kein ZIP, sondern ein Compound File mit den Streams "EncryptionInfo" und
 * "EncryptedPackage". Darin steckt das eigentliche ZIP, mit AES verschlüsselt.
 * Unterstützt: "Agile Encryption" (Office 2010 und neuer) und "Standard Encryption" (Office 2007).
 * Spezifikation: [MS-OFFCRYPTO].
 */
object OfficeCrypto {

    /** Excel nutzt dieses Passwort, wenn nur ein Schreibschutz gesetzt ist. */
    const val DEFAULT_PASSWORD = "VelvetSweatshop"

    fun isEncryptedPackage(cfb: Cfb): Boolean = cfb.has("EncryptionInfo") && cfb.has("EncryptedPackage")

    /** Entschlüsselt in [out]. Wirft [PasswordRequired], wenn das Passwort fehlt oder falsch ist. */
    fun decrypt(cfb: Cfb, password: String?, out: File) {
        val info = cfb.stream("EncryptionInfo") ?: throw ReadProblem("Beschädigte Office-Datei.")
        val pkg = cfb.stream("EncryptedPackage") ?: throw ReadProblem("Beschädigte Office-Datei.")
        val major = le16(info, 0)
        val minor = le16(info, 2)
        // Ohne Passwort zuerst das Standardpasswort probieren (nur Schreibschutz).
        val candidates = if (password.isNullOrEmpty()) listOf(DEFAULT_PASSWORD) else listOf(password)
        for (candidate in candidates) {
            val plain = when {
                major == 4 && minor == 4 -> agile(info, pkg, candidate)
                (major == 3 || major == 4) && minor == 2 -> standard(info, pkg, candidate)
                else -> throw ReadProblem("Diese Office-Verschlüsselung wird nicht unterstützt.")
            }
            if (plain != null) {
                out.writeBytes(plain)
                return
            }
        }
        throw PasswordRequired(wrong = !password.isNullOrEmpty())
    }

    // ------------------------------------------------------------------ Agile (Office 2010+)

    private class KeyData {
        var salt = ByteArray(0)
        var hash = "SHA1"
        var blockSize = 16
        var keyBits = 128
        var cipher = "AES"
        var chaining = "ChainingModeCBC"
    }

    private class Encryptor {
        var spinCount = 100_000
        var salt = ByteArray(0)
        var hash = "SHA1"
        var hashSize = 20
        var keyBits = 128
        var blockSize = 16
        var verifierInput = ByteArray(0)
        var verifierHash = ByteArray(0)
        var keyValue = ByteArray(0)
        var found = false
    }

    private val BLOCK_VERIFIER_INPUT = bytes(0xfe, 0xa7, 0xd2, 0x76, 0x3b, 0x4b, 0x9e, 0x79)
    private val BLOCK_VERIFIER_HASH = bytes(0xd7, 0xaa, 0x0f, 0x6d, 0x30, 0x61, 0x34, 0x4e)
    private val BLOCK_KEY = bytes(0x14, 0x6e, 0x0b, 0xe7, 0xab, 0xac, 0xd0, 0xd6)

    private fun agile(info: ByteArray, pkg: ByteArray, password: String): ByteArray? {
        val keyData = KeyData()
        val enc = Encryptor()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val decoder = Base64.getDecoder()
        factory.newSAXParser().parse(InputSource(ByteArrayInputStream(info, 8, info.size - 8)), object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
                val local = localName?.ifEmpty { null } ?: qName.orEmpty().substringAfter(':')
                when (local) {
                    "keyData" -> {
                        keyData.salt = decoder.decode(a.getValue("saltValue").orEmpty())
                        keyData.hash = a.getValue("hashAlgorithm") ?: "SHA1"
                        keyData.blockSize = a.getValue("blockSize")?.toIntOrNull() ?: 16
                        keyData.keyBits = a.getValue("keyBits")?.toIntOrNull() ?: 128
                        keyData.cipher = a.getValue("cipherAlgorithm") ?: "AES"
                        keyData.chaining = a.getValue("cipherChaining") ?: "ChainingModeCBC"
                    }
                    "encryptedKey" -> if (!enc.found && a.getValue("spinCount") != null) {
                        enc.found = true
                        enc.spinCount = a.getValue("spinCount").toInt()
                        enc.salt = decoder.decode(a.getValue("saltValue"))
                        enc.hash = a.getValue("hashAlgorithm") ?: "SHA1"
                        enc.hashSize = a.getValue("hashSize")?.toIntOrNull() ?: 20
                        enc.keyBits = a.getValue("keyBits")?.toIntOrNull() ?: 128
                        enc.blockSize = a.getValue("blockSize")?.toIntOrNull() ?: 16
                        enc.verifierInput = decoder.decode(a.getValue("encryptedVerifierHashInput"))
                        enc.verifierHash = decoder.decode(a.getValue("encryptedVerifierHashValue"))
                        enc.keyValue = decoder.decode(a.getValue("encryptedKeyValue"))
                    }
                }
            }
            override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(ByteArrayInputStream(ByteArray(0)))
        })
        if (!enc.found) throw ReadProblem("Office-Datei mit Zertifikatsverschlüsselung wird nicht unterstützt.")
        if (keyData.cipher != "AES" || enc.blockSize != 16 || keyData.chaining != "ChainingModeCBC") {
            throw ReadProblem("Diese Office-Verschlüsselung (${keyData.cipher}) wird nicht unterstützt.")
        }

        val md = digest(enc.hash)
        var h = md.digest(enc.salt + password.toByteArray(Charsets.UTF_16LE))
        val iterator = ByteArray(4)
        for (i in 0 until enc.spinCount) {
            iterator[0] = i.toByte(); iterator[1] = (i shr 8).toByte()
            iterator[2] = (i shr 16).toByte(); iterator[3] = (i shr 24).toByte()
            md.update(iterator)
            h = md.digest(h)
        }
        fun derive(block: ByteArray): ByteArray = fit(md.digest(h + block), enc.keyBits / 8, 0x36)

        val iv = fit(enc.salt, enc.blockSize, 0x36)
        val verifierInput = aesCbc(derive(BLOCK_VERIFIER_INPUT), iv, enc.verifierInput).copyOf(enc.salt.size)
        val expected = aesCbc(derive(BLOCK_VERIFIER_HASH), iv, enc.verifierHash).copyOf(enc.hashSize)
        if (!MessageDigest.isEqual(md.digest(verifierInput), expected)) return null

        val key = aesCbc(derive(BLOCK_KEY), iv, enc.keyValue).copyOf(keyData.keyBits / 8)

        val size = (le32(pkg, 0).toLong() and 0xFFFFFFFFL) or ((le32(pkg, 4).toLong() and 0xFFFFFFFFL) shl 32)
        if (size < 0 || size > pkg.size) throw ReadProblem("Beschädigte Office-Datei.")
        val out = java.io.ByteArrayOutputStream(size.toInt())
        val kmd = digest(keyData.hash)
        var segment = 0
        var pos = 8
        while (pos < pkg.size && out.size() < size) {
            val len = minOf(4096, pkg.size - pos) and 0x7FFFFFF0
            if (len <= 0) break
            val segIv = fit(kmd.digest(keyData.salt + le32bytes(segment)), keyData.blockSize, 0x36)
            out.write(aesCbc(key, segIv, pkg.copyOfRange(pos, pos + len)))
            pos += len
            segment++
        }
        return out.toByteArray().copyOf(size.toInt())
    }

    // ------------------------------------------------------------------ Standard (Office 2007)

    private fun standard(info: ByteArray, pkg: ByteArray, password: String): ByteArray? {
        val headerSize = le32(info, 8)
        val h = 12
        val algId = le32(info, h + 8)
        val keyBits = le32(info, h + 16)
        if (algId !in listOf(0x660E, 0x660F, 0x6610)) {
            throw ReadProblem("Diese Office-Verschlüsselung (RC4) wird nicht unterstützt.")
        }
        val v = h + headerSize
        val saltSize = le32(info, v)
        if (saltSize != 16 || v + 4 + 16 + 16 + 4 + 32 > info.size) throw ReadProblem("Beschädigte Office-Datei.")
        val salt = info.copyOfRange(v + 4, v + 20)
        val encVerifier = info.copyOfRange(v + 20, v + 36)
        val encVerifierHash = info.copyOfRange(v + 40, v + 72)

        val md = MessageDigest.getInstance("SHA-1")
        var hash = md.digest(salt + password.toByteArray(Charsets.UTF_16LE))
        for (i in 0 until 50_000) hash = md.digest(le32bytes(i) + hash)
        hash = md.digest(hash + le32bytes(0))
        val x1 = md.digest(ByteArray(64) { (0x36 xor (hash.getOrElse(it) { 0 }.toInt())).toByte() })
        val x2 = md.digest(ByteArray(64) { (0x5C xor (hash.getOrElse(it) { 0 }.toInt())).toByte() })
        val key = (x1 + x2).copyOf(keyBits / 8)

        val verifier = aesEcb(key, encVerifier)
        val verifierHash = aesEcb(key, encVerifierHash).copyOf(20)
        if (!MessageDigest.isEqual(md.digest(verifier), verifierHash)) return null

        val size = (le32(pkg, 0).toLong() and 0xFFFFFFFFL) or ((le32(pkg, 4).toLong() and 0xFFFFFFFFL) shl 32)
        val body = pkg.copyOfRange(8, 8 + ((pkg.size - 8) and 0x7FFFFFF0))
        if (size < 0 || size > body.size) throw ReadProblem("Beschädigte Office-Datei.")
        return aesEcb(key, body).copyOf(size.toInt())
    }

    // ------------------------------------------------------------------ Hilfen

    private fun digest(name: String): MessageDigest = MessageDigest.getInstance(
        when (name.uppercase()) {
            "SHA1", "SHA-1" -> "SHA-1"
            "SHA256", "SHA-256" -> "SHA-256"
            "SHA384", "SHA-384" -> "SHA-384"
            "SHA512", "SHA-512" -> "SHA-512"
            "MD5" -> "MD5"
            else -> throw ReadProblem("Diese Office-Verschlüsselung ($name) wird nicht unterstützt.")
        }
    )

    private fun aesCbc(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data, 0, data.size and 0x7FFFFFF0)
    }

    /** ECB ist hier vom Format (Office 2007 "Standard Encryption") vorgegeben; wir entschlüsseln nur. */
    @android.annotation.SuppressLint("GetInstance")
    private fun aesEcb(key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data, 0, data.size and 0x7FFFFFF0)
    }

    /** Auf [size] Bytes kürzen oder mit [pad] auffüllen. */
    private fun fit(b: ByteArray, size: Int, pad: Int): ByteArray =
        if (b.size >= size) b.copyOf(size) else b + ByteArray(size - b.size) { pad.toByte() }

    private fun le32bytes(i: Int) = byteArrayOf(i.toByte(), (i shr 8).toByte(), (i shr 16).toByte(), (i shr 24).toByte())

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
