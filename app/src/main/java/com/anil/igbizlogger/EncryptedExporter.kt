package com.anil.igbizlogger

import android.content.Context
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * File format (must match web-viewer/decrypt.js exactly):
 *   bytes 0-4   : magic "IGBZ1"
 *   bytes 5-20  : salt (16 bytes)
 *   bytes 21-32 : IV (12 bytes)
 *   bytes 33-.. : AES-256-GCM ciphertext (tag included, GCM standard)
 * Key derivation: PBKDF2WithHmacSHA256, 150,000 iterations, 256-bit key.
 */
object EncryptedExporter {

    private const val MAGIC = "IGBZ1"
    private const val ITERATIONS = 150_000
    private const val KEY_LEN_BITS = 256

    fun exportToday(context: Context, passphrase: String): File? {
        val logsDir = File(context.getExternalFilesDir(null), "IGBizLogs")
        val txtFiles = logsDir.listFiles { f -> f.extension == "txt" } ?: return null
        if (txtFiles.isEmpty()) return null

        val exportsDir = File(context.getExternalFilesDir(null), "IGBizExports").apply { mkdirs() }
        val dateTag = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val zipBytes = zipFiles(txtFiles)

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(zipBytes)

        val outFile = File(exportsDir, "IGBizExport_$dateTag.encz")
        outFile.outputStream().use { out ->
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.write(salt)
            out.write(iv)
            out.write(ciphertext)
        }
        return outFile
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_LEN_BITS)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun zipFiles(files: Array<File>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for (f in files) {
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
