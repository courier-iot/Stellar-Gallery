package com.courieriot.stellargallery.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Size
import com.courieriot.stellargallery.data.MediaItem
import com.courieriot.stellargallery.data.MediaRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class VaultItem(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val dateTaken: Long,
) {
    val isVideo: Boolean get() = mime.startsWith("video/")
}

/**
 * Private, encrypted storage inside the app's own folder.
 * Files are AES-256 encrypted with a data key that is itself wrapped by a key in the Android Keystore.
 */
class Vault(private val context: Context) {
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val tmpDir get() = File(context.cacheDir, "vault_tmp").apply { mkdirs() }
    private val indexFile = File(dir, "index.dat")
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val rng = SecureRandom()
    private val lock = Any()
    private var cache: List<VaultItem>? = null

    // ---------- PIN ----------

    fun hasPin(): Boolean = prefs.contains("pin_hash")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also(rng::nextBytes)
        val hash = pbkdf2(pin, salt)
        prefs.edit()
            .putString("pin_salt", b64(salt))
            .putString("pin_hash", b64(hash))
            .apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = prefs.getString("pin_salt", null)?.let(::unb64) ?: return false
        val hash = prefs.getString("pin_hash", null)?.let(::unb64) ?: return false
        return MessageDigest.isEqual(hash, pbkdf2(pin, salt))
    }

    // ---------- Decoy PIN + unlock log ----------

    enum class PinResult { REAL, DECOY, WRONG }

    fun hasDecoy(): Boolean = prefs.contains("decoy_hash")

    fun setDecoyPin(pin: String) {
        val salt = ByteArray(16).also(rng::nextBytes)
        prefs.edit()
            .putString("decoy_salt", b64(salt))
            .putString("decoy_hash", b64(pbkdf2(pin, salt)))
            .apply()
    }

    fun clearDecoy() { prefs.edit().remove("decoy_salt").remove("decoy_hash").apply() }

    private fun checkDecoy(pin: String): Boolean {
        val salt = prefs.getString("decoy_salt", null)?.let(::unb64) ?: return false
        val hash = prefs.getString("decoy_hash", null)?.let(::unb64) ?: return false
        return MessageDigest.isEqual(hash, pbkdf2(pin, salt))
    }

    fun check(pin: String): PinResult = when {
        checkPin(pin) -> PinResult.REAL
        checkDecoy(pin) -> PinResult.DECOY
        else -> PinResult.WRONG
    }

    data class Attempt(val time: Long, val kind: String)

    fun logAttempt(kind: String) {
        val list = attempts().takeLast(49) + Attempt(System.currentTimeMillis(), kind)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.time).put("k", it.kind)) }
        prefs.edit().putString("attempts", arr.toString()).apply()
    }

    fun attempts(): List<Attempt> = runCatching {
        val arr = JSONArray(prefs.getString("attempts", "[]"))
        List(arr.length()) { i -> arr.getJSONObject(i).let { Attempt(it.getLong("t"), it.getString("k")) } }
    }.getOrDefault(emptyList())

    fun clearAttempts() { prefs.edit().remove("attempts").putLong("attempts_seen", System.currentTimeMillis()).apply() }

    /** Failed / decoy attempts since the real vault was last opened. */
    fun unseenAttempts(): Int {
        val seen = prefs.getLong("attempts_seen", 0L)
        return attempts().count { it.time > seen && it.kind != "unlocked" }
    }

    fun markAttemptsSeen() { prefs.edit().putLong("attempts_seen", System.currentTimeMillis()).apply() }

    var biometricEnabled: Boolean
        get() = prefs.getBoolean("biometric", false)
        set(v) = prefs.edit().putBoolean("biometric", v).apply()

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 60_000, 256))
            .encoded

    // ---------- Keys ----------

    private val dataKey: SecretKey by lazy { loadOrCreateDataKey() }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun loadOrCreateDataKey(): SecretKey {
        val mk = masterKey()
        val stored = prefs.getString("dek", null)
        if (stored != null) {
            val raw = unb64(stored)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, mk, GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            return SecretKeySpec(c.doFinal(raw.copyOfRange(12, raw.size)), "AES")
        }
        val dek = ByteArray(32).also(rng::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, mk)
        val wrapped = c.iv + c.doFinal(dek)
        prefs.edit().putString("dek", b64(wrapped)).commit()
        return SecretKeySpec(dek, "AES")
    }

    private fun encryptTo(file: File, input: InputStream) {
        val iv = ByteArray(16).also(rng::nextBytes)
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, dataKey, IvParameterSpec(iv))
        FileOutputStream(file).use { fos ->
            fos.write(iv)
            CipherOutputStream(fos, c).use { input.copyTo(it, 256 * 1024) }
        }
    }

    private fun decryptStream(file: File): InputStream {
        val fis = FileInputStream(file)
        val iv = ByteArray(16)
        DataInputStream(fis).readFully(iv)
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.DECRYPT_MODE, dataKey, IvParameterSpec(iv))
        return CipherInputStream(fis, c)
    }

    // ---------- Index ----------

    fun items(): List<VaultItem> = synchronized(lock) { loadIndex() }

    private fun loadIndex(): List<VaultItem> {
        cache?.let { return it }
        val list: List<VaultItem> = if (!indexFile.exists()) emptyList() else runCatching {
            val json = decryptStream(indexFile).use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                VaultItem(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    mime = o.getString("mime"),
                    size = o.optLong("size"),
                    dateTaken = o.optLong("date"),
                )
            }
        }.getOrDefault(emptyList())
        cache = list
        return list
    }

    private fun saveIndex(list: List<VaultItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("mime", it.mime)
                    .put("size", it.size).put("date", it.dateTaken),
            )
        }
        val tmp = File(dir, "index.tmp")
        encryptTo(tmp, arr.toString().toByteArray(Charsets.UTF_8).inputStream())
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            tmp.renameTo(indexFile)
        }
        cache = list
    }

    // ---------- Operations ----------

    /** Encrypts a gallery item into the vault. Blocking. The original is NOT removed here. */
    fun add(item: MediaItem): VaultItem {
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString()
        val data = File(dir, "$id.dat")
        val thumb = File(dir, "$id.thm")
        try {
            val input = resolver.openInputStream(item.uri) ?: error("Couldn't open ${item.name}")
            input.use { encryptTo(data, it) }
            runCatching {
                val bmp = resolver.loadThumbnail(item.uri, Size(480, 480), null)
                val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                encryptTo(thumb, bytes.inputStream())
            }
            val v = VaultItem(id, item.name, item.mimeType, (data.length() - 16).coerceAtLeast(0), item.dateTaken)
            synchronized(lock) { saveIndex(loadIndex() + v) }
            return v
        } catch (e: Exception) {
            data.delete(); thumb.delete()
            throw e
        }
    }

    fun thumbnail(item: VaultItem): Bitmap? = runCatching {
        val f = File(dir, "${item.id}.thm")
        if (!f.exists()) return@runCatching null
        val bytes = decryptStream(f).use { it.readBytes() }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /** Decrypts a photo into memory, scaled to fit [maxEdge]. */
    fun decodeImage(item: VaultItem, maxEdge: Int): Bitmap {
        val bytes = decryptStream(File(dir, "${item.id}.dat")).use { it.readBytes() }
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            if (w > maxEdge || h > maxEdge) {
                val s = maxEdge.toFloat() / maxOf(w, h)
                decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
            }
        }
    }

    /** Decrypts a video to a temporary cache file for playback. Deleted on lock. */
    fun decryptToTemp(item: VaultItem): File {
        val ext = item.name.substringAfterLast('.', "mp4")
        val out = File(tmpDir, "${item.id}.$ext")
        decryptStream(File(dir, "${item.id}.dat")).use { input ->
            FileOutputStream(out).use { input.copyTo(it, 256 * 1024) }
        }
        return out
    }

    /** Decrypts back into the public gallery and removes it from the vault. */
    fun restore(item: VaultItem, repo: MediaRepository): Boolean {
        val uri = decryptStream(File(dir, "${item.id}.dat")).use { input ->
            repo.insertStream(item.name, item.mime, MediaRepository.RESTORED_PATH, input)
        }
        if (uri != null) delete(listOf(item))
        return uri != null
    }

    fun delete(items: List<VaultItem>) {
        val ids = items.map { it.id }.toSet()
        synchronized(lock) {
            items.forEach {
                File(dir, "${it.id}.dat").delete()
                File(dir, "${it.id}.thm").delete()
            }
            saveIndex(loadIndex().filterNot { it.id in ids })
        }
    }

    fun clearTemp() {
        File(context.cacheDir, "vault_tmp").listFiles()?.forEach { it.delete() }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val MASTER_ALIAS = "stellar_vault_master"
    }
}
